# RTA 측정 저장과 겹쳐 보기 — 구현 계획

명세: [2026-09-29-rta-measurement-store-design.md](../specs/2026-09-29-rta-measurement-store-design.md)

**목표:** 같은 자리에서 L·R·L+R 을 재어 남기고, 지금 곡선 위에 겹쳐 본다.

## 전역 제약

- `dsp` 모듈에 안드로이드 의존성을 넣지 않는다. 저장소는 `app` 에 둔다.
- UI 문구는 한국어. 파일은 UTF-8.
- 시험을 쓰면 **변이를 넣어 그 시험만 실패하는지** 확인한 뒤 커밋한다.
- 계측 시험을 돌리기 전에 **기기를 깨운다**(자면 「시험 실패」처럼 보인다).
  계측 시험 메서드 이름에 **공백을 쓰지 않는다**(DEX 040 미만).
- 앱 APK 를 다시 만들지 않고 계측 시험을 돌려 「통과」를 찍은 일이 두 번
  있었다. **변이 검사에서는 `:app:assembleDebug` 를 반드시 함께 돌린다.**

---

## 1단계 — 저장소와 모형 (JVM 만으로 끝난다)

**왜 먼저인가.** 화면도 평균도 여기 없으면 못 얹는다. 그리고 이 단계는
**안드로이드 없이 전부 시험할 수 있다** — 파일 입출력은 임시 폴더로 된다.

**파일**
- 새로: `app/src/main/java/kr/joa/selahrta/data/rta/RtaMeasurement.kt`
  — 기록 한 벌과 비교 세트의 모형, 겉장 인코딩·디코딩
- 새로: `app/src/main/java/kr/joa/selahrta/data/rta/RtaMeasurementStore.kt`
  — 폴더 하나에 기록 하나, `.tmp` → 바꿔치기
- 시험: `app/src/test/java/kr/joa/selahrta/data/rta/RtaMeasurementStoreTest.kt`

**모형** (명세 1·2장 그대로)

```kotlin
data class RtaMeasurement(
    val id: String,
    val setId: String,
    val nameKo: String,          // "본당 중앙 · L"
    val method: String,          // "rta" — 나중에 "sweep" 이 온다(명세 4장)
    val bandsSpl: DoubleArray,   // 31칸
    val signal: String,          // "Pink"
    val channel: String,         // "Left" | "Right" | "Both"
    val outputDbfs: Double,
    val averagedFrames: Int,
    val conditions: RtaConditions,
    val measuredAtEpochMs: Long,
    val memoKo: String = "",
)
```

`RtaConditions` 는 명세 7-3 의 다섯 가지만 담는다(입력 장치·보정 상태·보정
출처·주파수 보정 곡선·FFT 길이·표본율·평균 장수). **`MeasurementConditions`
를 통째로 담지 않는다** — 그 안에는 잰 값과 무관한 것이 많고, 늘어날 때마다
겉장 모양이 흔들린다.

**단계**
- [ ] 시험을 먼저 쓴다: 썼다 읽으면 31칸이 그대로다 · 겉장이 반쯤 쓰이면
      그 기록만 없는 것으로 읽힌다 · 같은 세트의 것이 함께 묶여 나온다 ·
      같은 채널을 두 번 저장해도 **덮이지 않는다** · 지우면 사라진다.
- [ ] 실패 확인 → 구현 → 통과 확인.
- [ ] **변이**: 바꿔치기 대신 바로 쓰게 한다 → 「반쯤 쓰인 겉장」 시험만 실패.
- [ ] **변이**: 같은 채널 저장 시 덮어쓰게 한다 → 그 시험만 실패.
- [ ] 커밋.

## 2단계 — 평균 내어 저장한다

**왜.** 한 장만 저장하면 **이 기능이 통째로 쓸모없어진다**(명세 7-2).

**파일**
- 고침: `app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt`
  — `saveRtaMeasurement(nameKo, setId?)`. `MeasurementTap` 을 걸어 230장을
  모으고 밴드마다 평균을 내어 저장한다. 진행률을 상태로 알린다.
- 새로: `app/src/main/java/kr/joa/selahrta/data/rta/BandAverage.kt`
  — 장 묶음을 31칸 평균으로. **에너지로 평균 낸 뒤 dB 로 바꾼다.**
- 시험: `app/src/test/java/kr/joa/selahrta/data/rta/BandAverageTest.kt`

**단계**
- [ ] 시험: dB 를 그냥 산술평균하면 틀린다 — 60dB 와 80dB 의 평균은 70 이
      아니라 77.0dB 다(에너지 평균). 빈 묶음은 저장할 것이 없다.
- [ ] 실패 확인 → 구현 → 통과 확인.
- [ ] **변이**: 산술평균으로 바꾼다 → 그 시험만 실패.
- [ ] ViewModel 배선. FR 이 쓰는 `installMeasurementTap`·`collectFrames` 를
      그대로 쓴다 — **겹쳐 돌지 않게** FR 과 같은 자물쇠(작업 하나)를 쓴다.
- [ ] 커밋.

## 3단계 — 「저장된 측정」 과 겹쳐 보기

**파일**
- 새로: `app/src/main/java/kr/joa/selahrta/ui/screens/SavedRtaSheet.kt`
- 고침: `AnalyzeScreens.kt`(RTA) — 「저장된 측정」 단추, 겹쳐 그리기
- 시험: `app/src/androidTest/.../SavedRtaSheetTest.kt`

**그리기**: 저장 곡선은 실시간 곡선보다 **가늘게, 점선으로**. 색은 채널마다
(L·R·L+R). 곡선마다 보이기·숨기기. 각 곡선에 채널과 측정 시각을 적는다.

**단계**
- [ ] 시험: 세트를 고르면 그 안의 곡선이 뜬다 · 곡선을 끄면 사라진다 ·
      실시간 곡선도 따로 끌 수 있다 · 비어 있으면 안내가 뜬다.
- [ ] 실패 확인 → 구현 → 통과 확인 → **변이** → 커밋.

## 4단계 — 조건이 다를 때 알린다

**파일**
- 새로: `app/src/main/java/kr/joa/selahrta/data/rta/ConditionDiff.kt`
- 시험: `app/src/test/java/kr/joa/selahrta/data/rta/ConditionDiffTest.kt`

명세 7-3 의 다섯 가지만 본다. **출력 채널·레벨은 안 본다** — 그것이 다른
것이 비교하려는 까닭이다. **막지 않고 알리기만 한다.**

**단계**
- [ ] 시험: 다섯 가지 각각이 다를 때 잡힌다 · 채널·레벨이 달라도 안 잡힌다 ·
      다 같으면 조용하다. 문구에 **무엇이 다른지** 적힌다.
- [ ] 실패 확인 → 구현 → 통과 확인 → **변이**(한 가지를 빼 본다) → 커밋.
- [ ] CHANGELOG(가지의 마지막 커밋).

---

## 실기기에서만 갈리는 것 (미검증으로 남길 목록)

- 예배당에서 10초 평균이 충분히 가라앉는지 (저역은 더 걸릴 수 있다)
- 겹친 곡선이 눈에 갈리는지
- 기록이 쌓였을 때 목록이 쓸 만한지
