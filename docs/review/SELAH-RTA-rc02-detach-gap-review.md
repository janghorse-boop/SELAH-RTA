# SELAH RTA RC02 detach/pending 전환 재검증

2026-09-21 · 대상 `f026cf1..0204f7f` · 구현 커밋 `85c2640`

**판정: RC02 잔여 경합 해결. 이번 수정 범위의 개발 게이트 승인. 새 Critical/High 및 추가 조치가 필요한 결함은 확인하지 못했다.**

이는 SignalPlayer의 모든 플랫폼 경로·현장 정확도·출시 승인까지 뜻하지 않는다. 요청서의 주장을 코드 및 독립 실행과 대조했으며 production 코드는 변경하지 않았다.

## 독립 실행 결과

| 검증 | 결과 |
|---|---|
| 전체 JVM 시험 | **287개 통과** — DSP 194 + 앱 93 |
| 기존 Capture 게이트 probe | 중지 값 보존 및 옛 snapshot 격리 통과 |
| 이전 AutoEndGapProbe | **1,000회 상한 위반 미재현** |
| 별도 상태 이전 barrier probe | 재시작이 수명주기 잠금에서 대기함을 확인, 추가 open 거절 |

전체 시험의 287개에는 400회 반복하는 DetachGapTest가 하나의 시험으로 포함된다. 독립 probe의 1,000회는 별도 관찰 횟수이며 시험 개수에 더하지 않았다. 구현자가 보고한 과거 2,000회 및 Gradle build/lint 결과와도 구분한다.

```text
OK (287 tests)
DETACH_GAP reproduced=false attempts=1000
HANDOFF_BARRIER blockedOnLifecycleLock=true newOpenRefused=true opens=2 pending=2
```

기존 Capture probe도 Current 103.012611, MAX 103.013066, frames 102400을 중지 후 유지했고, 새 세션 id=3에 옛 snapshot이 덮어쓰지 못했다.

## RC02 해결 근거

`SignalPlayer.kt`의 `endWithError()`는 current 분리와 pending 등록을 같은 `synchronized(lock)` 안에서 수행한다. `stop()`도 같은 규칙을 따른다. 실제 release·stopSink·join은 그 잠금 밖에 있다.

start는 먼저 stop을 호출하므로 같은 lock을 반드시 거친 뒤 pending을 sweep하고 상한을 검사한다. 따라서 다음 두 순서에서 모두 종료 중인 Playback을 놓치지 않는다.

- 자동 오류 종료가 먼저 lock을 잡으면: current를 비우고 pending 등록을 마친 뒤 start의 stop이 진입한다.
- start의 stop이 먼저 lock을 잡으면: 그 경로에서 현재 Playback을 pending으로 옮기며, 뒤늦은 자동 오류 종료의 등록은 중복되지 않는다.

이 판단의 전제는 현재 앱처럼 사용자 제어의 start/stop을 한 스레드에서 직렬 호출하는 것이다. 임의의 여러 호출자가 동시에 start를 호출해도 안전하다는 API 보증까지 검증한 것은 아니다. 필요하면 이 호출 전제를 주석이나 MainThread 표기로 더 명확히 할 수 있다.

이전에는 `playing=null`을 관찰한 새 start가 B를 current와 pending 어디서도 보지 못했다. 현재는 playing이 먼저 null로 보여도 start가 stop의 lock에 대기하므로, 등록이 끝나기 전 상한 검사로 진행할 수 없다.

## 잠금 중첩에 대한 답변

**현재 호출 그래프에서는 제시한 판단이 맞다.** 수명주기 lock에서 PendingList lock으로 들어가는 순서는 있으나, production의 PendingList 술어는 volatile 상태만 읽고 수명주기 lock을 다시 잡지 않는다. 역방향 경로를 발견하지 못했다.

또한 다음 경계를 확인했다.

- PendingList는 내부 ArrayList를 외부로 노출하지 않는다.
- 목록 잠금 안에서 stop/release/join을 호출하지 않는다.
- Playback의 sinkLock은 stop/release를 직렬화하며, 정상 production 경로에서 이 잠금을 가진 채 수명주기 lock이나 목록 잠금을 기다리지 않는다.
- 사용자 콜백은 수명주기 lock을 놓은 뒤 호출한다.

따라서 이번 중첩 때문에 생기는 잠금 순환은 확인되지 않았다. 향후 목록 술어에 블로킹 호출이나 다른 잠금 획득을 추가하지 않는 계약은 유지해야 한다.

## 관찰 시험과 barrier 시험에 대한 답변

관찰 시험임을 명시한 것은 정확하다. 400회·2,000회 통과 자체로 경합 부재를 증명할 수는 없지만, 수정 전 재현과 수정 후 회귀 점검으로 유용하다. 이번 승인 판단은 반복 횟수뿐 아니라 위 상태 이전 구조와 다음 독립 시험에 근거한다.

“구간을 없앴으므로 barrier를 놓을 자리가 없다”는 설명은 너무 강하다. 등록을 일부러 지연시켜 **다른 제어 경로가 그 동안 기다리는지** 확인할 수 있다. 이번에 production 수정 없이 JVM reflection으로 기존 두 monitor의 참조만 읽어 다음 시험을 했다.

1. A의 해제 실패를 만들어 pending=1로 둔다.
2. 시험 스레드가 PendingList의 monitor를 잠시 보유한다.
3. B를 자동 오류 종료시켜 playing이 null이 되게 한다. B는 수명주기 lock을 가진 채 pending 등록에서 기다린다.
4. 별도 제어 스레드가 C의 start를 시도한다.
5. ThreadMXBean으로 이 스레드가 **수명주기 lock**에서 BLOCKED임을 확인한다.
6. 목록 monitor를 놓은 뒤 B 등록이 완료되고 C의 start가 NONE을 반환하며 opens=2, pending=2임을 확인한다.

이 시험은 production 필드 값을 변경하거나 코드에 sleep/hook을 삽입하지 않았다. private 필드 이름에 의존하는 JVM 검증용 probe이므로 그대로 제품 테스트의 장기 설계로 삼아야 한다는 뜻은 아니다. 현재 작은 수정에 더 큰 추상화를 도입하는 것은 필수가 아니다. 장기적으로 결정적 회귀를 저장소에 남기고 싶다면 상태 이전 경계의 테스트 접근점을 작게 두는 방법을 고려할 수 있다.

## 범위와 남은 위험

- HEAD를 `0204f7f`로 고정한 git archive 사본의 원본 production 소스와 시험을 컴파일했다. 검증 시 원본 working tree는 clean이었다.
- Kotlin 2.2.20, JUnit 4.13.2, 로컬 AndroidX 및 strict mockable Android jar를 사용했다. Gradle의 전체 의존성 해석 환경과 동일하다는 주장은 하지 않는다.
- Gradle build/lint 및 실기기 소리 출력은 이번에 독립 실행하지 않았다. 기존 Galaxy S23 트랙 수·로그 보고를 청감상 연속 출력 또는 파형 정확도의 독립 증거로 확대하지 않는다.
- `start()`의 open 실패 뒤 직접 release하는 초기화 경로는 Playback 종료 추적 밖에 있다. 실제 adapter 초기화 실패·해제 실패 조합은 남은 검증 항목이며 “모든 자원 정리가 통합됐다”는 판단은 하지 않는다.
- 앱 안 멈추기 버튼·ON_STOP·Compose wiring, Android adapter, USB 정책·재라우팅 상한, ViewModel 지연 전달, 44.1kHz 2·3층, 기준 소음계·현장 정확도, 장시간 부하·열·메모리 및 main 큐 실기기 지연은 이번 승인으로 해소되지 않는다.
- 실제 장치가 stop/release에서 멈추는 경우 전체 제어 호출이 일정 시간 이내 끝난다는 보장도 이번 검증 범위 밖이다.

## 재실행 자료

- `run-detach-gap-verification.ps1`, `FollowupProbe.kt`, `detach-gap-verification-log.txt`: 고정 커밋 전체 JVM 시험·기존 Capture 게이트 재현.
- `AutoEndGapProbe.kt`, `run-detach-gap-probe.ps1`, `detach-gap-observation-log.txt`: 이전 경합 재현의 1,000회 관찰 실행. 로그의 reproduced 값과 attempts를 확인한다.
- `DetachHandoffProbe.kt`, `run-detach-handoff-probe.ps1`, `detach-handoff-probe-log.txt`: 상태 이전 중 실제 잠금 대기와 추가 open 거절 확인.

일부 한국어 JVM 출력은 인코딩 차이로 깨져 있으나 결과·개수·수치는 확인 가능하다. production 변경·커밋·외부 전송은 없었다.
