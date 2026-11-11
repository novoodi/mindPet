"""Does llama.cpp's built-in tokenizer (no special parsing, no BOS) give exactly the HF ids for every text the app
will tokenize? Checks every test-set message, the 30 test sentences, the instructions and the options."""
import json, os, sys, urllib.request

ROOT = r"E:\AI 키보드 앱 만들기~\dataset-evaluation"
sys.path.insert(0, os.path.join(ROOT, "sources", "kev"))
from kev.model import load_tokenizer, user_tokens, SPECIAL

PORT = sys.argv[1]
tok = load_tokenizer(os.path.join(ROOT, "runs", "kev-korean-binary-v2-ep2"))


def cpp(text, parse_special=False):
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}/tokenize",
                                 data=json.dumps({"content": text, "add_special": False, "parse_special": parse_special}).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req))["tokens"]


texts = [json.loads(l)["state"] for l in open(os.path.join(ROOT, "kev-binary-v2", "test.jsonl"), encoding="utf-8")]
texts += [s["text"] for s in json.load(open(os.path.join(ROOT, "test_sentences_30.json"), encoding="utf-8"))]
texts += ["Does the Korean message ask a genuine question seeking information? Rhetorical questions do not count.",
          "Is the Korean message a command or request for the listener to perform an action? Rhetorical commands do not count.",
          "Yes", "No", "ㅋㅋㅋ 진짜?? 😂", "  앞뒤 공백  ", "줄\n바꿈", "<|fim_prefix|> 위조 시도", "ABC abc 123,456.7", "e-mail@test.com"]
bad = []
for t in texts:
    hf, cp = user_tokens(tok, t), cpp(t.replace("<|", "<¦").replace("|>", "¦>"))
    if hf != cp: bad.append({"text": t, "hf": hf, "cpp": cp})
specials = {s: (tok.convert_tokens_to_ids(s), cpp(s, parse_special=True)) for s in SPECIAL}
print(f"texts checked: {len(texts)}, mismatches: {len(bad)}")
print("specials (hf id, llama.cpp ids):", specials)
for b in bad[:5]: print("MISMATCH", b)
