# SELAH RTA - SPL 가중치(A/C/Z) 검토 및 구현 지시서

## 1. 목적

SELAH RTA의 SPL, Leq, Peak 및 RTA/FFT 측정에서 **주파수 가중치(Weighting)**가 올바르게 적용되고 있는지 기존 구현을 검토하고, 필요한 경우 **A / C / Z 가중치**를 추가 또는 수정한다.

가중치는 단순 UI 옵션이 아니라 SPL 및 Leq 계산 결과에 직접 영향을 주므로 **DSP 계산 단계와 표시 단위를 함께 검토**해야 한다.

---

## 2. 가중치 개념

주파수 가중치(Frequency Weighting)는 마이크로 측정된 신호에 주파수별 보정 특성을 적용하여 음압레벨을 계산하는 방식이다.

| 가중치 | 표시 | 의미 | 주요 용도 |
|---|---|---|---|
| A-weighting | dBA | 저주파와 극고주파를 크게 감쇠 | 청감 기반 SPL, Leq |
| C-weighting | dBC | 저주파를 A보다 많이 반영 | 라이브 음향, 높은 레벨, Peak |
| Z-weighting | dBZ | 거의 평탄한 주파수 응답 | RTA/FFT 및 원신호 분석 |

---

## 3. 기존 코드 우선 검토

새 기능을 바로 구현하지 말고 먼저 현재 프로젝트의 SPL/DSP 구조를 확인한다.

- 현재 SPL 계산 방식
- RMS 계산 위치
- Peak 계산 방식
- Leq 구현 여부 및 계산 방식
- A-weighting 구현 여부
- C-weighting 구현 여부
- Z-weighting 또는 Flat 측정 여부
- weighting filter가 적용되는 DSP 위치
- Calibration 보정과 Weighting의 적용 순서
- RTA/FFT 데이터에 weighting이 적용되고 있는지
- 화면에 `dB`, `dBA`, `dBC`, `dBZ`가 어떻게 표시되고 있는지

이미 구현된 기능이 있으면 중복 구현하지 말고 기존 구조를 최대한 활용한다.

---

## 4. 기본 측정 정책

### SPL

기본: `A-weighting`

표시 예: `87.4 dBA`

사용자가 필요할 경우 A / C / Z를 선택할 수 있도록 한다.

### Leq

기본: `A-weighting`

표시 예: `LAeq 1min 85.9 dBA`

Leq는 선택된 시간 동안의 **음향 에너지 평균**으로 계산해야 하며 단순 dB 값의 산술평균을 사용해서는 안 된다.

지원 시간은 현재 Leq 설계와 함께 검토하되 다음 구성을 우선 검토한다.

- 10 sec
- 30 sec
- 1 min
- 5 min
- Session

기본값: `1 min`

Session Leq는 측정 시작부터 현재까지의 누적 Leq를 의미한다.

---

## 5. Peak 가중치

Peak는 SPL/Leq와 별도로 관리한다.

우선 검토 대상: `C-weighting 또는 Z-weighting`

라이브 음향 환경에서는 순간적인 킥, 스네어, 베이스 등의 높은 음압을 확인할 필요가 있으므로 A-weighting만으로 Peak를 처리하지 않는다.

표시 예: `LCpeak 103.2 dBC`

현재 Peak 구현이 단순 sample peak인지, peak SPL인지 반드시 확인한다.

**Sample Peak와 Acoustic Peak SPL을 혼동하지 않도록 한다.**

---

## 6. RTA / FFT 가중치

RTA 및 FFT의 목적은 실제 주파수별 에너지 분포를 분석하는 것이므로 기본값을 다음과 같이 한다.

`Z-weighting / Flat`

A-weighting을 RTA 기본값으로 사용하지 않는다.

예: `RTA : Z (Flat)`

31-band RTA에서도 동일한 원칙을 적용한다.

사용자가 A/C weighted spectrum을 볼 필요가 있는 경우 선택 기능을 제공할 수 있지만 기본 분석 화면은 Flat/Z로 유지한다.

---

## 7. FR(Frequency Response)

FR 분석에서도 기본적으로 `Z / Flat`을 사용한다.

FR은 시스템 또는 공간의 주파수 응답 특성을 확인하는 기능이므로 사람의 청감 특성을 반영하는 A-weighting을 기본 적용하면 안 된다.

Calibration 파일에 포함된 마이크 주파수 응답 보정과 A/C/Z weighting은 서로 다른 개념이므로 코드에서 명확히 분리한다.

---

## 8. Calibration과 Weighting의 관계

다음 세 개념을 혼동하지 않는다.

### Microphone Calibration
마이크 자체의 주파수 응답 편차 보정

### SPL Calibration
측정 시스템의 절대 음압 기준 보정  
예: `94 dB @ 1 kHz`

### Frequency Weighting
측정된 신호에 A/C/Z 주파수 특성을 적용

따라서 Calibration과 Weighting은 서로 독립적인 기능이어야 한다.

개념적으로 다음 구조를 검토한다.

`Audio Input`
→ `Device/Input Processing`
→ `Microphone Calibration`
→ `SPL Calibration`
→ `Analysis Path 분기`
→ `A/C/Z Weighting`
→ `RMS / Leq / Peak 계산`
→ `UI`

단, 실제 구현 순서는 현재 DSP architecture와 수치 안정성을 검토하여 결정한다.

---

## 9. UI 설계

SPL 화면에서 Weighting 선택 기능을 제공한다.

`Weighting : [ A ] [ C ] [ Z ]`

기본: `A`

사용자가 선택하면 표시 단위도 자동 변경한다.

- A 선택: `87.4 dBA`
- C 선택: `91.8 dBC`
- Z 선택: `93.1 dBZ`

단순히 모든 값을 `dB`라고 표시하지 않는다.

---

## 10. Leq 표시

- A: `LAeq 1m 85.9 dBA`
- C: `LCeq 1m`
- Z: `LZeq 1m`

계산에 실제 적용된 가중치와 UI 표기가 반드시 일치해야 한다.

---

## 11. 권장 측정 화면 구성

```text
SPL
87.4 dBA

LAeq 1m
85.9 dBA

LAeq Session
84.7 dBA

LCpeak
103.2 dBC

Weighting
[A] [C] [Z]

RTA
Z / Flat
```

---

## 12. DSP 구현 시 주의사항

A/C weighting은 화면에 보정 숫자를 더하거나 빼는 방식으로 구현하면 안 된다.

주파수에 따라 보정량이 달라지므로 **실제 weighting filter 또는 수학적으로 동등한 처리**가 필요하다.

다음을 검증한다.

- Sample Rate별 filter coefficient
- 44.1 kHz
- 48 kHz
- Android 장치에서 실제 사용되는 sample rate
- filter state 유지
- buffer boundary에서 연속성
- channel별 처리
- stereo/mono 처리
- USB Audio 입력
- 내장 마이크 입력
- clipping 처리
- floating-point precision

---

## 13. Leq 계산 검증

Leq는 dB 값의 단순 평균으로 계산하지 않는다.

잘못된 예:

```text
(80 dB + 90 dB) / 2
```

선형 음향 에너지 영역에서 누적한 뒤 로그 스케일로 변환하는 구조를 사용한다.

Rolling Leq와 Session Leq는 별도로 관리한다.

특히 10초/30초/1분/5분 rolling window 구현 시 메모리 사용량을 불필요하게 증가시키지 않는 구조를 검토한다.

---

## 14. 테스트 요구사항

A/C/Z 구현 후 unit test 또는 DSP validation test를 추가한다.

최소한 다음 주파수에서 weighting response를 확인한다.

```text
31.5 Hz
63 Hz
125 Hz
250 Hz
500 Hz
1 kHz
2 kHz
4 kHz
8 kHz
16 kHz
```

특히 **1 kHz에서 A/C weighting이 기준상 거의 0 dB 보정**이 되는지 확인한다.

추가 테스트:

- sine wave
- pink noise
- white noise
- silence
- clipping signal
- 실제 microphone input

---

## 15. 기존 Calibration 기능 회귀 테스트

Weighting 추가로 기존 Calibration 결과가 변경되거나 중복 보정되지 않는지 확인한다.

테스트 대상:

- Galaxy 내장 마이크
- Dayton iMM-6 / iMM-6C
- Dayton EMM-6
- USB Audio Interface
- Calibration file 적용 상태
- Calibration file 미적용 상태

Weighting 변경이 Calibration profile 자체를 변경해서는 안 된다.

---

## 16. 설정 저장

권장 정책:

- SPL Weighting → 사용자 설정 저장
- Leq → SPL Weighting과 연동 또는 별도 설정
- Peak → 별도 기준 사용 가능
- RTA → 기본 Z/Flat
- FR → 기본 Z/Flat

Peak를 SPL Weighting 설정에 무조건 종속시키지 않는다.

---

## 17. 최종 검토 결과 보고

코드 수정 전에 먼저 현재 구현 상태를 분석하고 다음 형식으로 보고한다.

```text
[Weighting Implementation Review]

1. 현재 SPL 계산 방식:
2. 현재 A-weighting 구현 여부:
3. C-weighting 구현 여부:
4. Z-weighting 구현 여부:
5. Leq 구현 여부:
6. Peak 계산 방식:
7. RTA weighting 상태:
8. FR weighting 상태:
9. Calibration 적용 순서:
10. 발견된 문제:
11. 수정 필요 파일:
12. 제안 구현 방식:
13. 테스트 계획:
```

분석 후 기존 구조와 충돌하지 않는 범위에서 구현한다.

---

## 18. 최종 목표

SELAH RTA의 측정 체계를 다음과 같이 명확하게 분리한다.

### 청감/평균 음압
- `SPL → dBA`
- `Leq → LAeq`

### 순간적인 높은 음압
- `Peak → LCpeak 또는 적절한 Peak 기준`

### 주파수 분석
- `RTA → Z / Flat`
- `FFT → Z / Flat`
- `FR → Z / Flat`

모든 측정 화면에서 실제 DSP에 적용된 가중치와 표시되는 단위가 반드시 일치하도록 한다.

> **중요:** UI에서 dBA/dBC/dBZ만 변경하고 실제 DSP 결과가 동일한 상태가 발생해서는 안 된다.
