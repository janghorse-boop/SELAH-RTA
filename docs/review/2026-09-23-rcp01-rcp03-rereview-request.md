# 독립 재재검토 요청 — RCP01 · RCP02 · RCP03 을 닫았다

**보낸 사람**: 개발 장훈 (JANGHUN) · JOA — Lead Developer
**받는 사람**: Codex — 독립 검증자 (명세 §24)
**날짜**: 2026-09-23
**범위**: `47ef620..4a8916c` (두 커밋, 640 줄 추가 / 31 줄 삭제, 11 파일)
**직전 검토서**: `docs/review/SELAH-RTA-CP01-CP04-rereview.md`
**보내 주신 probe**: `docs/review/CP01-CP04-RecheckProbe.kt`

---

## 0. 한 줄 요약

High 2건·Medium 1건을 닫았다. 고치기 전에 probe 를 본문 그대로 옮겨 세
관측이 **소수 끝자리까지** 일치하는 것을 먼저 확인했다.

가장 중요한 결과는 **거절로 덮은 것이 아니라 계산이 고쳐졌다**는 것이다 —
같은 입력에서 보정이 `-3.500000000000007` → `0.0` 이 됐다.

그리고 §4 에 적어야 할 것이 하나 있다: **제가 쓴 RCP01 회귀 시험이
아무것도 재고 있지 않았다.** 되돌려 봤더니 통과했고, 까닭을 찾아 고쳤다.

---

## 1. 고치기 전 — 세 관측이 모두 재현됐다

`docs/review/CP01-CP04-RecheckProbe.kt` 를 `RecheckProbeTest.kt` 로 옮겼다.
바꾼 것은 JUnit 껍데기와 import 뿐이다.

| probe 관측 | 검토서 값 | 제 실행 |
|---|---|---|
| `KEPT_GATE` | kept=2 total=8 minimum=**8** verdict=**Pass** | 일치 |
| `ZERO_NORMALIZATION` | pointsUsed=**0** validCount=64 correction=**10.0** Pass | 일치 |
| `UNEQUAL_SUPPORT` | identicalInputs=true correction=**−3.500000000000007** valid=48 | 일치 |

커밋 `bdef955` 가 이 재현만 담는다.

---

## 2. 고친 뒤

```
KEPT_GATE kept=2 total=8 minimum=2 verdict=Fail
ZERO_NORMALIZATION verdict=Degraded usable=19/31 pointsUsed=- validCount=- correction=거절
UNEQUAL_SUPPORT verdict=Degraded identicalInputs=true correction=거절 valid=-
ZERO_NORMALIZATION_WITH_REFSNR ok=false why=레벨을 맞출 대역이 없습니다. 300~3000Hz 안에…
UNEQUAL_SUPPORT_WITH_REFSNR identicalInputs=true correction=0.0 valid=48 normPoints=19 bands=6
```

아래 두 줄을 **제가 더했다.** 앞의 두 줄은 기준 배경 미측정(RCP02) 때문에
거절되는데, 그러면 **RCP01 이 계산으로 고쳐졌는지 볼 수 없다.** 거절이
결함을 덮는 것을 막으려고 기준 배경까지 준 경우를 따로 찍었다.

마지막 줄이 요점이다 — 같은 입력에서 보정이 **정확히 0.0** 이다.

---

## 3. 무엇을 어떻게 고쳤나

### RCP01 — 이름만 같은 대역, 실제로는 다른 자리

두 곡선 모두 300~3000Hz 로 정규화했지만 **각자의 `valid` 로** 평균했다.
CAL 범위가 기준에만 걸리므로 실제로 평균한 주파수들이 달라졌고, 기울어진
곡선에서 그 차이가 그대로 오프셋 차이가 되었다.

- `normalizeToBand(..., support: BooleanArray?)` — 평균에 쓸 자리를 바깥이
  정한다.
- `calibrateResponse` 가 `reference.valid ∧ internalRaw.valid` 를 구해
  **두 곡선 모두에 같은 support** 를 넘긴다.
- 정규화할 자리가 0 이면 `calibrateFromSession` 이 **거절한다.** 조용히
  오프셋 0 으로 넘어가면 녹음 게인 차이가 통째로 보정이 된다.
- 쓴 자리를 점 수(`normalizeSupportPoints`)와 **대역 수**
  (`normalizeBandsUsed`)로 함께 남긴다. 축이 1/12옥타브라 대역 하나에서
  서너 점이 나오므로 점 수만으로는 넓이를 말할 수 없다는 지적 그대로다
  (`ThirdOctave.nearestBand` 를 더했다). 위 관측에서 `normPoints=19`
  인데 `bands=6` 인 것이 그 차이다.
- 기준 쪽 오프셋도 `CalibrationOutcome.referenceNormalized` 로 남기고
  파일에도 적는다(지시서 4장 「적용한 정규화 오프셋」).

> **묻고 싶은 것**: 「0 은 반드시 거절」은 넣었고 그 위의 문턱은 **넣지
> 않았다.** 근거 없는 숫자를 하나 더 만들고 싶지 않았다. 대신 점 수와
> 대역 수를 결과에 남겨 화면이 보여 줄 수 있게 했다. 이 선택이 맞는가,
> 아니면 지금이라도 최소 대역 수를 정해야 하는가?

### RCP02 — 대상이 조용한 것은 기준이 조용했다는 증명이 아니다

`qualityFromSession` 이 대상 배경만 받아, `calibrateFromSession` 이 그
마스크를 기준에 **그대로 베껴** 썼다.

- `qualityFromSession(session, noiseDb, referenceNoiseDb, …)`.
- `QualityReport` 에 `referenceBands` · `referenceUsable` · `bothUsable` ·
  `referenceSnrKnown`.
- 기준 SNR 을 **모르면** Degraded(Pass 아님)이고 `calibrateFromSession`
  이 거절한다 — 「모른다」와 「SNR 0」을 문구에서도 가른다.
- 기준 쪽만 시끄러운 대역은 보정에서 빠진다.
- 기준 경로가 통째로 시끄러우면 Fail.

### RCP03 — 여덟 장을 넣고 둘을 썼는데 「여덟 장」이라 했다

`minFramesPerStep` 을 `totalFrames` 로 셌다. **제가 만든 결함이다.**
승인용 `minKeptFramesPerStep` 과 진단용 `minTotalFramesPerStep` 으로
나눴고, 품질 보고에는 앞의 것이 간다. 폴백(`noStableFrames`) 경로는
그대로 유지된다.

---

## 4. **제 회귀 시험이 아무것도 재고 있지 않았다**

이번에 가장 배운 것이다.

RCP01 회귀 시험 「같은 곡선이면 지지구간이 달라도 보정이 0 이다」를 쓰고,
공통 지지구간을 각자 `valid` 로 되돌려 봤다. **통과했다.**

까닭: 표본의 배경을 40dB 로 두었는데, 그 기울어진 곡선은 1414Hz 아래에서
SNR 12dB 를 못 넘는다. 그래서 **CAL 범위가 지지구간을 가르기도 전에**
SNR 이 두 마스크를 똑같이 잘라 버렸다 — 지지구간이 갈리지 않으니 잴
것이 없었다.

| 밴드 중심 | 곡선(dB) | SNR(배경 40dB) | SNR(배경 0dB) |
|---|---|---|---|
| 794Hz | 48.67 | **8.67** (미달) | 48.67 |
| 1000Hz | 50.00 | **10.00** (미달) | 50.00 |
| 1259Hz | 51.33 | **11.33** (미달) | 51.33 |

보내 주신 probe 는 배경 0dB 를 썼기에 결함을 봤고, 제 시험은 못 봤다.
**같은 상황을 만들었다고 생각했지만 아니었다.**

고친 것:
1. 배경을 0dB 로 — 지지구간을 가르는 것이 오직 CAL 범위가 되게.
2. **전제 확인 두 줄**을 넣었다 — 정규화 대역 안에서 기준·대상 마스크가
   *정말 다른지* 먼저 단언한다. 앞으로 표본이 바뀌어 우연히 같아지면
   그때 걸린다.

그러자 되돌린 판이 잡혔다.

이 세션에서 「시험 이름이 단언보다 앞서간」 것이 세 번째다(PoolProbeTest,
ProfileCodecTest 의 valid 표시, 이번). 앞의 둘은 제가 넣어 둔 전제 확인이
잡았는데, 이번에는 **그 전제 확인을 안 넣어서** 놓쳤다.

---

## 5. 되돌려서 확인 (변이 4건, 모두 잡힘)

| 변이 | 실패한 시험 |
|---|---|
| 공통 지지구간 → 각자 `valid` | 1건 (**§4 를 고친 뒤에야** 잡혔다) |
| 정규화 자리 0 거절 제거 | 1건 |
| 기준 마스크를 대상 것으로 베낌 | 1건 |
| 최소 표본을 넣은 장으로 | 1건 |

---

## 6. 검토서의 답변에 대한 처리

1. **Fail 인데 진단 곡선이 남는 것** — 허용으로 읽고 유지했다. 저장·자동
   적용은 판정이 막는다. 실제 저장 차단은 파일 바인딩이 없어 못 잰다.
2. **8프레임 / 2dB 는 검증된 기준이 아니다** — 동의한다. 숫자를 바꾸지
   않았고, 실측으로 정해야 한다는 사실을 KDoc 과 §8 에 남겼다.
3. **정규화 최소 점수** — 0 거절만 넣고 그 위는 비워 두었다(§3 질문).
4. **`referenceCalApplied` 의 더 나은 경계** — **아직 고치지 않았다.**
   지금도 부르는 쪽이 넣는 Boolean 이고, 말씀대로 **실제 bin 보정 실행의
   증거가 아니다.** 제한된 생성자가 만드는 타입에 CAL 식별자·샘플레이트·
   FFT 설정·적용 세대를 담아 넘기는 쪽이 맞다고 보는데, 그것은 캡처
   경로(보정된 스펙트럼을 실제로 만드는 코드)가 생길 때 함께 만드는 편이
   낫다고 판단했다. 지금 타입만 만들면 **여전히 아무도 채우지 않는
   표시**가 될 뿐이다. 이 판단이 맞는지 봐 주시기 바란다.

---

## 7. 확인 범위

- dsp **334** · app **325** 테스트 통과, 실패 0, `:app:lintDebug` 통과.
  `--rerun-tasks` 로 캐시 없이 다시 돌렸다.
- 파일 형식이 바뀌었다(`refNormalized.*`). **아직 아무것도 파일에 쓰인
  적이 없으므로** 판 번호는 올리지 않았다 — 옮길 것이 없다. 이 판단이
  과한 단순화인지 봐 주시기 바란다.
- `PipelineProbeTest`(앞 회차 probe)도 기준 배경을 주도록 한 줄 고쳤다.
  안 주면 거절되어 그 관측점이 재려던 것을 볼 수 없다.

## 8. 확인하지 않은 것 (명시)

- **실제 음향 측정을 하지 않았다.** 지금까지 잰 것은 계산이지 소리가
  아니다.
- **아무것도 파일에 쓰이지 않는다** — `ProfileCodec` 은 글자로 바꾸는
  데까지다.
- **화면에 붙지 않았다.** 교정 마법사가 없고 `CalibrationCompareCard` 는
  아직 한 번도 기기 화면에서 본 적이 없다. DoD 7.4(비교 화면 수치와 저장
  데이터의 일치)는 그래서 못 잰다.
- `referenceCalApplied` 가 선언값인 것(§6.4), 정규화 최소 문턱이 0 뿐인
  것(§3), 정책 기본값 둘이 실측 근거가 없는 것 — 세 가지가 열려 있다.
- 앞 회차에서 열어 둔 것도 그대로다: `SignalPlayer.stopSink()` 의
  `releaseStarted` 가드가 어떤 단언에도 묶이지 않은 것, 알림의 정지 단추를
  실제로 눌러 본 적이 없는 것, TalkBack 을 실제로 들어 본 적이 없는 것.
