# 독립 재검토 요청 — RCP-F01 을 닫았다

**보낸 사람**: 개발 장훈 (JANGHUN) · JOA — Lead Developer
**받는 사람**: Codex — 독립 검증자 (명세 §24)
**날짜**: 2026-09-23
**범위**: `be2f8c7..5022fe4` (두 커밋, 434 줄 추가 / 51 줄 삭제, 9 파일)
**직전 검토서**: `docs/review/SELAH-RTA-RCP01-RCP03-rereview.md`
**보내 주신 probe**: `docs/review/RCP-ApprovalProbe.kt`

---

## 0. 한 줄 요약

High 1건을 닫았다. 고치기 전에 probe 를 본문 그대로 옮겨 두 관측이
일치하는 것을 먼저 확인했다.

지적의 핵심은 **제가 `bothUsable` 을 만들어 놓고 판정에서 쓰지 않았다**는
것이었다. 앞 회차에 RCP02 를 고치며 교집합을 계산해 두었는데, 정작
`judgeQuality` 는 여전히 각 경로를 따로 보고 있었다.

---

## 1. 고치기 전 — 두 관측이 재현됐다

| probe 관측 | 검토서 값 | 제 실행 |
|---|---|---|
| `INTERSECTION` | target=20 reference=19 common=8 **Pass auto=true** correctionValid=24 normPoints=24 | 일치 |
| `NARROW_CAL` | **Pass auto=true** correctionValid=4 normPoints=4 bands=2 | 일치 |

커밋 `6cb85e8` 이 이 재현만 담는다.

---

## 2. 고친 뒤

```
INTERSECTION target=20 reference=19 common=8 total=31 verdict=Fail auto=false correctionValid=24 normPoints=24
NARROW_CAL verdict=Fail auto=false correctionValid=4 normPoints=4 bands=2
```

`common=8` 과 `correctionValid=24` 는 그대로다 — 마스크와 곡선은 원래
맞았고, **틀렸던 것은 승인과 안내**였다는 지적 그대로다.

---

## 3. 무엇을 어떻게 고쳤나

### 3.1 승인을 교집합으로 (권고 1)

- `QualityReport` 에 `referenceCalRangeHz` 가 들어간다. `bothUsable` 이
  **대상 SNR ∧ 기준 SNR ∧ CAL 범위**를 본다.
- `approvedCount` · `approvedRatio` · `approvedRangeHz` 를 더하고,
  최소 비율 검사와 좁은 범위 안내가 **이것들을** 쓴다.
- 각 경로의 개별 통계는 지우지 않고 **까닭 문구에 함께 적는다** —
  「보정에 쓸 수 있는 대역이 8/31(26%)뿐입니다 (대상 20 · 기준 19 ·
  CAL 20~20000Hz)」처럼. 어느 쪽을 고쳐야 하는지는 그 둘을 봐야 안다.

### 3.2 실제 보정 범위를 최종 승인에 (권고 2)

`judgeCalibration(quality, outcome)` 을 더했다. `judgeQuality` 는 **잰
것**만 보는데, 보정이 실제로 걸리는 범위는 그 뒤에도 더 줄어든다 —
공통 축 보간, 정규화 지지구간, 보정 상한. 그래서 곡선까지 보고 나서
한 번 더 판정한다:

- `correction.valid` 가 하나도 없으면 Fail.
- 계산을 마친 뒤 남은 범위가 좁으면 Degraded + 그 범위를 적는다.
- 정규화 지지구간이 0 이면 Fail.
- **몇 개 대역에서 레벨을 맞췄는지는 판정을 움직이지 않는 안내로** 적는다
  (아래 §5 참고).

### 3.3 승인과 곡선이 다른 CAL 범위를 볼 수 없게 (권고 3의 전제)

`calibrateFromSession` 에서 `referenceCalRangeHz` **인자를 없앴다.**
이제 품질 보고에서 읽는다.

RCP-F01 은 결국 「승인이 본 것」과 「곡선이 쓴 것」이 갈라져 있던
문제였다. 인자로 따로 받는 한 부르는 쪽이 둘을 다르게 줄 수 있으므로,
**받는 곳을 하나로 만들어 갈라질 수가 없게** 했다.

---

## 4. **제 기대값이 또 두 번 틀렸다**

회귀 시험을 쓰면서:

| 내가 적은 값 | 실제 | 까닭 |
|---|---|---|
| 교집합 **7** | **8** | 12~18 의 일곱에 index 30 이 더해진다 — probe 가 보고한 `common=8` 과 같다 |
| CAL 200~6000Hz → **Degraded** | **Fail** | 15/31 = 48.4% 로 비율 관문(60%)에 먼저 걸린다 |

두 번 다 **코드가 맞고 제 셈이 틀렸다.** 값을 느슨하게 하는 대신
계산해서 표본을 고쳤다(좁은 범위 안내를 보려면 20~1300Hz = 19/31 =
61.3% 가 필요하다).

이 세션에서 제 기대값이 틀린 것이 이것으로 다섯 번째다. 매번 고친
방식은 같다 — **관문을 맞추지 않고 표본을 계산한다.**

그리고 §1 의 반례 시험에는 **전제 확인**을 넣었다:

```kotlin
assertTrue("대상은 60% 를 넘는다", rep.usableRatio > 0.6)
assertTrue("기준도 60% 를 넘는다", rep.referenceUsable.count { it } / 31.0 > 0.6)
assertEquals("겹치는 것은 여덟뿐이다", 8, rep.approvedCount)
```

「다른 관문이 대신 막아도 통과로 보이는」 것을 막으라는 지적을 그렇게
받았다.

---

## 5. 정책 질문에 대한 처리

- **정규화 충분성**: 「숫자를 표시하는 것만으로 신뢰도가 검증되지 않는다」에
  동의한다. 다만 RCP-F01 을 먼저 고치라는 순서도 그대로 따랐다. 지금은
  0 만 거절하고, 대역 수·점 수·오프셋을 `judgeCalibration` 의 안내로
  적되 **판정은 움직이지 않는다.** 폭·분포·오프셋 반복성까지 보려면
  실측이 있어야 한다고 보아 손대지 않았다.
- **`referenceCalApplied`**: 연기에 합의해 주신 대로 **고치지 않았다.**
  「Boolean true 를 증거로 승격하지 않는다」는 조건과 「캡처 경로 연결
  단계의 필수 통합 시험」을 §7 에 남은 항목으로 적어 두었다.
- **형식 버전**: 「외부 소비자·fixture 까지 없다는 사실은 이번 검토로
  증명하지 않았다」는 지적을 받아들인다. 저장소를 훑어보니 곡선 파일
  fixture 는 없고 쓰는 코드도 없지만, **그것을 제가 증명했다고 말하지는
  않겠다.** 첫 영속화를 만들 때 schema 계약을 확정하고 구형 문자열의
  거절 동작을 명시하겠다 — 지금 하면 쓰지 않는 규약을 먼저 굳히게 된다.
- **8프레임 / 2dB**: 그대로 두었다. 잔여 위험으로 유지한다.

---

## 6. 되돌려서 확인 (변이 1건, 3 시험이 잡음)

| 변이 | 실패한 시험 |
|---|---|
| 승인을 `approvedRatio` → `usableRatio`, 범위를 `approvedRangeHz` → `usableRangeHz` | 3건 |

## 7. 확인 범위와 열려 있는 것

- dsp **340** · app **325** 테스트 통과, 실패 0, `:app:lintDebug` 통과.
  `--rerun-tasks` 로 캐시 없이 다시 돌렸다.
- **실제 음향 측정을 하지 않았다.** 지금까지 잰 것은 계산이지 소리가
  아니다.
- **아무것도 파일에 쓰이지 않는다.** 교정 마법사도 없고,
  `CalibrationCompareCard` 는 아직 한 번도 기기 화면에서 본 적이 없다 —
  DoD 7.4 는 그래서 못 잰다.
- `referenceCalApplied` 가 선언값인 것, 정규화 충분성 기준이 0 뿐인 것,
  정책 기본값 둘의 근거 없음, 저장 형식 계약 미확정 — 넷이 열려 있다.
- `judgeCalibration` 은 **아직 아무도 부르지 않는다.** probe 와 시험에서만
  쓰인다. 마법사를 붙일 때 저장 경로가 이것을 거치게 해야 하고, 그때까지
  「저장이 실제로 막히는가」는 검증되지 않은 상태다.
- 앞 회차에서 열어 둔 것도 그대로다: `SignalPlayer.stopSink()` 의
  `releaseStarted` 가드가 어떤 단언에도 묶이지 않은 것, 알림의 정지 단추를
  실제로 눌러 본 적이 없는 것, TalkBack 을 실제로 들어 본 적이 없는 것.
