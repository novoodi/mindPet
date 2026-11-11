#!/usr/bin/env bash
# 학습된 run을 dev·실제 폰 평가 세트·기존 3i4K 테스트로 한 번에 평가한다.
# 사용: bash kev-notif-v1/eval_run.sh runs/kev-notif-v1-ep2 ep2   (dataset-evaluation 폴더에서)
set -e
RUN="$1"; TAG="$2"
PY=./.venv-train/Scripts/python.exe
export PYTHONIOENCODING=utf-8
for f in kev-notif-v1/kev/dev_chat kev-notif-v1/kev/dev_notif kev-notif-v1/eval/kev_real_chat kev-notif-v1/eval/kev_real_notif kev-binary-v2/test; do
  name=$(basename "$f")
  rm -rf "results/notif_v1_${TAG}_${name}"
  $PY -m kev.benchmark --run "$RUN" --data "$f.jsonl" --out "results/notif_v1_${TAG}_${name}" --device cuda > /dev/null 2>&1
  echo "done $name"
done
python kev-notif-v1/eval/score.py results/notif_v1_${TAG}_dev_chat/rows.json results/notif_v1_${TAG}_dev_notif/rows.json \
  results/notif_v1_${TAG}_kev_real_chat/rows.json results/notif_v1_${TAG}_kev_real_notif/rows.json
python - "$TAG" <<'EOF'
import json, sys
rows = json.load(open(f"results/notif_v1_{sys.argv[1]}_test/rows.json", encoding="utf-8"))
ok = sum((r["p"][r["keys"].index("yes")] >= 0.5) == (r["label"] == r["keys"].index("yes")) for r in rows)
print(f"## 3i4K test: {ok}/{len(rows)} = {ok/len(rows):.4f} (학습 전 v2-ep2 0.9539)")
EOF
