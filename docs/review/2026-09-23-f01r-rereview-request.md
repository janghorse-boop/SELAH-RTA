# 독립 재검토 요청 — F01-R 을 닫았다

**보낸 사람**: 개발 장훈 (JANGHUN) · JOA — Lead Developer
**받는 사람**: Codex — 독립 검증자 (명세 §24)
**날짜**: 2026-09-23
**범위**: `563c3ed..69cf550` (두 커밋, 352 줄 추가 / 1 줄 삭제, 6 파일)
**직전 검토서**: `docs/review/SELAH-RTA-RCPF01-rereview.md`
**보내 주신 probe**: `docs/review/RCPF-PostLimitProbe.kt`

---

## 0. 한 줄 요약

High 1건을 닫았다. 고치기 전에 probe 를 본문 그대로 옮겨 재현했다.

지적의 핵심은 **새 함수의 계약이 덜 되어 있었다**는 것이었다 —
`judgeCalibration` 을 「계산 뒤를 본다」고 만들어 놓고, 정작 본 것은
유효점 **개수**와 **양끝 주파수**뿐이었다. 가운데가 무너진 것은 그
둘로는 보이지 않는다.

---

## 1. 고치기 전 — 재현됐다

```
POST_LIMIT approved=30/31 pre=Pass final=Pass auto=true
           valid=49/120 centers=3/31
           span=25.19842099789747..19330.54592372118 norm=40
```

앞선 두 반례가 Fail 로 남아 있는 것도 같은 실행에서 확인됐다:

```
INTERSECTION approved=8 verdict=Fail auto=false
NARROW verdict=Fail auto=false
```

커밋 `56bfbf9` 가 이 재현만 담는다.

---

## 2. 고친 뒤

```
POST_LIMIT approved=30/31 pre=Pass final=Fail auto=false
           valid=49/120 centers=3/31
           span=25.19842099789747..19330.54592372118 norm=40
```

**`valid` · `centers` · `span` · `norm` 이 모두 그대로다.** 곡선은
건드리지 않았고 판정만 바뀌었다 — 「마스크 자체가 사라지거나 무효
보정값이 적용된다는 주장은 아니다」는 지적과 같은 자리다.

---

## 3. 「지원된다」를 어떻게 정의했나 (권고의 핵심)

`CalibrationOutcome.supportedBands` 를 더했다.

> 어떤 1/3옥타브 밴드가 **지원된다**는 것은, 그 밴드의 경계
> `[lowerEdge, upperEdge]` 안에 축 점이 하나 이상 있고 **그 점들이
> 모두** `correction.valid` 인 경우다.

`judgeCalibration` 이 `supportedBandRatio` 를 기존
`QualityPolicy.minUsableBandRatio`(60%) 와 견준다.

### 왜 축 점이 아니라 밴드인가

「보간 점 개수를 원래 대역 수와 무조건 동일하게 취급하지 않는다」는
지적 그대로다. 축 점 수는 설정에 따라 달라진다:

| 옥타브당 점 | 축 점 수 |
|---|---|
| 6 | 60 |
| 12 (기본) | 120 |
| 24 | 240 |

정책이 **원래 밴드 비율**로 되어 있으므로 계산 뒤의 지원도 같은 단위로
세야 견줄 수 있다. 이것을 시험으로 잰다 — 6점/옥타브와 24점/옥타브가
**같은 지원 대역 수**를 낸다(`축 해상도를 바꿔도 지원 대역 수가 같다`).

### 왜 「모두」 유효해야 하는가

실제 보정은 [`binCorrectionLinear`] 로 **칸마다** 걸린다. 밴드의 절반만
유효하면, 그 밴드에 에너지가 어디 놓이느냐에 따라 못 믿는 자리를 지난다.
절반만 살아 있는 것을 「보정된다」고 부르지 않기로 했다.

예: 밴드 17(1000Hz)의 경계는 891.3~1122.0Hz 이고, 기본 축에서 그 안에
서너 점이 든다. 그 넷이 다 유효해야 이 밴드를 센다.

### 양끝 범위는 따로 남겼다

「양끝 범위는 별도 안내로 유지하되 내부 결손과 상한 초과 제외를 숨기지
않는다」는 지적대로, 범위 안내(Degraded)는 그대로 두고 **지원 비율 미달은
따로 Fail** 로 낸다. 문구에도 상한을 적는다 — 「보정량이 상한(12dB)을
넘는 자리가 많다는 뜻입니다」.

---

## 4. 요구하신 회귀 시험

| 요구 | 시험 |
|---|---|
| 1. 위 세션 그대로, 입력 통과·정규화 양수·양끝 통과를 **전제로 단언**한 뒤 최종 Pass 금지 | `상한으로 가운데가 무너지면 최종 승인을 막는다` |
| 2. 정상 평탄 입력 → Pass | `평탄한 입력은 최종 승인을 받는다` |
| 3. 최종 유효점 0개 / 좁은 단일 구간 / 양끝만 남고 중간이 빔 | 0개는 `믿을 수 있는 대역이 없으면 거절한다`, 좁은 구간은 `CAL 범위가 좁으면 승인에 반영된다`, 양끝만 남는 경우가 위 1번 |
| 4. 축 해상도 변경 시 판정 일관성 | `축 해상도를 바꿔도 지원 대역 수가 같다` |
| 5. 저장 경로 통합 시험 | **아직 못 한다** — §6 참고 |

1번의 전제 단언은 이렇게 넣었다:

```kotlin
assertTrue("입력 교집합은 넉넉하다", q.approvedRatio >= 0.6)
assertEquals("입력 판정은 통과다", QualityVerdict.Pass, judgeQuality(q).verdict)
assertTrue("정규화 자리도 있다", out.normalizeSupportPoints > 0)
assertTrue("아래끝이 100Hz 보다 낮다", lo < 100.0)
assertTrue("위끝이 8kHz 보다 높다", hi > 8_000.0)
assertTrue("지원 대역이 절반도 안 남아야 한다", out.supportedBandRatio < 0.5)
```

「다른 관문이 대신 막아도 통과로 보이는」 것을 막으라는 뜻으로 읽었다.

그리고 「`judgeCalibration` 에 대한 직접 회귀 단언이 없다」는 지적은
이것으로 닫았다 — `CalibrationSessionTest:659, 676` 이 그 함수의 판정을
직접 단언한다.

---

## 5. 되돌려서 확인 (변이 2건, 모두 잡힘)

| 변이 | 실패한 시험 |
|---|---|
| 계산 뒤 지원 검사 제거 | 1건 |
| 지원 정의를 「하나라도 유효하면」으로 | 2건 |

---

## 6. 열려 있는 것 (이번에 줄지 않았다)

- **`judgeCalibration` 을 production 저장 경로가 아직 부르지 않는다.**
  시험과 probe 뿐이다(코드 검색으로 확인). 마법사를 붙일 때 저장·자동
  적용이 이 함수를 거치게 해야 하고, 그때까지 **「저장이 실제로
  막히는가」는 검증되지 않은 상태**다. 요구하신 통합 시험 5번이 이것이다.
- `referenceCalApplied` 가 선언 Boolean 인 것 — 실제 bin 보정 증명 아님.
- `minFramesPerStep = 8` · `maxReferenceBandDriftDb = 2.0` 의 실측 근거
  없음. 이번에 더한 지원 비율은 **새 숫자를 만들지 않고** 기존
  `minUsableBandRatio` 를 재사용했다.
- 정규화 충분성 기준이 0 거절뿐인 것. 「결과가 불확실함을 표시하는 것과
  Pass 승인은 구분해야 한다」는 말씀을 받아, 정규화 대역 수·점 수·
  오프셋은 판정을 움직이지 않는 안내로만 적는다.
- 저장 형식 계약 미확정.
- **실제 음향 측정을 하지 않았다.** 파일 바인딩도, 교정 마법사도 없고
  `CalibrationCompareCard` 는 아직 한 번도 기기 화면에서 본 적이 없다 —
  DoD 7.4 는 그래서 못 잰다.
- 앞 회차들에서 열어 둔 것도 그대로다: `SignalPlayer.stopSink()` 의
  `releaseStarted` 가드가 어떤 단언에도 묶이지 않은 것, 알림의 정지 단추를
  실제로 눌러 본 적이 없는 것, TalkBack 을 실제로 들어 본 적이 없는 것.

## 7. 확인 범위

- dsp **345** · app **325** 테스트 통과, 실패 0, `:app:lintDebug` 통과.
  `--rerun-tasks` 로 캐시 없이 다시 돌렸다.
- 이번 변경은 `judgeCalibration` 과 `CalibrationOutcome` 두 자리뿐이다.
  곡선 계산 경로(`calibrateResponse` 이하)는 건드리지 않았다.
