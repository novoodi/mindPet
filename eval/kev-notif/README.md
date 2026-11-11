# kev-notif-v1: 알림·카톡 판단용 Kev 재학습 데이터

## 폴더
| 경로 | 내용 |
|---|---|
| `raw/notif_*.jsonl` | 합성 알림 1,487줄 (안티그래비티 생성, 라벨 검수·수정 완료) |
| `raw/chat_*.jsonl` | 합성 카톡 826줄 (같음) |
| `rejected/` | 복사·템플릿 문제로 걸러 낸 원본 |
| `kevfmt.py` | **입력 형식과 질문문의 기준.** 앱도 이 형식 그대로 Kev를 불러야 함 |
| `prepare_train.py` | raw → `kev/` 학습 파일 |
| `kev/train.jsonl` | 학습 7,332개 = 합성 알림 1,339 + 합성 카톡 743 + "나"를 뺀 카톡 사본 250 + 3i4K 5,000 |
| `kev/dev_notif.jsonl`, `kev/dev_chat.jsonl` | 합성 10% (148 / 83), 체크포인트 고르기용 |
| `eval/` | **실제 폰** 카톡 325 + 알림 44. 학습에 절대 넣지 않음 |
| `eval/score.py` | benchmark `rows.json` → 맞힘/잘못 찍음/놓침 |
| `결과_학습전_기준점수.md` | 학습 전 점수 |
| `../prompts/안티그래비티_데이터_프롬프트.md` | 데이터 생성 프롬프트 |

## 질문 4개
- `chat_question` / `chat_request`: 앞 대화를 보고, 마지막 메시지가 "나"에게 하는 질문·요청인지
- `notif_promo` / `notif_timely`: 광고·참여 유도인지, 오늘 봐야 하는지
- 기존 3i4K 레코드는 원래 질문(`information_question`, `action_request`) 그대로 둠 (일반 화행 지식 유지용)

## 입력 형식 (`kevfmt.py`)
```
[대화방] 단톡
[앞 대화]
민수: 앞 메시지
[마지막 메시지]
지민: 마지막 메시지
```
```
[앱] 토스
[제목] 제목
[내용] 내용
```
앞 대화는 최대 4줄. 실제 카톡 알림에는 내가 보낸 말이 없으므로 "나"를 뺀 사본을 학습에 섞었다.
단, 단톡에서 누구에게 하는 말인지가 "나"의 말에 달린 유형(group_other, group_me)과 바로 앞이 "나"인 줄은 사본에서 뺐다.

## 학습 (RTX 3060 기준, 지난 5,000개 3에폭이 약 38분 → 이번은 약 1시간 예상)
```bash
cd "E:\AI 키보드 앱 만들기~\dataset-evaluation"
.venv-train/Scripts/python.exe -m kev.train \
  --data kev-notif-v1/kev/train.jsonl \
  --init_from models/kev-0.8b \
  --base "Qwen/Qwen3.5-0.8B-Base" --base_revision "dc7cdfe2ee4154fa7e30f5b51ca41bfa40174e68" \
  --lora 16 --head_dim 256 --epochs 2 --lr 2e-5 --accum 8 --batch 1 --max_state 384 \
  --device cuda --out runs/kev-notif-v1-ep2 --seed 42
```
지난번에는 2에폭이 가장 좋았다. 3에폭도 비교하려면 `--epochs 3 --out runs/kev-notif-v1-ep3`로 한 번 더 돌린다.
4단계 시험 학습(`--max_steps 4`)으로 데이터가 끝까지 학습·저장·평가되는 것을 확인했다 (2026-10-03).

## 평가
```bash
for f in kev/dev_chat kev/dev_notif eval/kev_real_chat eval/kev_real_notif; do
  .venv-train/Scripts/python.exe -m kev.benchmark --run runs/kev-notif-v1-ep2 \
    --data kev-notif-v1/$f.jsonl --out results/notif_v1_ep2_$(basename $f) --device cuda
done
.venv-train/Scripts/python.exe -m kev.benchmark --run runs/kev-notif-v1-ep2 \
  --data kev-binary-v2/test.jsonl --out results/notif_v1_ep2_3i4k_test --device cuda   # 기존 능력 유지 확인
python kev-notif-v1/eval/score.py results/notif_v1_ep2_*/rows.json
```
체크포인트는 dev로 고르고, 실제 폰 평가 세트(`eval/`)는 마지막 확인에만 쓴다.

## 앱에 넣을 때
- `KevClassifier.TASKS`의 질문문을 `kevfmt.TASKS`로 바꾸고, state를 `kevfmt.chat_state`/`notif_state`와 똑같이 조립한다.
- 알림에도 Kev를 돌려야 하므로(지금은 메시지·메일만) 호출 수가 늘어난다. 질문당 약 0.3초.
- GGUF 변환과 폰 Parity 검증은 `../llamacpp/` 절차를 다시 따른다.

## 공개 저장소에서 뺀 것
- `eval/` (실제 폰에서 뽑은 카톡 325개·알림 44개): 다른 사람의 메시지가 들어 있어 올리지 않는다. 점수는 `docs/04`, `docs/05`에 있다.
- `raw/`, `kev/` 학습 데이터와 `runs/` 체크포인트, GGUF 파일: 용량 때문에 뺐다. 생성 프롬프트는 `docs/09`에 있다.
