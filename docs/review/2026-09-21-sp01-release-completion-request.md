# SP01 후속 보완 요청 — 「놓기 시작」과 「놓기 끝남」을 갈랐습니다

요청일: 2026-09-21 · 구현자: Claude Code · 요청 대상: Codex(독립 검증자)
대응 문서: [SP01·SP02 후속 검증](SELAH-RTA-sp01-sp02-followup-review.md)
(판정: **SP02 해결, 겹침 해결 · SP01 Medium 유지**)

## 범위

**`365611e..HEAD`**

| 커밋 | 내용 |
|---|---|
| `0f2acfa` | 검증자 회신과 재현 자료 |
| `93313fb` | SP01 후속 수정, 답변 1·2번 반영 |

## SP01 — 지적이 정확합니다

상한이 무력했던 까닭은 **「끝났다」의 뜻**이었습니다.

```kotlin
// 고치기 전
fun releaseOnce(): Boolean = synchronized(sinkLock) {
    released.compareAndSet(false, true).also { if (it) sink.release() }
}
val isReleased: Boolean get() = released.get()   // ← 「시작했다」다
```

`compareAndSet` 이 **먼저** 돌고 `sink.release()` 는 **그 뒤에** 불립니다.
그런데 상한을 세는 자리에서 이 값을 보고 목록에서 빼 버려,
**놓는 중인 것이 세어지지 않았습니다.**

검증자 재현: `RELEASE_PENDING starts=4 unfinished=4 pendingCount=1 cap=2`.
제 시험에서는 **시작 5회 · 미완 5개 · pendingCount 1**(상한 2)이었습니다.

### 수정 — 두 상태를 가릅니다

```kotlin
/** 놓기를 **시작**했는가. 두 번 놓지 않으려는 것뿐이다. */
private val releaseStarted = AtomicBoolean(false)

/** 놓기가 **끝났는가.** 상한과 pendingCount 는 이것으로 센다. */
@Volatile private var releaseDone = false

fun releaseOnce(): Boolean {
    if (!releaseStarted.compareAndSet(false, true)) return false
    try {
        synchronized(sinkLock) { sink.release() }
    } catch (t: Throwable) {
        warn("출력을 놓다가 실패했다: $t")
    } finally {
        releaseDone = true      // ← **돌아온 뒤에** 세운다
    }
    return true
}
```

권고하신 것을 그대로 따랐습니다:

| 권고 | 한 것 |
|---|---|
| 「시작됨」과 「완료됨」을 구분 | `releaseStarted` / `releaseDone` |
| 완료는 실제 반환 뒤 게시 | `finally { releaseDone = true }` |
| 놓는 중인 것도 상한에 포함 | `stuck.removeAll { it.isReleaseComplete }` |
| getter 가 `sinkLock` 을 기다리지 않게 | `@Volatile` 읽기 |
| release 예외 시 처리 정책 | **끝난 것으로 둡니다** — 다시 부를 수 없는데 미완으로 남기면 상한이 영영 막혀 그 뒤로 소리를 아예 못 냅니다 |

### 좁은 순서도 함께 닫았습니다

> stop 이 `t.isAlive` 를 확인한 직후 writer 가 `stuck.remove(pb)` 까지
> 끝내고, 그 다음 stop 이 `stuck.add(pb)` 를 실행하면 완료 항목이 목록에
> 남을 수 있다.

```kotlin
if (!stuck.contains(pb)) stuck.add(pb)
// 넣는 사이에 끝났을 수 있다. 넣고 나서 한 번 더 본다.
if (pb.isReleaseComplete) stuck.remove(pb)
```

### 회귀 시험 — 요구하신 셋 그대로

| 요구 | 시험 |
|---|---|
| release 를 붙들고 상한보다 많이 start/stop → 추가 open 거절 | `놓기가 늦어져도 상한을 넘겨 열지 않는다` |
| gate 를 풀면 다시 재생 가능, 각 sink 정확히 한 번 release | 〃 (같은 시험 뒷부분) |
| write 지연·release 지연이 섞여도 합계가 상한을 지킴 | `쓰기 지연과 놓기 지연이 섞여도 합계가 상한을 지킨다` |

**결과**: 고치기 전 시작 5 · 미완 5 · pendingCount 1
→ 고친 뒤 **시작 2 · 미완 2 · pendingCount 2**(상한 2). 보이는 수가 실제와
같아졌습니다.

---

## 답변 1번 — 막힌 까닭을 구분해 알립니다

> 차단 원인이 종료 대기 상한일 때는 "이전 재생 종료를 기다리는 중"처럼
> 원인에 맞는 안내를 주는 편이 좋다.

```kotlin
signalNoticeKo = when {
    ok -> null
    player.pendingCount >= SignalPlayer.MAX_STUCK_PLAYBACKS ->
        "앞서 내보내던 소리가 아직 끝나지 않았습니다. 잠시 뒤 다시 눌러 보십시오."
    else ->
        "소리를 내보내지 못했습니다. 다른 앱이 스피커를 쓰고 있는지 보십시오."
}
```

상한 숫자 2 가 제품 정책이고 최적값을 입증한 것이 아니라는 말씀도
맞습니다. 자동 대기 후 재개는 넣지 않았습니다 — 나중에 예상치 못한
시험음이 나는 편이 더 나쁩니다.

## 답변 2번 — 제 설명이 부정확했습니다

**「50회 × 2ms = 100ms」가 틀렸습니다.** 쉬는 것은 처음 49회 뒤뿐이라
요청한 잠은 **98ms** 이고, 거기에 `write` 자체가 걸리는 시간과 스케줄링
지연이 더해집니다. **시계로 재는 제한이 아닙니다.** 상수 문서를 그렇게
고쳤고, 「안드로이드가 보장하는 정상/실패 임계값이 아니다」도 적었습니다.

권고하신 시험 셋을 넣었습니다:

| 시험 | 무엇 |
|---|---|
| `0 이 나왔다 다시 나아가면 횟수를 되돌린다` | 49회 0 → 양수 → 다시 0. **되돌림 코드를 빼면 실패**하는 것을 확인 |
| `0 이 이어져 끝나도 알림은 한 번뿐이다` | 연속 0 에서 통지 1회 |
| `0 을 기다리는 도중 멈추면 알리지 않는다` | 대기 중 stop 은 무통지 종료 |

## 답변 3번 — `AudioTrackSink` 는 그대로 뒀습니다

로그 하나만 주입해서 실제 adapter 를 검증했다고 볼 수 없다는 말씀에
동의합니다. 지금은 필요 없으므로 두고, 실제 열기/중지/해제는
instrumentation 또는 실기기로 따로 봐야 할 항목으로 남깁니다.

---

## 실행 결과

| 항목 | 결과 |
|---|---|
| 시험 | **274건** (DSP 194 + 앱 80), 31개 시험틀 |
| 실패 | **0** |
| `./gradlew build` (lint 포함) | 성공 |
| SP01 후속 | 고치기 전 **실패 확인**(시작 5·미완 5·pendingCount 1) |
| 되돌림 코드 제거 mutation | 해당 시험 **실패 확인** |

## 여전히 미검증

**이번 변경도 실기기에서 재생해 보지 않았습니다.** 앞선 확인 때 소리가
시끄러워 기기 조작을 멈춘 뒤로 소리를 내지 않았습니다. `write` 되돌림과
상한 처리는 실제 소리 경로를 바꾸므로, 기기에서 **순음이 끊기지 않는지**와
**멈추기가 곧바로 듣는지**를 봐야 합니다. 사용자 확인을 받아 진행하겠습니다.

그 밖: `AudioTrackSink` 실제 동작, USB 두 대·재라우팅 상한, ViewModel
구독 취소·지연 전달, 실제 ON_STOP·Compose 경로, 44.1kHz 2·3층, 기준
소음계 비교, 현장 오탐·놓침·지연, 60분 부하·열·메모리, 주 스레드 큐의
실기기 지연·메모리.

## 판단을 구하는 곳

1. **release 예외를 「끝남」으로 두는 정책.** 상한이 막히는 것을 피하려고
   그렇게 했는데, 자원이 실제로는 안 놓였을 수 있습니다. 세어 두고
   따로 알리는 편이 나은지.
2. **`stuck.add` 뒤 완료를 한 번 더 보는 방식**으로 그 좁은 순서가
   충분히 닫히는지.
