package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **현재 재생에서 떼어 내는 것과 종료 추적에 넘기는 것이 한 전환인가.**
 *
 * 독립 검증자가 `AutoEndGapProbe.kt` 로 재현한 것을 옮겼다. 내가 「놓기를
 * 시도하기 전에 먼저 등록한다」까지는 했는데, **`current` 에서 빠진 직후
 * 등록되기 전의 짧은 구간**이 남아 있었다:
 *
 * 1. A 가 이미 해제에 실패해 pending=1
 * 2. B 가 쓰기 오류로 끝나며 `current`·`playing` 을 null 로 바꾼다
 * 3. B 가 아직 등록되기 **전에** C 를 시작한다
 * 4. `stop()` 은 current 가 null 이라 바로 끝나고, 정리는 A 만 보므로
 *    C 가 열린다
 * 5. B·C 가 해제에 실패하면 **pending=3**(상한 2)
 *
 * 목록 자체의 자물쇠는 멀쩡하다 — **목록과 `current` 사이의 이전**이
 * 따로 일어난 것이 문제다.
 *
 * 검증자 실행: `DETACH_GAP iteration=22 opens=3 pending=3 cap=2`.
 */
class DetachGapTest {

    /**
     * [mode] 0 = 곧바로 쓰기 오류, 1 = 게이트를 기다렸다 쓰기 오류,
     * 2 = 정상. 놓기는 **언제나 실패**한다.
     */
    private class GapSink(val mode: Int) : SignalSink {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)

        override fun open(sampleRate: Int, frames: Int, channels: Int) = true

        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            entered.countDown()
            if (mode > 0) check(gate.await(5, TimeUnit.SECONDS))
            return if (mode < 2) SignalSink.ERROR_DEAD_OBJECT else frames
        }

        override fun stop() {
            gate.countDown()
        }

        override fun release() = false
    }

    /**
     * **오류로 끝나는 순간 다시 시작해도 상한을 넘기지 않는다.**
     *
     * 검증자 probe 의 순서를 그대로 쓴다 — `playing` 이 null 이 되는 것을
     * 본 **즉시** 다음 재생을 시작한다. 그 순간 B 가 이미 종료 추적에
     * 들어가 있어야 한다.
     *
     * **이것은 barrier 로 고정한 시험이 아니라 관찰 시험이다.**
     *
     * 내가 요청서에 「고친 뒤에는 그 구간 자체가 없어 barrier 를 놓을 자리가
     * 없다」고 적었는데 **그 말은 과했다.** 검증자가 곧바로 보여 줬다 —
     * 등록을 일부러 늦추고 **다른 제어 경로가 그동안 기다리는지**를 보면
     * 된다. `PendingList` 의 monitor 를 잠깐 쥔 채 B 를 오류로 끝내고 C 의
     * `start()` 를 시도하면, 그 스레드가 **수명주기 자물쇠에서 BLOCKED** 가
     * 되고 등록이 끝난 뒤 `NONE` 을 받는다:
     *
     * ```
     * HANDOFF_BARRIER blockedOnLifecycleLock=true newOpenRefused=true opens=2 pending=2
     * ```
     *
     * 검증자는 그 probe 를 private 필드 이름에 기대는 reflection 으로 만들어
     * 「제품 시험의 장기 설계로 삼으라는 뜻은 아니다」라고 적었다. 그래서
     * 여기 저장소에는 관찰 시험만 두되, **결정적 회귀를 남기려면 상태 이전
     * 경계에 작은 시험 접근점을 두면 된다**는 것을 적어 둔다.
     *
     * 검증자는 22번째 시도에서 재현했고, 여기서는 그보다 넉넉히 돌린다.
     */
    @Test
    fun `오류로 끝나는 순간 재시작해도 상한을 넘기지 않는다`() {
        val cap = SignalPlayer.MAX_STUCK_PLAYBACKS
        var worstOpens = 0
        var worstPending = 0

        repeat(ATTEMPTS) { attempt ->
            val firstEnded = CountDownLatch(1)
            val secondEnded = CountDownLatch(1)
            val sinks = ArrayList<GapSink>()
            val p = SignalPlayer(
                onEnded = { gen, _ ->
                    if (gen == 1L) firstEnded.countDown() else if (gen == 2L) secondEnded.countDown()
                },
                openSink = { GapSink(sinks.size).also { sinks.add(it) } },
                warn = {},
            )

            // A — 곧바로 오류로 끝나고 놓기도 실패한다.
            p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE))
            check(firstEnded.await(5, TimeUnit.SECONDS))
            assertEquals("A 가 자리를 하나 차지해야 한다", 1, p.pendingCount)

            // B — 게이트를 풀면 오류로 끝난다.
            p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE))
            check(sinks[1].entered.await(5, TimeUnit.SECONDS))
            sinks[1].gate.countDown()

            // **`playing` 이 비는 순간을 노린다.**
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (p.playing != null && System.nanoTime() < until) Thread.onSpinWait()
            check(p.playing == null) { "B 가 끝나지 않았다" }

            val third = p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE))
            check(secondEnded.await(5, TimeUnit.SECONDS))

            if (third != SignalPlayer.NONE) {
                p.stop()
                worstOpens = sinks.size
                worstPending = p.pendingCount
                println("[RC02 틈] 시도 ${attempt + 1} · 열림 $worstOpens · pending $worstPending · 상한 $cap")
            }
            assertTrue(
                "상한을 넘겨 열렸다 (시도 ${attempt + 1}: 열림 ${sinks.size} · pending ${p.pendingCount})",
                third == SignalPlayer.NONE && p.pendingCount <= cap,
            )
        }
        println("[RC02 틈] ${ATTEMPTS}회 모두 상한을 지켰다")
    }

    private companion object {
        /** 검증자는 22번째에 재현했다. 그보다 넉넉히 돌린다. */
        const val ATTEMPTS = 400
    }
}
