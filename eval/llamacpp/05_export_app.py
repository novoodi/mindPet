"""학습된 Kev run을 앱용으로 내보낸다.

python 05_export_app.py <run 폴더> <태그>
  → llamacpp/merged-hf-<태그>/      LoRA를 합친 HF 모델 (convert_hf_to_gguf.py 입력)
  → llamacpp/app-<태그>/head.bin    앱의 KevClassifier.readHead 형식
  → llamacpp/app-<태그>/parity.json 앱 Parity Test용 PyTorch 기준값 (kevfmt 형식 state, 질문 4개 + 3i4K 질문 2개)

head.bin: little-endian int32 d, int32 dp, float32 scale, float32 temperature, qW[dp*d], qb[dp], kW[dp*d], kb[dp]
"""
import json, os, shutil, struct, sys
import numpy as np
import torch
from safetensors import safe_open
from safetensors.torch import save_file

ROOT = r"E:\AI 키보드 앱 만들기~\dataset-evaluation"
sys.path.insert(0, os.path.join(ROOT, "sources", "kev"))
sys.path.insert(0, os.path.join(ROOT, "kev-notif-v1"))
from kev.model import encode, rows_of
from kev.checkpoint import Checkpoint
import kevfmt

SNAP = os.path.join(ROOT, r".cache\huggingface\hub\models--Qwen--Qwen3.5-0.8B-Base\snapshots\dc7cdfe2ee4154fa7e30f5b51ca41bfa40174e68")
OLD_TASKS = {
    "information_question": "Does the Korean message ask a genuine question seeking information? Rhetorical questions do not count.",
    "action_request": "Is the Korean message a command or request for the listener to perform an action? Rhetorical commands do not count.",
}


def write_merged(model, out):
    os.makedirs(out, exist_ok=True)
    for name in os.listdir(SNAP):
        if not name.endswith(".safetensors") and name != "model.safetensors.index.json":
            shutil.copyfile(os.path.join(SNAP, name), os.path.join(out, name))
    merged = {f"model.language_model.{k}": v.detach().to(torch.float32).contiguous() for k, v in model.lm.state_dict().items()}
    tensors, replaced = {}, 0
    with safe_open(os.path.join(SNAP, "model.safetensors-00001-of-00001.safetensors"), "pt") as f:
        for k in f.keys():
            if k in merged: tensors[k] = merged.pop(k); replaced += 1
            else: tensors[k] = f.get_tensor(k)
    if merged: raise SystemExit(f"merged keys missing from base layout: {list(merged)[:5]}")
    save_file(tensors, os.path.join(out, "model.safetensors"), metadata={"format": "pt"})
    print(f"wrote {out} ({replaced} language-model tensors replaced)")


def head_bin(model):
    sd = {k: v.float().numpy() for k, v in model.head.state_dict().items()}
    qW, qb, kW, kb = sd["q.weight"], sd["q.bias"], sd["k.weight"], sd["k.bias"]
    dp, d = qW.shape
    assert kW.shape == (dp, d) and qb.shape == kb.shape == (dp,)
    temp = float(getattr(model.head, "temperature", 1.0))
    return (struct.pack("<iiff", d, dp, float(model.head.scale), temp)
            + qW.astype("<f4").tobytes() + qb.astype("<f4").tobytes() + kW.astype("<f4").tobytes() + kb.astype("<f4").tobytes())


def parity_cases():
    """앱이 실제로 보낼 형식의 입력. 실제 폰 평가 세트는 개인 대화라 넣지 않고 합성 dev에서 고른다."""
    here = os.path.join(ROOT, "kev-notif-v1", "kev")
    cases = []
    for f, n in (("dev_chat.jsonl", 14), ("dev_notif.jsonl", 10)):
        rows = [json.loads(l) for l in open(os.path.join(here, f), encoding="utf-8")]
        for r in rows[:: max(1, len(rows) // n)][:n]:
            cases += [(r["state"], q["instructions"], qid) for qid, q in r["questions"].items()]
    # 앞 대화가 없는 카톡 (실제 알림의 절반)
    for who, text in (("민수", "내일 몇 시에 와?"), ("엄마", "들어올 때 우유 좀 사 와"), ("지훈", "ㅋㅋㅋ 개웃기네")):
        st = kevfmt.chat_state("1:1", [], {"who": who, "text": text})
        cases += [(st, kevfmt.TASKS[t], t) for t in ("chat_question", "chat_request")]
    for text in ("안녕하세요.", "이거 어떻게 하는 거야?", "문 좀 닫아 줄래"):
        cases += [(text, ins, t) for t, ins in OLD_TASKS.items()]
    return cases


def parity(tok, model):
    out = []
    for state, instr, task in parity_cases():
        enc = encode(tok, {"state": state, "questions": [{"instr": instr, "options": ["Yes", "No"], "label": 0}]})
        with torch.no_grad():
            p = model.probs(enc)[0]
        state_ids, state_pos, rows = rows_of(enc)
        ids = state_ids + rows[0]["ids"]
        assert state_pos + rows[0]["pos"] == list(range(len(ids)))
        out.append({"text": state, "task": task, "ids": ids, "pt_yes": float(p[0])})
    return out


if __name__ == "__main__":
    run, tag = sys.argv[1], sys.argv[2]
    tok, model = Checkpoint(run).load("cpu")
    model.eval()
    app = os.path.join(ROOT, "llamacpp", f"app-{tag}")
    os.makedirs(app, exist_ok=True)
    open(os.path.join(app, "head.bin"), "wb").write(head_bin(model))
    rows = parity(tok, model)
    json.dump(rows, open(os.path.join(app, "parity.json"), "w", encoding="utf-8"), ensure_ascii=False)
    print(f"head.bin + parity.json ({len(rows)} rows) -> {app}")
    if "--head-only" not in sys.argv:
        write_merged(model, os.path.join(ROOT, "llamacpp", f"merged-hf-{tag}"))
