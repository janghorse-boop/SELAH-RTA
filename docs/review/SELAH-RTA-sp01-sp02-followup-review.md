# SELAH RTA SP01·SP02 후속 독립 검증

2026-09-21 · 대상 `d66681e..365611e` · 구현 커밋 `37300e9`

**판정: SP02 해결, stop/release 겹침 해결. SP01은 write 대기 경로에서는 개선됐지만 release 지연 경로에서 상한이 우회되므로 Medium으로 유지한다. 이번 범위에서 Critical/High 결함은 확인하지 못했으며 기존 개발 게이트 승인은 유지한다.**

기존 시험 **269개(DSP 194 + 앱 75) 전부 통과**했고 기존 독립 게이트 probe도 통과했다. 추가 독립 probe는 아래 상한 우회를 재현했다. production 코드 수정이나 실제 소리 출력은 하지 않았다.

## SP01 후속 — 해제 시작을 완료로 계산해 미종료 상한이 무력화된다

- **Severity: Medium** — 기존 SP01의 미해결 경로.
- **파일/위치:** `app/src/main/java/kr/joa/selahrta/audio/SignalPlayer.kt:83,90–91`의 `isReleased`/`releaseOnce`, `:137`의 `stuck.removeAll`, `:273–281`의 종료 대기 처리.
- **코드 근거:** `released.compareAndSet(false, true)`가 먼저 실행되고 그 뒤 `sink.release()`를 호출한다. 따라서 `isReleased=true`는 해제 완료가 아니라 해제 시작을 뜻한다. 그런데 start는 이 값을 보고 stuck 목록에서 제거해 새 출력을 연다.
- **재현 순서:** write 진입 → stop이 write를 풀어 줌 → writer가 release에 진입하되 barrier로 반환을 지연 → join 500ms 시간 초과 → 다음 start가 isReleased=true인 옛 항목을 삭제 → 새 출력 생성. 모두 직렬 start/stop이며 동시 UI 호출을 가정하지 않는다.
- **독립 실행 결과:** `RELEASE_PENDING starts=4 unfinished=4 pendingCount=1 cap=2`. 실제 release 반환이 끝나지 않은 출력은 네 개인데 노출된 pendingCount는 하나였다. release gate를 모두 풀자 네 개 모두 정확히 한 번 해제 완료됐다.
- **사용자 영향:** 장치/출력 해제가 지연되는 조건에서 반복 재시도가 여전히 미종료 스레드와 출력 자원을 누적시킬 수 있다. 표시되는 pending 수까지 실제보다 작아진다. 가짜 sink에서 순서를 확정해 검증했으며 Galaxy S23의 실제 release 지연·고갈을 관측했다는 뜻은 아니다.
- **권장 수정:** 중복 release 방지용 ‘시작됨’과 상한 계산용 ‘완료됨’을 구분한다. 완료 상태는 실제 release 반환 뒤 게시하고, release 중인 항목도 상한에 포함한다. 단순히 isReleased getter에서 sinkLock을 기다리게 바꾸면 start/UI가 지연된 release에 막힐 수 있으므로 피한다. release 예외 시 처리 정책도 명확히 한다.
- **필요 회귀 시험:** release 자체를 붙들고 상한보다 많은 start/stop을 요청해 추가 open이 거절되는지 검사한다. gate를 풀어 완료시킨 뒤 새 재생이 다시 가능하고 각 sink는 정확히 한 번 release돼야 한다. write 지연·release 지연이 섞인 경우에도 합계가 상한을 지켜야 한다.

현재 시험의 stuck sink는 write만 지연하고 release는 즉시 반환한다. 그래서 기존 269개가 통과하면서 이 경로가 남을 수 있다.

종료 목록에는 작은 후속 점검도 필요하다. stop이 `t.isAlive`를 확인한 직후 writer가 `stuck.remove(pb)`까지 끝내고, 그 다음 stop이 `stuck.add(pb)`를 실행하면 완료 항목이 목록에 남을 수 있다. 다음 start의 정리가 실제 재생 차단을 막아 주지만 pendingCount는 그 사이 부정확할 수 있다. 위 완료 상태/등록 순서를 정리할 때 함께 다루는 것이 좋다. 이 좁은 순서는 이번에 별도 재현한 결함 건수에는 넣지 않았다.

## 해결 확인

### SP02 — 부분 전송

`SignalSink.write`에 offset이 추가됐고 실제 AudioTrack 호출에도 전달된다. 한 블록을 전부 보내기 전에는 `sent`만큼 offset을 옮겨 나머지를 보내며, 중지되면 남은 부분을 버린다. 0 반환 시 sleep과 유한 재시도가 있어 기존의 무제한 반복도 방지한다.

다음 시험을 원본 production 소스로 독립 실행해 통과 확인했다.

- 128 → 256 → 나머지 전송 후, 두 블록의 수락된 전체 샘플열이 기준 1kHz 순음과 일치.
- 부분 전송 뒤 DEAD_OBJECT에 대한 종료/안내.
- 부분 전송 중 stop 시 수락된 128개 이후 추가 전송하지 않음.
- 0만 반환하면 종료·해제·안내.
- 기존 probe 회귀: 다음 입력 sample은 `-0.04330127`, offset은 128.

Pink도 생성한 같은 buf를 이어 쓰므로 부분 재시도 때 난수열을 새로 생성하지 않는다. 이번 전체 파형 수치 시험은 순음에 대한 것이며 실제 장치의 Pink/Sweep 출력까지 확인한 것은 아니다.

### stop/release 겹침

Playback의 sinkLock은 stop과 release만 직렬화하고 write를 감싸지 않는다. 기존 교차 순서의 시험에서 stop을 유지한 동안 release가 호출되지 않았고, stop을 풀면 정상 종료·해제됐다. 로그: `releasedDuringStop=false`, `stop 중 release=false`.

이 수정 자체는 적절하다. 위 SP01 잔여 문제는 lock 부재가 아니라 완료 상태의 의미가 잘못 연결된 데 있다.

### 로그 및 문서

전역 `isReturnDefaultValues` 옵션이 제거됐고 SignalPlayer는 warn을 주입받는다. 이번 검증도 Android 호출 시 예외를 던지는 strict mockable jar로 수행해 269개가 통과했다. 이전 기본값 반환 jar를 그대로 사용하지 않았다.

폭 문턱의 “중앙 정렬 모형” 전제와 JOIN_MS가 전체 stop 시간 상한이 아니라는 설명도 반영됐다.

## 요청한 세 가지 판단

### 1. MAX_STUCK_PLAYBACKS=2와 신규 재생 거절

상한을 두고 명시적인 사용자 재시도를 받는 방향이 적절하다. 숫자 2는 제품 정책이며 현재 시험으로 최적값을 입증한 것은 아니다. 자동 대기 후 재생 재개는 사용자가 나중에 예상치 못한 시험음을 듣게 할 수 있어, 지금 이를 추가할 필요는 없다. 먼저 SP01의 완료 상태를 고쳐 상한 자체가 유효해야 한다.

pendingCount가 열려 있지만 ViewModel은 현재 generic 시작 실패 문구(다른 앱이 스피커를 사용하는지 확인)를 표시한다. 차단 원인이 종료 대기 상한일 때는 “이전 재생 종료를 기다리는 중”처럼 원인에 맞는 안내를 주는 편이 좋다. 별도 차단 결함으로 올리지는 않는다.

### 2. 0 반환 50회 × 2ms

무제한 반복을 끊는 방어 정책으로 수용 가능하다. 다만 **Android가 보장하는 정상/실패 구분 임계값은 아니다.** 공식 write 계약은 0/short 반환 가능성을 규정하지만 이 애플리케이션의 100ms 제한을 보증하지 않는다. [AudioTrack write 계약](https://developer.android.com/reference/android/media/AudioTrack#write(float%5B%5D,int,int,int)).

정확히는 첫 49회 뒤에만 sleep하므로 요청된 sleep 합계는 **98ms**다. 여기에 각 write 실행 시간과 스케줄링 지연이 더해진다. 즉 벽시계 기준 정확한 100ms timeout도 아니다. sleep은 호출량을 줄이고 연속 0 횟수는 종료를 결정한다.

일시적인 0 뒤 진행이 재개되면 idleRounds를 0으로 되돌리는 코드는 맞다. 추가 권장 시험은 49회 0→양수→다시 0에서 정상 진행 및 횟수 초기화, 50회 연속 0에서 1회만 종료 통지, 이 대기 중 stop의 무통지 종료다. 실기기에서 정상 출력이 반복 0 때문에 잘리는지는 아직 확인하지 않았으므로 출시 전 관찰 대상이다.

### 3. AudioTrackSink까지 warn을 주입할지

현재 fake sink 기반 SignalPlayer 시험을 위해 필수는 아니다. 전역 스텁 옵션을 되돌릴 이유도 없다. 향후 AudioTrackSink 자체를 JVM에서 시험하려면 로그뿐 아니라 AudioTrack 생성·상태·write 동작의 경계가 필요하다. 로그 하나만 주입해 실제 Android adapter 검증을 마쳤다고 볼 수는 없다. 실제 adapter의 열기/중지/해제는 instrumentation 또는 실기기 시험으로 별도 확인하는 것이 맞다.

## 실행·검토 경계

- 요청서 HEAD를 검사 시작 시 `365611e`로 고정했다. 현재 작업 트리 변경을 시험에 섞지 않았다.
- git archive 사본의 production 소스와 전체 DSP/관련 앱 JVM 시험을 직접 컴파일했다. Kotlin 2.2.20, JUnit 4.13.2, 로컬 캐시 AndroidX 및 strict Android API jar를 사용했다. Gradle 전체 의존성 해석 환경과 동일하다는 주장은 하지 않는다.
- 전체 시험 `OK (269 tests)`. 추가 gate probe에서 stop 전후 Current 103.012611, MAX 103.013066, frames 102400이 유지됐고, 이전 세션의 늦은 snapshot 격리가 통과했다.
- 추가 `SpFollowupProbe`는 release 지연 상한 우회를 재현한 뒤 gate를 풀어 모든 자원을 정리했다. 이 probe의 exit 0은 **문제 재현 성공**이며 수정 완료가 아니다.
- Gradle build/lint, 실제 순음 재생·멈추기 버튼·ON_STOP·Android 드라이버 경로는 이번에 실행하지 않았다. 요청서의 실기기 검증 필요 판단은 그대로 유효하다.
- USB 정책, ViewModel 지연 전달, 44.1kHz 2·3층, 현장 정확도, 장시간 부하 등 이전 잔여 위험도 이번 수정으로 해소되지 않았다.
- 검증 종료 시 원본 working tree는 clean. production 코드 변경 없음.

## 재현 자료

- `run-sp-followup-verification.ps1` 및 `FollowupProbe.kt`: 고정 커밋 전체 JVM 시험·기존 게이트 재현.
- `sp-followup-verification-log.txt`: 269개 시험 및 gate probe 출력.
- `SpFollowupProbe.kt`, `run-sp-followup-probe.ps1`: release 지연 상한 우회 독립 재현.
- `sp-followup-probe-log.txt`: 4개 해제 미완료 및 정리 완료 기록.

일부 JVM 한국어 출력은 인코딩 차이로 깨져 있지만 수치·시험 개수·결과는 확인 가능하다.
