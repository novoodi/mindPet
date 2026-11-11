# Kev-0.8B (한국어 v2-ep2) llama.cpp PC 검증 결과 — 2026-10-01

## 결론
ONNX에서 막혔던 Qwen3.5(DeltaNet) 본체를 **llama.cpp(b11319)로 원본과 동일하게 실행**하는 데 성공했습니다.
본체는 llama.cpp로 토큰별 벡터(`--embeddings --pooling none`)를 뽑고, PointerHead(행렬곱 2개)는 별도로 계산합니다.

## 비교 1: 30문장 × 2질문 = 64행 (PyTorch 대비)
| 버전 | 크기 | 판정 일치 | decide 벡터 cos 최저 | 최대 확률 차 | 평균 지연(PC CPU) |
|---|---|---|---|---|---|
| F32 | 3.1GB | 64/64 | 0.9999998 | 0.001 | 112ms |
| Q8_0 | 834MB | 64/64 | 0.9996 | 0.021 | 119ms |
| Q6_K | 647MB | 64/64 | 0.9974 | 0.077 | 84ms |
| Q4_K_M | 542MB | 62/64 | 0.9918 | 0.636 | 73ms |
(참고: 기존 ONNX FP32 = 49/64)

## 비교 2: 테스트셋 전체 488문장 × 2질문 = 976문항
| 버전 | 정확도 | PyTorch 정확도 | PyTorch와 판정 일치 |
|---|---|---|---|
| Q8_0 | 95.39% | 95.39% | 974/976 |
| Q6_K | 95.18% | 95.39% | 970/976 |

**권장: Q6_K (647MB)** — 정확도 손실 0.2%p. 여유가 있으면 Q8_0(834MB, 손실 0).

## 재현 순서
1. `01_merge_and_reference.py` — LoRA 병합(fp32) → `merged-hf/`, PyTorch 기준값 `pt_reference.json`, 헤드 `head.npz`
2. `src/convert_hf_to_gguf.py merged-hf --outtype f32` → `kev-ko-f32.gguf`, `bin/llama-quantize` 로 Q8_0/Q6_K/Q4_K_M
3. `bin/llama-server -m <gguf> --embeddings --pooling none` 후 `02_compare.py`(64행), `03_testset.py`(976문항)

## 앱 연동 시 주의
- 토큰 id는 Python `encode`/`rows_of`와 동일하게 만들어 **id 배열로** 넣을 것 (BOS 자동 추가 없음 확인됨: 벡터 수 = 토큰 수).
- 위치는 0..n-1 순차 (질문마다 state + 질문 row를 한 줄로 실행).
- 헤드: `z = (k(h_opts)) · q(h_decide) * 0.0625`, temperature 1.0, softmax → [Yes, No].

## Fold7 실기기 결과 (SM-F966N, Snapdragon 8 Elite SM8750, RAM 16GB) — 2026-10-01
앱: MaeumPet `KevTestActivity` Parity Test (libkev.so = llama.cpp b11319, CPU, Q6_K 647MB)

| 스레드 | 판정 일치 (vs PyTorch) | 토큰 구성 일치 | 최대 확률 차 | 문항당 시간 |
|---|---|---|---|---|
| 2 | 64/64 | 64/64 | 0.0765 | 473ms |
| **4** | **64/64** | **64/64** | 0.0765 | **296ms** |
| 6 | 64/64 | 64/64 | 0.0765 | 288ms |
| 8 | 64/64 | 64/64 | 0.0765 | 310ms |

- 모델 로드 0.86초, 네이티브 메모리 약 1.6GB (모델 647MB보다 큼 → 다음 최적화 대상).
- 메시지 1개 = 질문 2개 = 약 0.6초 (4스레드). 두 질문이 같은 메시지 부분을 매번 다시 계산하므로 공유하면 더 줄일 수 있음.
- 주의: 모델 폴더(`files/kev`)를 adb가 먼저 만들면 앱이 파일을 못 봄. 앱이 폴더를 만든 뒤 push 해야 함.

## Fold7 메모리 분석 (2026-10-01)
처음 1.5GB(PSS)였던 원인: **가중치가 두 벌** — 파일 매핑 원본(608MB) + i8mm용 재배열 사본(CPU_REPACK 588MB).
계산 버퍼(n_ubatch × 248k 어휘 × 4B, 512일 때 492MB)는 대부분 예약만 되어 실사용 영향 작음 → n_ubatch 128로 축소(123MB).

| 설정 (4스레드, ubatch 128) | PSS | 문항당 | 일치 |
|---|---|---|---|
| mmap + repack (이전) | 1.52GB | 281ms | 64/64 |
| **read + repack (채택)** | **1.12GB** | **285~304ms** | **64/64** |
| mmap, repack 끔 | 0.92GB | 683ms | 64/64 |

- 채택안에서 남은 heap ≈ 재배열 사본 588MB + 재배열 못 하는 token_embd 201MB + 계산 버퍼 등.
- ubatch 16으로 강제 분할해도 64/64 → 긴 메시지 분할 처리 안전.
- 측정 주의: 화면이 꺼지면 앱 CPU가 멈춤. 측정 중 `svc power stayon usb`, 끝나면 `svc power stayon false`.
