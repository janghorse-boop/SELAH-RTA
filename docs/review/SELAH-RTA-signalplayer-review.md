# SELAH RTA SignalPlayer 독립 검증

2026-09-21 · 대상 `e7c55e5..d66681e`

**판정: S01·S02를 겨냥한 수정은 유효하다. 기존 개발 게이트 승인은 유지하되, SignalPlayer 수명주기 검증을 전부 완료한 것으로 처리하지 않는다. 이번 범위에서 Critical/High 결함은 확인하지 못했다. 추가 수정 권고는 Medium 2건이며, stop/release 겹침은 별도 잔여 위험으로 기록한다.**

요청서와 스크린샷은 구현자의 주장으로 읽었으며 실제 코드·시험과 대조했다. production 파일은 수정하지 않았다. 아래 재현은 대상 커밋의 원본 production 소스를 임시 사본에서 컴파일해 수행했다.

## 추가 발견

### SP01 — 시간 초과된 출력이 반복 재시작마다 누적된다

- **Severity: Medium**
- **위치:** `app/src/main/java/kr/joa/selahrta/audio/SignalPlayer.kt:105` (`start`), `:198–213` (`stop`).
- **근거:** stop은 current/thread를 지운 뒤 500ms 동안 기다린다. 시간 초과된 Playback을 별도로 추적하지 않고 다음 start는 새 sink를 연다. 따라서 요청서의 “출력 하나가 남는다”는 전체 player의 상한이 아니다.
- **독립 재현:** stop으로 풀리지 않는 write를 둔 sink로 start/stop을 세 번 직렬 실행했다. `TIMEOUT_RETAINED=3`, `playing=null`이었다. 이후 모두 unblock하면 각각 해제되므로, 영구 hang이 아니더라도 지연 중인 자원이 겹쳐 쌓일 수 있다. 원래 요청서가 가정한 장치 hang 조건을 그대로 사용했다.
- **사용자 영향:** 문제 장치에서 사용자가 재시도할수록 스레드·오디오 자원 보유가 늘어난다. 재생 실패나 자원 고갈을 악화시킬 수 있다. 정상 장치에서 항상 발생한다는 뜻은 아니다.
- **권장 수정:** 종료 대기 중인 Playback을 추적하고 제한된 수를 넘으면 새 재생을 거절하거나 대기시킨다. 완료 시 제거하고 UI에 종료 대기/실패를 알린다. 일정 시간이 지났다는 이유만으로 진행 중인 write의 자원을 강제 release하는 방식은 권하지 않는다.
- **회귀 시험:** timeout 상태에서 반복 start/stop해 미종료 수가 정한 상한을 넘지 않는지 확인한다. 옛 write를 풀면 정확히 한 번 release되고 다시 시작할 수 있어야 한다. 새 세대 콜백 격리도 함께 유지한다.

### SP02 — short write를 성공 전체 전송으로 취급해 나머지 샘플을 버린다

- **Severity: Medium**
- **위치:** `app/src/main/java/kr/joa/selahrta/audio/SignalPlayer.kt:136–157`, `app/src/main/java/kr/joa/selahrta/audio/SignalSink.kt:114–115`.
- **근거:** sample을 1024 증가시키고 한 번 write한 뒤, 반환값이 음수인지만 검사한다. 양수 128이 돌아와도 남은 896프레임을 버리고 다음 1024프레임을 생성한다. SignalSink 자체 계약도 실제 쓴 개수를 반환하도록 되어 있다.
- **독립 재현:** 1kHz 순음에서 첫 write가 128만 수락하도록 했다. 다음 입력은 sample 128의 `-0.04330127`이어야 하는데 sample 1024의 `+0.04330127`이었다. `PARTIAL_NEXT_SAMPLE=0.04330127; expected_after_128=-0.04330127; skipped=896`.
- **플랫폼 근거:** Android 공식 문서는 WRITE_BLOCKING도 중지·일시정지 또는 I/O 오류 중 short transfer를 반환할 수 있다고 명시한다. 일부 데이터가 전송된 경우 DEAD_OBJECT는 다음 write에서 반환될 수 있다. [AudioTrack float write 계약](https://developer.android.com/reference/android/media/AudioTrack#write(float%5B%5D,int,int,int)).
- **사용자 영향:** 재생을 계속하는 short-write 경로에서 순음 위상이 끊기고 sweep/noise 출력 데이터가 누락된다. 시험 신호의 정확성에 영향을 준다. 사용자가 이미 중지한 경우에는 나머지를 버리는 것이 맞으므로 두 경우를 구분해야 한다. 실제 장치에서 이 경로의 발생 빈도는 이번에 측정하지 않았다.
- **권장 수정:** 현재 블록의 offset을 유지해 미전송 부분을 재시도한다. 이를 위해 sink에 offset을 전달하거나 미전송 구간을 명시적으로 제공한다. running이 false면 재시도하지 않는다. 0 반환의 반복에는 무한 busy loop를 피하는 제한/실패 정책을 둔다.
- **회귀 시험:** 128/256/나머지처럼 부분 반환하는 sink로 수락된 전체 샘플열을 원본과 비교한다. 0 반복, 부분 반환 뒤 음수, 부분 반환 중 stop도 검사한다.

## stop/release 겹침 — 재현된 잔여 위험, 플랫폼 장애로 단정하지 않음

`SignalPlayer.kt:162,177`의 writer release와 `:211`의 제어 스레드 stop은 직렬화되지 않는다. `releaseOnce`는 release 횟수를 제한하지만 다른 sink 호출 완료를 기다리지는 않는다.

독립 barrier 시험에서 write 진입 → 제어 스레드 stop 진입·대기 → write 반환 순서로 진행하자 **stop이 끝나기 전에 release가 실행됐다**(`RELEASE_DURING_STOP=true`). 현재 `AudioTrackSink.kt:117–126`에도 stop과 release를 묶는 동기화는 없다.

이는 “마지막에 손을 떼는 쪽이 놓는다”라는 설명을 stop까지 포함하면 성립하지 않는다는 근거다. 다만 가짜 sink의 겹침만으로 Android 내부 use-after-free나 크래시를 입증한 것은 아니다. 이를 High 결함으로 올리지 않는다. 실제 sink의 동시 호출 계약을 명시하거나, write 전체를 잠그지 않으면서 stop 완료와 writer 종료를 모두 확인한 뒤 release하는 종료 프로토콜을 두는 것을 권한다. write까지 같은 자물쇠로 감싸 stop을 막으면 교착 위험이 생긴다.

회귀 시험은 stop 실행 도중 writer가 종료되는 barrier 순서에서 release 시점을 검증해야 한다. 현재 “한 번만 release” 시험은 이 순서를 검사하지 않는다.

## 요청한 판단

### 1. timeout 뒤 자원을 놓지 않는 선택

진행 중인 write와 무조건 release를 겹치지 않게 한 방향은 타당하다. 그러나 **시간 제한 없는 보류와 제한 없는 재시작을 동시에 허용하는 것은 보완해야 한다**(SP01). 강제 release 상한 대신 미종료 재생 수의 상한과 재시도 정책이 먼저다. 또한 JOIN_MS는 `sink.stop()`이 돌아온 뒤부터 적용되므로 전체 stop 호출 시간이 항상 500ms 이내라는 보장도 아니다.

### 2. 전역 isReturnDefaultValues=true

로그 스텁 실패를 제품 결함으로 읽지 않은 판단은 맞다. 실제 독립 실행에서도 예전 throwing mockable jar로는 Log.w 예외 때문에 오류 종료 관련 3개 시험이 실패했다. 변경 설정과 같은 기본값 반환 mockable jar로 다시 검사했다.

그러나 전역 옵션은 로그뿐 아니라 Android 메서드의 예기치 않은 호출도 0/null로 돌려준다. 다른 시험이 실제 프레임워크에 잘못 의존해도 실패를 숨길 수 있다. **이번 시험에서 그런 거짓 통과가 확인됐다고 주장하지는 않는다.** 장기적으로 로그 경계를 좁혀 주입하거나 필요한 호출만 mock하고 기본 strict 설정을 유지하는 편이 검증력이 좋다. Android 공식 문서도 이 옵션이 실패할 시험을 통과시킬 수 있어 최후 수단으로 쓰라고 설명한다. [로컬 단위 시험 지침](https://developer.android.com/training/testing/local-tests#error-not-mocked).

이 옵션을 사용한 JVM 시험으로 AudioTrackSink의 Android 동작이나 실제 앱의 멈추기 버튼을 검증했다고 해석하면 안 된다.

### 3. 폭 문턱 6 × 칸폭 설명

중앙 bin에 맞춘 대칭 로렌츠 봉우리의 이번 모형에서는 맞다. **0.25/0.5 bin offset까지 같은 공식으로 덮지는 않는다.** 이때는 이산 peak 높이와 half-power 이상 bin 구성이 달라지므로 63.5/56.0Hz 등의 별도 결과가 필요하다. ADR이 중앙 정렬의 제한과 offset 결과를 분리한 점은 적절하다.

기본값 공유와 5→7 시험은 유효하다. 다만 `SampleRateEquivalenceTest.kt`의 표/출력에서 70.3/64.6Hz를 읽을 때도 “중앙 정렬 모형”이라는 전제를 유지해야 한다. 모든 실제 음향 신호의 보편적인 문턱은 아니다. 기존의 44.1kHz 2·3층 검증은 여전히 남는다.

## 기존 수정 확인과 검증 경계

**기존 JVM 시험 262개 전부 통과(DSP 194 + 앱 68). 기존 게이트 독립 probe도 통과했다. 추가 독립 probe는 위 세 문제/위험 순서를 재현하고 정상 종료했다.**

- Playback별 running 및 sink 소유로 이전 세대가 새 running을 보고 되살아나는 경로를 막았다.
- timeout 뒤 살아 있는 writer의 자원을 stop이 즉시 release하지 않는다. 늦은 오류의 current 비교와 ViewModel의 세대 재검사는 유지된다.
- Leq 시험은 null 검사에서 조용한 입력만의 새 세션과 이력이 있는 세션의 값을 비교하도록 강화됐다. 큐 시험 설명도 데이터 시점과 실제 회복 시간을 구분한다.
- 실행은 고정 커밋 사본의 DSP·관련 앱 원본 소스/JVM 시험을 Kotlin 2.2.20, JUnit 4.13.2와 로컬 AndroidX/Android API 캐시로 직접 컴파일한 것이다. Gradle 의존성 해석과 완전히 동일한 환경이라고 주장하지 않는다.
- Gradle build/lint 및 Galaxy S23 실기기 시험은 독립 재실행하지 않았다. 앱 내 멈추기 버튼·ON_STOP·실제 드라이버 오류·USB·장시간 부하 검증은 남는다. JVM 9건은 실제 버튼의 wiring/OS 동작을 대체하지 않는다.
- 저장소 production 변경 없음. 검토 시 working tree는 clean이었다.

## 재현 자료

`run-signalplayer-verification.ps1`, `FollowupProbe.kt`, `signalplayer-verification-log.txt`는 기존 전체 시험 및 게이트 회귀 자료다. `SignalPlayerProbe.kt`, `run-signalplayer-probe.ps1`, `signalplayer-probe-log.txt`는 추가 세 순서의 재현 자료다. **추가 probe의 종료 코드 0은 현재 문제 동작을 재현했다는 뜻**이며, 수정 완료 판정은 아니다. probe에서 생성한 대기 스레드는 gate를 풀어 종료했다.

