# SELAH RTA RC01·RC02·RC03 재검증

2026-09-21 · 대상 `bb30a09..430aca8` · 구현 커밋 `c92f258`

**판정: RC01(High) 해결, RC03(Low) 해결. RC02는 자동 오류 종료 경로가 빠져 Medium으로 유지한다. 이번 범위에서 남은 Critical/High는 확인하지 못했으므로 RC01로 인한 개발 게이트 승인 보류 사유는 해소됐다. 다만 RC02 전체 해결·SignalPlayer 검증 완료로 처리할 수는 없다.**

원본 커밋 사본에서 **280개 JVM 시험(DSP 194 + 앱 86)과 기존 독립 Capture 게이트 probe가 모두 통과**했다. 별도 독립 재현으로 목록 잠금, 수동 중지 해제 실패의 상한 유지, 자동 오류 종료의 상한 누락을 확인했다. production 수정과 실제 소리 출력은 하지 않았다.

## 남은 문제 — RC02 자동 오류 종료 후 해제 실패가 등록되지 않는다

**Severity: Medium**

**위치:** `app/src/main/java/kr/joa/selahrta/audio/SignalPlayer.kt:284–297` (`endWithError`), 대조 경로 `:335–343` (`stop`).

**논리적 근거:**

1. `endWithError()`는 현재 재생을 `current=null`로 분리한다.
2. `pb.releaseOnce()`로 해제를 시도한다.
3. 성공했을 때만 `stuck.remove(pb)`를 호출한다. 실패 시 목록에 넣는 처리가 없다.
4. 사용자가 먼저 stop하지 않은 재생은 원래 stuck에 없으므로 실패해도 계속 목록 밖에 남는다.
5. 다음 start의 stop은 current가 null이라 즉시 돌아오고, 빈 목록을 보고 새 출력을 연다.

수동 stop의 `stuck.addIfPending(pb)`만으로는 이 경로를 보완하지 못한다. 같은 `endWithError`를 쓰는 DEAD_OBJECT·일반 음수·연속 0 종료가 모두 점검 대상이다.

**독립 재현:** fake sink의 write가 DEAD_OBJECT를 반환한 뒤 release가 false를 반환하거나 예외를 던지게 했다. 각 onEnded 콜백 완료를 latch로 기다린 뒤 다시 시작했으며 동시 start/stop을 가정하지 않았다.

| 경로 | 해제 결과 | 시작 수 | failedReleaseCount | pendingCount |
|---|---|---:|---:|---:|
| 수동 stop | false | 2 | 2 | 2 |
| 수동 stop | 예외 | 2 | 2 | 2 |
| 자동 오류 종료 | false | **4** | **0** | **0** |
| 자동 오류 종료 | 예외 | **4** | **0** | **0** |

상한은 2이며 각각의 release 호출은 한 번이었다. fake sink로 실패 상태를 주입한 결과다. 실제 Android 장치의 누출량이나 발생률을 측정한 것은 아니다.

**사용자 영향:** 장치 오류와 해제 실패가 함께 발생하면 사용자의 재시도마다 자원 상태를 모르는 출력을 계속 누적시킬 수 있다. 실패 개수가 0이라 이번에 추가한 정리 실패 안내도 선택되지 않는다.

**권장 수정:** 수동 중지와 자동 오류 종료가 같은 종료 추적 규칙을 사용하게 한다. 현재 재생을 분리할 때 미종료 항목을 추적하고, 성공한 해제만 목록에서 제거한다. 실패/상태 불명은 상한과 실패 수에 남긴다. **실패가 반환된 뒤에만 등록하지 말고 release 진행 중도 추적해야 한다.** 현재 자동 오류 경로는 current를 먼저 지우므로 release 자체가 지연될 때도 새 재생이 그 미완료 항목을 놓칠 수 있기 때문이다. 이 지연 경로는 코드상 근거이며 이번 추가 probe의 실제 실행 표는 위 실패 반환 두 경우다.

전체 종료 처리를 광범위하게 재작성할 필요는 없다. 종료 추적의 등록·완료 규칙을 하나로 모으고 모든 종료 진입점에서 사용하도록 국소 수정하는 것이 적절하다.

**필요한 회귀 시험:**

- 사용자 stop 없이 DEAD_OBJECT → release false/예외를 반복하여 두 번째 실패 뒤 추가 open이 거절되는지 확인.
- 일반 write 오류와 연속 0 종료에도 같은 실패 기록 규칙 적용.
- 자동 오류 뒤 release를 barrier로 지연시키고, 진행 중인 항목도 상한에 포함되는지 확인.
- 성공한 자동 종료는 슬롯이 회수되고 다음 재생이 가능하며, 해제는 한 번만 시도되는지 확인.
- 늦은 옛 오류가 새 재생 UI를 지우지 않는 세대 격리 유지.

## RC01 해결 확인

`PendingList`의 ArrayList는 외부로 노출되지 않고 size/sweep/addIfPending/remove/count가 같은 잠금을 사용한다. SignalPlayer가 전달하는 술어는 volatile 상태 읽기다. 목록 잠금 안에 실제 stop/release/join 호출은 없다. 이전 Kotlin predicate removeAll의 복합 인덱스 갱신 경로가 제거됐다.

독립 `RcReviewProbe`에서 sweep의 술어에 진입한 동안 다른 스레드가 remove를 시도하게 했고, 그 스레드가 실제로 `BLOCKED` 상태가 된 것을 확인한 뒤 sweep을 끝냈다. 정리 뒤 미완료 항목은 유지됐다.

```text
PENDING_LOCK blocked=true remaining=1 pendingItemPreserved=true
```

기존 production 코드에서 발생했던 전체 시험 실패도 이번 280개 실행에서는 발생하지 않았다. 이 판단은 반복 통과 횟수뿐 아니라 모든 접근의 잠금 구조와 독립 교차 순서 확인에 근거한다.

보완 제안: 저장소의 `PendingListTest`는 술어에서 latch를 열지만 worker가 remove에 도달하기 전에 sweep이 끝날 수도 있다. 사용되지 않는 letGo latch도 있다. 이번 독립 probe처럼 실제 잠금 대기/정리 순서를 확인하도록 강화하면 회귀 검출력이 더 좋다. 현 production 잠금의 결함으로 보지는 않으며 별도 Low 이슈를 추가하지 않는다.

## RC03 해결 확인

이제 A의 stop은 writeGate를 열지 않아 write 안에 남는다. B는 write를 풀고 release에서 기다린다. 시험은 각 진입 상태, 합계 pending=2, 추가 open 거절, B 해제 후 1, A 해제 후 0, 다시 시작 가능 여부를 확인한다. 이전의 “둘 다 release 지연” 문제는 해소됐다. 이 시험은 이번 독립 전체 실행에서 통과했다.

## 요청한 세 가지 판단

### 1. PendingList 술어를 문서로 제한해도 되는가

현재 internal 범위와 실제 호출처에서는 충분하다. 술어가 volatile 읽기뿐이고, 외부가 리스트를 직접 만질 수 없으며 잠금 안의 작업도 작다. 지금 새로운 추상화나 별도 스레드를 추가할 필요는 없다. 향후 재사용 시 술어 안에서 블로킹 작업·다른 잠금 획득·목록 재진입을 하지 않는다는 계약을 코드 리뷰로 유지해야 한다. 필요하면 API를 구체적인 pending 상태 항목으로 좁히는 정도가 가능하다.

### 2. 실패가 영구히 슬롯을 막는 정책

해제 성공 여부를 모르는 자원을 상한에 남기는 방향은 맞다. 복구했다고 확인하지 않고 카운터만 초기화하는 버튼이나 자동 재시작을 넣으면 같은 문제가 되돌아온다. 같은 sink에 무조건 release를 재시도할 필요도 없다.

앱 안내에서 “다시 켜기”는 단순히 화면을 나갔다 돌아오는 것과 프로세스 재시작이 다르다는 점을 고려해야 한다. 실패 상태는 기다리기만 해서는 풀리지 않는다. 사용자 복구 흐름은 실제 adapter/프로세스 수명주기 시험을 거쳐 정하고, 그 전까지는 실패 안내와 신규 출력 제한을 유지하는 것이 타당하다. 단, 먼저 위 자동 종료 누락이 수정돼야 이 정책이 일관된다.

### 3. false와 예외를 모두 허용하는 release 계약

둘 다 “해제를 확인하지 못했다”로 처리한다는 의미가 명시돼 있어 호출자 입장에서는 모호하지 않다. 정상적인 예상 실패는 false, 예기치 않은 예외는 방어적으로 실패로 정규화하는 식으로 설명하면 더 명확하다. 핵심은 true만 성공으로 처리하고 실패 원인은 진단 가능하게 남기는 것이다.

실제 AudioTrackSink가 native release 실패를 Boolean으로 전달하도록 바뀐 점은 적절하다. 다만 adapter의 실제 Android 호출은 JVM fake 시험으로 검증되지 않는다. 같은 sink에 두 번째 release를 호출하면 track=null이라 true를 돌려줄 수 있으므로, Player의 “한 번만 시도하며 최초 결과를 보존”하는 규칙을 유지해야 한다.

## 실행 경계

- 요청서 HEAD를 `430aca8`로 고정하고 git archive 사본에서 컴파일했다. 원본 working tree는 clean이었다.
- Kotlin 2.2.20, JUnit 4.13.2, 로컬 AndroidX/strict Android API jar를 사용한 독립 JVM 실행이다. Gradle 전체 의존성 해석 환경과 동일하다고 주장하지 않는다.
- `OK (280 tests)`. 기존 Capture probe는 stop 전후 Current 103.012611, MAX 103.013066, frames 102400 보존과 이전 세션의 늦은 snapshot 격리를 통과했다.
- 독립 RcReviewProbe는 잠금 및 수동 실패 처리의 수정 확인과 자동 오류 실패 누락 재현을 함께 수행했다. exit 0은 전체 문제 해결을 뜻하지 않는다.
- Gradle build/lint, 실제 소리 재생·멈추기 버튼·ON_STOP, AudioTrack adapter, USB, 장시간 부하 등은 이번에 재실행하지 않았다. 44.1kHz 2·3층과 현장 정확도 검증도 그대로 남는다.
- production 수정이나 커밋·외부 전송 없음.

## 재실행 자료

- `run-rc-verification.ps1`, `FollowupProbe.kt`, `rc-verification-log.txt`: 전체 시험 및 기존 게이트 재현.
- `RcReviewProbe.kt`, `run-rc-probe.ps1`, `rc-probe-log.txt`: 목록 잠금과 수동/자동 종료 × false/예외 비교.

일부 한국어 JVM 로그는 출력 인코딩 차이로 깨져 있으나 수치·시험 개수·결과는 확인 가능하다.
