"""1) Fold the v2-ep2 LoRA into Qwen3.5-0.8B-Base and write a HF directory laid out exactly like the base
(Qwen3_5ForConditionalGeneration, only model.language_model.* replaced) so llama.cpp's converter treats it like
the official release.  2) Dump PyTorch reference rows (token ids, readout indices, probs, readout hidden states)
for the 30 test sentences, to compare llama.cpp against."""
import json, os, shutil, sys
import numpy as np
import torch
from safetensors import safe_open
from safetensors.torch import save_file

ROOT = r"E:\AI 키보드 앱 만들기~\dataset-evaluation"
sys.path.insert(0, os.path.join(ROOT, "sources", "kev"))
from kev.model import encode, rows_of
from kev.checkpoint import Checkpoint

RUN = os.path.join(ROOT, "runs", "kev-korean-binary-v2-ep2")
SNAP = os.path.join(ROOT, r".cache\huggingface\hub\models--Qwen--Qwen3.5-0.8B-Base\snapshots\dc7cdfe2ee4154fa7e30f5b51ca41bfa40174e68")
OUT = os.path.join(ROOT, "llamacpp", "merged-hf")
TASKS = {
    "information_question": "Does the Korean message ask a genuine question seeking information? Rhetorical questions do not count.",
    "action_request": "Is the Korean message a command or request for the listener to perform an action? Rhetorical commands do not count.",
}


def write_merged(model):
    os.makedirs(OUT, exist_ok=True)
    for name in os.listdir(SNAP):
        if not name.endswith(".safetensors") and name != "model.safetensors.index.json":
            shutil.copyfile(os.path.join(SNAP, name), os.path.join(OUT, name))
    merged = {f"model.language_model.{k}": v.detach().to(torch.float32).contiguous() for k, v in model.lm.state_dict().items()}
    out, replaced = {}, 0
    with safe_open(os.path.join(SNAP, "model.safetensors-00001-of-00001.safetensors"), "pt") as f:
        for k in f.keys():
            if k in merged: out[k] = merged.pop(k); replaced += 1
            else: out[k] = f.get_tensor(k)
    if merged: raise SystemExit(f"merged keys missing from base layout: {list(merged)[:5]}")
    save_file(out, os.path.join(OUT, "model.safetensors"), metadata={"format": "pt"})
    print(f"wrote {OUT} ({replaced} language-model tensors replaced)")


def reference(tok, model):
    sents = json.load(open(os.path.join(ROOT, "test_sentences_30.json"), encoding="utf-8"))
    rows_out = []
    for item in sents:
        rec = {"state": item["text"], "questions": [{"instr": i, "options": ["Yes", "No"], "label": 0} for i in TASKS.values()]}
        enc = encode(tok, rec)
        with torch.no_grad():
            probs = model.probs(enc)
        state_ids, state_pos, rows = rows_of(enc)
        for q, (task, row) in enumerate(zip(TASKS, rows)):
            ids = state_ids + row["ids"]
            pos = state_pos + row["pos"]
            assert pos == list(range(len(ids))), "positions are not plain 0..n-1"
            decide = len(state_ids) + row["decide"]
            opts = [len(state_ids) + o for o in row["opts"]]
            with torch.no_grad():
                h = model.lm(input_ids=torch.tensor([ids]), position_ids=torch.tensor([pos])).last_hidden_state[0].float()
                logits = model.head(h[decide], h[opts])
            rows_out.append({"text": item["text"], "task": task, "ids": ids, "decide": decide, "opts": opts,
                             "pt_probs": probs[q].tolist(), "pt_logits": logits.tolist(),
                             "h_decide": h[decide].tolist(), "h_opts": h[opts].tolist()})
    json.dump(rows_out, open(os.path.join(ROOT, "llamacpp", "pt_reference.json"), "w", encoding="utf-8"), ensure_ascii=False)
    head = {k: v.float().numpy() for k, v in model.head.state_dict().items()}
    np.savez(os.path.join(ROOT, "llamacpp", "head.npz"), scale=np.float32(model.head.scale),
             temperature=np.float32(getattr(model.head, "temperature", 1.0)), **{k.replace(".", "_"): v for k, v in head.items()})
    print(f"reference rows: {len(rows_out)}; head keys: {list(head)}; scale={model.head.scale}")


if __name__ == "__main__":
    tok, model = Checkpoint(RUN).load("cpu")
    model.eval()
    reference(tok, model)
    write_merged(model)
