# mindPet (마음펫)

> An Android app that runs a fine-tuned 0.8B model fully on-device to decide which KakaoTalk messages and notifications actually need you.
> The model body runs on llama.cpp through a small JNI bridge I wrote; nothing leaves the phone.
> On a Galaxy Z Fold7: **296 ms per question, 64/64 agreement with PyTorch, 1.12 GB memory.**

폰에 쌓이는 카톡과 알림 중에서 "나한테 묻는 말인가, 나한테 시키는 일인가, 광고인가, 오늘 봐야 하는가"를 폰 안에서만 판단하는 안드로이드 앱이다. 메시지는 기기 밖으로 나가지 않는다.

1인 개발, 2026-09-06 ~ 2026-10-03.

## 핵심 결과

| 항목 | 값 | 근거 |
|---|---|---|
| 실기기 지연 (Galaxy Z Fold7, CPU 4스레드, Q6_K 647MB) | 질문 1개당 296ms | [docs/01](docs/01_llamacpp_검증_및_Fold7_실측.md) |
| 실기기 판정 일치 (PyTorch 대비) | 64/64 | 같음 |
| 실기기 메모리 (PSS) | 1.52GB → 1.12GB | 같음 |
| 모델 로드 | 0.86초 | 같음 |
| PC 전체 테스트셋 976문항 (Q6_K) | 95.18% (PyTorch 95.39%) | 같음 |
| 한국어 학습 효과 (360문장, 두 판단 모두 정답) | 69.2% → 91.1% | [docs/02](docs/02_한국어_학습_1200문장.md), [docs/03](docs/03_한국어_학습_5000문장.md) |

실기기 수치는 한국어 v2 모델 기준이다. 지금 앱에 들어 있는 알림용 v3 모델은 PC 평가([docs/05](docs/05_알림_재학습_v1_v3.md))까지 했고, 실기기 일치 검사 결과는 아직 기록하지 않았다.

## 어떻게 여기까지 왔나

1. **모델 고르기.** 추가 학습 없이 Laya 322M과 Kev-0.8B를 같은 한국어 문항으로 비교했다. 둘 다 못 썼다(35.5% 이하). → [docs/06](docs/06_모델_비교.md), [docs/07](docs/07_모델_원인_진단.md)
2. **한국어 학습.** Kev-0.8B(Qwen3.5-0.8B 기반, LoRA + 분류 헤드)를 3i4K 1,200문장, 이어서 5,000문장으로 학습했다. 5,000문장의 개선폭은 95% 신뢰구간이 −0.61 ~ +2.87%p라 "확실한 개선"이라고 쓰지 않았다. → [docs/03](docs/03_한국어_학습_5000문장.md)
3. **ONNX 변환 실패.** Qwen3.5의 DeltaNet 연산이 ONNX로 풀리지 않아 그래프가 수만 노드로 불어나고 세션 생성에서 멈췄다. 이 시점의 결론은 "실행 불가"였다. → [docs/00](docs/00_ONNX_변환_실패_기록.md)
4. **llama.cpp로 우회.** 본체는 llama.cpp에서 토큰별 은닉 벡터만 뽑고(`embeddings`, `pooling none`), 분류 헤드(행렬곱 2개)는 Kotlin에서 계산하게 나눴다. PC에서 F32·Q8·Q6·Q4를 PyTorch와 비교해 Q6_K를 골랐다. → [docs/01](docs/01_llamacpp_검증_및_Fold7_실측.md)
5. **실기기.** JNI 브리지([native/kev/kev_jni.cpp](native/kev/kev_jni.cpp))를 arm64로 빌드해 앱 안의 일치 검사 화면(`KevTestActivity`)에서 스레드 수별로 쟀다.
6. **메모리.** 1.5GB의 원인이 "가중치 두 벌"(파일 매핑 원본 + CPU용 재배열 사본)임을 찾았다. 파일 매핑 대신 읽기 방식으로 바꾸고 계산 버퍼(`n_ubatch`)를 512 → 128로 줄여 1.12GB로 낮췄다. 속도는 그대로다.
7. **알림·카톡용 재학습.** 합성 데이터 2,313줄을 만들어 다시 학습했다. 합성 검증셋만 보면 좋아진 v2가 실제 폰 카톡에서는 오탐이 9 → 16으로 늘어난 것을 실제 데이터 평가로 잡았고, 원인(짧은 말을 전부 "예"로 가르친 보강 데이터)을 찾아 v3에서 고쳤다. → [docs/04](docs/04_알림_재학습_기준점수.md), [docs/05](docs/05_알림_재학습_v1_v3.md)

## 구조

```
app/src/main/java/com/maeumpet/app/
  kev/       KevNative(JNI), KevRuntime(모델 수명), KevClassifier(입력 조립 + 분류 헤드), KevTestActivity(실기기 일치 검사)
  notif/     알림 수집·저장, 중요도 학습(폰 안 로지스틱 회귀), 브리핑, 지출·할 일 추출, Gemini Nano 요약
  plan/      메시지에서 약속 후보 추출
  service/   접근성 서비스, 홈 화면 펫 팝업
  ui/        화면
native/kev/  kev_jni.cpp, CMakeLists.txt, build_native.ps1 (llama.cpp를 정적으로 묶어 libkev.so 생성)
eval/        PC 검증 스크립트(llamacpp/), 학습 데이터 형식과 준비 스크립트(kev-notif/)
docs/        실험 기록 (번호 순서가 진행 순서)
```

Kotlin·C++ 약 5,100줄, JVM 단위 테스트 71개.

## 실행

```
gradlew.bat assembleDebug
install_and_test_kev.bat 4      # APK 설치 → 모델 전송 → 실기기 일치 검사 (4스레드)
```

- GGUF 모델 파일(647MB)은 저장소에 없다. `eval/llamacpp/`의 순서대로 직접 만들어야 한다.
- `libkev.so`(arm64)는 들어 있다. 다시 빌드하려면 llama.cpp 소스(b11319)를 받아 `native/kev/build_native.ps1`의 경로를 맞춘다.
- 단위 테스트: `gradlew.bat testDebugUnitTest`

## 저장소에 없는 것

- 실제 폰에서 뽑은 평가용 카톡·알림: 다른 사람의 메시지라 올리지 않았다. 점수만 문서에 있다.
- 학습 데이터, 체크포인트, GGUF: 용량 때문에 뺐다. 합성 데이터 생성 프롬프트는 [docs/09](docs/09_합성데이터_생성_프롬프트.md)에 있다.

## 한계

- 실제 폰 평가셋은 정답 "예"가 19개뿐이라 모델 간 1~2개 차이는 우연일 수 있다.
- 실제 카톡 "답장 필요" 판정은 아직 약하다(v3, 기준 0.8: 맞힘 13 / 오탐 9 / 놓침 6).
- arm64 전용이고 CPU만 쓴다.
- 앱의 알림 기능 설명은 [docs/app-features.md](docs/app-features.md)에 있다.

## 사용한 것

- [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT)
- [jaredpalmer/kev-0.8b](https://huggingface.co/jaredpalmer/kev-0.8b) (Apache-2.0), 기반 모델 Qwen/Qwen3.5-0.8B-Base
- 3i4K 한국어 화행 데이터셋
