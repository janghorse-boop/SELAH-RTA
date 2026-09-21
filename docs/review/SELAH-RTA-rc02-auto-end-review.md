# SELAH RTA RC02 자동 종료 후속 검증

2026-09-21 · 대상 `430aca8..f026cf1` · 구현 `0373239`

**판정: 자동 오류 종료의 해제 실패 누락은 해결됐다. 그러나 현재 재생 분리와 pending 등록 사이의 경합으로 상한 2를 넘겨 3개가 남는 경로를 독립 재현했으므로 RC02는 Medium으로 유지한다. 이번 범위에서 Critical/High 결함은 확인하지 못했다. 기존 개발 게이트 승인은 유지하되 RC02 전체 완료로 처리하지 않는다.**

원본 커밋 사본의 **286개 JVM 시험(DSP 194 + 앱 92), 기존 Capture 게이트 probe, 이전 RC02 독립 재현의 수정 후 기대 단언이 모두 통과**했다. 추가 재시작 probe에서 아래 경합을 발견했다. production 코드는 변경하지 않았다.

## RC02 잔여 경합 — current 분리와 pending 등록이 하나의 상태 전환이 아니다

**Severity: Medium**

**위치:** `app/src/main/java/kr/joa/selahrta/audio/SignalPlayer.kt:303–312` (`current=null` 이후 `settle` 호출), `:294–297` (`settle`), `:192–202`의 start/상한 검사.

**문제 순서:**

1. A가 이미 해제 실패해 pending=1이다.
2. 현재 재생 B가 쓰기 오류로 끝나며 `endWithError()` 안에서 current와 playing을 null로 바꾼다.
3. B가 아직 `settle()`의 `stuck.addIfPending()`에 도달하기 전에 제어 스레드가 C의 start를 호출한다.
4. start의 stop은 current가 null이라 즉시 끝나고, sweep은 A 하나만 보므로 C를 새로 연다.
5. B가 pending에 등록되고 해제 실패한다. C까지 중지 후 해제 실패하면 pending=3이다.

목록 자체의 잠금은 유지되므로 RC01의 인덱스 예외가 재발한 것은 아니다. **목록과 current 사이의 상태 이전이 따로 일어나는 문제**다. release 전에 등록하는 것만으로는, current에서 빠진 직후의 구간까지 보호하지 못한다.

**독립 재현:** 실제 production 소스를 그대로 사용했다. 모든 start/stop은 하나의 제어 스레드에서 호출했다. A의 종료 콜백을 기다려 pending=1을 확인하고, B의 write 오류를 gate로 허용한 뒤 playing=null을 관측하면 바로 C를 시작했다. 최대 1,000회로 제한한 시험에서 **22번째 시도**에 다음 결과를 얻었다.

```text
DETACH_GAP iteration=22 opens=3 pending=3 cap=2
DETACH_GAP reproduced=true attempts=22
```

production 코드에 지연이나 test hook을 넣지 않았다. 빠른 재시작을 검사하기 위해 volatile playing을 읽는 busy-wait는 probe에서만 사용했다. 이 한 실패 실행은 상한 위반의 증거이며, 22회가 실기기 발생 확률이나 주기를 뜻하지는 않는다. 실제 Android 자원은 생성하지 않았고 가짜 sink는 release 실패를 반환했다.

**사용자 영향:** 오류로 종료되는 순간 재시작하면 이미 미정리 자원이 상한에 도달할 상황인데도 새 출력을 열 수 있다. 이번에 확인한 것은 상한 2 대신 3개까지 허용되는 경로다. 이 결과만으로 무제한 누적이나 앱 크래시를 주장하지 않는다.

**권장 수정:** B를 pending으로 이전하는 작업을 current에서 분리하는 작업과 같은 수명주기 임계구역에서 수행한다. start의 상한 검사와 일관되게 동작하도록 순서를 정해, 어떤 재시작도 B를 current와 pending 양쪽에서 모두 놓치지 않게 한다. 잠금 안에는 짧은 등록·상태 갱신만 두고 실제 release/stop/join은 밖에 둔다. `settle()`을 성공 시 정리하는 공통 경로로 유지할 수 있으며 광범위한 production 재작성은 필요하지 않다.

**필요 회귀 시험:** pending 실패 1개 + 자동 오류 종료 중인 B를 준비하고, current 분리/pending 이전과 C 시작이 겹치는 순서를 제어한다. 재시작이 B를 보거나 B의 이전 완료를 기다려 상한을 지켜야 한다. 필요한 경우 작은 상태 이전 경계를 추출해 barrier로 순서를 고정한다. 실패 2개가 된 뒤에는 세 번째 open이 호출되지 않아야 한다. B의 성공 해제 시 슬롯 회수·늦은 콜백 세대 격리·수동 stop도 함께 유지한다.

현재 새 AutoEndTrackingTest는 대부분 onEnded를 기다린 다음 재시작한다. release 지연 시험도 releaseEntered를 기다린 뒤 pending을 본다. 두 방식 모두 등록 전의 위 짧은 구간을 지나고 검사하므로 이 경합을 잡지 못한다.

## 이번에 해결된 것

이전 RcReviewProbe의 재현 순서는 유지하고 자동 종료의 기대 단언만 수정했다.

| 경로 | 해제 결과 | 시작 수 | failedReleaseCount | pendingCount |
|---|---|---:|---:|---:|
| 수동 stop | false | 2 | 2 | 2 |
| 수동 stop | 예외 | 2 | 2 | 2 |
| 자동 오류 종료 | false | 2 | 2 | 2 |
| 자동 오류 종료 | 예외 | 2 | 2 | 2 |

즉 **자동 종료를 끝까지 기다린 뒤 재시도하는 기존 RC02 재현은 해결**됐다. 이전의 자동 경로 시작 4회·pending 0은 더 이상 발생하지 않았다. false와 예외 모두 최초 해제 결과가 보존됐고 sink당 해제 시도는 한 번이었다.

새 시험 6건도 독립 전체 실행에서 통과했다. DEAD_OBJECT·일반 오류·연속 0·release 진행 중의 pending 등록·정상 release 뒤 슬롯 반환을 검사한다. 구현자가 보고한 mutation 결과는 이번에 별도 재실행하지 않았다.

PendingList의 독립 교차 순서 확인도 `blocked=true`, 미완료 항목 보존으로 통과했다. 저장소 PendingListTest가 실제 BLOCKED 상태를 확인하도록 강화된 점도 적절하다.

## 요청한 판단

### 1. settle로 종료 규칙이 하나가 됐는가

정상 loop 종료, 자동 오류 종료, stop의 완료된 worker 정리에서 **등록·해제·성공 시 제거라는 처리 자체는 공통화됐다.** 이는 이전의 자동 실패 누락을 고친다. 다만 위처럼 자동 종료가 current를 지운 뒤 공통 처리로 진입하므로 **현재 상태에서 종료 추적으로 넘어가는 순간**까지 하나의 규칙으로 묶인 것은 아니다.

별도 범위로, `start()`의 `open` 실패 뒤 `s.release()`는 Playback 생성 전 직접 정리 경로라 settle 밖에 있다. 이번에 이 초기화 경로의 새로운 결함을 독립 재현한 것은 아니며 추가 이슈로 세지 않는다. “모든 자원 정리가 settle로 통합됐다”라고 확대 해석하지 말고, 실제 adapter 초기화 실패 시험에서 release 결과·예외 처리를 확인할 필요가 있다.

### 2. 정상 종료 때마다 목록에 넣었다 빼는 비용

현재 짧은 목록에 대한 종료 시점 작업이므로 이 비용을 줄이려고 등록을 생략할 근거는 없다. 매 샘플이나 매 write마다 실행되는 경로가 아니다. 이번에는 성능 수치를 벤치마크하지 않았으므로 ‘측정상 무시 가능’이라고 단정하지는 않지만, 추가 최적화보다 상태 이전의 정확성이 우선이다. 실제 release/stop/join을 목록 잠금 밖에 두는 원칙은 계속 유지해야 한다.

## 실기기 보고의 해석

요청서의 Galaxy S23 10초 재생·트랙 1개·오류 0·종료 뒤 0은 **구현자 보고**이며 이번 독립 검증에서 재실행하지 않았다. 트랙 수 유지와 오류 로그 부재는 유용한 증거지만, 그것만으로 청감상 연속 파형이나 실제 출력 파형 무결성까지 입증되는 것은 아니다.

앱 내 멈추기 버튼의 wiring·ON_STOP·Compose 경로가 여전히 미확인이라는 구분은 맞다. 요청서의 소리 재생 제안을 사용자의 실행 지시로 간주하지 않았으며 기기 소리를 내지 않았다.

## 검증 경계와 자료

- HEAD를 `f026cf1`로 고정해 git archive 사본의 원본 소스를 사용했다. 원본 working tree는 clean이었다.
- Kotlin 2.2.20 / JUnit 4.13.2 / 로컬 AndroidX 및 strict mockable Android jar로 직접 컴파일했다. Gradle 의존성 해석 환경과 완전히 동일하다는 주장은 하지 않는다.
- 전체 `OK (286 tests)`. 기존 Capture probe의 중지 전후 Current 103.012611, MAX 103.013066, frames 102400이 보존됐으며 늦은 옛 snapshot 격리도 통과했다.
- Gradle build/lint, Android adapter instrumentation, 실기기 및 장시간 부하는 독립 재실행하지 않았다. USB·ViewModel 지연 전달·44.1kHz 2·3층·현장 정확도 등 이전 잔여 검증도 남는다.
- production 수정·커밋·외부 전송 없음.

재실행 자료:

- `run-auto-end-verification.ps1`, `FollowupProbe.kt`, `auto-end-verification-log.txt`: 전체 JVM 시험 및 기존 Capture 게이트 회귀.
- `AutoEndReviewProbe.kt`, `run-auto-end-probe.ps1`, `auto-end-probe-log.txt`: 수동/자동 종료 × false/예외의 수정 확인 및 목록 잠금 확인.
- `AutoEndGapProbe.kt`, `run-auto-end-gap-probe.ps1`, `auto-end-gap-log.txt`: current/pending 이전 구간의 상한 위반 탐색. 재현 성공은 `reproduced=true`와 pending 수로 판단한다. exit 0만으로 재현 또는 수정 성공을 판단하지 않는다.

일부 한국어 JVM 로그는 출력 인코딩 차이로 깨져 있지만 시험 개수·결과·숫자는 확인 가능하다.
