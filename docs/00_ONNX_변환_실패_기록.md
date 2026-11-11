# Kev-0.8B (Qwen3.5) Galaxy Z Fold7 온디바이스 실행 검증 결과

## 1. 실행 환경 및 모델 구조
- **기기**: Galaxy Z Fold7 (현재 `unauthorized` 상태로 앱 실측 대기 중, APK는 빌드 완료)
- **선정 모델**: `dataset-evaluation/runs/kev-korean-binary-v2-ep2` (학습 5,000문장)
- **기반 모델**: `Qwen/Qwen3.5-0.8B-Base` (히든 사이즈: 1024)
- **모델 아키텍처**: Linear Attention / RNN 하이브리드 (Mamba/DeltaNet 구조)
- **실행 백엔드(앱)**: `onnxruntime-android`

## 2. 모델 변환 및 실행 경로 분석 (결과: 실패)
Android의 `onnxruntime`에서 해당 모델을 실행하기 위해 PyTorch → ONNX 변환을 시도했으나, **Qwen3.5의 Mamba/DeltaNet 핵심 아키텍처 지원 한계로 인해 ONNX 독립 실행이 불가능함**을 확인했습니다.

### 정확한 실패 지점 및 근거
1. **ONNX Opset 제약과 순환문 전개 (Loop Unrolling) 문제**:
   - Qwen3.5의 핵심인 `chunk_gated_delta_rule`은 PyTorch의 `torch.linalg.solve_triangular`를 사용합니다. 하지만 ONNX Opset 14는 이 연산을 지원하지 않습니다.
   - ONNX 변환기(JIT Tracer)가 이를 우회하기 위해 순수 Python `for` 루프(sequence length만큼 반복)로 전환하여 그래프를 생성(Traced)합니다.
   - 이로 인해 시퀀스 길이를 64로 고정해도 1개 레이어당 63번의 루프 연산(`Gather`, `Slice`, `Scatter` 등)이 하드코딩된 **수만 개의 노드**를 가진 거대한 ONNX 그래프가 생성됩니다.
   - 이 비정상적인 ONNX 그래프는 PC와 Android 모두에서 `OrtEnvironment.createSession` 시 그래프 최적화 단계에서 무한 루프(Hang) 및 메모리 초과(OOM)를 발생시킵니다.

2. **Dynamo (PyTorch 2.9+) Exporter 컴파일 실패**:
   - 레거시 JIT Tracer 대신 최신 ONNX Dynamo Exporter(`dynamo=True`)를 사용하여 `dynamic_axes` 기반의 깔끔한 그래프 추출을 시도했습니다.
   - 그러나 HuggingFace Qwen3.5 구현체의 복잡한 텐서 뷰 조작(`query.reshape`, `RotaryEmbedding`의 Complex 연산 등)에서 `torch._dynamo.exc.UserError: Constraints violated` 및 `ScalarType ComplexDouble is an unexpected tensor scalar type` 오류가 발생하며 컴파일이 원천적으로 차단됩니다.

## 3. 대안 및 향후 계획

### 왜 다른 LLM 백엔드(llama.cpp, MediaPipe)를 사용할 수 없는가?
- 해당 모델은 텍스트를 생성하는 `CausalLM`이 아니라 판단 결과를 점수로 내는 `SequenceClassification` (PointerHead 적용) 모델입니다.
- llama.cpp와 MediaPipe는 Qwen 시리즈의 텍스트 생성을 지원하지만, 마지막 토큰의 은닉 상태(hidden_state)를 추출해 이진 분류를 수행하는 커스텀 분류 헤드(PointerHead)의 실행을 기본적으로 지원하지 않습니다. 이를 지원하려면 C++ 코어 레벨의 포팅이 필요합니다.

### 결론
**최종 상태: ③ 모델 변환 또는 실행 지원 문제로 실행 불가**

현재 Kev-0.8B 모델을 Galaxy Z Fold7의 앱에서 즉시 실행하는 것은 불가능합니다. Qwen3.5 (DeltaNet) 모델은 ONNX 기반 모바일 추론에 적합하지 않은 연산을 다수 포함하고 있습니다.

**해결 방향**:
1. **모델 교체**: Android ONNX 실행이 필수라면, Linear Attention 구조가 아닌 일반 Transformer 기반 모델(예: Qwen2.5-0.5B, Llama-3.2-1B)로 재학습하는 것이 가장 현실적입니다.
2. **KevTestActivity**: Android에 `KevTestActivity` 및 테스트용 디버그 빌드(`install_and_test_kev.bat`)는 준비해 두었습니다. 기기 디버깅 승인 후 앱 화면은 확인할 수 있으나 추론 시점에는 위에서 설명한 ONNX 로드 실패가 발생합니다.
