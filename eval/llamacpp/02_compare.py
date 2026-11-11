"""Feed the exact PyTorch token rows to a llama-server (--embeddings --pooling none), apply the PointerHead in numpy,
and compare against pt_reference.json: hidden-state cosine, logit diff, prob diff, decision flips."""
import json, os, sys, time, urllib.request
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = sys.argv[1] if len(sys.argv) > 1 else "8091"
TAG = sys.argv[2] if len(sys.argv) > 2 else "f32"

ref = json.load(open(os.path.join(HERE, "pt_reference.json"), encoding="utf-8"))
hd = np.load(os.path.join(HERE, "head.npz"))
qW, qb, kW, kb = hd["q_weight"], hd["q_bias"], hd["k_weight"], hd["k_bias"]
scale, temp = float(hd["scale"]), float(hd["temperature"])


def head(h_decide, h_opts):
    z = (h_opts @ kW.T + kb) @ (qW @ h_decide + qb) * scale
    return z / temp


def embed(ids):
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}/embeddings", data=json.dumps({"content": ids}).encode(),
                                 headers={"Content-Type": "application/json"})
    out = json.load(urllib.request.urlopen(req, timeout=300))
    return np.array(out[0]["embedding"], dtype=np.float32)


def softmax(z):
    e = np.exp(z - z.max()); return e / e.sum()


rows, flips, cos_d, dl, dp, times = [], [], [], [], [], []
for r in ref:
    t0 = time.perf_counter(); H = embed(r["ids"]); times.append(time.perf_counter() - t0)
    if H.shape[0] != len(r["ids"]):
        raise SystemExit(f"token count mismatch: got {H.shape[0]} vectors for {len(r['ids'])} ids (BOS added?)")
    hd_, ho = H[r["decide"]], H[r["opts"]]
    pd = np.array(r["h_decide"], dtype=np.float32)
    cos_d.append(float(hd_ @ pd / (np.linalg.norm(hd_) * np.linalg.norm(pd))))
    logits = head(hd_, ho); p = softmax(logits)
    dl.append(float(np.abs(logits - np.array(r["pt_logits"])).max()))
    dp.append(float(np.abs(p - np.array(r["pt_probs"])).max()))
    pt_choice, cpp_choice = int(np.argmax(r["pt_probs"])), int(np.argmax(p))
    if pt_choice != cpp_choice: flips.append({"text": r["text"], "task": r["task"], "pt": r["pt_probs"], "cpp": p.tolist()})
    rows.append({"text": r["text"], "task": r["task"], "pt_yes": r["pt_probs"][0], "cpp_yes": float(p[0])})

n = len(ref)
summary = {"tag": TAG, "rows": n, "agreement": f"{n - len(flips)}/{n}", "min_cos_decide": min(cos_d),
           "max_logit_diff": max(dl), "max_prob_diff": max(dp), "mean_prob_diff": float(np.mean(dp)),
           "mean_ms_per_row": 1000 * float(np.mean(times[1:])), "flips": flips}
json.dump({"summary": summary, "rows": rows}, open(os.path.join(HERE, f"compare_{TAG}.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(json.dumps({k: v for k, v in summary.items() if k != "flips"}, ensure_ascii=False, indent=1))
for f in flips: print("FLIP", f)
