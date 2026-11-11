"""합성 알림·카톡(raw/*.jsonl)과 기존 3i4K 학습 데이터를 Kev 학습 형식으로 묶는다.

출력 (kev-notif-v1/kev/):
  train.jsonl      합성 알림·카톡 90% + "나"의 말을 뺀 카톡 사본 + 3i4K 학습 5,000
  dev_notif.jsonl  합성 알림 10% (학습 중 체크포인트 고르기용)
  dev_chat.jsonl   합성 카톡 10%
  manifest.json    개수, 분포, 해시, 지시문
실제 폰 평가 세트(eval/)는 여기에 넣지 않는다.
"""
import collections
import difflib
import hashlib
import json
import random
import re
from pathlib import Path

import kevfmt

HERE = Path(__file__).resolve().parent
RAW = HERE / "raw"
OUT = HERE / "kev"
V2_TRAIN = HERE.parent / "kev-binary-v2" / "train.jsonl"
SEED = 20261003
DEV_FRAC = 0.1
# "나"의 말이 있어야 정답이 정해지는 유형은 "나"를 뺀 사본을 만들지 않는다
NO_STRIP_TYPES = {"group_other", "group_me"}
# 1차 학습 후 실제 폰 오답 유형으로 만든 보강 배치: 학습에만 넣고 dev는 1차와 똑같이 유지한다
TRAIN_ONLY = {"chat_13", "notif_16", "chat_14"}


def read(path):
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def write(path, rows):
    path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")


def split(rows, key, rng):
    """정답 조합별로 나눠 dev 비율을 맞춘다."""
    groups = collections.defaultdict(list)
    for r in rows: groups[key(r)].append(r)
    train, dev = [], []
    for g in groups.values():
        rng.shuffle(g)
        n = max(1, round(len(g) * DEV_FRAC))
        dev += g[:n]; train += g[n:]
    return train, dev


def _norm(s):
    return re.sub(r"[\s\W_]+", "", s)


def eval_leak_filter():
    """실제 폰 평가 세트와 거의 같은 문장(6글자 이상, 유사도 0.85 이상)이면 True. 시험 문제가 학습에 섞이는 것을 막는다."""
    ev = [_norm(json.loads(l)["target"]["text"]) for l in (HERE / "eval" / "real_chat_eval.jsonl").open(encoding="utf-8")]
    ev += [_norm(r.get("title", "") + r["text"]) for r in map(json.loads, (HERE / "eval" / "real_notif_eval.jsonl").open(encoding="utf-8"))]
    ev = [e for e in ev if len(e) >= 6]

    def leaked(text):
        t = _norm(text)
        return len(t) >= 6 and any(difflib.SequenceMatcher(None, t, e).ratio() >= 0.85 for e in ev)
    return leaked


def main():
    rng = random.Random(SEED)
    OUT.mkdir(exist_ok=True)
    leaked = eval_leak_filter()

    notif = []
    for f in sorted(RAW.glob("notif_*.jsonl")):
        for i, r in enumerate(read(f)):
            notif.append({**r, "id": f"syn-{f.stem}-{i:03d}", "_train_only": f.stem in TRAIN_ONLY})
    chat = []
    for f in sorted(RAW.glob("chat_*.jsonl")):
        for i, r in enumerate(read(f)):
            assert r["target"]["who"] not in ("나", "도윤"), (f, i)
            chat.append({**r, "id": f"syn-{f.stem}-{i:03d}", "_train_only": f.stem in TRAIN_ONLY})

    n_before, c_before = len(notif), len(chat)
    notif = [r for r in notif if not leaked(r.get("title", "") + r["text"])]
    chat = [r for r in chat if not leaked(r["target"]["text"])]
    removed_leaks = {"notif": n_before - len(notif), "chat": c_before - len(chat)}

    n_train, n_dev = split([r for r in notif if not r["_train_only"]], lambda r: (r["promo"], r["timely"]), rng)
    c_train, c_dev = split([r for r in chat if not r["_train_only"]], lambda r: (r["question"], r["request"]), rng)
    n_train += [r for r in notif if r["_train_only"]]
    c_train += [r for r in chat if r["_train_only"]]

    # 실제 카톡 알림처럼 "나"의 말을 뺀 사본 (마지막 앞 줄이 "나"면 대답 관계가 사라지므로 제외)
    stripped = []
    for r in c_train:
        if r.get("type") in NO_STRIP_TYPES or not r["context"] or r["context"][-1]["who"] == "나":
            continue
        ctx = [c for c in r["context"] if c["who"] != "나"]
        if len(ctx) == len(r["context"]):
            continue
        stripped.append({**r, "context": ctx, "id": r["id"] + "-nome"})

    train = ([kevfmt.notif_record(r, "syn_notif") for r in n_train]
             + [kevfmt.chat_record(r, "syn_chat") for r in c_train]
             + [kevfmt.chat_record(r, "syn_chat_nome") for r in stripped])
    for r in read(V2_TRAIN):
        r["_meta"] = {"id": r["_meta"]["id"], "source": "3i4k"}
        train.append(r)
    rng.shuffle(train)

    write(OUT / "train.jsonl", train)
    write(OUT / "dev_notif.jsonl", [kevfmt.notif_record(r, "syn_notif_dev") for r in n_dev])
    write(OUT / "dev_chat.jsonl", [kevfmt.chat_record(r, "syn_chat_dev") for r in c_dev])

    def dist(rows, a, b): return {f"{k[0]}/{k[1]}": v for k, v in sorted(collections.Counter((r[a], r[b]) for r in rows).items())}
    manifest = {
        "seed": SEED, "dev_frac": DEV_FRAC, "removed_eval_leaks": removed_leaks,
        "counts": {"train_total": len(train), "train_notif": len(n_train), "train_chat": len(c_train),
                   "train_chat_without_me": len(stripped), "train_3i4k": sum(1 for r in train if r["_meta"]["source"] == "3i4k"),
                   "dev_notif": len(n_dev), "dev_chat": len(c_dev)},
        "label_distribution": {"train_notif promo/timely": dist(n_train, "promo", "timely"),
                               "train_chat question/request": dist(c_train, "question", "request"),
                               "dev_notif promo/timely": dist(n_dev, "promo", "timely"),
                               "dev_chat question/request": dist(c_dev, "question", "request")},
        "instructions": kevfmt.TASKS,
        "state_format": {"chat": kevfmt.chat_state("group", [{"who": "민수", "text": "앞 메시지"}], {"who": "지민", "text": "마지막 메시지"}),
                         "notif": kevfmt.notif_state("토스", "제목", "내용")},
        "sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(OUT.glob("*.jsonl"))},
        "not_included": "eval/ 의 실제 폰 카톡·알림 (평가 전용)",
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(manifest["counts"], ensure_ascii=False))
    print(json.dumps(manifest["label_distribution"], ensure_ascii=False))


if __name__ == "__main__":
    main()
