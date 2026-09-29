# 신호 명령 소유권 독립 재검증 — 10회차

검토일: 2026-09-29 · 검토자: Codex

## 1. 판정과 범위

**Critical/High 없음. 이전 High SRLR-01은 해결됐습니다. Medium 잔여 1건 때문에 신호 기능의 완료 승인은 보류합니다. Low 문서 항목 1건은 차단 항목이 아닙니다.**

이전 회귀 일곱 개(결함 여섯 + 정상 retune 하나)는 조건을 나누어 모두 통과했습니다. 다만 포커스 거절을 막는 SRLR-06에 **정지와 재시도가 합쳐질 때의 우회**가 남아 있습니다. 이것은 지난번 검토자가 제시한 패치에도 있었던 빈틈입니다. 제안 패치의 이전 시험 통과를 모든 요청 순서에 대한 보장으로 확대할 수 없었습니다.

- 고정본: **`8fdca3a8abbd3ebc6c19182c9e74f177224b0757`**, 수정 커밋 `43eff6490adacd2c733a18b7a211bdadb82d0a65`, PR #74.
- 검토 도중 #74가 병합되어 HEAD가 **`dd796fb230cbcd7b79374372c4e79a7158e2cb56`**로 바뀌었습니다. 고정본과 트리 차이는 없습니다.
- PR #73의 RTA 저장 기능이 고정본에 포함됩니다. 공통 ViewModel에서 신호 제어와 연결되는 부분 및 전체 빌드·기존 시험은 확인했습니다. **#73의 저장 포맷, 측정 조건의 일관성, 저장·겹쳐 보기 UI 전체에 대한 기능 승인은 이번 범위에 포함하지 않습니다.**
- `git archive` 격리본으로 실행했습니다. 제품 소스는 수정하지 않았고, 실제 휴대폰에도 설치하거나 재생하지 않았습니다. 계측은 API 34의 별도 무음 에뮬레이터에서 실행했습니다.
- 이후 다른 작업자가 추가한 RTA 차이 계산 시험 등은 수정하거나 검토본에 섞지 않았습니다.

## 2. 이전 지적의 상태

| 항목 | 독립 확인 | 판정 |
|---|---|---|
| SRLR-01 종료 뒤 고아 출력 | open 반환을 보류한 채 ViewModel을 종료해도 반환 뒤 최종 해제됨 | 해결 |
| SRLR-02 늦은 시작 결과 | 시작 중 stop 이후 UI가 재생 상태로 되돌아가지 않음 | 해결 |
| SRLR-03 옛 주파수 복원 | 대기 중 세기 변경 뒤 3 kHz 변경을 함께 반영. 일반 Hz-only 변경은 같은 트랙 유지 | 해결 |
| SRLR-04 종료 이벤트가 새 시작 취소 | 이전 writer 종료가 새 Pink 요청을 지우지 않음 | 해결 |
| SRLR-05 실패 시작의 자원 유지 | open false 뒤 수신기 등록 해제 | 기존 재현 해결 |
| SRLR-06 포커스 거절 무시 | 일반 시작의 거절은 sink 미개방·수신기 해제·안내 표시 확인 | 기본 경로 해결, 아래 재시도 우회 잔여 |

추가 시험에서 **닫기 전 큐에 들어간 onEnded + 닫은 뒤 도착한 onEnded**, **새 재생 뒤 늦게 도착한 옛 generation**, **채널 변경과 주파수 변경의 병합**도 통과했습니다.

## 3. SRLRO-01 — Medium: 정지가 취소된 뒤 retune이 포커스 재획득을 우회

**위치:** `app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:1437–1446`, `1455`, `1516–1523`; 포커스 상실 콜백 `872–878`.

Custom 재생 중 포커스 상실을 받으면 `stopSignal()`이 UI를 먼저 끄고 정지 명령을 큐에 넣습니다. 실행자가 아직 그 정지를 처리하지 않았을 때 같은 Custom을 재시도하면 최신 명령 규칙에 따라 앞의 정지가 생략됩니다.

이때 `activeSignalRequest`와 실제 옛 Playback은 여전히 살아 있습니다. 새 요청은 같은 세기·채널·Custom이므로 **retune 분기에서 반환**합니다. 아래의 `interruptions.acquire()`에 도달하지 않습니다. 단일 실행자로 모은 것만으로는 「중간에 정지를 요청했음」이라는 이력이 보존되지 않습니다.

### 독립 재현

1. 포커스 허용 상태에서 Custom을 시작하고 실제 writer의 쓰기를 확인합니다.
2. 명령 실행자를 latch로 보류합니다.
3. 실제 등록된 `OnAudioFocusChangeListener`에 `AUDIOFOCUS_LOSS`를 전달합니다. 실제 VM 콜백이 stop을 예약하며 UI가 null이 되는 것을 확인합니다.
4. **검토용 에뮬레이터에만** `TAKE_AUDIO_FOCUS ignore`를 설정합니다. 별도 AudioInterruptions.acquire가 false임을 확인합니다.
5. 같은 Custom을 다시 시작한 뒤 실행자를 풉니다.

관측:

```text
SRLRO_FOCUS_RETRY playing=Custom ui=Custom opened=1 held=true
expected null, but was:<Custom>
```

새 sink는 열지 않았지만 **옛 sink가 계속 출력**합니다. 기존의 「거절되면 sink를 열지 않는다」 시험만으로는 이 사례를 잡을 수 없습니다. 추가 네 계측 중 이 한 개가 실패했고 나머지 셋은 통과했습니다. 최종 회귀 파일에서는 latch를 시간이 지나면 자동 해제하지 않도록 바꾼 뒤에도 원본에서 같은 실패를 재확인했습니다.

포커스 상실 콜백은 시험이 직접 전달했고, 거절 응답은 Android AppOps로 만들었습니다. 실제 전화 수신·다른 앱의 선점·이어폰 분리를 일으킨 시험은 아닙니다. 실행자 보류는 순서를 고정하는 장치이며 현장에서 이 경합이 얼마나 자주 생기는지를 측정한 것이 아닙니다.

**사용자 영향:** 포커스를 잃어 정지 안내를 본 뒤 재시도했는데, 포커스를 얻지 못한 상태로 예전 출력이 유지되고 화면도 정상 재생으로 돌아옵니다. SRLR-06에서 제거하려던 조용한 우회가 다시 생깁니다.

**권장 수정:** 마지막 정지 의도를 남기고 그 정지보다 오래된 재생에는 retune 재사용을 허용하지 마십시오. queued stop 자체를 생략하더라도 뒤따르는 start가 정규 시작 경로(포커스 재획득 및 기존 재생 정리)를 거쳐야 합니다. 모든 Hz 변경마다 포커스를 재요청하거나 모든 순음 변경을 재시작할 필요는 없습니다.

제안의 핵심은 다음 세 곳입니다. [전체 제안 패치](2026-09-29-srlr-stop-boundary-proposed.patch)는 ViewModel 한 파일만 바꿉니다.

```kotlin
private val lastSignalStopIntent = AtomicLong()

// stopSignal: worker가 실행하기 전에도 재사용 권리를 무효화한다.
lastSignalStopIntent.set(signalIntent.incrementAndGet())

// retune 조건에 추가한다.
activeSignalIntent > lastSignalStopIntent.get()
```

**필요 회귀:** [계측 회귀 파일](2026-09-29-srlr-ownership-independent-regression.kt)의 `focusLossThenSameToneRetryMustAcquireFocusAgain`. 포커스 상실 후 동일 조건 재시도를 고정된 순서로 실행하고, sink 수 외에 **기존 출력 종료·수신기 해제·안내 표시**를 함께 단언해야 합니다. 기존 `frequencyOnlyRetuneKeepsTheTrackAndUsesLatestHz`도 유지해 정상 retune이 불필요하게 재시작되지 않게 해야 합니다.

## 4. SRLRO-02 — Low: 새 계약과 주석의 불일치

**위치:** `app/src/main/java/kr/joa/selahrta/audio/SerialCommands.kt:28–35`, `70–79`; `app/src/main/java/kr/joa/selahrta/audio/AudioInterruptions.kt:94–102`.

`postAlways`는 설명과 달리 닫는 시점까지 반드시 실행되는 함수가 아닙니다. close가 `closed=true`로 바꾸면 이미 큐에 들어간 이벤트도 `if (!closed)`에서 생략됩니다. 현재 ViewModel은 **finalizer가 같은 자원 정리를 대신하므로 이 경로에서 누수는 재현되지 않았습니다.**

독립 `closeSubstitutesFinalizerForQueuedWorkExactlyOnce`에서는 상태 명령, lifecycle 이벤트, close, 늦은 이벤트를 순서대로 넣었을 때 **finalizer만 한 번 실행**됐습니다. 따라서 「번호가 새로 올라와도 생략하지 않는다」와 「닫을 때도 무조건 실행한다」를 구별해 설명해야 합니다.

또한 AudioInterruptions.acquire KDoc에는 아직 「얻지 못해도 막지 않는다」「다른 앱 하나 때문에 준비가 멈춘다」는 이전 정책이 남아 있습니다. helper는 결과를 반환하고, 정상 caller는 false일 때 출력을 막도록 바뀌었습니다.

**영향:** 다음 유지보수자가 필수 해제 작업이 close 뒤에도 자동 실행된다고 오해하거나, 폐기한 무포커스 재생 정책을 다시 따를 수 있습니다. 현재 동작을 차단할 심각도는 아닙니다.

**권장 수정:** `postAlways`는 **열려 있는 동안 최신 상태 명령 때문에 생략되지 않음; close 때는 finalizer가 자원 정리를 인수**한다고 적으십시오. acquire 설명은 반환값과 caller의 실패 처리 의무로 바꾸십시오. 코드의 close 시 이벤트 생략을 무조건 제거하라는 제안은 아닙니다.

**회귀:** [실행자 회귀 파일](2026-09-29-srlr-serial-independent-regression.kt)에 위 동작과 실행 중인 작업 뒤 finalizer 순서를 포함한 네 계약 시험을 첨부했습니다. 문구를 바꾸기 위해 별도의 문자열 일치 시험을 만들 필요는 없습니다.

## 5. 시험 결과

### 원본 고정본

`--offline --no-build-cache --rerun-tasks --max-workers=2`로 `:dsp:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`를 실행했습니다.

| 확인 | 결과 |
|---|---|
| 전체 JVM | DSP **633** + 앱 **893** = **1,526개**, 실패 0·건너뜀 0 |
| 전체 기존 계측 | runner `OK (35 tests)`; **34 통과·포커스 거절 조건 1건 건너뜀** |
| 거절 조건 별도 실행 | 해당 1건 통과. 위 건너뜀을 별도 조건으로 검증 |
| 추가 실행자 JVM 시험 | **4개 통과** |
| 추가 실제 VM 계측 | **3개 통과·1개 실패**, SRLRO-01 재현 |
| debug APK / 계측 APK / lint | 성공. lint Error/Fatal 0, Warning 11, Hint 4 |

**「7개 전부 통과」나 「35개 통과」라는 runner 출력만으로 조건부 시험까지 실행됐다고 세지 않았습니다.** 일반 실행에서는 거절 시험이 assumption으로 건너뛰며, `ignore`에서 따로 통과한 것이 그 조건의 증거입니다.

새 계측 파일을 `app/src/androidTest/java/kr/joa/selahrta/ui/SrlOwnershipIndependentTest.kt`, 실행자 파일을 `app/src/test/java/kr/joa/selahrta/audio/SerialOwnershipIndependentTest.kt`로 복사해 실행할 수 있습니다. 재시도 시험은 검토용 에뮬레이터에서 AppOps를 직접 allow→ignore→allow로 바꾸므로 **실제 사용 중인 휴대폰에서 그대로 돌리지 마십시오.**

```powershell
# 실제 기기 대신 검토용 무음 에뮬레이터
adb -s emulator-5582 shell am instrument -w -r -e class kr.joa.selahrta.ui.SrlOwnershipIndependentTest kr.joa.selahrta.test/androidx.test.runner.AndroidJUnitRunner
```

실행 로그는 `build/independent-review/srlr-20260929/`에 있습니다. 핵심 출력은 [실행 요약](2026-09-29-srlr-ownership-evidence.txt)에 보존합니다.

### 최소 제안의 격리본 검증

제품에 적용하지 않고 격리본의 ViewModel 한 파일에 제안을 적용했습니다. `git apply --check`는 현재 원본에서도 통과합니다.

| 제안 검증 | 결과 |
|---|---|
| 앱 JVM | 기존 893 + 추가 실행자 4 = **897개 통과**, 실패·오류·건너뜀 0 |
| APK 두 종류 / lint | 빌드 성공, lint 성공 |
| 새 재시도 반례 | `playing=null, ui=null, opened=1, held=false` — 옛 출력 종료·안내 표시 확인 |
| 전체 계측 39개 실행 | **37 통과·1 실패·1 조건부 건너뜀** |
| 실패와 관련 경로를 분리한 재실행 | 지연 3 + 기존 소유권 7 + 새 계측 4 = **13 통과·1 조건부 건너뜀** |
| 기본 포커스 거절 별도 실행 | **1개 통과** |

**전체 제안 계측이 처음부터 전부 통과한 것은 아닙니다.** `SignalCommandLatencyTest.대역_슬라이더를_끌어도_주_스레드가_안_잡힌다`가 한 번 **50.1 ms / 한도 40 ms**로 실패했습니다. 빌드 완료 후 관련 세 클래스만 한 번 분리해 다시 실행했을 때는 통과했습니다. 원인을 확정하지 않았고 한도를 늘리거나 시험을 삭제하지 않았습니다. 따라서 제안의 **경합 반례 해결**과 **실제 화면 성능 보장**은 구별해야 합니다.

지연 시험은 ViewModel을 직접 만들고 `onBackground()` 후 300 ms 대기만 하며 `ViewModelStore.clear()`로 소유자를 종료하지 않습니다. 격리성을 높이려면 다른 소유권 회귀처럼 store를 통해 생성·종료하고 명령 실행자 정리를 확인하는 쪽이 낫습니다. 이것이 이번 50.1 ms의 원인이라고 단정하지는 않습니다. 실제 기기의 프레임 지연은 별도 확인 사항입니다.

DSP 제품 소스는 바꾸지 않았습니다. 제안 검증 숫자에 원본 DSP 633개를 다시 실행한 것처럼 합산하지 않았습니다.

## 6. 요청서의 세 질문에 대한 답

1. **close와 postAlways의 경합:** 현재 사용처에서 finalizer가 정리 책임을 인수하는 동작은 독립 JVM·실제 VM 시험으로 확인했습니다. close 전 큐에 선 이벤트와 close 뒤 도착한 이벤트를 모두 넣었으며 자원 해제와 실행자 종료를 확인했습니다. 「이벤트를 모두 실행했다」는 뜻은 아닙니다.
2. **retune 조건:** Custom·세기·채널 일치만으로는 부족합니다. **그 사이 정지로 종료하려던 재생이 아닌지**가 빠졌습니다. 정상 Hz-only 변경은 같은 트랙으로 유지해도 되지만, stop 이후의 재시도는 새로 시작할 자격을 확인해야 합니다.
3. **반복되는 판단 오류를 줄이는 방법:** 근거를 찾기 전에 관찰 가능한 계약을 쓰고, 그 계약을 깨는 순서를 정하십시오. 예를 들어 「락이 짧다」는 지연의 근거이고 「화면 주파수와 실제 출력이 같다」의 근거는 아닙니다. 「shutdownNow 호출」은 취소 시도이고 「모든 자원의 해제 완료」와 같지 않습니다. 이번에는 「요청 값이 같다」와 「같은 재생을 계속 써도 된다」를 구별해야 했습니다.

| 먼저 쓸 계약 | 그 계약에 필요한 증거 |
|---|---|
| 정지 뒤 옛 결과가 UI를 되돌리지 않는다 | open/결과 게시 앞을 붙들고 stop을 끼운 시험 |
| 종료 뒤 새로 열린 자원도 최종 해제된다 | 인터럽트와 무관하게 늦게 돌아오는 open, 해제 완료 관측 |
| 거절된 재시도는 옛 출력도 유지하지 않는다 | 정지 처리 전 같은 요청, 거절, 기존 writer와 수신기의 종료 |
| 평상시 주파수 변경은 연속 재생이다 | 동일 트랙 유지와 실제 출력 주파수 확인 |

구현 문구, API 호출, 상태 플래그를 결과의 대용품으로 세지 않는 것이 요점입니다. 지난번 제안에 없던 마지막 두 계약의 조합까지 이번 시험을 확장했습니다.

## 7. 남은 검증 경계

실제 기기의 느린 AudioTrack open, 이어폰 분리, PA의 클릭 소리, 손가락 조작 중 화면 프레임, 44.1 kHz 출력, 장시간 부하, 절대 SPL 보정은 이번에 확인하지 않았습니다. 호출 순서와 자원 소유권의 코드 검증을 그 결과로 확대하지 않습니다. **94 dB 기준소음계가 없어서 이번 코드 승인이 보류된 것은 아닙니다.** SRLRO-01은 무음 시험으로 수정·재검증할 수 있습니다.
