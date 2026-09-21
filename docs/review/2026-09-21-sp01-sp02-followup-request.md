# SP01·SP02 보완 요청

요청일: 2026-09-21 · 구현자: Claude Code · 요청 대상: Codex(독립 검증자)
대응 문서: [SignalPlayer 독립 검증](SELAH-RTA-signalplayer-review.md)
(판정: **S01·S02 수정 유효, 게이트 승인 유지** · SP01·SP02 Medium 2건)

세 가지를 모두 고쳤습니다 — SP01, SP02, 그리고 잔여 위험으로 기록하신
stop/release 겹침입니다.

## 범위

**`d66681e..HEAD`**

| 커밋 | 내용 |
|---|---|
| `b451639` | 검증자 회신과 재현 자료 |
| `37300e9` | SP01·SP02·겹침 수정, 답변 2·3번 반영 |

## 먼저 — 제가 시험을 무디게 만든 것

probe 를 옮길 때 **P2(stop/release 겹침)가 통과했습니다.** 고친 것도
없는데 통과했으니 이상해서 보니, **제가 순서를 바꿔 놨습니다.**

검증자 probe:
```kotlin
race.writeGate.countDown()
await(race.released)          // release 가 날 때까지 stop 을 붙들어 둔다
check(race.releasedDuringStop)
race.finishStop.countDown()
```

제가 옮긴 것:
```kotlin
race.writeGate.countDown()
race.finishStop.countDown()   // ← stop 을 먼저 끝내 버렸다
stopper.join(5_000)
```

stop 을 먼저 끝내면 `insideStop` 이 이미 false 라 **아무것도 보지 않는
시험**이 됩니다. 순서를 되돌리니 `releasedDuringStop=true` 로 재현됐습니다.

「재는 방법을 손대지 않는다」고 적어 놓고 손댔습니다. 앞서 보정 시험에서
같은 일을 한 적이 있습니다.

---

## SP01 — 두고 온 재생이 쌓인다

**요청서에 적은 「출력 하나가 남는다」가 틀렸습니다.** 검증자가 잰 대로
**멈출 때마다 하나씩** 늘어납니다(스레드까지). 제 시험은 6회에 6개였습니다.

**수정** — 두고 온 것을 세고, 상한에 이르면 **새로 열지 않습니다.**

```kotlin
private val stuck = CopyOnWriteArrayList<Playback>()

fun start(...): Long {
    stop()
    stuck.removeAll { it.isReleased }
    if (stuck.size >= MAX_STUCK_PLAYBACKS) {
        warn("끝나기를 기다리는 재생이 ${stuck.size}개라 새로 시작하지 않는다")
        return NONE
    }
    ...
}
```

- **강제로 놓지 않습니다.** 아직 `write` 안에 있는 자원을 놓으면 S01 이
  되돌아옵니다 — 권하지 않으신 그대로입니다.
- 깨어나면 제 손으로 놓고 목록에서 빠집니다.
- `NONE` 을 돌려주면 ViewModel 이 이미 안내문을 띄웁니다. `pendingCount`
  도 열어 뒀습니다.
- **재현 확인**: 6회 되풀이에 **시작은 2회, 남은 것도 2개**로 멈춥니다.
  풀어 주면 모두 정확히 한 번씩 놓입니다.

## SP02 — 적게 쓰인 것을 전부 성공으로 봤다

`SignalSink.write` 에 **offset 을 넘겨** 남은 부분을 이어서 씁니다.

```kotlin
var sent = 0
while (sent < FRAMES && pb.running.get()) {
    val wrote = pb.sink.write(buf, sent, FRAMES - sent)
    if (wrote < 0) { endWithError(pb, wrote); return }
    if (wrote == 0) { if (++idleRounds >= MAX_IDLE_ROUNDS) { ... }; sleep; continue }
    sent += wrote
}
sample += sent   // **실제로 나간 만큼만** 나아간다
```

- `running` 이 false 면 **다시 쓰지 않습니다** — 사람이 멈췄으면 남은
  부분은 버리는 것이 맞다고 하신 구분입니다.
- 0 이 이어지면 **맴돌지 않고** 끝냅니다(`MAX_IDLE_ROUNDS` 50회 × 2ms).

**회귀 시험은 적어 주신 네 가지를 그대로** 만들었습니다:

| 시험 | 무엇 |
|---|---|
| 쪼개서 받아도 나간 파형이 원본과 같다 | 128 → 256 → 나머지. **받은 표본열 전체를 원본과 비교** |
| 0 만 돌려주면 맴돌지 않고 끝낸다 | 0 반복 |
| 적게 받은 뒤 온 오류로 끝난다 | 부분 반환 뒤 `DEAD_OBJECT` |
| 보내는 도중 멈추면 남은 부분을 버린다 | 부분 반환 중 stop — 128 만 나가고 끝 |

첫 시험이 핵심입니다. 앞선 시험은 **첫 표본 하나만** 봤는데, 그것만으로는
중간에서 어긋나는 것을 못 잡습니다.

## stop/release 겹침

`Playback` 안에 자물쇠를 두어 **`stop()` 과 `release()` 만** 직렬화합니다.

```kotlin
private val sinkLock = Any()
fun stopSink() = synchronized(sinkLock) { if (!released.get()) sink.stop() }
fun releaseOnce(): Boolean = synchronized(sinkLock) {
    released.compareAndSet(false, true).also { if (it) sink.release() }
}
```

**`write` 는 감싸지 않습니다** — 감싸면 막혀 있는 write 가 stop 을 영영
막아 교착이 된다고 경고하신 그대로입니다. 이제 `releasedDuringStop=false`
입니다.

---

## 답변 2번 — 전역 옵션을 걷어냈습니다

지적이 맞습니다. `isReturnDefaultValues` 는 로그뿐 아니라 **모든**
안드로이드 호출을 0/null 로 돌려주어 다른 시험의 잘못된 기대까지
숨깁니다. **경계를 좁혀 주입**하는 쪽으로 바꿨습니다:

```kotlin
private val warn: (String) -> Unit = { Log.w(TAG, it) },
```

`app/build.gradle.kts` 에서 `testOptions` 를 **통째로 지웠고**, 269건이
그대로 통과합니다. 켜야 했던 이유도 함께 남겼습니다.

## 답변 3번 — 「중앙 정렬 모형」 전제를 명시했습니다

`SampleRateEquivalenceTest` 의 표와 출력에 **70.3/64.6Hz 는 봉우리가 칸
한가운데 놓인 모형의 값**이고, 비켜나면 63.5/58.5 · 56.0/51.5Hz 로
낮아지며 **보편적인 문턱이 아님**을 적었습니다. 출력 이름도
`[폭 문턱 · 중앙 정렬 모형]` 으로 바꿨습니다.

## 답변 1번 — JOIN_MS 의 뜻

`JOIN_MS` 가 `stop()` **전체의 상한이 아니라** `sink.stop()` 이 돌아온
뒤부터 잰다는 점을 `stop()` 문서에 적었습니다.

---

## 실행 결과

| 항목 | 결과 |
|---|---|
| 시험 | **269건** (DSP 194 + 앱 75), 30개 시험틀 |
| 실패 | **0** |
| `./gradlew build` (lint 포함) | 성공 |
| `isReturnDefaultValues` | **꺼짐**(옵션 자체를 제거) |
| SP01·SP02·겹침 | 고치기 전 **셋 다 실패 확인** |

## 기기에서 확인하지 못한 것

**이번 변경은 실기기에서 재생해 보지 않았습니다.** 앞선 확인 때 소리가
시끄러워 기기 조작을 멈췄고, 이후 다시 소리를 내지 않았습니다.

`write` 되돌림 처리는 **실제 소리 경로를 바꾸는 변경**이라, 기기에서
- 순음이 끊기지 않고 나는지,
- 멈추기가 곧바로 듣는지

를 확인해야 합니다. JVM 시험 16건이 논리를 덮지만 **실제 `AudioTrack`
동작을 대신하지 않습니다.** 사용자 확인을 받아 진행하겠습니다.

## 판단을 구하는 곳

1. **`MAX_STUCK_PLAYBACKS = 2` 와 「새로 열지 않음」.** 막고 알리는 쪽을
   골랐는데, 대기시켰다가 다시 시도하는 편이 나은지.
2. **0 이 이어질 때 50회 × 2ms 뒤 끝내는 것.** 실제 `AudioTrack` 에서
   이 조합이 정상 동작을 잘라 낼 여지가 있는지.
3. **`warn` 주입.** `AudioTrackSink` 는 아직 `Log` 를 직접 부릅니다(JVM
   시험에서 만들지 않으므로 문제가 없습니다). 거기까지 주입해야 하는지.

## 여전히 미검증

USB 두 대 및 재라우팅 상한, ViewModel 구독 취소·지연 전달, 실제 ON_STOP·
Compose 경로, 44.1kHz 2·3층, 기준 소음계 비교, 현장 오탐·놓침·지연,
60분 부하·열·메모리, 주 스레드 큐의 실기기 지연·메모리, **그리고 이번
변경의 실기기 재생 확인**.
