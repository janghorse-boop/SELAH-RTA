# SELAH RTA release 완료 처리 독립 재검증

2026-09-21 · 대상 `365611e..bb30a09` · 구현 `93313fb`

**판정: 이번 수정 승인 보류. High 1건, Medium 1건, Low 1건.**

기존 release 지연 상한 우회는 막혔다. 그러나 독립 전체 시험에서 재생 재시작 중 목록 정리 경합으로 실제 예외가 발생했다. 구현자의 “274개 전부 통과”와 달리 이번 독립 실행은 **274개 중 1개 실패**였다. Critical은 확인하지 못했다. production 수정 및 실제 소리 출력은 하지 않았다.

## RC01 — CopyOnWriteArrayList의 Kotlin predicate removeAll이 writer 삭제와 경합한다

**Severity: High**

**위치:** `app/src/main/java/kr/joa/selahrta/audio/SignalPlayer.kt:175`의 `stuck.removeAll { it.isReleaseComplete }`; 동시에 writer가 `:259,275`에서 `stuck.remove(pb)` 실행. UI 호출 위치는 `app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:409`.

**독립 실행 근거:** 전체 시험의 `StuckPlaybackCapTest.놓기가 늦어져도 상한을 넘겨 열지 않는다`가 release gate를 푼 뒤 재시작하는 부분(`:98`)에서 실패했다.

```text
Tests run: 274, Failures: 1
java.lang.ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 0
  java.util.concurrent.CopyOnWriteArrayList.remove
  kotlin.collections.CollectionsKt__MutableCollectionsKt.filterInPlace (MutableCollections.kt:296)
  kotlin.collections.CollectionsKt__MutableCollectionsKt.removeAll (MutableCollections.kt:270)
  kr.joa.selahrta.audio.SignalPlayer.start (SignalPlayer.kt:175)
  ...StuckPlaybackCapTest... (StuckPlaybackCapTest.kt:98)
```

**원인:** CopyOnWriteArrayList의 개별 연산이 안전하다고 해서 Kotlin의 `removeAll(predicate)` 전체가 원자적이 되는 것은 아니다. 이 Kotlin 경로는 RandomAccess 리스트의 인덱스를 읽고 변경하는 복합 연산을 한다. 중간에 writer가 항목을 삭제하면 인덱스가 무효가 된다. `CopyOnWriteRemoveAllProbe.kt`에서 predicate 실행 중 다른 스레드가 두 항목을 삭제하도록 barrier로 고정하자 같은 `filterInPlace/removeAll` 경로의 IndexOutOfBounds 예외가 재현됐다. 최소 재현은 컬렉션 연산을 검증하며, production 경로 증거는 위 실제 전체 시험 실패다.

대상 시험 클래스만 추가로 5회 실행했을 때는 모두 통과했다. 이것은 경합이 간헐적이라는 근거이며 최초 실패를 무효로 하지 않는다. 전체 시험을 반복해 우연히 통과한 결과로 덮지 않았다.

**사용자 영향:** 이전 재생이 종료되는 순간 사용자가 새 신호를 시작하면 주 스레드의 `player.start()`가 예외를 던질 수 있다. 현재 ViewModel 경로에서 이를 처리하지 않아 앱 크래시와 진행 중 측정 중단으로 이어질 수 있다. JVM에서 실제 production 코드의 예외를 확인했으며 Android 화면 크래시를 직접 실행한 것은 아니다.

**권장 수정:** snapshot을 순회하며 완료된 Playback 객체를 개별 remove하거나, 모든 목록 갱신을 동일한 짧은 잠금으로 직렬화한다. 지원 환경을 확인한 원자적 bulk API도 선택지다. CopyOnWriteArrayList 위에서 Kotlin 인덱스 기반 predicate removeAll을 그대로 쓰지 않는다. 목록 잠금 안에서 release/stop/join을 기다리지는 않는다.

**회귀 시험:** 완료 항목 정리 중 writer가 같은 항목을 제거하는 순서를 barrier로 고정한다. 정리와 재시작이 예외 없이 끝나고 미완료 항목은 유지되며 상한도 지키는지 검사한다. 단순 반복 실행의 통과만으로 대체하지 않는다.

## RC02 — release 예외를 성공과 동일하게 계산해 상한에서 제외한다

**Severity: Medium** — SP01의 실패 처리 경로.

**위치:** `SignalPlayer.kt:119–128` (`finally { releaseDone = true }`), `:258–259,274–275,314–315`의 무조건 목록 제거. 실제 adapter는 `app/src/main/java/kr/joa/selahrta/audio/SignalSink.kt:125–127`에서 release 예외를 삼키고 track 참조를 지운다.

**독립 재현:** 자원을 해제하기 전에 release가 예외를 던지는 fake sink로 start/stop을 네 번 직렬 실행했다.

```text
RELEASE_FAILURE starts=4 unreleased=4 pending=0 warnings=4
```

각 release는 한 번씩 시도됐지만 성공하지 않았다. 이후에도 신규 재생이 허용됐고 pending은 0이었다. 실제 장치에서 자원 누출을 계측한 결과가 아니라, 해제 실패를 주입했을 때의 상한 정책 검증이다.

**사용자 영향:** 해제가 실패해 자원 상태를 확신할 수 없는 상황에서도 계속 새 출력을 열 수 있어, 상한이 막으려던 자원 누적을 다시 허용한다. “다시 소리를 낼 수 있게 한다”는 이유는 불확실한 자원이 없어졌다는 근거가 아니다.

**권장 수정:** 해제 시도 종료와 해제 성공을 구분한다. 실패/상태 불명은 별도 격리 수로 상한에 포함하고 사용자가 이해할 수 있는 실패 안내를 남긴다. 같은 sink에 무조건 release를 반복할 필요는 없다. 현재 실제 adapter가 실패를 숨기므로 결과/예외가 위 계층에 전달되는 경계도 함께 정리해야 한다. `finally` 한 줄만 바꾸고 무조건 `stuck.remove(pb)`를 남기면 해결되지 않는다.

**회귀 시험:** release 이전 실패, release 지연 뒤 실패, 정상 반환을 나눠 시험한다. 실패가 연속되면 추가 open이 제한되고 실패 수·안내가 유지돼야 한다. 실제 AudioTrackSink 실패 전달은 별도 adapter 시험으로 확인한다.

## RC03 — 혼합 지연 시험이 실제로는 release 지연만 검사한다

**Severity: Low**

**위치:** `app/src/test/java/kr/joa/selahrta/audio/StuckPlaybackCapTest.kt:45–46,111–137`.

**근거:** fake sink의 stop은 항상 writeGate를 연다. “쓰기 지연과 놓기 지연이 섞여도” 시험의 두 분기는 모두 `p.stop()`을 호출한다. 한 분기에서 releaseEntered를 추가로 기다릴 뿐, write를 끝내지 못한 Playback과 release를 끝내지 못한 Playback이 동시에 남는 상태를 의도적으로 만들지 않는다. 주석도 사실상 release 지연으로 바뀐다고 인정한다.

**사용자 영향:** production 장애를 새로 입증한 것은 아니다. 그러나 요청서가 주장한 혼합 경로의 회귀 검증 근거가 없어, 종료 목록의 서로 다른 상태 처리를 놓칠 수 있다.

**권장 수정/회귀 시험:** stop이 write를 풀지 않는 sink A와, stop이 write를 풀되 release에서 멈추는 sink B를 함께 둔다. A는 아직 write 안이고 B는 release 안임을 각각 latch로 확인한 뒤 합계 상한·추가 open 거절을 검사한다. 각 gate를 따로 풀어 하나씩 정리되고 다시 시작할 수 있는지도 확인한다.

## 기존 수정에서 확인된 것

이전 독립 `SpFollowupProbe`의 입력 순서를 유지하고 기대 단언을 바꿔 재실행했다.

```text
RELEASE_PENDING starts=2 unfinished=2 pendingCount=2 cap=2
CLEANUP all 2 releases completed exactly once
```

따라서 **release가 계속 진행 중인 정상 지연 경로**에서는 시작과 완료를 나눈 수정이 효과가 있다. volatile getter가 sinkLock을 기다리지 않는 점도 타당하다. SP02의 offset 처리와 stop/release 직렬화에 대한 기존 해결 판단을 뒤집을 근거는 찾지 못했다.

0 반환에 대한 횟수 초기화·단일 통지·사람이 중지하면 무통지라는 세 시험은 이번 전체 실행에서 실패하지 않았다. 98ms가 요청한 sleep 합계이고 벽시계 상한이 아니라는 설명도 맞다. 구현자가 보고한 mutation은 이번에 독립 재실행하지 않았다.

## 요청한 두 가지 판단

1. **release 예외 정책:** 성공처럼 지우지 않는 편이 맞다. 위 RC02처럼 실패/상태 불명을 따로 남기고 제한·안내해야 한다. 재생 가능성을 유지하려고 실패 자원을 0개로 세는 정책은 상한의 목적과 맞지 않는다.
2. **add 뒤 완료 재확인:** 지적했던 ‘writer가 먼저 remove한 뒤 stop이 add하는 순서’ 자체에는 적절하다. 완료/remove가 add보다 앞이면 뒤의 완료 확인이 제거하고, add 이후 완료라면 writer가 제거한다. 다만 이 국소 순서가 맞아도 RC01의 별도 bulk 정리 경합까지 안전해지지는 않는다. 목록 처리 전체를 함께 확인해야 한다.

## 검증 범위와 재실행 자료

- HEAD를 `bb30a09`로 고정하고 별도 git archive 사본의 원본 production 소스와 시험을 직접 컴파일했다. Kotlin 2.2.20, JUnit 4.13.2, 로컬 AndroidX 및 strict mockable Android jar를 사용했다.
- 전체 실행 **274개 중 1개 실패**. runner는 실패 시 중단하므로 이번 전체 실행 뒤의 기존 Capture gate probe는 실행되지 않았다. 이를 통과했다고 주장하지 않는다.
- 문제 시험 클래스의 추가 5회 실행은 5회 모두 통과. 경합의 재현성과 수학적 원인은 별도 barrier 기반 컬렉션 probe로 확인했다.
- release 지연 및 예외 probe는 종료 코드 0으로 완료했다. 이는 지연 수정 확인과 실패 정책 문제 재현에 성공했다는 뜻이다. 실제 Android 자원은 생성하지 않았다.
- Gradle build/lint, 실제 AudioTrack 재생·멈추기 버튼·ON_STOP 및 기기 부하 시험은 이번에 실행하지 않았다. 요청서의 실기기 검증 제안은 별도 사용자 행동을 요구하는 문서 내용이며, 이번 확인 요청을 소리 재생 지시로 해석하지 않았다.
- 검토한 원본 working tree는 clean. production 수정 없음.

자료:

- `run-release-completion-verification.ps1`, `release-completion-verification-log.txt`: 고정 커밋 전체 시험과 최초 실패 로그.
- `ReleaseCompletionProbe.kt`, `run-release-completion-probe.ps1`, `release-completion-probe-log.txt`: 지연 상한 및 release 실패 정책 재현.
- `CopyOnWriteRemoveAllProbe.kt`, `run-cow-removeall-probe.ps1`, `cow-removeall-probe-log.txt`: 순서를 고정한 컬렉션 경합 재현.
- `release-completion-targeted-log.txt`: 대상 클래스 추가 5회 결과.

일부 한국어 JVM 출력은 인코딩 차이로 깨져 있지만 예외 클래스·코드 위치·수치·시험 결과는 확인 가능하다.
