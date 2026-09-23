# 독립 재검토 요청 — CP01 · CP02 · CP03 · CP04 를 닫았다

**보낸 사람**: 개발 장훈 (JANGHUN) · JOA — Lead Developer
**받는 사람**: Codex — 독립 검증자 (명세 §24)
**날짜**: 2026-09-23
**범위**: `5f33ea4..aa6a29b` (세 커밋, 938 줄 추가 / 152 줄 삭제)
**직전 검토서**: `docs/review/SELAH-RTA-calibration-pipeline-review.md`
**보내 주신 probe**: `docs/review/Calibration-PipelineProbe.kt`

---

## 0. 한 줄 요약

High 2건·Medium 2건을 모두 닫았다. **고치기 전에 보내 주신 probe 를 본문
그대로 옮겨 다섯 관측이 모두 보고서 값과 일치하는 것을 먼저 확인**했고,
고친 뒤 되돌리기 11건으로 검증했다.

지적해 주신 것 중 **제가 틀렸던 것 둘**이 있었다 — 요청서 §6.4 의 사실
오류와, §5 에서 제안한 수정안이 모자랐던 것. 둘 다 §4 에 적었다.

---

## 1. 고치기 전 — 다섯 관측이 모두 재현됐다

`docs/review/Calibration-PipelineProbe.kt` 를 `PipelineProbeTest.kt` 로
옮겼다. 바꾼 것은 JUnit 껍데기와 import, 그리고 **한 줄**뿐이다
(`CODEC_NAN` 의 `getOrThrow()` → `getOrNull()`. 성공일 때 값이 같아
고치기 전 출력은 그대로이고, 고친 뒤 decode 가 실패를 돌려줄 때 probe 가
예외로 죽지 않게 하려는 것이다).

| probe 관측 | 보고서 값 | 제 실행 |
|---|---|---|
| `UNSTABLE_REFERENCE` | spread=50, kept=2, dropped=0, **Pass** | 일치 |
| `SPECTRAL_DRIFT` | drift=0, Pass, correctionMax **6.7337** | `6.73374204940048` |
| `MASK` | snrUsable=false, correctionValid=**true**, 30Hz=**true** | 일치 |
| `CODEC_NAN` | success=**true**, validNan=**120** | 일치 |
| `CENTER_CAL` | actual **3.2554** | `3.2554237993212123` |

커밋 `929c4f6` 이 이 재현만 담고 있다 — 고침은 그 뒤에 있다.

---

## 2. 고친 뒤

```
UNSTABLE_REFERENCE spread=50.0 kept=2 dropped=0 noStable=true verdict=Fail
SPECTRAL_DRIFT drift=0.0 bandDrift=10.0 verdict=Fail correctionMax=6.73374204940048
MASK quality=Fail snrUsable=false correctionValid=false calOutside30HzValid=false
CODEC_NAN success=false validNan=-
CENTER_CAL refusedWhenCalNotApplied=true why=기준 측정에 CAL 이 걸리지 않았습니다…
```

**`correctionMax` 가 6.73 으로 남은 것은 의도한 것이다.** 곡선은 진단용으로
여전히 그리되 판정이 Fail 이라 저장·자동 적용이 막힌다 — 검토서의 「미검증
기준을 그래프로 보는 진단 동작과 저장 승인은 분리한다」를 그렇게 읽었다.
이 해석이 맞는지 봐 주시기 바란다.

API 가 바뀌어 probe 본문은 더 이상 그대로 컴파일되지 않는다(그것이 CP04 의
고침이다 — `applyMicCalibration` 이 사라졌다). **관측점은 그대로 두고** 새
API 로 겨누었고, 파일 머리에 고치기 전 값을 적어 두었다. 이 파일은 회귀
시험이 아니라 관측 도구라는 것도 KDoc 에 적었다.

---

## 3. 무엇을 어떻게 고쳤나

### CP01 — 세 가지가 겹쳐 있었다

제 요청서 §5 는 이것을 **한 가지 문제**로 보았는데 틀렸다.

1. **`repeatSpreadDb` 에 대상의 벌어짐만 담겼다.** 기준이 50dB 출렁여도
   대상이 얌전하면 통과했다. → 세 단계 중 **가장 나쁜 것**을 쓴다.
2. **폴백이 돌면 `droppedFrames = 0` 이라 「다 안정적이었다」로 읽혔다.**
   → `StepResult.noStableFrames` 를 따로 적고 관문이 막는다.
   `dropped`(정말 버린 수) · `kept` · `noStableFrames`(고를 것이 없었다)를
   구분하라는 지적 그대로다.
3. **앞뒤 기준을 광대역으로만 견주어 모양 변화가 상쇄됐다.** →
   `referenceBandDriftDb`(대역별 차이의 최댓값)를 따로 재고
   `QualityPolicy.maxReferenceBandDriftDb = 2.0` 으로 막는다. 광대역
   허용치(1.0)보다 느슨한 것은 대역 하나의 우연한 흔들림이 광대역보다
   크기 때문이다.

덤으로 **단일 프레임의 `spread = 0`** 을 `null`(모른다)로 바꿨고,
`QualityPolicy.minFramesPerStep = 8` 로 바닥을 뒀다. 벌어짐이나 장 수를
**모르는** 경우는 Pass 가 아니라 Degraded 다.

> **묻고 싶은 것**: `minFramesPerStep = 8` 은 근거가 약한 숫자다. 「바닥이지
> 충분함의 증명이 아니다」라고 KDoc 에 적어 두었고 정책 손잡이로 뺐지만,
> 실제 세션 길이를 기준으로 정하는 편이 나은지 의견을 구한다.

### CP02 — 빠진 고리는 입구 하나였다

`normalizeToBand` · `correctionCurve` · `smoothFractionalOctave` ·
`limitCorrection` 은 **이미 `valid` 를 제대로 다루고 있었다.** 평활은
무효 점을 평균에 넣지 않고 `valid` 를 그대로 복사한다.

빠진 것은 `interpolateToAxis` 에 **점별 유효성을 넣을 자리가 없었다**는
것뿐이다. 그래서 SNR 마스크가 들어갈 통로가 아예 없었다.

- `interpolateToAxis(points, axis, pointValid)` — **양끝이 다 믿을 만할
  때만** 그 사이를 믿는다(무효 구간을 가로질러 보간하지 않는다).
- `calibrateResponse(..., referenceValid, internalValid)`.
- `calibrateFromSession(session, quality, referenceCalRangeHz)` 이
  `quality.usable` 과 CAL 의 실제 측정 범위를 교집합으로 넘긴다.
- `CalibrationCurve.rangeHz` / `covers(hz)` 를 더했다 — `gainDbAt` 은 범위
  밖에서도 끝점 값을 돌려주므로 **숫자가 나온다는 것이 잰 적이 있다는
  뜻이 아니다.**
- 믿을 수 있는 대역이 하나도 없으면 `Result.failure` 로 거절한다.

> **아직 못 한 것**: **기준 경로의 SNR 을 따로 재지 않는다.** 배경을 경로
> 마다 재야 하는데 그 측정이 아직 없어서, 지금 기준 유효성은 CAL 범위만
> 본다. 회귀 항목으로 드신 「기준 측정만 SNR 미달」은 그래서 못 잰다.
> 측정 세션을 만들 때 채우겠다.

> **묻고 싶은 것**: 저신뢰 구간이 **정규화 대역 안**에 있는 경우, 지금은
> `normalizeToBand` 가 그 점을 빼고 평균하되 `pointsUsed` 만 줄어든다.
> 남은 점이 몇 개여야 오프셋을 믿을 수 있는지는 아직 관문이 없다.
> 이것도 막아야 하는가?

### CP03 — 길이가 맞는 것과 뜻이 맞는 것은 다르다

- `toDoubleOrNull` 이 `NaN`·`Infinity` 를 **성공으로** 읽는다 →
  `toFiniteOrNull` 로 바꿨다.
- 축이 **양수이고 엄격히 커지는지** 본다(역순·중복 축도 길이 검사는
  통과한다).
- `sampleRate > 0`, `channelCount ≥ 1`, `channelIndex ∈ [0, channelCount)`,
  `usableBandRatio ∈ [0,1]`, 유효 범위가 둘 다 있고 뒤집히지 않았는지,
  `updatedAt ≥ createdAt`, 정규화 대역·평활·상한이 양수인지.
- **`encodeCurves` 에서도** 다섯 곡선이 같은 축 위에 있는지 확인한다
  (지적하신 대로 `CalibrationOutcome` 은 공개 타입이고 배열은 가변이다).
- 망가진 입력 다섯 가지에서 **예외가 새지 않고** `Result.failure` 로
  돌아오는 것도 잰다.

### CP04 — 이 저장소에 이미 적혀 있던 교훈이었다

`applyMicCalibration` 을 **지웠다.**

반례 계산은 보내 주신 그대로 재현됐다: 한 밴드에 +6/−6dB 성분이 하나씩
있고 중심이 0dB 일 때, 실제 기준은 0dB 인데 측정 밴드 레벨은 3.2554dB 이고
중심에서 0 을 빼 봐야 **3.2554dB 가 그대로 남는다.**

부끄러운 것은 **이 저장소가 그 교훈을 이미 적어 두었다**는 점이다 —
`CalibrationCurve.bandCenterResponseDb` 의 KDoc 이 「측정값 보정에 쓰지
않는다 … 예전에는 아래끝·중심·위끝을 전력 평균해 뺐는데 … +2.4157dB …
(독립 검증 R05)」라고 말한다. 새 기준 경로에서 같은 계산을 되살렸다.
**문서에 적어 두는 것만으로는 막히지 않는다**는 증거다.

이제 CAL 은 `binCorrectionLinear` 로 FFT 칸에 걸고, 세션은 그렇게 보정된
스펙트럼을 받는다. `CalibrationSession(referenceCalApplied = …)` 가 그것을
기록하고, false 면 `calibrateFromSession` 이 **거절한다** — 이중 적용도
이 표시로 막는다.

> **묻고 싶은 것**: 지금은 `referenceCalApplied` 가 **부르는 쪽이 선언하는
> 값**이다. 칸 보정을 실제로 걸었는지 세션이 확인할 길은 없다. 캡처 경로를
> 붙일 때 더 나은 이음매가 있는가? (예: 보정된 스펙트럼을 만드는 함수가
> 표시가 붙은 타입을 돌려주게 하는 식)

---

## 4. 제가 틀렸던 것 둘

### 4.1 요청서 §6.4 의 사실 오류 — 지적해 주신 그대로다

「저장된 `deviceAddress` 가 빠지면 적용 시 케이스 경고도 없어진다」고
썼는데 **틀렸다.** `judgeProfileApply` 는 `was.isRear` 가 아니라
**`now.isRear`** 를 본다(`MeasuredProfile.kt:275`). 현재 경로의 주소가
`back` 이면 경고는 그대로 남는다. 코드를 확인하고 바로잡았다.

저장된 메타데이터가 손실되는 것은 별개 문제라는 말씀도 맞다 —
`labelKo` 가 물리 위치를 잃는다. 그건 아직 손대지 않았다.

### 4.2 §5 에서 제안한 수정안이 모자랐다

요청서에서 「`StepResult` 에 `noStableFrames` 를 더하면 된다」고 제안했다.
검토서의 「noStableFrames 만 표시하고 기존 품질 계산을 유지하면 CP01 을
막지 못한다」가 맞다 — **기준 단계의 벌어짐이 애초에 품질로 전달되지
않고 있었고**, 광대역 비교가 모양 변화를 상쇄하고 있었다. 제 진단은 셋 중
하나만 본 것이었다.

---

## 5. 되돌려서 확인 (변이 11건, 모두 잡힘)

| 변이 | 실패한 시험 |
|---|---|
| `toFiniteOrNull` → `toDoubleOrNull` | 3건 |
| 대역별 흐름을 0 으로 | 1건 |
| 벌어짐을 대상만 보게 + `noStableFrames` 를 false 로 | 2건 |
| `interpolateToAxis` 의 점별 유효성 무시 | 3건 |
| CAL 미적용 거절 제거 | 1건 |
| (앞 커밋에서) 신원 비교 제거 / 모노 규칙 제거 / 피해 쓰기 제거 / 마지막 `=` | 앞 회차에서 확인 |

## 6. 시험 표본을 온전하게 만든 것 (관문을 느슨하게 하지 않았다)

`CalibrationQualityTest` 넷이 깨졌다. **반복성을 말할 근거가 없는 보고는
이제 Pass 가 되지 않기 때문**인데, 그건 의도된 동작이다. 그래서 관문을
되돌리는 대신 표본에 반복성 항목을 채우는 `report()` 도우미를 두고 그 넷을
거기로 옮겼다. 그 시험들은 SNR 문턱과 정책 배선을 재는 것이지 반복성을
재는 것이 아니다.

---

## 7. 확인 범위와 한계 (명시)

- dsp **326** · app **325** 테스트 통과, 실패 0, `:app:lintDebug` 통과.
  `--rerun-tasks` 로 캐시 없이 다시 돌려 확인했다.
- **실제 음향 측정은 여전히 하지 않았다.** 지금까지 잰 것은 계산이지
  소리가 아니다. 사무실이 조용해지면 이 파이프라인에 진짜 핑크 노이즈를
  흘려 넣는다.
- **아무것도 파일에 쓰이지 않는다.** `ProfileCodec` 은 글자로 바꾸는
  데까지고 DataStore/파일 바인딩이 없다.
- **화면에 붙지 않았다.** 교정 마법사(지시서 6장 마지막 줄)가 없고,
  `CalibrationCompareCard` 는 아직 한 번도 기기 화면에서 본 적이 없다.
  비교 화면의 수치가 저장 데이터와 일치하는지(DoD 7.4)는 그래서 못 잰다.
- 기준 경로 SNR 미측정(§3 CP02), `referenceCalApplied` 가 선언값인 것
  (§3 CP04), 정규화 대역 안의 저신뢰 구간 관문 없음 — 세 가지가 열려 있다.
- 앞선 회차에서 열어 둔 것도 그대로다: `SignalPlayer.stopSink()` 의
  `releaseStarted` 가드가 어떤 단언에도 묶이지 않은 것, 알림의 정지 단추를
  실제로 눌러 본 적이 없는 것, TalkBack 을 실제로 들어 본 적이 없는 것.
- `QualityPolicy` 의 새 기본값 둘(`maxReferenceBandDriftDb = 2.0`,
  `minFramesPerStep = 8`)은 **실측 근거가 없는 숫자**다. 정책 손잡이로
  빼 두었으나 값 자체는 검토가 필요하다.
