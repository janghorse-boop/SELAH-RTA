# 테스트 신호–RTA 연동 독립 검토 — 8회차

검토일: 2026-09-29 · 검토자: Codex

## 1. 판정과 검증 범위

**Critical/High는 발견하지 않았습니다. Medium 3건 때문에 이번 신호–RTA 기능의 완료 승인은 보류합니다. 기존 UIS 승인과 UIS7-01의 해결 확인은 유지합니다.** 새 세 문제에는 각각 수정 제안과 회귀 시험을 첨부했습니다. 94 dB 교정기 없이도 재현되는 소프트웨어 결함입니다.

- 기준: `a4eb1e1` → 검토 고정본 **`3e342d51f64f45ff691a2199bc9bdeb100d28299`**.
- 요청서의 PR #67과 #68 모두 이 고정본에 병합되어 있습니다. 요청서의 #68 「열림」 표시는 과거 상태입니다.
- 새 UI가 사용하는 선행 변경 중 순음 재조율과 대역 잡음 경로도 확인했습니다. SRL-02는 **#66에서 들어온 선행 결함**입니다. #67이 그 결함을 처음 만든 것은 아닙니다.
- 마이크 위치 DB 삭제 등 선행 PR 전체를 재검증한 것은 아닙니다.
- 요청서와 `docs/superpowers/plans/2026-09-29-signal-rta-link.md`, 기존 Master Spec의 신호 관련 항목을 읽었습니다. 요청서가 인용하는 별도 「테스트 신호–RTA 연동 개발지시서」 원문은 저장소에서 찾지 못했습니다. 따라서 그 원문의 모든 조항 준수까지 승인하지 않습니다.
- `git archive`로 만든 분리 복사본에서 실행했습니다. 검토 중 주 작업 폴더에는 `5623b8c`, `aec7a59`, `baa7c82`가 추가되었습니다. 특히 `aec7a59`의 인터럽트 기능은 **이번 고정본 이후의 변경으로, 이번 판정에 포함되지 않습니다.** 다른 작업자의 변경은 건드리지 않았습니다.

제품 파일은 수정하지 않았습니다. 아래 패치는 검토용 복사본에서 시험한 제안이며, 주 작업 폴더에는 검토 문서·시험 원본·패치만 추가했습니다.

## 2. 발견 사항

### SRL-01 — Medium: 부분 쓰기 뒤의 감쇠 파형이 버린 표본만큼 위상을 건너뜀

**위치:** `app/src/main/java/kr/joa/selahrta/audio/SignalPlayer.kt:376–378`, `414–444`.

순음의 `phase`는 1,024 프레임 버퍼를 **만들 때** 모두 전진합니다. 정지 요청이 오면 쓰지 않은 나머지를 버리지만, 실제 수락량으로 되돌리는 것은 `sample`뿐입니다. 다음 감쇠 버퍼는 끝까지 생성했던 위상에서 시작합니다. 진폭이 30 ms에 걸쳐 줄어들어도 감쇠 시작점에는 별도의 위상 불연속이 생깁니다.

**독립 재현:** 48 kHz·1 kHz·진폭 0.4, 처음 두 버퍼 수락 후 세 번째 `write`를 latch로 붙들었습니다. 같은 제어 스레드에서 `stop()`을 부르고 `playing == null` 전환을 관측한 뒤 세 번째 쓰기를 풀었습니다. 따라서 임의의 sleep에 기대어 정지 시점을 맞추지 않습니다.

| 세 번째 버퍼 수락 | 감쇠 직전 수락 프레임 | 감쇠 첫 표본 기대 | 실제 | 감쇠 표본열 최대 절대 오차 |
|---|---:|---:|---:|---:|
| 0 프레임 | 2,048 | -0.3461696 | 약 0 | 0.6884902 |
| 128 프레임 | 2,176 | +0.3461696 | 약 0 | 0.6846412 |

감쇠 1,440 프레임은 모두 수락됐습니다. 시간 초과로 감쇠 자체를 포기한 사례가 아니라 **감쇠가 실행돼도 연결점이 틀리는** 사례입니다. 기존 `SignalRampTest`는 전량 수락한 파형의 크기·길이를 보므로 둘 다 놓칩니다.

**사용자 영향:** 정지·신호 교체 시 없애려던 클릭성 불연속이 남습니다. 실제 PA에서 얼마나 들리는지는 별도 확인해야 합니다.

**권장 수정:** 버퍼 안의 프레임 경계별 위상을 보존하고, 쓰기 후 `phase`를 `sent / CHANNELS` 경계로 되돌립니다. 재조율이 버퍼 생성 도중 일어날 수 있으므로 현재 Hz 하나로 버린 프레임을 역산하면 안 됩니다. 첨부 패치는 재생당 `DoubleArray(1025)` 하나를 재사용합니다.

**회귀 시험:** 첨부 `SignalRtaIndependentProbeTest`의 0/128 프레임 두 경로, 기존 부분 쓰기·재조율·램프·늦은 종료·강제 해제 방지 시험. 수정 제안에서는 독립 두 시험과 기존 관련 시험을 합한 **30건 모두 통과**했고, 독립 표본열 오차는 `7.6e-14` 이하였습니다.

### SRL-02 — Medium: 고역 대역 잡음의 표시 주파수와 실제 통과 대역이 크게 다름

**위치:** `dsp/src/main/kotlin/kr/joa/selahrta/dsp/BandNoiseFilter.kt:43–59`; 표시 연결은 `app/src/main/java/kr/joa/selahrta/ui/components/SignalMiniBar.kt:129`.

필터가 `w0 = 2πf0`인 아날로그 원형을 사전 보정 없이 쌍일차 변환합니다. 실제 디지털 최대 통과점은 `fs/π · atan(πf0/fs)`가 됩니다. 20 kHz 선택은 추가로 `f0`를 19.2 kHz로 잘라 버립니다. 그런데 `centerHz`와 미니 바는 선택한 호칭값을 그대로 적습니다.

**독립 재현:** 제품의 `BandNoiseFilter.process()`에 순음을 직접 넣고, 정착 구간 뒤 RMS 이득을 측정했습니다. 단순히 `centerHz` 속성이 같은지만 검사하지 않았습니다.

| 화면의 대역 | 원래 코드의 최대 통과점 | 표시 주파수에서의 이득 |
|---:|---:|---:|
| 3,150 Hz | 약 3,106.5 Hz | -0.055 dB |
| 8,000 Hz | 약 7,369.7 Hz | -2.252 dB |
| 16,000 Hz | 약 12,352.2 Hz | -19.561 dB |
| 20,000 Hz | 약 13,730.2 Hz | -34.737 dB |

각 표의 최대 통과점에서는 약 0 dB입니다. 특히 16/20 kHz는 반올림이나 눈금 차이가 아닙니다. 기존 파형 시험은 1 kHz 중심에 몰려 있고, 3,147→3,150 시험은 숫자 속성만 확인해 고역 오류를 놓칩니다.

**사용자 영향:** 16 kHz EQ 슬라이더를 점검한다고 생각하면서 실제로는 약 12.35 kHz 쪽 소리를 듣게 됩니다. 다른 주파수의 반응을 해당 밴드 문제로 해석할 수 있습니다. RTA 분석기의 계산 자체가 이 필터를 사용하는 것은 아닙니다.

**권장 수정:** 중심 하나만 바꾸지 말고 원하는 디지털 대역의 **양 끝을 prewarp**한 뒤 필터를 설계합니다. 첨부안은 다음을 사용합니다.

```text
fL = fc / 2^(1/6), fH = fc · 2^(1/6)
ΩL = 2fs tan(π fL/fs), ΩH = 2fs tan(π fH/fs)
Ω0 = sqrt(ΩL ΩH)
Qsection = Ω0/(ΩH−ΩL) · sqrt(sqrt(2)−1)
```

두 동일 구간을 이으면 디지털 양 끝이 약 -3.0103 dB가 됩니다. `centerHz`는 **호칭 대역 중심**이며 필터의 응답 최대점과 항상 동일하다고 설명하지 않아야 합니다.

**회귀 시험:** 첨부 시험은 48 kHz의 31개 호칭 대역 모두에서 중심 이득 ≥ -1 dB, 양 끝 -3.0103 ± 0.10 dB를 확인합니다. 기존 8건과 독립 5건, **13건 통과**했습니다. 수정 후 16/20 kHz 중심 이득은 각각 -0.059/-0.366 dB입니다.

**제안의 적용 조건:** 첨부안은 대역 상단이 Nyquist를 넘으면 명시적으로 거절합니다. 현재 `SignalPlayer`는 48 kHz라 20 kHz 대역 상단까지 표현할 수 있습니다. 향후 44.1 kHz 출력을 허용하면 20 kHz의 완전한 1/3 옥타브 대역은 상단이 Nyquist를 넘으므로 UI에서 제한하거나 별도 정책을 정해야 합니다. IEC 적합성을 인증한 수정안은 아닙니다.

### SRL-03 — Medium: RTA에서 출력이 실패하면 미니 바만 사라지고 오류 원인은 숨겨짐

**위치:** `app/src/main/java/kr/joa/selahrta/ui/SelahApp.kt:375–381`, `AppBottomArea:888–899`; 오류 생산은 `CaptureViewModel.kt:727–735`, 유일한 표시 전달은 `ui/screens/ToolsScreen.kt:60`.

출력이 `ERROR_DEAD_OBJECT`로 끝나면 ViewModel은 `playingSignal = null`과 오류 문구를 함께 저장합니다. 그러나 전역 아래 영역에는 `playingSignal`만 내려가고, 오류 문구는 신호 화면 안의 카드에서만 그립니다. 따라서 RTA에서는 미니 바가 사라질 뿐 **사용자가 정지한 것과 장치 오류가 화면상 구분되지 않습니다.** 측정은 계속될 수 있습니다.

**독립 재현:** 무음 API 34 에뮬레이터에서 실제 `MainActivity → SelahApp → CaptureViewModel`을 열었습니다. 검토용 reflection으로 VM의 `SignalPlayer`에만 가짜 `SignalSink`를 주입했으며, VM의 원래 종료 콜백·세대 검사는 그대로 사용했습니다.

1. 가짜 출력으로 핑크 재생을 시작합니다.
2. 실제 도구 탭 → 실제 「RTA에서 재기」를 누릅니다.
3. 화면 이동 동안 재생 상태 유지, `sink.stop == 0`, 자원 미해제를 확인합니다.
4. `ERROR_DEAD_OBJECT`를 돌려줍니다.
5. VM의 오류 문구 저장·재생 상태 null·자원 해제까지 확인합니다.
6. 같은 오류 문구가 화면에 보여야 한다는 단언은 **실패**합니다.

짝 시험인 실제 미니 바 → `vm.stopSignal()` → 가짜 출력 정지·해제 경로는 **통과**했습니다. 즉, 현재 정지 버튼이 다른 재생을 멈추거나 아무 일도 하지 않는다는 주장은 아닙니다.

**사용자 영향:** 테스트 입력이 끊긴 것을 모르고 RTA의 레벨 감소를 방·PA·마이크의 응답으로 읽을 수 있습니다.

**권장 수정:** 기존 `signalNoticeKo`를 전역 알림에도 연결하십시오. 첨부안은 신호 화면 이외의 화면에서 확인 버튼이 있는 지속 Snackbar를 표시합니다. 전체 재생 계층을 다시 만들 필요는 없습니다.

**회귀 시험:** 실제 도구→RTA 이동, 사용자 정지 시 정지·해제, 현재 세대 오류의 전역 표시. 이어서 오래된 세대 오류가 새 재생의 알림을 덮지 않는 경우, 확인 후 알림 제거, 다른 화면 전환도 정식 회귀에 추가하는 것이 좋습니다. 검토용 reflection은 경로를 확인하기 위한 임시 수단이며, 제품 테스트에서는 Player 팩터리 주입을 권장합니다.

## 3. 요청서의 질문에 대한 답

### nullable 신호와 generation이면 상태 기계와 같은가

**현재의 화면 간 소유권 유지·재생 세대 분리는 그 구조로 가능합니다. 계층 이름을 맞추기 위한 재작성은 필요 없습니다.** 실제 Activity/VM 경로 시험에서도 화면 이동이 출력 장치를 닫지 않고, 미니 바 정지가 그 출력을 멈추는 것을 확인했습니다.

다만 「동일한 상태 기계」라는 주장은 넓습니다. 실제로는 `signalNoticeKo`, `pendingCount`, `failedReleaseCount`도 상태 일부이며, SRL-03처럼 Error가 주 화면에 전달되지 않는 자리가 있습니다. Starting/Stopping은 현재 동기 호출 사이에 가려져 UI가 볼 수 없습니다. 지시서의 Interrupted에 대응하는 포커스·출력 경로 변경 처리는 고정본에 없습니다. 요청서에서 유보한 §7 저장·비교도 미완료입니다.

비동기 제어를 추가한다면 최소한 `generation + request + phase + reason`을 한 상태로 발행하고 **명령을 한 제어 실행자에서 직렬 처리**하는 방식을 권장합니다. 작업마다 별도 `launch`로 `start/stop`을 병렬 호출하면 지금의 단일 제어 스레드 계약을 깨뜨립니다.

### 120 ms는 다른 자리를 늦추는가

**주 스레드를 기다리게 합니다.** `playSignal`, `stopSignal`, 레벨·채널 변경과 대역 주파수 변경에서 직접 `player.start/stop`을 부릅니다. `await`가 lock 밖이라는 것은 교착 방지에 필요하지만 UI가 기다리지 않는다는 뜻은 아닙니다. 정지마다 최대 120 ms의 감쇠 대기가 추가되고, 그 뒤 `sink.stop()`·최대 500 ms join 등이 이어집니다. 전체 함수가 120 ms 안에 끝난다는 보장도 없습니다.

이번 실행만으로 정상 장치에서 항상 120 ms를 소모한다거나 ANR이 발생한다고 단정하지 않습니다. 다만 연속 슬라이더 조작의 지연 검증과 직렬 제어 실행자로의 대기 이전은 권장합니다. 이동할 때는 늦게 끝나는 writer를 강제 release하지 않는 기존 계약을 유지해야 합니다.

### 쓰기를 끝내면 귀에도 램프가 끝난 것인가

구분해야 합니다. Android 문서상 streaming `write` 반환은 데이터를 **재생 큐에 넣었다**는 뜻이며 실제 청취 완료는 아닙니다. `MODE_STREAM.stop()`은 남은 버퍼 재생 동작도 갖습니다. 따라서 fake sink의 파형만으로 실제 클릭 제거·정지 지연·출력 장치별 tail 동작을 승인하지 않았습니다. 이번 검토에서 「즉시 release라 실제 장치에서 반드시 tail이 잘린다」고 단정한 것도 아닙니다. [Android AudioTrack 공식 문서](https://developer.android.com/reference/android/media/AudioTrack).

## 4. 독립 실행 결과

| 검증 | 결과 |
|---|---|
| 고정본 DSP JVM | 628건, 실패·오류·skip 0 |
| 고정본 앱 JVM | 838건, 실패·오류·skip 0 |
| 고정본 debug APK·androidTest APK 빌드 | 성공 |
| 고정본 lintDebug | 오류 0, 경고 11, hint 4 |
| 고정본 기존 UI 계측 | API 34 무음 에뮬레이터, 17건 통과 |
| 독립 JVM 반례 초기 6건 | 위상 2건 실패, 고역 3건 실패, 3.15 kHz 대조 1건 통과 |
| 실제 화면·VM 독립 계측 2건 | 정지/이동 1건 통과, 오류 표시 1건 실패 |
| 위상 수정 제안 | 독립 2 + 기존 관련 28 = 30건 통과 |
| 대역 수정 제안 | 독립 5 + 기존 8 = 13건 통과, 31대역 중심·경계 포함 |
| 전역 알림 수정 제안 | 독립 2 + 기존 17 = 19건 통과, `OK (19 tests)` 확인 |
| 세 제안을 함께 적용한 전체 JVM | DSP 633 + 앱 840 = **1,473건**, 실패·오류·skip 0 |

기준 빌드와 테스트를 먼저 완료하고 성공한 APK를 설치했습니다. 계측 명령의 종료 코드만 보지 않고 `OK (...)`/`FAILURES!!!`를 확인했습니다. 실기기에는 APK를 설치하거나 소리를 내지 않았습니다.

## 5. 전달 파일과 재현 방법

- `2026-09-29-signal-rta-proposed.patch`: 세 문제의 최소 수정 제안. **고정본 기준 적용 가능 여부 검사 통과.** 이후 구현자의 변경과는 병합 조정이 필요할 수 있습니다.
- `2026-09-29-signal-rta-phase-regression.kt`: `app/src/test/java/kr/joa/selahrta/audio/SignalRtaIndependentProbeTest.kt`로 복사.
- `2026-09-29-signal-rta-band-regression.kt`: `dsp/src/test/kotlin/kr/joa/selahrta/dsp/BandCenterIndependentProbeTest.kt`로 복사.
- `2026-09-29-signal-rta-ui-regression.kt`: `app/src/androidTest/java/kr/joa/selahrta/ui/SignalFailureIndependentProbeTest.kt`로 복사.

```powershell
# 제품 작업 폴더 대신 고정한 검토용 복사본에서 실행
.\gradlew.bat --offline --no-build-cache --max-workers=2 `
  :app:testDebugUnitTest --tests '*SignalRtaIndependentProbeTest' `
  :dsp:test --tests '*BandCenterIndependentProbeTest'

# APK 빌드 성공을 확인한 뒤 에뮬레이터에 두 APK를 설치
adb -s emulator-5580 shell am instrument -w -r `
  -e class kr.joa.selahrta.ui.SignalFailureIndependentProbeTest `
  kr.joa.selahrta.test/androidx.test.runner.AndroidJUnitRunner
```

원본 로그·XML·분리 소스는 `build/independent-review/signal-rta-20260929/`에 보관했습니다. `baseline-results/`, `probe-results-before/`, `baseline-build.log`, `independent-probes-before.log`, `phase-proposal-check.log`, `band-proposal-31bands.log`, `combined-proposal-jvm.log`, `ui-instrumentation.log`, `ui-rta-probe-before.log`, `ui-proposal-check.log`를 서로 구분했습니다. 빌드 폴더는 Git 추적 대상이 아닙니다. 검토용 에뮬레이터는 시험 후 종료했습니다.

남는 실물 검증은 실제 출력 경로의 청감·정지 지연, 이어폰/USB/포커스 인터럽트, 화면 공간 사용성, 장시간 부하와 음향 교정입니다. 이를 이유로 위 세 소프트웨어 문제를 미룰 필요는 없으며, 위 세 문제를 고쳤다고 실물 검증까지 완료되는 것도 아닙니다.
