# SELAH RTA 간편·정밀 Transfer Function 개발지시서

## 1. 개발 목적

SELAH RTA에 두 가지 Transfer Function 측정 모드를 추가한다.

### 간편 Transfer Function
- 입력: 스마트폰 내장 마이크
- 출력: 스마트폰 USB-C 유선 오디오
- Reference: SELAH RTA 내부 Signal Generator가 실제 출력한 원본 신호
- 목적: 오디오인터페이스 없이 Delay, Magnitude, Coherence를 쉽게 측정하고, Phase는 신뢰도 조건을 만족할 때 고급/참고 정보로 제공

### 정밀 Transfer Function
- USB 오디오인터페이스의 2채널 동시 입력 사용
- Reference 채널 + Measurement Mic 채널
- 동일 하드웨어 clock을 공유하는 구조를 우선
- 목적: Delay, Magnitude, Coherence, Phase를 보다 안정적으로 측정하고 Main/Sub, Main/Delay Speaker alignment 등에 활용

---

## 2. 사전 실기기 검증 결과를 개발 전제로 사용

`2026-10-01-builtin-mic-with-usb-c-output.md` 결과를 기준으로 한다.

Galaxy S23 Ultra(SM-S918N, Android 16)에서:

- USB-C 출력: 정상
- 내장 마이크 입력: 정상
- Input/Output 동시 사용: PASS
- 30분 동안 입력 route 변경 0회
- 30분 동안 capture 중단 0회
- AudioRecord 재시작 0회
- 84,705 frame 중 무음 1 frame
- read error 0
- Signal Generator 실제 출력 확인
- USB-C 분리 시 폰 스피커로 fallback
- USB-C 재연결 시 외부 출력 자동 복귀
- 입력은 끝까지 BuiltIn/bottom 유지
- 48 kHz / mono / Float 유지
- AGC / NS / AEC off 확인

따라서 **라우팅 관점에서 간편 Transfer Function 개발은 진행 가능**하다.

### 반드시 반영할 제한사항

1. Galaxy S23 Ultra는 `UNPROCESSED`가 아니라 `VoiceRecognition` 경로를 사용했다.
2. AGC/NS/AEC는 꺼졌지만, 제조사 내부 DSP까지 완전히 없다고 단정하지 않는다.
3. 현재 PASS는 ADAM D3V 같은 **출력 전용 USB Audio Device**에서 확인한 결과다.
4. UMC404HD, iMM-6C처럼 입출력 기능을 가진 USB 장치에는 그대로 일반화하지 않는다.
5. USB 재연결 시 Android device id가 바뀌므로 `id`만으로 동일 장치를 식별하지 않는다.
6. 60분 테스트와 **clock/timestamp drift 측정은 아직 완료되지 않았다.**
7. Transfer Function 구현 전 다음 관문은 반드시 clock drift 검증이다.

---

## 3. 공통 개발 순서 — 순서 변경 금지

다음 순서로 구현한다.

```text
1. Delay Finder
2. Clock Drift Estimation / Correction
3. Magnitude
4. Coherence
5. Phase
```

시간축이 맞지 않은 상태에서 Magnitude/Phase 그래프부터 만들지 않는다.

---

## 4. 간편 Transfer Function 구조

```text
SELAH RTA Signal Generator
        ↓
Reference Tap ───────────────→ Transfer DSP
        ↓
AudioTrack
        ↓
USB-C Audio Output
        ↓
Mixer / DSP / Amplifier
        ↓
Speaker
        ↓
Room
        ↓
Phone Built-in Mic
        ↓
AudioRecord
        ↓
Measurement Tap ─────────────→ Transfer DSP
```

### Reference 원칙
Reference는 “같은 신호를 나중에 다시 생성한 값”이 아니라,
**실제로 AudioTrack에 전달한 원본 sample stream**을 사용한다.

### Measurement 원칙
Measurement는 기존 `MicSource` / `AudioRecord` 경로를 재사용한다.

기존 SPL/RTA 측정과 동시에 사용할 수 있도록 하되,
capture thread에서 무거운 FFT/TF 연산을 직접 수행하지 않는다.

---

## 5. 정밀 Transfer Function 구조

권장 구성:

```text
Audio Interface
  CH1 = Reference
  CH2 = Measurement Mic
```

가능하면 별도의 AudioRecord 두 개보다 **하나의 multichannel capture stream**에서 두 채널을 분리한다.

목표:
- 같은 sample clock
- 같은 frame timeline
- 최소 clock drift
- 안정적인 Phase/Delay 측정

Reference/Measurement 채널 번호는 사용자가 직접 선택할 수 있어야 한다.

예:

```text
Reference: CH1
Measurement: CH2
```

고정 하드코딩하지 않는다.

---

## 6. Delay Finder

### 목적
Reference와 Measurement 사이의 전체 시간차를 찾는다.

Delay에는 다음이 포함될 수 있다.

- Android output buffer
- USB-C output latency
- mixer/DSP latency
- amplifier/loudspeaker latency
- acoustic propagation
- microphone/input latency

Cross-correlation 기반 delay estimation을 우선 검토한다.

### 표시 예

```text
Detected Delay
23.4 ms
```

음속 기준 참고 거리를 표시할 경우:

```text
Approx. distance
8.0 m
```

단, 전기적/디지털 지연도 포함되므로 실제 물리 거리로 단정하지 않는다.

---

## 7. Clock Drift — 간편 모드 최우선 검증

간편 모드는 다음처럼 서로 다른 clock을 사용할 가능성이 있다.

```text
Output clock = USB-C output device
Input clock  = Phone ADC
```

### 반드시 측정
- 1분
- 5분
- 10분
- 30분
- 60분

각 구간에서 delay 변화를 추적한다.

기록 예:

```text
Initial Delay
Current Delay
Drift ms/min
Drift samples/min
Estimated ppm
```

### 필요한 경우 보정
- periodic delay re-estimation
- fractional delay correction
- sample-rate ratio estimation
- asynchronous resampling
- PLL-like drift tracking

정확도와 CPU 부하를 함께 검증한다.

---

## 8. Magnitude

기본 전달함수:

```text
H(f) = Y(f) / X(f)
```

- X(f) = Reference
- Y(f) = Measurement

표시 기준:

```text
0 dB  = Reference와 동일
+dB   = Measurement가 상대적으로 큼
-dB   = Measurement가 상대적으로 작음
```

### RTA와 명확히 구분

RTA:
> 현재 마이크에 들어오는 주파수 에너지 분포

Transfer Function Magnitude:
> Reference 대비 시스템 전달 특성

UI/도움말에서 혼동하지 않게 한다.

---

## 9. Coherence

Magnitude와 함께 핵심 기능으로 제공한다.

목적:
- 주파수별 측정 신뢰도 표시
- 반사/외부소음/콤필터/위상간섭/낮은 SNR 영향 판단

예:

```text
2.5 kHz
Magnitude: -8.2 dB
Coherence: 0.31

신뢰도 낮음
반사 또는 간섭 영향일 수 있습니다.
EQ Boost 전에 위치를 바꿔 다시 확인하세요.
```

### 중요
Coherence가 낮은 dip을 자동 EQ 보정하지 않는다.

---

## 10. Phase

Delay와 Drift가 안정적으로 보정되고,
Coherence가 충분한 구간에서만 신뢰 가능한 Phase를 표시한다.

기본 표기:

```text
-180° ~ +180°
```

주요 용도:
- Main/Sub alignment
- Main/Delay Speaker alignment
- crossover 관계
- driver alignment 참고

일반 EQ 톤 조정 화면에서는 전면 노출하지 않는다.

---

## 11. 간편 모드 Phase 신뢰도 정책

간편 모드는 서로 다른 clock을 사용할 수 있으므로 다음 상태를 함께 판단한다.

- delay lock
- drift tracking/correction
- coherence
- dropped blocks
- clipping
- reference level
- measurement level

예:

```text
Phase Quality: GOOD
```

또는:

```text
Phase Quality: REFERENCE ONLY
Clock drift not stable
```

실측 데이터를 보고 threshold를 정한다.

---

## 12. UI 구조

분석 메뉴:

```text
분석
└─ Transfer Function
      ├─ 간편
      └─ 정밀
```

### 간편 모드

```text
간편 Transfer Function

INPUT
Galaxy S23 Ultra Built-in Mic / Bottom

OUTPUT
USB-C Audio / ADAM Audio D3V

REFERENCE
Internal Signal Generator

STATUS
Delay Locked
Clock Drift Tracking
Measurement Ready

Delay
23.4 ms

[Magnitude] [Coherence] [Phase]
```

### 정밀 모드

```text
정밀 Transfer Function

Audio Interface
UMC404HD

Reference
CH1

Measurement
CH2 · EMM-6

Clock
Shared

Calibration
EMM-6 profile applied

Delay
12.8 ms

[Magnitude] [Coherence] [Phase]
```

---

## 13. 사용자 안내 문구

### 간편 모드

> 스마트폰 내장 마이크와 USB-C 유선 출력을 이용하는 간편 측정입니다.  
> 추가 오디오인터페이스 없이 사용할 수 있지만, 스마트폰 내부 오디오 처리와 서로 다른 입출력 clock의 영향을 받을 수 있습니다.

### 정밀 모드

> USB 오디오인터페이스의 Reference와 Measurement 채널을 동시에 사용합니다.  
> 동일한 오디오 clock을 공유하는 구조를 사용하여 Delay와 Phase 측정에 더 적합합니다.

---

## 14. Signal Generator

기존 SELAH RTA Signal Generator를 재사용한다.

초기 후보:
- Pink Noise
- Periodic Pink Noise
- Sine Sweep
- Broadband noise

MVP는 Pink Noise를 우선 검토하되,
Magnitude/Coherence 안정성을 비교한 뒤 최종 결정한다.

---

## 15. Buffer / Thread 구조

권장 개념:

```text
Reference Producer
Measurement Producer
        ↓
Timestamped Buffers
        ↓
Transfer Function Worker
        ↓
UI State
```

원칙:
- Audio capture/playback thread blocking 금지
- hot path allocation 최소화
- bounded buffer
- dropped block 진단
- TF FFT/averaging은 worker에서 처리

---

## 16. Timestamp

Reference와 Measurement에 monotonic time base를 사용한다.

벽시계만으로 동기화하지 않는다.

Android AudioTimestamp 계열 API와 sample count 기반 timestamp를 함께 검토한다.

개발 진단에서는 다음을 볼 수 있어야 한다.

```text
Reference timestamp
Measurement timestamp
Initial delay
Current delay
Drift
Resampling status
```

---

## 17. 정밀 모드 Calibration

Measurement Mic에는 기존 SELAH RTA Calibration Profile을 적용한다.

예:
- Mic model
- Serial
- Orientation
- Frequency correction file
- SPL calibration

Reference 채널에는 Measurement Mic 보정을 적용하지 않는다.

### Magnitude 단위
Transfer Magnitude는 기본적으로 **relative dB**다.

RTA/SPL의 dB SPL과 혼동하지 않는다.

---

## 18. Frequency Correction

Measurement microphone의 frequency correction은 TF Measurement 경로에 적용할 수 있도록 검토한다.

단:
- Reference에는 적용하지 않는다.
- reading direction이 확정되지 않은 calibration file은 자동 적용하지 않는다.
- 기존 file hash / confirmation / orientation 보호 로직을 재사용한다.

---

## 19. Multi-Point 기능 연계

Transfer Function 완료 후 다음 기능을 연결한다.

예:

```text
Point 1: Center
Point 2: Left
Point 3: Right
Point 4: Rear
```

각 포인트에서 저장:
- Magnitude
- Coherence
- Delay
- 필요 시 Phase
- 위치 이름 / 메모

표시:
- individual curves
- average
- median
- min/max envelope

단순 dB 산술평균을 무조건 사용하지 않는다.
평균 방식은 목적과 수학적 타당성을 별도 검증한다.

---

## 20. 단일 포인트 한계 안내

> 한 지점의 결과만으로 전체 공간을 판단하지 마세요.  
> 여러 청취 위치에서 측정한 결과를 함께 비교하는 것을 권장합니다.

---

## 21. Polarity / Reverse Phase 주의

향후 polarity 판단을 추가하더라도:

```text
역상 = 무조건 고장
```

으로 판정하지 않는다.

의도적 crossover/driver 설계 가능성이 있으므로
L/R 불일치, Main/Sub, Main/Delay 중첩 문제 등 실제 시스템 관계에서 판단한다.

---

## 22. USB 장치 호환성

현재 PASS는:

```text
Galaxy S23 Ultra
Built-in Mic
USB-C Output-only Device
```

에서 확인되었다.

다른 Android/USB 장치는 runtime capability를 확인한다.

상태 예:

```text
Supported
Limited
Unsupported
Not Tested
```

기종명만으로 지원 여부를 하드코딩하지 않는다.

---

## 23. 입출력 겸용 USB 장치 주의

UMC404HD 등 USB Audio Interface는 출력 전용 장치와 routing 동작이 다를 수 있다.

기존 실기기 기록에서 같은 USB 카드로 입출력을 처리했을 때 입력이 디지털 무음이 된 사례가 있으므로,
정밀 모드 구현 전에 **multichannel capture 방식**을 별도로 검증한다.

정밀 모드는 “내장 마이크 + USB 출력” 구조를 억지로 재사용하지 않는다.

---

## 24. 60분 Release Gate

간편/정밀 모두 최소:

- 10분
- 30분
- 60분

검증한다.

확인:
- delay drift
- audio dropout
- underrun/read error
- memory increase
- ANR/crash
- thermal
- USB reconnect
- charging/powered hub 조건

---

## 25. TF Quality 상태

Transfer Function 결과에 Quality 상태를 둔다.

예:

```text
GOOD
FAIR
LOW CONFIDENCE
```

판정 후보:
- coherence
- delay lock
- drift lock
- dropped blocks
- clipping
- reference level
- measurement level

실측 후 threshold 확정.

---

## 26. Clipping / Low Signal

Reference 또는 Measurement가:
- clipping
- too low
- silent

이면 정상 그래프처럼 계속 표시하지 않는다.

경고 또는 LOW CONFIDENCE 상태로 전환한다.

---

## 27. 테스트 신호 안전

> 믹서와 앰프의 레벨을 낮춘 상태에서 시작하세요.  
> 테스트 신호는 큰 음압으로 재생될 수 있습니다.

앱이 자동으로 위험한 출력 gain을 올리지 않는다.

---

## 28. 단위

- Delay: `ms`
- Magnitude: `dB relative`
- Coherence: `0.00 ~ 1.00`
- Phase: `degrees`

---

## 29. Averaging

Transfer Function 전용 averaging 설정을 둔다.

후보:
- None
- Short
- Medium
- Long

RTA smoothing과 TF averaging은 별개다.

---

## 30. 저장 데이터

Transfer Function snapshot/session에 다음을 저장할 수 있도록 설계한다.

```text
timestamp
mode(simple / precision)
reference device
measurement device
reference channel
measurement channel
sample rate
FFT size
window
delay
drift status
magnitude
coherence
phase
calibration profile
measurement position
notes
```

---

## 31. 권장 패키지 구조

실제 프로젝트 구조를 우선 분석하되 예시는 다음과 같다.

```text
transfer/
  TransferMode.kt
  ReferenceSource.kt
  TransferEngine.kt
  DelayEstimator.kt
  DriftEstimator.kt
  MagnitudeEstimator.kt
  CoherenceEstimator.kt
  PhaseEstimator.kt
  TransferAverager.kt
  TransferQuality.kt
```

---

## 32. 개발 Phase

### TF-0 — Architecture / Feasibility
- 기존 `SignalPlayer` 분석
- 기존 `MicSource` 분석
- timestamp 구조 확인
- 2채널 USB capture 가능성 검증
- buffer ownership 설계

### TF-1 — Delay Finder
- synthetic test
- 실기기 test
- stable delay lock

### TF-2 — Clock Drift
- 1/5/10/30/60분
- ppm estimation
- correction/resampling 필요성 결정

### TF-3 — Magnitude
- flat
- known gain
- known EQ response

### TF-4 — Coherence
- correlated signal
- independent noise
- SNR 변화
- reflection/간섭 상황

### TF-5 — Phase
- known phase offset
- known delay
- wrap/unwrapped 검증

### TF-6 — 간편 모드 UI
- Built-in Mic + USB-C
- quality indicator
- 제한사항 표시

### TF-7 — 정밀 모드
- 2-channel interface
- Reference/Measurement channel selection
- shared-clock verification

### TF-8 — Multi-Point
- 위치 저장
- 곡선 비교
- 평균/median/envelope

### TF-9 — Field Validation
- 예배당 Main PA
- Main/Sub
- Main/Delay
- 장시간 측정

---

## 33. Unit Test 필수

### Delay
- 0 ms
- 5 ms
- 20 ms
- fractional-sample delay
- noise 포함

### Drift
- 0 ppm
- +20 ppm
- -50 ppm
- 장시간 simulated drift

### Magnitude
- flat
- +6 dB
- -6 dB
- known EQ curve

### Coherence
- identical → high
- independent noise → low
- SNR 단계별

### Phase
- 0°
- 45°
- 90°
- 180°
- delay-derived phase slope

---

## 34. Codex 독립 검증 Gate

각 핵심 단계는 Codex 검증을 거친다.

검증 항목:
- FFT scaling
- complex transfer function
- cross spectrum
- auto spectrum
- coherence formula
- averaging domain
- phase wrap/unwrap
- delay correction
- fractional delay
- drift estimation
- resampling artifact
- timestamp assumption
- channel alignment
- dropped-buffer handling

Critical/High 이슈가 있으면 다음 Phase로 넘어가지 않는다.

---

## 35. 간편 모드 실기기 Gate

Galaxy S23 Ultra:

```text
Built-in Mic
+
USB-C wired output
+
Internal Pink Noise
```

필수:
- 60분
- Delay 안정성
- Clock Drift
- Magnitude 안정성
- Coherence
- Phase quality
- USB 분리/재연결

---

## 36. 정밀 모드 실기기 Gate

UMC404HD 또는 동급 2채널 이상 USB Audio Interface:

```text
Reference Channel
+
Measurement Mic Channel
```

필수:
- multichannel simultaneous capture
- 동일 sample count
- shared-clock 확인
- channel swap
- disconnect/reconnect
- gain change
- 60분 안정성

---

## 37. 금지 표현

앱/UI/문서에서 근거 없이 다음 표현을 사용하지 않는다.

- Smaart와 동일
- Class 1
- Class 2
- 공인 측정
- 법정 측정 대체
- 자동 EQ 정답

기능과 한계를 그대로 설명한다.

---

## 38. 완료 보고 형식

각 Phase 종료 시:

1. 구현 완료 항목
2. 변경 파일
3. DSP 수학 정의
4. buffer/timestamp 구조
5. unit test 결과
6. 실기기 test 결과
7. drift 결과
8. 알려진 제한
9. 기존 기능 회귀 여부
10. Codex 검증 요청 범위

---

## 39. 최종 완료 조건

### 간편 Transfer Function
- Built-in Mic + USB-C Output 동시 사용
- Internal Reference
- Delay Finder
- Clock Drift 추적/보정
- Magnitude
- Coherence
- Phase 또는 신뢰도 부족 시 제한 표시
- 60분 실기기 검증
- USB reconnect 복구
- 기존 SPL/RTA 정상

### 정밀 Transfer Function
- USB Interface 2채널 동시 입력
- Reference/Measurement 채널 선택
- same-clock 확인
- Delay
- Magnitude
- Coherence
- Phase
- 60분 안정성
- Measurement Mic Calibration 적용
- Main/Sub 및 Main/Delay field test

---

## 40. 핵심 원칙 요약

> **간편 모드는 스마트폰 하나로 쉽게 측정하는 것이 목적이다.**  
> 내장 마이크와 USB-C 출력을 사용하되, 서로 다른 clock과 스마트폰 내부 오디오 처리의 한계를 숨기지 않는다.

> **정밀 모드는 동일 오디오인터페이스의 Reference / Measurement 채널을 사용한다.**  
> 가능한 한 동일 multichannel capture stream과 shared clock을 사용한다.

> **Delay → Clock Drift → Magnitude → Coherence → Phase 순서로 개발한다.**  
> 시간축이 맞지 않은 상태에서 그럴듯한 그래프를 만들지 않는다.

> **Coherence가 낮은 dip을 EQ로 자동 보정하지 않는다.**

> **RTA와 Transfer Function은 서로 다른 도구다.**  
> RTA는 현재 입력의 에너지 분포이고, Transfer Function은 Reference 대비 Measurement의 전달 특성이다.
