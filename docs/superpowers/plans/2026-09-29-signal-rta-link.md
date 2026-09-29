# 테스트 신호–RTA 연동 구현 계획

> 지시서: `SELAH_RTA_테스트신호_RTA_연동_개발지시서.md`(담당자 전달, 2026-09-29)
> 지시서는 부품 이름을 **역할 예시**로 적었다. 아래는 **실제 저장소를 확인한 뒤** 다시 쓴 것이다.

**목표:** 테스트 신호를 틀어 둔 채 RTA로 건너가 재고, 그 결과를 L/R/L+R로 겹쳐 본다.

**이미 되어 있는 것 — 다시 만들지 않는다:**

| 지시서 | 어디에 있나 |
|---|---|
| §1 재생이 화면 전환에 유지 | `CaptureViewModel` 이 액티비티 하나에 붙는다(`MainActivity.kt:46`). 화면은 `when(screen)` 분기일 뿐 각자 VM을 갖지 않는다. |
| §3 채널 매핑(L: 왼쪽만, R: 오른쪽만, BOTH: 양쪽 같은 표본·무감쇠) | `SignalChannels` + `SignalPlayer.kt:326-327` |
| §5 백그라운드 시 재생 정지, 자동 재개 없음 | `SelahApp.kt:281` `ON_STOP → vm.onBackground()` |
| §5 세대 번호로 늦게 도착한 콜백 무시 | `SignalPlayer` 의 generation |

**남은 것:** 아래 4단계.

## 전역 제약 (모든 과업에 걸린다)

- `dsp` 모듈에 안드로이드 의존성을 넣지 않는다.
- UI 문구는 한국어. 파일은 UTF-8.
- 표를 새로 만들면 `ScrollableTable` 을 쓰고 `npm run check:tables` 에 해당하는 검사는 이 저장소엔 없으므로 해당 없음.
- 시험을 쓰면 **변이를 먼저 넣어 그 시험만 실패하는지** 확인한 뒤 커밋한다(누적 요청서 5-1의 6번이 네 번 반복됐다).
- 기기에서만 드러나는 것(부드러움·클릭음·실제 정지)은 **실기기 확인 전까지 「미검증」으로 적는다.**

---

## 1단계 — 어디서나 보이고, 어디서나 멈춘다

**왜 먼저인가.** 재생이 화면을 넘어 유지되는 것은 **이미 되어 있는데**, 그것을 알리는 표시도 멈출 수단도 없다.
테스트 신호에서 핑크 노이즈를 틀고 RTA로 넘어가면 소리는 계속 나고 화면에는 아무 말이 없다.
`SelahApp.kt:271` 의 주석이 걱정하는 상황("예배당에서 순음을 켜 놓고 앱을 나가면 멈출 방법이 화면에 없다")이 **앱 안에서** 그대로 재현된다.

**파일**
- 새로 만듦: `app/src/main/java/kr/joa/selahrta/ui/components/SignalMiniBar.kt`
- 고침: `app/src/main/java/kr/joa/selahrta/ui/SelahApp.kt` — `Scaffold` 의 `bottomBar` 위에 한 줄
- 시험: `app/src/androidTest/java/kr/joa/selahrta/ui/components/SignalMiniBarTest.kt`

**무엇을 그리나.** 재생 중일 때만 나타나는 한 줄: `■ 1 kHz 순음 · 양쪽` + 오른쪽에 **정지**.
테스트 신호 화면 자신에는 띄우지 않는다 — 그 화면엔 이미 본체가 있다.

**단계**
- [ ] 시험을 먼저 쓴다: `playingSignal = null` 이면 아무것도 안 보이고, 값이 있으면 신호 이름과 정지 단추가 보인다. 정지를 누르면 콜백이 한 번 불린다.
- [ ] 실패를 확인한다(`SignalMiniBar` 가 없다).
- [ ] `SignalMiniBar(state, onStop)` 를 만든다. 문구는 `TestSignal.labelKo` + `effectiveHz` + `SignalChannels.labelKo`.
- [ ] `SelahApp` 의 `bottomBar` 람다 안, 탭 줄 **위**에 놓는다. `screen != Screen.Signal` 일 때만.
- [ ] 통과 확인 → **변이**: `if (state.playingSignal == null) return` 을 지운다 → 「안 틀었는데 줄이 뜬다」 시험만 실패해야 한다.
- [ ] 커밋.

## 2단계 — 시작·정지·전환에 딸깍 소리를 없앤다 (지시서 §3)

**왜.** 지금은 첫 표본부터 최대 진폭이 나가고, 정지도 그 자리에서 끊는다. 스피커가 그 계단을 「딱」으로 낸다.
슬라이더 재조율은 위상을 이어 이미 매끄럽지만(`ToneRetuneTest`), **시작·정지**는 그대로다.

**파일**
- 고침: `app/src/main/java/kr/joa/selahrta/audio/SignalPlayer.kt`
- 시험: `app/src/test/java/kr/joa/selahrta/audio/SignalRampTest.kt`

**어떻게.** 재생 쪽에 `ramp`(0→1, 1→0) 를 둔다. 표본마다 `ramp += 1/rampSamples` 로 선형으로 움직이고,
`rampSamples = sampleRate * 0.03`(30 ms — 지시서가 준 20~50 ms 안).
정지는 **램프를 다 내린 뒤** 출력을 닫는다. 이미 있는 `RecordingSink` 방식으로 표본을 받아 확인할 수 있다.

**단계**
- [ ] 시험: 시작 직후 첫 표본의 절대값이 진폭의 1/10 보다 작고, 30 ms 뒤에는 진폭에 가깝다.
- [ ] 시험: `stop()` 뒤 마지막으로 나간 표본들이 0 을 향해 내려간다(끝 표본 절대값 < 진폭의 1/10).
- [ ] 실패 확인 → 구현 → 통과 확인.
- [ ] **변이**: `rampSamples` 를 1 로 바꾼다 → 두 시험이 실패해야 한다.
- [ ] 커밋.

## 3단계 — 「RTA에서 재기」 한 걸음 (지시서 §1)

**왜.** 지금은 테스트 신호를 틀어 놓고 도구 → 분석 → RTA 로 손가락을 세 번 옮겨야 한다.
그 사이에 무엇도 끊기지 않지만, **끊길까 봐 사람이 먼저 멈춘다.**

**파일**
- 고침: `app/src/main/java/kr/joa/selahrta/ui/components/SignalGeneratorCard.kt` — 재생 중일 때만 보이는 「RTA에서 재기」
- 고침: `app/src/main/java/kr/joa/selahrta/ui/SelahApp.kt` — 화면 전환 콜백을 내려 준다
- 시험: 기존 `SignalGeneratorCard` 계측 시험에 한 줄

**단계**
- [ ] 시험: 재생 중이 아니면 그 단추가 없고, 재생 중이면 있고, 누르면 콜백이 `Screen.Rta` 로 불린다.
- [ ] 실패 확인 → 구현 → 통과 확인.
- [ ] **재생이 안 끊기는지**는 시험으로 못 본다(화면 전환이 VM을 건드리지 않는다는 것은 구조상 참이지만 계측 시험이 그 경로를 지나지 않는다). **실기기에서 확인하고 그렇게 적는다.**
- [ ] 커밋.

## 4단계 — 스냅샷 저장과 L/R 겹쳐 보기 (지시서 §7) — **별도 명세가 필요하다**

지시서 §7 은 `WaitingForStableSignal → Averaging → ReadyToSave → Saved` 상태와
필수 메타데이터 여남은 가지, 무효화 규칙, 비교 제한까지 요구한다. 지금 저장소에는
**측정 결과를 담아 두는 자리 자체가 없다**(FR 은 한 번 재고 화면에 띄우고 끝난다).

스키마·보관 위치·기록 화면과의 관계를 먼저 정해야 한다. 1~3단계와 **독립적으로 쓸모가 있으므로**
여기서 끊고, 별도 명세(`docs/superpowers/specs/`)로 넘긴다.

---

## 실기기에서만 갈리는 것 (미검증으로 남길 목록)

- 램프가 실제로 딸깍 소리를 없앴는가 (파형이 매끄러운 것과 **들리는 것**은 다르다)
- 화면을 넘겨도 소리가 정말 안 끊기는가
- 미니 바의 정지가 실제로 소리를 끊는가
