# SELAH RTA — UI 및 분석 파이프라인 독립 검토

검토일: 2026-09-25. **판정: 승인 보류 — Critical 0, High 3, Medium 2.**

요청서의 주장을 코드·수식·독립 실행으로 검증했다. production 파일은 수정하지 않았다. 기존 단위 시험의 통과와 새 기능의 올바름을 구분했다.

## 범위와 기준

- 요청 범위 B: `f6ce966^..23369aa` = `ba80aaf72fbe1148cc48f0f76fffc3b0e95141eb..23369aab4f9eb1fc466eeaa07f8a0ccd590bb3fa`.
- 요청서에서 별도로 설명한 저역 봉우리 수정 `90f0067`도 포함했다. 실제 실행 기준은 `d140ebfe3e07a0a1ba056f622cee8fbb157e4b5d`의 소스다.
- 검토 도중 HEAD가 merge commit `31c842cf621f5e4ce0c0a803843f1c37fd126ff5`로 이동했다. `git diff d140ebf HEAD`가 비어 있어 실행한 소스와 내용은 같다. 이 후의 변경은 검토하지 않았다.
- A 구간의 교정 마법사 전체는 이번 승인 대상이 아니다. B에서 변경한 DSP, 보정 분기, Controller/ViewModel 연결, FR 작업, Spectrum/Spectrogram, 출력 신호 변경을 우선했다. UI 전체의 실기기 배치·회전·접근성 QA는 수행하지 않았다.
- 요청서의 “Compose 화면은 틀려도 보기만 나쁘다”는 범위 판단은 받아들이지 않았다. 아래 UA-02~04는 UI 계층에 있는 측정값·출력 제어·시간축 결함이다.

## UA-01 — High: 초기 주파수 이동이 트랙에 영구히 남아, 이후 안정된 순음도 후보에서 사라진다

**위치:** [FeedbackDetector.kt:502](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/FeedbackDetector.kt:502). 관련: 같은 파일 389~390, 405~415, 482행.

**근거 및 재현:** `minHz`/`maxHz`는 트랙의 전체 수명에서 누적되고 초기화되지 않는다. 천천히 이동하는 봉우리는 현재 EMA 주파수와 25cent 이내이므로 같은 트랙에 붙는다. 한 번 전체 흔들림이 35cent를 넘으면, 뒤에 아무리 오래 안정되어도 `!stable -> None`이 유지된다. 무음 공백으로 트랙이 없어져야 풀린다.

실제 PCM → RtaEngine → FeedbackDetector 경로로 48kHz, FFT4096, hop2048, 진폭 0.25, 위상이 연속인 순음을 넣었다.

| 입력 | 4~29.994초의 관측 610회 | 마지막 상태 | 이벤트 수 |
|---|---:|---|---:|
| 처음부터 1059.463Hz 고정 | 후보·Persistent 모두 610회 | Persistent | 1 |
| 1kHz → 2초 동안 +40cent → 약 28초 고정 | 후보 0회 | None | 1 |
| 1kHz → 2초 동안 +100cent → 약 28초 고정 | 후보 0회 | None | 0 |

40cent는 약 23.37Hz 이동이다. 이 경우 초반에 만들어진 이벤트가 남아 있다는 것과 현재 후보가 복구된다는 것은 다르다. 별도의 회귀 시험에서도 2초 이동 후 약 8초 고정했을 때 기대 `Persistent`, 실제 `None`으로 실패했다.

**영향:** 발진 초기에 주파수가 움직인 뒤 고정되는 소리를 장시간 놓친다. 고정음 대조군만으로는 검출력을 보장할 수 없다. 전체 수명 흔들림 때문에 Persistent가 안 되는 문제 자체는 기존에도 있었고, 이번 변경은 Suspect까지 막아 현재 후보가 완전히 사라지게 한 회귀다.

**권장 수정:** 최근 관측 창의 안정성을 평가하고, 안정 구간이 충분히 이어지면 다시 승격할 수 있게 한다. 안정 시간은 해당 구간부터 세어야 한다. 단순히 35cent 문턱을 넓히거나 매번 트랙을 새로 만드는 방식은 기존 비브라토 오탐을 되살릴 수 있다.

**회귀 시험:** 고정음, 40/100cent 초기 이동 후 고정, 지속 비브라토, 간헐음, 실제 무음 뒤 재등장에 대해 검출·회복 시간과 오탐을 함께 단언한다. 44.1kHz도 포함한다.

## UA-02 — High: FR 배경값이 측정 세션·입력 장치에 묶이지 않는다

**위치:** [CaptureViewModel.kt:647](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:647), 같은 파일 509, 687~688, 716행. 종료 경로는 423~428행과 [CaptureController.kt:624](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CaptureController.kt:624).

**근거 및 재현 순서:** 외부 신호 모드에서 입력 A로 “1. 배경 재기”를 끝낸 뒤 측정을 중지하고 입력 B로 다시 시작한다. FR로 돌아오면 `responseQuietReady`가 그대로이고 “2. 응답 재기”가 활성화된다. `responseQuietMean`은 ViewModel에 저장된 배열이며 세션/장치/채널 식별자가 없다. 사용 전에도 null 여부만 본다. `cancelResponse()`, Controller의 stop/start, `onStoppedHook` 모두 이 값을 무효화하지 않는다. 따라서 B의 신호를 A의 배경과 비교한다.

수치 영향은 실제 `computeRoomResponse()`로 확인했다. 전 밴드 신호가 −40dBFS인 40장에 예전 배경 −70dBFS를 넣으면 SNR 30dB, **31개 밴드 사용 가능**으로 성공한다. 현재 입력의 배경 −42dBFS를 넣으면 `NotEnoughReferenceBands`로 실패한다. 이것은 단순 표시 잔상 문제가 아니다. 장치 전환 UI를 실제 기기에서 수행한 것은 아니며, 전환 경로와 상태 보존은 코드로 확인했다.

**영향:** 장치/채널/입력 이득이 달라진 뒤 배경에 묻힌 대역을 신뢰할 수 있는 응답으로 승인할 수 있다. 반대로 정상 대역을 버릴 수도 있다.

**권장 수정:** 배경 결과에 캡처 세션, 확인된 입력 경로, 채널, sample rate/FFT 설정을 묶고 소비 직전에 일치를 확인한다. stop/reopen/route 변경 시 배경과 ready 상태를 함께 무효화한다. 실행 중인 FR 작업도 시작 세션을 보유하게 하고, 종료·전환 뒤 이전 tap의 결과를 새 상태에 발행하지 못하게 한다. 현재 `collectFrames()`에는 세션 검사가 없고, 신호 단계는 목표 미달이어도 20장 이상이면 계산을 계속한다.

**회귀 시험:** A 배경 → stop → B 시작 → 신호 요청은 거절되고 ready=false여야 한다. 같은 장치 재시작, 채널/sample rate 변경, 수집 도중 disconnect/restart, 오래된 작업의 늦은 완료도 검증한다. 순수 `RoomResponseTest`로는 이 연결 오류를 잡을 수 없다.

## UA-03 — High: FR 배경 측정 중 앱을 나가면 뒤늦게 시험 소리가 시작될 수 있다

**위치:** [CaptureViewModel.kt:700](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:700), 관련 661~676, 927~929행. [SelahApp.kt:200](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/SelahApp.kt:200).

**논리적 재현:** “이 폰에서 소리 내기”를 켜고 FR 재기를 시작한다. 약 3초의 배경 수집이 끝나기 전에 홈으로 나간다. `ON_STOP`은 `onBackground()`를 통해 **현재 player만** 멈춘다. 그러나 `viewModelScope`의 `responseJob`은 취소되지 않고, 포그라운드 서비스의 마이크 수집은 계속된다. 배경 70장이 모이면 살아 있는 작업이 700행의 `playSignal(Pink, ...)`를 호출한다. 재생 직전 foreground 검사도 없다.

FR의 `DisposableEffect.onDispose`는 분석 탭을 떠날 때의 방어이며, Activity가 단순히 STOPPED로 되는 것과 같지 않다. `viewModelScope`의 자동 취소는 ViewModel이 clear될 때다. [Android lifecycle/coroutines 공식 문서](https://developer.android.com/topic/libraries/architecture/coroutines).

**영향:** 화면을 떠나 소리를 멈췄다고 생각한 뒤 폰/연결된 PA에서 핑크 잡음이 시작될 수 있다. 이는 이 앱이 정한 “백그라운드에서는 소리만 멈춘다”는 동작을 깨뜨린다. 실제 스피커 재생은 이번 검토에서 하지 않았다.

**권장 수정:** 백그라운드 전환에서 FR 자동 재생 작업도 취소하고, 이후 단계가 실행되지 않도록 작업 세대/foreground 상태를 확인한다. 수동 재기 재개를 요구한다. 배경 측정 서비스 자체를 무조건 중지할 필요는 없다.

**회귀 시험:** 배경 단계 완료 직전에 작업을 barrier로 멈추고 `onBackground()`를 호출한 뒤 남은 프레임을 공급해도 fake player.start 호출이 0회여야 한다. 신호 단계 중 background 진입은 재생을 종료하고 결과를 중단 상태로 남겨야 한다. foreground 복귀만으로 자동 재생이 재개되지 않아야 한다.

## UA-04 — Medium: 스펙트로그램 시간 눈금과 데이터 위치가 다른 좌표계를 쓴다

**위치:** [SpectrogramChart.kt:134](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/components/SpectrogramChart.kt:134), 같은 파일 181~187행. 입력 시각은 [AnalyzeScreens.kt:374](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/screens/AnalyzeScreens.kt:374).

**근거 및 재현:** 용량 720장 중 360장을 100ms 간격으로 넣으면 실제 span은 35.9초다. 그림은 화면 오른쪽 절반에 그리므로 가장 오래된 장은 x=0.5W에 있다. 그러나 눈금은 전체 폭에 span을 배분해 x=0.5W를 **17.95초 전(표시 −18초)**이라고 쓴다. 균등한 데이터만 넣어도 이미 약 18초 틀린다. 가득 찬 뒤에도 불규칙 간격을 같은 폭으로 그리므로 중간 시각이 맞지 않는다.

추가로 push 시각은 캡처 시각이 아닌 `System.currentTimeMillis()`다. UI 지연·멈춤 후 이어보기·벽시계 변경의 영향을 받는다. `SpectrumView`의 객체 정체성은 새 측정 프레임의 정체성과도 같지 않다. `withMeasurement()`는 같은 스냅샷을 기본 상태 변경 때 새 View로 다시 감쌀 수 있다.

**영향:** “언제 발생했고 얼마나 이어졌는가”를 잘못 읽는다. 요청서에서 인정한 불규칙 간격 문제 외에, 부분 충전 상태의 결정적 축 오류가 있다.

**권장 수정:** 측정 프레임의 단조 시각과 고유 번호를 전달하고, 실제 시각으로 x 위치/눈금을 함께 정한다. 고정 간격으로 재표본화한다면 빠진 시간을 빈 구간으로 표현한다. 아직 비어 있는 영역에 실제 데이터 span을 잘못 늘여 붙이지 않는다.

**회귀 시험:** 1장·절반·가득 참·wrap, 불규칙 간격, freeze/resume, UI 지연, 동일 프레임 재발행, 세션 전환에서 알려진 사건의 x와 눈금이 일치해야 한다. 순수 시간→좌표 매핑 시험과 Compose 픽셀/표시 시험이 모두 필요하다.

## UA-05 — Medium: 20Hz 하한 수정 뒤에도 보간 결과가 20Hz 아래로 돌아간다

**위치:** [SpectrumDisplay.kt:190](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/SpectrumDisplay.kt:190), 관련 181행.

**근거 및 재현:** `ceil`은 가장 큰 칸을 찾는 범위만 제한한다. 이어 호출하는 `refineBinHz()`는 범위 밖 이웃 칸까지 쓰며 최대 ±0.5칸 이동한 값을 그대로 반환한다. 48kHz/4096에서 2번 칸은 23.4375Hz지만 보간 결과는 17.578125Hz까지 내려갈 수 있다.

실제 Hann FFT로 19Hz 순음을 넣고 `topSpectrumPeak(..., lowHz=20, highHz=20000)`를 호출하면 **18.987297833Hz**를 반환한다. 17Hz 입력은 **17.578125Hz**였다. `p[1]=100, p[2]=1, p[3]=0.0001`인 전력 배열에서도 같은 하한 이탈을 확인했다. 따라서 “1번 칸을 직접 고르지 않는다”는 수정은 유효하지만, “표시되는 봉우리가 요청 범위 안에 있다”는 보장은 아직 없다.

**영향:** 범위 밖 초저역의 누설 에너지가 여전히 최고 봉우리 표시를 차지할 수 있다. 새 ceil 회귀 시험만으로는 이 경로를 검출하지 못한다.

**권장 수정:** 보간 후 범위 검사를 포함해 표시 계약을 완성한다. 범위 밖으로 추정된 봉우리는 제외하고 다음 유효 봉우리를 찾는 방식을 검토한다. 무조건 20Hz로 clamp하면 범위 밖 신호를 20Hz 신호로 오인시킬 수 있다. 정확히 20Hz인 신호도 수치 추정 오차가 있으므로 경계 허용오차·표시 정책을 명시한다.

**회귀 시험:** 합성 전력뿐 아니라 17/19/20Hz PCM, 범위 밖 큰 신호+범위 안 작은 신호, 상한 부근, 44.1/48kHz를 함께 시험한다. 반환 주파수의 계약을 직접 단언해야 한다.

## 요청한 여섯 질문에 대한 답

1. **FR 보정 전 경로:** 현재 화면이 밝힌 “소리원·방·마이크를 합친 대역 균형”이라는 정의라면 raw 경로 자체를 수학적 결함으로 판정하지 않는다. 단, 교정용 tap이라는 사실이 FR까지 무보정이어야 하는 근거는 아니다. 상대 정규화에서는 전대역 offset은 상쇄되지만 주파수별 CAL은 상쇄되지 않는다. “마이크 주파수 보정 미적용”을 FR에 명시하고, 교정된 RTA와 동일 곡선이라고 설명하지 않아야 한다. 마이크 영향을 제거한 응답을 원하면 FR 전용 보정 정책과 배경/신호의 일관된 적용이 필요하다.
2. **로그 칸 경계:** `ceil(lo/binHz)..ceil(hi/binHz)-1` 유도는 맞다. 실제 256개 칸 전체를 독립적인 `lo <= b*binHz < hi` 판정과 비교했다. 48kHz에서는 대상 FFT 칸 1728개, 44.1kHz에서는 1881개 모두 각 1회 배정됐다. 끝 칸의 반 폭 확장도 포함한 수다. 다만 빈 칸에서 가까운 FFT 칸을 빌리는 정책까지 포함하면 값의 중복은 의도적으로 생긴다. fallback 칸은 각각 79/75개였다. “434Hz 아래가 모두 fallback”이라는 설명은 부정확하다. 좁은 구간에도 FFT 중심이 들어가면 자기 칸이 있다. 저역 그림의 칸 중심을 실제 봉우리의 정밀 주파수로 읽어서는 안 된다.
3. **82dB 대 79dB:** 같은 신호의 밴드 전력 합과 최대 FFT 칸은 다른 양이다. 실제 엔진의 48kHz/4096/1kHz 순음 차이는 **2.388573dB**였다. 칸 중심에서는 **1.761973dB**, 반 칸에서는 **3.184887dB**다. Hann 에너지 정규화의 분산(약 10log10(1.5)=1.761dB)에 칸 사이 손실이 더해진다. 따라서 정수 표시에서 3dB 차이는 가능하지만, 그 사실만으로 실기기 82/79 수치를 검증한 것은 아니다. 44.1kHz의 정확한 1kHz에서는 **1.842919dB**였다. 임의의 +3dB 보정을 넣으면 안 된다.
4. **색 눈금 고정:** 한 기록 내에서 색의 뜻이 바뀌지 않는 것은 타당하다. 다만 10~90dB는 모든 기기를 보장하는 범위가 아니다. 기록 시작 전 수동 범위를 고르거나, 범위를 바꿀 때 기록을 비우고 새 범례를 적용하는 방법이 가능하다. 자동 범위를 쓴다면 원래 레벨을 보존해 과거 전체를 다시 칠해야 한다. 현재의 색 비트맵만으로 과거의 정확한 dB를 복원할 수는 없다. 미보정 상태와 상·하한 포화도 구별해 보여야 한다.
5. **Compose:** 현재 코드에서 RtaView/SpectrumView는 새 배열·새 객체로 발행되므로 동일성 equals 자체 때문에 새 데이터가 누락되는 근거는 찾지 못했다. Spectrogram은 그리기 중 읽는 head/frames/spanMs를 snapshot state로 바꿔 redraw 원인을 만든 것이 맞다. 이는 단순히 “val이라 stable”라는 설명보다 정확하다. 관측 가능한 상태 읽기 여부와 새 값 발행을 각각 봐야 한다. [Compose 단계와 상태 읽기 공식 문서](https://developer.android.com/develop/ui/compose/phases). 같은 SpectrogramState 인스턴스를 둔 채 push하고 픽셀이 바뀌는지, wrap 이후에도 바뀌는지 실제 Compose 시험으로 확인해야 한다. 현재 호출 경로의 push는 UI effect에서 수행된다. Android 렌더링/장시간 bitmap 갱신은 이번에 기기로 검증하지 않았다.
6. **하울링 관문:** UA-01처럼 놓친다. 일정한 1kHz 순음이 Persistent가 되는 것은 후보 탐지기로서 정상이며, 순음 검출만으로 실제 음향 피드백임을 증명하지는 못한다.

### 보정 및 동시성에 대한 별도 판단

현재 앱 호출 경로에서는 `spectrumEnabled` 변경이 [CaptureController.kt:190](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CaptureController.kt:190)의 명령 큐를 거치고, `setCurve`도 [CaptureViewModel.kt:1071](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:1071)에서 같은 큐에 들어간다. `onBlock`은 DSP 처리 전에 이를 비우고 같은 캡처 스레드에서 두 결과를 스냅샷에 담는다. 이 경로에서는 두 번의 binCorrection 읽기 사이에 UI가 곡선을 바꾸는 실행 순서를 찾지 못했다.

안전 근거는 단일 처리 스레드에 명령을 모은 구조다. `latest=null`로 지운다는 사실이나 `@Volatile`만으로 동시 실행이 안전해지는 것은 아니다. RtaEngine을 다른 호출자에서 병렬로 직접 조작하는 것은 별도 계약이 필요하다. 명령 큐 경로를 통과하는 변경/스냅샷 통합 시험을 유지해야 한다.

또한 “같은 계수이므로 RTA와 Spectrum이 항상 같은 dB만큼 이동한다”는 주장은 제한해야 한다. 균일한 보정이나 순음 주엽에서 거의 일정한 보정이면 맞지만, 밴드 내부에서 계수가 달라지면 전력 합과 최대값의 이동량은 일반적으로 다르다. raw sink를 그대로 유지하고 표시용 배열에만 보정을 곱하는 구현은 확인했다.

## 실행 결과와 검증 한계

- Kotlin 2.2.20/JBR, 로컬 캐시의 JUnit으로 **DSP 기존 시험 520건 통과**. 이 수에는 범위 밖 기존 DSP 시험도 포함된다.
- 실제 SignalPlayer/SignalSink/TestSignals/PendingList와 fake sink를 사용한 선택된 9개 시험 클래스 **36건 통과**. 전체 app test 수를 뜻하지 않는다.
- 독립 stereo probe: 1234Hz, 진폭 0.1, Both/Left/Right 각 2048 float. 2채널 interleave 및 비활성 채널 0 확인, 최대 오차 0.0. 실제 Android 출력 장치를 검증한 것은 아니다.
- 독립 regression **2건 실행, 2건 실패**: 안정화 뒤 하울링 재검출(UA-01), 보간 뒤 주파수 범위(UA-05).
- FR의 잘못된 배경 수치 영향은 실제 DSP 함수로 실행했다. FR ViewModel lifecycle/route 전환은 정적 경로로 확인했으며 전체 ViewModel 통합 실행으로 재현했다고 주장하지 않는다.
- 시간축은 실제 Timeline 결과와 production 그리기·눈금 수식의 대조로 확인했다. 픽셀 캡처로 재현하지 않았다.
- 이번 실행은 Gradle assemble/lint, 전체 Android app 시험, Compose instrumentation, USB 연결·해제, 실제 AudioRecord/AudioTrack, 장시간/열/배터리 시험을 대체하지 않는다. A 구간의 교정 저장·자동 적용 게이트도 승인하지 않았다.

## 재현 자료

자료는 원본 저장소 밖의 검증 작업 폴더에 두었다.

- [DSP 실행 로그](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/ui-analysis/dsp-test-output.txt)
- [SignalPlayer 선택 시험 로그](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/ui-analysis/audio-test-output.txt)
- [독립 수치 probe 원본](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/ui-analysis/IndependentUiAnalysisProbe.kt) / [결과](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/ui-analysis/independent-probe-output.txt)
- [독립 회귀 시험 원본](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/ui-analysis/IndependentUiAnalysisRegressionTest.kt) / [실패 로그](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/ui-analysis/independent-regression-output.txt)
- [stereo probe 원본](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/ui-analysis/IndependentStereoProbe.kt) / [결과](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/ui-analysis/stereo-probe-output.txt)

동일 폴더의 `run.ps1`이 DSP를 컴파일·실행한다. 그 뒤 `probe.ps1`, `regression.ps1`로 독립 시험을 실행한다. `audio-tests.ps1` 다음 `stereo-probe.ps1`로 출력 버퍼 시험을 실행한다. `regression.ps1`은 현재 결함 때문에 exit code 1을 반환하는 것이 이번 관측 결과다.
