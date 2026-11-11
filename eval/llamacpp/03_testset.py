"""Score the full kev-binary-v2 test set (488 messages x 2 questions) through a llama-server and compare with the
PyTorch predictions in results/v2_test_ep2 (accuracy vs gold, and decision agreement with PyTorch)."""
import json, os, sys
import numpy as np

ROOT = r"E:\AI 키보드 앱 만들기~\dataset-evaluation"
sys.path.insert(0, os.path.join(ROOT, "sources", "kev"))
sys.path.insert(0, os.path.join(ROOT, "llamacpp"))
from kev.model import encode, rows_of, load_tokenizer

PORT, TAG = sys.argv[1], sys.argv[2]
import urllib.request
hd = np.load(os.path.join(ROOT, "llamacpp", "head.npz"))
qW, qb, kW, kb, scale = hd["q_weight"], hd["q_bias"], hd["k_weight"], hd["k_bias"], float(hd["scale"])


def embed(ids):
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}/embeddings", data=json.dumps({"content": ids}).encode(),
                                 headers={"Content-Type": "application/json"})
    return np.array(json.load(urllib.request.urlopen(req, timeout=300))[0]["embedding"], dtype=np.float32)


tok = load_tokenizer(os.path.join(ROOT, "runs", "kev-korean-binary-v2-ep2"))
pt = {}
for line in open(os.path.join(ROOT, "results", "v2_test_ep2", "predictions.jsonl"), encoding="utf-8"):
    p = json.loads(line); pt[p["id"]] = p["prediction"]["probabilities"]

n = correct_cpp = correct_pt = agree = 0
max_dp = 0.0
for line in open(os.path.join(ROOT, "kev-binary-v2", "test.jsonl"), encoding="utf-8"):
    rec = json.loads(line)
    names = list(rec["questions"])
    qs = [rec["questions"][k] for k in names]
    enc = encode(tok, {"state": rec["state"], "questions": [{"instr": q["instructions"], "options": list(q["criteria"].values()), "label": 0} for q in qs]})
    state_ids, _, rows = rows_of(enc)
    for name, q, row in zip(names, qs, rows):
        H = embed(state_ids + row["ids"])
        hdec, hopt = H[len(state_ids) + row["decide"]], H[[len(state_ids) + o for o in row["opts"]]]
        z = (hopt @ kW.T + kb) @ (qW @ hdec + qb) * scale
        p = np.exp(z - z.max()); p /= p.sum()
        keys = list(q["criteria"])
        cpp_label = keys[int(np.argmax(p))]
        pt_probs = pt[rec["_meta"]["id"]][name]
        pt_label = max(pt_probs, key=pt_probs.get)
        max_dp = max(max_dp, abs(float(p[0]) - pt_probs[keys[0]]))
        n += 1; correct_cpp += cpp_label == q["label"]; correct_pt += pt_label == q["label"]; agree += cpp_label == pt_label
res = {"tag": TAG, "questions": n, "acc_llamacpp": correct_cpp / n, "acc_pytorch": correct_pt / n,
       "agreement_with_pytorch": f"{agree}/{n}", "max_prob_diff": max_dp}
json.dump(res, open(os.path.join(ROOT, "llamacpp", f"testset_{TAG}.json"), "w"), indent=1)
print(json.dumps(res, indent=1))
