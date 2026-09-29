# SRL 수정 및 비동기 신호 제어 독립 재검증 — 9회차

검토일: 2026-09-29 · 검토자: Codex

## 1. 판정

**승인 보류: Critical 0, High 1, Medium 5. 기존 SRL-01·02·03은 해결 확인했습니다.** 이번 보류는 비동기 명령 처리와 오디오 포커스 경계에서 재현한 새 결함 때문입니다. 94 dB 교정기나 실제 SPL 보정과 관계없이 재현됩니다. 기존 UIS 승인 범위를 되돌리는 판정은 아닙니다.

- 기준 `3e342d5` → 고정본 **`07e0ee4c19c7099c92f8101c95a8af0535c468b1`**. PR #70의 `aec7a59`, #71의 SRL 수정, #72의 `3d54d46`을 포함합니다.
- 검토 도중 HEAD가 #72 병합 커밋 **`bd9d63ec8fb357dea760a36127b343bd05d5d071`**로 이동했습니다. `git diff 07e0ee4 HEAD`는 비어 있어 검토한 트리와 같습니다. 요청서의 #72 상태는 작성 당시의 것입니다.
- 요청서와 저장소 계획에 **인용된 지시 조항**을 기준으로 했습니다. 접근하지 못한 사내 지시서 원문 전체의 준수를 판단하지 않습니다.
- `git archive` 격리본에서 빌드·시험했습니다. 제품 소스는 수정하지 않았습니다. 이 문서, 독립 회귀 시험, 제안 패치와 실행 요약만 추가합니다. PR에 댓글을 쓰거나 병합하지 않았습니다.
- 계측은 **API 34 무음 에뮬레이터**에서 수행했습니다. 실제 휴대폰에는 설치·재생하지 않았습니다. 실제 ViewModel/Android 포커스/화면을 사용하되 결함의 순서를 고정할 때만 SignalSink를 가짜 출력으로 교체했습니다.

## 2. 이전 세 항목의 재검증

| 항목 | 독립 확인 | 판정 |
|---|---|---|
| SRL-01 위상 복원 | 이전 회귀 2개 재실행. 부분 수락 0/128 프레임 뒤 감쇠 첫 표본 각각 −0.3461696/+0.3461696. 기대 파형과 최대 절대 오차 약 7.6e−14 이하 | 해결 |
| SRL-02 고역 대역 | 이전 회귀 5개 재실행, 31개 대역 검사 포함. 호칭 8/16/20 kHz 자리 이득 −0.00189/−0.05909/−0.36577 dB | 현재 48 kHz 출력 경로 해결 |
| SRL-03 다른 화면의 오류 안내 | 실제 MainActivity에서 Tools→RTA 이동, 실제 VM 정지, writer 오류 뒤 RTA 안내를 확인한 이전 회귀 2개 통과 | 해결 |

SRL-03 시험의 정리 단계만 비동기 종료에 맞춰 명령 실행자를 비운 뒤 원래 player를 복구하도록 바꿨습니다. 화면 단언은 유지했습니다. 이 정상 경로의 통과가 아래의 시작/종료 경합까지 증명하지는 않습니다.

## 3. 새 발견 사항

아래 위치는 검토 고정본 기준입니다. 경로는 저장소 루트 기준이며, 회귀 원본은 [독립 계측 시험](2026-09-29-srl-command-independent-regression.kt)에 있습니다. 테스트 이름의 순서를 임의 sleep으로 맞추지 않고 open/write/실행자 입구를 latch로 제어했습니다. 500 ms는 종료 후 누수 관측 한도이며, open이 멎는 상황은 강제된 재현 조건입니다.

### SRLR-01 — High: ViewModel 종료 후 늦게 열린 출력이 계속 살아남음

**위치:** `app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:2677–2686`, `audio/SerialCommands.kt:57–58`, `audio/SignalPlayer.kt:266–291`.

`onCleared()`는 `shutdownNow()` 직후 주 스레드에서 `player.stop()`과 `interruptions.release()`를 호출합니다. 그런데 실행자에서 진행 중인 `SignalSink.open()`은 인터럽트로 반드시 취소되는 계약이 없습니다. 아직 `current`가 없을 때 주 스레드의 stop은 끝납니다. 그 뒤 open이 돌아오면 종료된 소유자의 worker가 새 Playback과 writer를 시작합니다. 정리 명령은 더 이상 없습니다. 요청서 §3의 **「돌아가고 나면 아무도 안 남는다」는 현재 코드에서 성립하지 않습니다.**

**재현:** 실제 VM에서 Pink 시작 → open 반환을 보류 → ViewModelStore.clear → open 허용 → 실행자 종료 확인. 관측은 `released=false, player=Pink, wrote=true`. 테스트 정리 코드로 별도 stop을 호출해야 해제됐습니다.

**영향:** 화면/소유자가 사라진 뒤 신호가 출력되고 오디오 자원이 남습니다. 사용자는 기존 화면의 정지 버튼으로 끌 수 없습니다. 단순 표기 오류보다 높은 위험입니다.

**권장 수정:** 시작·정지·retune·최종 해제를 동일한 제어 실행자가 소유해야 합니다. 닫힘/의도 번호로 이미 실행 중인 시작을 무효화하고, 그 작업 **뒤에 반드시 실행되는 최종 정리**를 예약하십시오. 주 스레드에서 stop을 경합시키거나 `shutdownNow()`를 완료 대기로 간주하지 마십시오. [JDK ExecutorService 문서](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ExecutorService.html)의 shutdownNow도 인터럽트 시도이며 종료 대기가 아닙니다.

**필요 회귀:** `clearedViewModelMustReleaseDelayedOpen`. 인터럽트를 무시하는 open이 나중에 반환해도 해제가 끝나고 `playing == null`이어야 합니다. 별도로 실제 기기에서 화면 종료 중 느린 장치 열기/정지를 확인해야 합니다.

### SRLR-02 — Medium: 늦은 시작 완료가 더 최신의 정지 화면을 되돌림

**위치:** `CaptureViewModel.kt:1327`, `1335–1351`, `1381–1389`.

`SerialCommands`는 **아직 실행하지 않은 명령**만 버립니다. 이미 실행 중인 start의 완료는 새 stop 이후에도 `onMainThread`에서 무조건 `playGeneration`과 `playingSignal`을 덮습니다. queued stop은 출력만 멈추고 UI를 다시 고치지 않습니다.

**재현:** Pink open 보류 → stopSignal → open 반환 → 실제 release와 명령 종료까지 대기. 최종 값은 **`ui=Pink, player=null`**입니다. 프레임 하나 동안의 중간 상태가 아닙니다.

**영향:** 꺼진 신호가 계속 재생 중으로 보이고, 이후 슬라이더가 그 상태를 읽어 의도치 않은 재시작을 유발할 수 있습니다.

**권장 수정:** 사용자 의도 번호를 main에서 증가시키고 시작 명령 및 결과 게시에 함께 전달하십시오. worker 완료 시와 main 적용 시 모두 최신 번호/닫힘 여부를 확인해야 합니다. Playback generation만으로 시작 전의 사용자 의도를 대체할 수 없습니다.

**필요 회귀:** `stopDuringOpenMustNotRestorePlayingUi`. start→stop, start→start, start→background, start→clear를 열린 장치의 반환 전후로 나눠 확인하십시오.

### SRLR-03 — Medium: 주 스레드 retune이 대기 중인 재시작의 오래된 주파수를 고치지 못함

**위치:** `CaptureViewModel.kt:1319–1324`, `1478–1482`, `1493–1511`.

현재 1 kHz Custom을 재생하면서 세기를 변경하면 **1 kHz를 담은 재시작 요청**이 큐에 들어갑니다. 이어서 3 kHz로 retune하면 현재 옛 Playback만 바뀝니다. retune이 true이므로 새 명령도 넣지 않습니다. 이후 대기한 요청이 실행되며 1 kHz로 되돌립니다.

**재현:** 기존 Custom 재생 → 실행자 보류 → 세기 0.1, 주파수 3,000 Hz 순서로 변경 → 실행자 해제. 새 출력의 2,048 표본 구간에서 영교차로 측정한 값 **1,007.8125 Hz**, 화면 **3,000 Hz**. 유한 구간의 영교차 해상도 때문에 1,000과 약간 차이 납니다. 3 kHz가 1 kHz로 되돌아간다는 판정에는 영향이 없습니다.

**영향:** 사람이 화면에 적힌 주파수와 다른 순음을 들으며 공진/EQ를 판단합니다.

**권장 수정:** 주파수도 세기·채널과 함께 불변 요청으로 직렬화하십시오. worker가 최신 요청을 받은 뒤 주파수만 달라졌으면 **기존 Playback을 retune**, 다른 조건이 바뀌었으면 재시작하도록 하면 평상시의 위상 연속성을 보존할 수 있습니다. 자물쇠가 짧다는 설명은 이 요청 간 정합성을 해결하지 않습니다.

**필요 회귀:** `retuneMustAlsoUpdateAPendingRestart`. 세기→Hz, 채널→Hz 순서 및 보통의 Hz-only 변경에서 출력 주파수/동일 트랙/위상 연속성을 함께 확인하십시오.

### SRLR-04 — Medium: 옛 종료의 자원 정리가 사용자가 누른 새 시작을 취소함

**위치:** `CaptureViewModel.kt:729–744`, `audio/SerialCommands.kt:47–52`.

옛 writer의 `onEnded`가 `postSignalCommand { interruptions.release() }`를 넣으면 그것도 최신 명령 번호를 증가시킵니다. 이 정리 작업보다 먼저 줄에 선 새 playSignal은 낡은 명령으로 간주돼 사라집니다. 새 시작이 아직 실행되지 않아 `playGeneration`이 옛 값인 경우라 기존 세대 검사는 막지 못합니다.

**재현:** Custom 재생 → 실행자 보류 → Pink 시작 요청 → 옛 writer DEAD_OBJECT → 종료 콜백 전달 → 실행자 해제. **`queuedPink=true, opened=1, ui=null`**. 두 번째 출력 자체가 열리지 않았습니다.

**영향:** 장치 오류/자동 종료와 신호 교체가 겹치면 사용자의 새 재생 요청을 잃습니다. 신호는 멎고 지난 재생의 오류 안내가 표시됩니다.

**권장 수정:** 최신 상태 명령과 **필수 생명주기 이벤트**를 분리하십시오. onEnded는 사용자 명령 번호를 갱신하지 않는 경로로 같은 실행자에 넣고, 그 실행자가 소유 중인 generation의 종료일 때만 정리하십시오. main 쪽 결과에는 별도의 의도 번호 검사를 적용하십시오.

**필요 회귀:** `oldEndedCleanupMustNotCancelNewStart`. 새 시작이 대기 중일 때와 이미 시작했을 때의 옛 종료를 모두 다루어야 합니다. 반환 전 매우 빨리 끝난 새 writer의 onEnded 역시 시작 등록 뒤에 처리되어야 합니다.

### SRLR-05 — Medium: 시작 실패 시 오디오 포커스와 수신기를 반환하지 않음

**위치:** `CaptureViewModel.kt:1339–1350`, `audio/AudioInterruptions.kt:95–111`, `120–126`.

acquire 뒤 `player.start()`가 NONE을 반환하면 UI만 오류로 바뀝니다. open 실패에는 writer의 onEnded가 오지 않으므로 그 경로의 release도 기대할 수 없습니다.

**재현:** 실제 AudioInterruptions를 사용하고 SignalSink.open만 false로 반환. 명령과 main 처리가 끝난 뒤 **`player=null, interruptionHeld=true`**. 등록 상태가 남고 포커스를 반환하는 호출 경로도 없습니다.

**영향:** 출력하지 않는 앱이 수신기를 계속 등록해 두고, 얻은 transient 포커스를 불필요하게 유지해 다른 앱의 재생을 방해할 수 있습니다. 별도 stop/background/clear까지 지속됩니다.

**권장 수정:** 자원 획득 뒤 실패하는 모든 시작 경로에서 release하십시오. 성공한 재생만 포커스 소유권을 유지하도록 성공/실패를 분리하는 것이 안전합니다. 예외 경로도 같은 원칙이 필요합니다.

**필요 회귀:** `failedOpenMustReleaseFocusAndReceiver`. open false 외에 pending 상한/NONE 반환, acquire 또는 장치 시작 예외도 추가하십시오.

### SRLR-06 — Medium: 포커스 거절을 무시하고 정상 재생으로 표시

**위치:** `CaptureViewModel.kt:1336–1340`, `audio/AudioInterruptions.kt:88–111`.

요청서가 밝힌 정책이기는 하지만, `acquire()`의 false를 무시하는 것은 승인하기 어렵습니다. 포커스를 얻지 못한 상태의 재생을 정상 시험 조건으로 취급하고 사용자에게 알리지도 않습니다. 포커스 요청 실패는 다른 앱의 존재 외에도 시스템 정책 때문일 수 있습니다.

**독립 재현:** 에뮬레이터의 해당 앱에만 `TAKE_AUDIO_FOCUS ignore`를 설정하고 별도 AudioInterruptions.acquire가 false인지 선행 확인했습니다. 이후 VM의 Pink 시작에서 **sink 1개가 열렸습니다**(기대 0). 일반 경합 시험은 `allow`로 분리했고, 이 사례는 `ignore`에서 따로 실행했습니다.

**영향:** 타 앱과의 출력 조정 및 포커스 상실 통지를 전제로 한 보호가 확보되지 않은 채 테스트 신호를 내보냅니다. 화면의 정상 재생 상태만 보고 측정 조건을 신뢰하게 됩니다.

**권장 수정:** 포커스 거절 시 신호를 시작하지 않고 획득한 수신기 등록을 해제하며 이유를 표시하십시오. 자동 재시작은 하지 않습니다. [Android 오디오 포커스 안내](https://developer.android.com/media/optimize/audio-focus)는 포커스를 얻은 뒤 재생하는 흐름을 설명합니다. 「다른 앱 때문에 준비를 막지 않는다」는 편의가 실패를 숨길 근거는 아닙니다.

**필요 회귀:** `deniedFocusMustNotOpenOutput`. 거절 후 sink 미개방/수신기 반환/안내 표시, 이어서 사용자가 재시도해 포커스를 얻었을 때만 시작하는 경로를 추가하십시오.

## 4. 요청서 질문에 대한 답

1. **retune을 main에 남기는 판단:** 반대합니다. 이번 반례는 lock 지연이 아니라 이전 요청에 담긴 값과 현재 UI 값의 불일치입니다. 실제 출력 제어를 한 실행자에 모으고, 그곳에서 retune 최적화를 선택하십시오.
2. **낡은 명령 버리기:** 완전한 목표 상태를 가진 start/stop끼리는 가능합니다. 실행 중인 명령의 완료와 onEnded 정리까지 같은 규칙으로 취급해서는 안 됩니다. SRLR-01·02·04가 각각 종료 소유권, 결과 게시, 이벤트 취소 문제입니다.
3. **CAN_DUCK에서 정지:** 고정된 시험 신호 크기를 몰래 바꾸지 않는다는 선택은 타당합니다. 다만 `setWillPauseWhenDucked(true)`가 CAN_DUCK를 반드시 LOSS_TRANSIENT로 바꾼다는 주석은 부정확합니다. 자동 duck 대신 listener로 처리할 수 있게 하는 요청입니다. 현재 매핑은 두 종류 모두 처리하므로 이 주석은 차단 결함으로 세지 않았습니다. [AudioFocusRequest 문서](https://developer.android.com/reference/android/media/AudioFocusRequest)를 기준으로 설명을 수정하십시오.
4. **44.1 kHz의 맨 위 대역:** 현재 player는 48 kHz 고정이어서 이번 승인 차단 항목에 넣지 않습니다. 44.1 kHz에서는 20 kHz의 전체 1/3옥타브 상단 22,449 Hz를 표현할 수 없습니다. 44.1 도입 시 UI/요청 검증에서 그 대역을 비활성화하거나 잘린 대역임을 표시하십시오. 재생 스레드에서 예외를 던지라는 뜻은 아닙니다. 양 끝 prewarp가 보장하는 경계와 이득 최대점도 구분해야 합니다. 현재 식의 최대점은 계산상 48 kHz에서 약 20,854 Hz, 44.1 kHz에서 약 21,740 Hz입니다. 「중심이 그대로 맞는다」고 최대점까지 단정할 수 없습니다. 위 수치는 필터 식 계산이며 실제 44.1 kHz 기기 측정이 아닙니다.

## 5. 검증 결과와 재실행 방법

원본 고정본에서 `--offline --no-build-cache --rerun-tasks --max-workers=2`로 `:dsp:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`를 실행했습니다.

| 원본 검증 | 결과 |
|---|---|
| 기존 JVM 시험 | DSP **633**, 앱 **851**, 합 **1,484** / 실패·오류·건너뜀 0 |
| 기존 Android 계측 | **28개 통과**, API 34 무음 에뮬레이터 |
| 이전 독립 회귀 | 위상 2 + 대역 5 + 실제 화면 2 = **9개 통과** |
| 새 독립 회귀 | 일반 실행 5개 실패 + 포커스 거절 별도 1개 실패 = **6개 결함 재현** |
| 빌드/lint | 성공, lint Error/Fatal 0 · Warning 11 · Hint 4 |

포커스 거절 시험은 일반 실행에서는 assumption으로 건너뜁니다. 따라서 일반 실행의 `Tests run: 6, Failures: 5`를 여섯 건의 통과/실패로 잘못 해석하지 않았습니다. `ignore` 조건에서 거절을 확인한 뒤 별도 실행한 1건이 추가 실패합니다.

재실행하려면 회귀 파일을 격리본의 `app/src/androidTest/java/kr/joa/selahrta/ui/SrlCommandIndependentTest.kt`로 복사하고 앱 APK와 test APK **둘 다** 빌드·설치하십시오. 실제 기기 대신 검토용 무음 에뮬레이터를 권합니다.

```powershell
# 검토용 에뮬레이터에 한정. 일반 경합 시험은 포커스 허용 상태로 실행.
adb -s emulator-5580 shell appops set kr.joa.selahrta TAKE_AUDIO_FOCUS allow
adb -s emulator-5580 shell am instrument -w -r -e class kr.joa.selahrta.ui.SrlCommandIndependentTest kr.joa.selahrta.test/androidx.test.runner.AndroidJUnitRunner

# 포커스 거절만 별도 실행. 종료 후 에뮬레이터 설정을 복구한다.
adb -s emulator-5580 shell appops set kr.joa.selahrta TAKE_AUDIO_FOCUS ignore
adb -s emulator-5580 shell am instrument -w -r -e class 'kr.joa.selahrta.ui.SrlCommandIndependentTest#deniedFocusMustNotOpenOutput' kr.joa.selahrta.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5580 shell appops set kr.joa.selahrta TAKE_AUDIO_FOCUS allow
```

최종 제공 회귀 파일에는 위 여섯 반례 외에 `frequencyOnlyRetuneKeepsTheTrackAndUsesLatestHz` 정상 경로 1개를 추가했습니다. 원본의 여섯 결함 재현 수에 이 정상 시험을 합산하지 않았습니다.

작업 로그는 `build/independent-review/srl-20260929/`에 있습니다. 이 경로는 git 추적 대상이 아니므로 핵심 출력은 [실행 요약](2026-09-29-srl-verification-evidence.txt)에도 보존했습니다.

## 6. 수정 제안과 승인 조건

[제안 패치](2026-09-29-srl-command-ownership-proposed.patch)는 `SerialCommands.kt`와 `CaptureViewModel.kt` 두 파일만 다룹니다. 제품에는 적용하지 않았습니다.

- 사용자 명령에는 의도 번호, worker의 재생 소유권에는 Playback generation을 사용합니다.
- 종료 이벤트는 사용자 명령을 덮어쓰지 않는 `postAlways`로 처리합니다.
- 화면 종료는 신규 접수를 닫은 뒤 같은 worker의 마지막 정리로 처리합니다. main에서 실행 중인 start와 stop을 경합시키지 않습니다.
- 주파수·세기·채널을 함께 전달하고, worker에서 조건이 맞을 때만 retune합니다.
- 포커스 거절과 시작 실패 시 획득 자원을 반환합니다.

**제안 패치 독립 실행 결과:**

| 제안 검증 | 결과 |
|---|---|
| 앱 JVM | 기존 851 + 이전 위상 회귀 2 = **853개 통과**, 실패·오류·건너뜀 0 |
| 빌드/lint | debug APK와 test APK 생성, lint 성공 |
| 전체 계측 실행 | 기존 28 + 이전 화면 회귀 2 + 새 반례 6 = runner 표기 **36개**, 실제 35 통과·포커스 조건 1건 건너뜀 |
| 최종 제공 회귀 파일 재실행 | 새 반례 6 + 정상 retune 1: **6 통과·포커스 조건 1건 건너뜀** |
| 포커스 거절 조건 별도 실행 | **1개 통과**, sink 미개방·수신기 반환·안내 존재 확인 |
| 적용 가능성 | 현재 원본에서 `git apply --check` 통과, 실제 적용은 하지 않음 |

관측은 `ui=null/player=null`, 종료 뒤 `released=true`, 새 Pink 요청 `opened=2`, 화면 3 kHz/출력 약 **2,976.56 Hz**(2,048 표본 영교차 해상도), 실패 시작의 `interruptionHeld=false`, 포커스 거절 시 `opened=0`으로 바뀌었습니다. DSP 제품 소스는 제안에서 바꾸지 않았으며 원본의 DSP 633개와 추가 대역 회귀 5개 통과 결과를 그대로 분리해 보고합니다.

제안 패치는 **이 여섯 반례의 해결 방향을 검증하기 위한 것**입니다. 네이티브 open이 영원히 반환하지 않는 문제를 강제 종료로 해결하거나, 닫힘 이후 단 한 표본도 출력되지 않는다고 보증하지 않습니다. 이번 종료 회귀는 늦게 반환한 open의 **최종 해제와 고아 writer 방지**를 확인합니다. 사용자가 정지한 뒤 이미 시작 중인 장치가 반환할 때까지의 실제 지연, 이어폰 분리 시 소리 누출, PA에서의 클릭·프레임 부드러움은 실제 기기 확인이 필요합니다.

구현자가 위 반례와 정상 retune/재시작 동작을 함께 보존하도록 수정한 후 재검증하면 코드 승인으로 전환할 수 있습니다. **이번에 코드 승인을 막는 여섯 항목을 해결하는 데 94 dB 기준소음계는 필요하지 않습니다.** 절대 SPL 정확도와 실제 음향 품질의 검증은 별도 경계로 남습니다.

검토 도중 주 작업 폴더에 별도의 RTA 저장 기능 소스·시험·계획 파일이 생성되었습니다. 이들은 이번 고정본과 검토 산출물에 포함하지 않았고 변경하지 않았습니다.
