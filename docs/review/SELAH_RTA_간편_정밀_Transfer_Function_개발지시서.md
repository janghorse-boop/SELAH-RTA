# SELAH RTA 간편·정밀 Transfer Function 개발지시서

> **개정 2026-10-01 — Codex 명세 검토 회신을 반영했습니다. 구현은 아직
> 시작하지 않았습니다.**
>
> | | 고친 곳 | 무엇 |
> |---|---|---|
> | 1 | **8장** | `H = Y/X` → **H1 스펙트럼 추정자**(`Sxy/Sxx`). H2·Hv 는 v1 에 안 넣음 |
> | 2 | **9장** | Coherence 를 **평균한 스펙트럼**으로 계산하는 식을 적음 |
> | 3 | **9장** | **유효 평균 1~7 숨김 / 8~15 안정화 중 / 16+ 표시** |
> | 4 | **29장** | `Averaging = None` 에서는 **Coherence 숨김** |
> | 5 | **5장** | 정밀 모드 기본 신호원은 **외부 Mixer/DSP/Console**. 앱 출력은 full-duplex 가 실기기로 검증된 장치에서만 선택지 |
> | 6 | **18장** | 마이크 곡선이 없으면 **「상대 비교 모드」로 제한**하고 화면에 그렇게 적음 |
> | 7 | **7장** | Clock Drift 를 **`AudioTimestamp` 로 먼저** 잼. 못 주는 route 는 **0 ppm 이 아니라 `UNAVAILABLE`** |
> | 8 | **29장** | 기본값 **FFT 8192 · Hann · 50% overlap**. 저역 정렬에는 16384 를 고급 선택지로 |
> | 9 | **10장** | Phase 에 **`delayRemovedMs` 를 함께 저장·표시** |
> | 10 | **19장** | Multi-Point 에서 **Phase 공간 평균 금지**, 크기는 **전력 평균** |
> | — | **32장** | 출시 단계를 넷으로 — **1차 MVP = Delay + Drift + Magnitude + Coherence** |
>
> **앞선 검토에서 제가 너무 세게 적은 것 하나를 Codex 가 바로잡았습니다**:
> 「FFT 블록이 음향 지연보다 반드시 길어야 한다」는 표현입니다. Delay Finder
> 로 **시간 정렬한 뒤** FFT 에 넣는 구조라면 그 둘은 직접적인 필수 관계가
> 아닙니다. 핵심 절충은 **주파수 분해능 ↔ 반사·시간 변화** 쪽입니다
> (29장에 그렇게 고쳐 적었습니다).

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

### 기본 신호원은 **앱이 아니라 외부 Mixer / DSP / Console** 이다

```text
Mixer / DSP / Console
        ├─ Reference → Interface CH1
        └─ PA → Speaker → 측정 마이크 → Interface CH2
```

**정밀 모드에서 SELAH RTA 는 기본적으로 2채널을 「받기만」 한다.**

**왜 그런가**: 같은 USB 인터페이스로 출력과 입력을 동시에 쓰면 입력이
**완전한 디지털 무음**이 된 사례가 실기기에 기록돼 있다(UMC404HD,
2026-09-30). 신호원을 외부에 두면 그 문제가 통째로 사라지고, 덤으로
**운용 중인 PA 를 그대로 잴 수 있다.**

앱 Signal Generator 로 같은 인터페이스에 내보내는 기능은 **삭제하지
않는다.** 다만 그 장치에서 **full-duplex 가 실기기로 검증된 경우에만**
선택지로 켠다. 검증 전에는 화면에서 고를 수 없게 한다 — 「안 될 수도
있습니다」라고 적어 두는 것으로는 부족하다. 되는 것처럼 보이다가
**디지털 무음을 정상 그래프로 그리게** 된다.

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

### 재는 방법 — **AudioTimestamp 로 먼저 잰다**

음향으로 재면 **클럭 드리프트와 방·온도 변화가 섞인다.** 음속은 1℃ 에 약
0.17% 변하고, 8 m 에서 1℃ 면 약 0.04 ms 다 — 찾으려는 드리프트와 같은
자릿수다.

`AudioTrack.getTimestamp()` 와 `AudioRecord.getTimestamp()` 는 frame
position 과 실제 시각의 짝을 준다. **음향이 전혀 필요 없다.**

```text
outputRate = ΔframesOut / ΔtimeOut
inputRate  = ΔframesIn  / ΔtimeIn

driftPPM = (outputRate / inputRate - 1) × 1,000,000
```

안정화된 뒤 **10초~1분 간격**으로 조회한다(안드로이드 문서 권고).

> **timestamp 를 못 주는 route 가 있다.** 일시적일 수도, 영구적일 수도
> 있다. 그때 **0 ppm 으로 처리하지 않는다** — 「드리프트 없음」으로 읽힌다.
> **`UNAVAILABLE` 로 명시**하고 그대로 보고한다.

**음향 확인은 가까이서 한다.** 폰을 스피커에서 수 cm 에 두면 음향 경로가
0.1 ms 수준이 되어 온도 영향이 사라진다. 멀리 두고 재면 드리프트가 아니라
**방이 데워지는 것을 재게** 된다.

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

**전달함수는 H1 추정자를 쓴다.** 스펙트럼을 **먼저 평균하고 그 뒤에
나눈다** — 블록마다 `Y/X` 를 내서 그 결과를 평균하면 편향되고, Coherence
를 낼 재료가 사라진다.

```text
Sxx = <X* · X>      자기 스펙트럼 (블록 평균)
Syy = <Y* · Y>
Sxy = <X* · Y>      상호 스펙트럼 (복소수로 평균 — 크기만 평균하지 않는다)

H1(f) = Sxy / Sxx
```

- X(f) = Reference
- Y(f) = Measurement

**왜 H1 인가**: 간편 모드의 Reference 는 앱 내부의 깨끗한 디지털 신호이고,
방·소음·마이크의 영향은 Measurement 쪽에 들어간다. 「입력은 상대적으로
깨끗하고 출력 쪽에 잡음이 있다」는 H1 의 전형적 가정과 맞는다.

**H2 와 Hv 는 v1 에 넣지 않는다.** Reference 자체의 잡음이 의미 있게 커지는
특수한 경우를 나중에 지원할 때 더한다.

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

**계산은 평균한 스펙트럼으로 한다** — 8장과 같은 `Sxx`·`Syy`·`Sxy` 다.

```text
γ²(f) = |Sxy|² / (Sxx · Syy)
```

### 평균 횟수가 모자라면 **표시하지 않는다**

**평균이 1회면 γ² 는 수식상 정확히 1.00 이다.**

```text
γ² = |X*·Y|² / (|X|²·|Y|²) = 1      입력이 무엇이든, 잡음만 재도 1.00
```

그대로 화면에 올리면 **가장 못 믿을 측정이 가장 믿음직해 보인다.** 그래서
유효 평균 횟수로 가른다.

| 유효 평균 | 표시 |
|---|---|
| **1~7** | **숨긴다.** 「측정 중」으로 적는다 |
| **8~15** | 표시할 수 있으나 **안정화 중**임을 함께 적는다 |
| **16 이상** | 기본 표시 |

**8 은 표시할 수 있는 최소 조건이고, 16 이 기본 목표다.**

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

### **제거한 Delay 를 반드시 함께 저장하고 표시한다**

```text
delayRemovedMs = 23.42
phase[]
```

같은 시스템이라도 **delay 보정값이 바뀌면 위상 곡선이 크게 달라진다.**
그 값이 안 보이면 두 측정을 견줄 때 **시스템이 변한 것인지 지연 추정이
변한 것인지 가릴 수 없다.**

Phase 그래프와 저장 자료 **양쪽에** 넣는다.

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

Measurement microphone의 frequency correction을 TF Measurement 경로에 **적용한다**.

단:
- Reference에는 적용하지 않는다.
- reading direction이 확정되지 않은 calibration file은 자동 적용하지 않는다.
- 기존 file hash / confirmation / orientation 보호 로직을 재사용한다.

### 곡선이 **없으면 상대 비교 모드로 제한한다**

간편 모드의 Reference 는 전기 신호다. 그래서 H 안에 **마이크 응답이 그대로
남는다** — 두 마이크를 견주는 구조가 아니라 상쇄되지 않는다.

**곡선이 없어도 쓸모 있는 것**(같은 마이크로 두 상태를 견주므로 상쇄된다):

- EQ 전/후 비교
- 같은 마이크로 위치 비교
- Delay 측정
- Main/Sub 상대 정렬
- Main/Delay 상대 정렬

**곡선이 있어야 하는 것**: 「이 시스템의 **절대적인** 주파수 응답은
어떤가」.

곡선이 없을 때 화면은 이렇게 적는다.

```text
상대 비교 모드
```

또는

```text
마이크 주파수 미보정 · 상대 비교용
```

**절대 응답으로 읽히게 두지 않는다.**

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
- Magnitude 개별 곡선
- Magnitude **전력 평균**(power average)
- Median magnitude
- Min/Max envelope

**단순 dB 산술평균을 쓰지 않는다.** 크기는 전력 영역에서 평균한다.

### **Phase 는 공간 평균하지 않는다**

위치마다 전파 지연이 달라, 평균하면 **뜻이 사라진다.** 이것을 기본 원칙으로
둔다 — 선택지로도 넣지 않는다.

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

### **`None` 에서는 Coherence 를 숨긴다**

평균이 1회면 γ² 는 수식상 **항상 1.00** 이다(9장). `None` 과 Coherence
표시를 함께 두면 **가장 못 믿을 설정이 가장 믿음직한 숫자를 띄운다.**

```text
Averaging = None   →  Coherence 숨김
그 밖            →  9장의 유효 평균 기준(1~7 숨김 / 8~15 안정화 중 / 16+ 표시)
```

**Averaging 은 재면서 계속 바꾸는 값이다.** 고급 설정에 묻지 말고 측정
화면에 둔다 — **고급 설정에 묻는 FFT 크기·Window 와는 다르게 다룬다.**

### 기본값

```text
FFT 크기   8192      (48kHz 기준 170.7ms · bin 5.86Hz)
Window     Hann
Overlap    50%
```

| FFT | 시간창 | bin 간격 |
|---|---|---|
| 4096 | 85.3 ms | 11.72 Hz |
| **8192** | **170.7 ms** | **5.86 Hz** |
| 16384 | 341.3 ms | 2.93 Hz |

**Sub/Main 저역 정렬처럼 저주파가 중요하면 16384 를 고급 선택지로** 둔다.

> **FFT 블록 길이와 음향 지연은 직접적인 필수 관계가 아니다.** Delay Finder
> 로 **시간 정렬한 뒤** FFT 에 넣는 구조이기 때문이다. 핵심 절충은
> **주파수 분해능 ↔ 반사·시간 변화** 쪽이다.

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

### 출시 단계는 **넷으로 줄인다**

```text
1차 MVP   Delay + Clock Drift + Magnitude + Coherence
2차       Phase
3차       정밀 2채널 모드
4차       Multi-Point
```

**이렇게 묶는 까닭**: Delay 와 Drift 가 시간축을 맞추고, 그 위에서
Magnitude 와 Coherence 가 **서로를 설명한다** — Coherence 없이 Magnitude
만 내면 믿을 수 없는 자리를 가릴 길이 없다. 넷을 한 묶음으로 내는 쪽이
기능 복잡도도 낮다.

**1차 MVP 의 간편 모드는 내장 마이크 보정 곡선이 없으면 「상대 비교
모드」로 제한한다**(18장).

아래 TF-0~TF-9 는 그 네 단계 안에서의 **작업 차례**다.

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
