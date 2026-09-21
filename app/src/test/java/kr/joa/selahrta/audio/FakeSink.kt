package kr.joa.selahrta.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 시험이 순서를 쥐는 가짜 출력.
 *
 * **실제 `AudioTrack` 으로는 이 순서를 만들 수 없다.** 검증자가 남겨 둔
 * 항목 — 출력 오류, `stop()` 의 시간 초과, 빠른 재시작 — 은 전부 스레드
 * 순서의 문제라, 어느 `write` 에서 멈출지·언제 풀릴지를 시험이 정해야 한다.
 */
class FakeSink(
    /** 열 때 실패시킬 것인가. */
    private val openFails: Boolean = false,
    /** 이 번째 write 에서 막는다(1부터). null 이면 막지 않는다. */
    private val blockAtWrite: Int? = null,
    /** 이 번째 write 에서 이 값을 돌려준다(음수 = 오류). */
    private val failAtWrite: Int? = null,
    private val failCode: Int = -1,
    /**
     * [stop] 이 막힌 write 를 풀어 주는가.
     *
     * 실제 `AudioTrack.stop()` 은 대개 풀어 준다. **false 면 풀어 주지
     * 않는 기기**를 흉내 내어 `join` 시간 초과를 만든다.
     */
    private val unblockOnStop: Boolean = true,
) : SignalSink {

    private val gate = CountDownLatch(1)

    val writeCount = AtomicInteger(0)

    @Volatile
    var opened = false
        private set

    @Volatile
    var stopped = false
        private set

    @Volatile
    var released = false
        private set

    /** 몇 번이나 놓였는가. 두 번 놓는 것을 잡는다. */
    val releaseCount = AtomicInteger(0)

    /** **놓인 뒤에** 쓴 횟수. 0 이 아니면 자원을 놓고도 쓴 것이다. */
    val writesAfterRelease = AtomicInteger(0)

    /** 막힌 write 에 들어갔음을 알린다. */
    val entered = CountDownLatch(1)

    override fun open(sampleRate: Int, frames: Int): Boolean {
        if (openFails) return false
        opened = true
        return true
    }

    override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
        val n = writeCount.incrementAndGet()
        if (released) writesAfterRelease.incrementAndGet()
        if (blockAtWrite != null && n == blockAtWrite) {
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            // 풀린 뒤에도 놓였는지 다시 본다 — 막혀 있는 동안 놓였을 수 있다.
            if (released) writesAfterRelease.incrementAndGet()
        }
        if (failAtWrite != null && n >= failAtWrite) return failCode
        return frames
    }

    override fun stop() {
        stopped = true
        if (unblockOnStop) gate.countDown()
    }

    override fun release(): Boolean {
        released = true
        releaseCount.incrementAndGet()
        return true
    }

    /** 막아 둔 write 를 시험이 직접 푼다. */
    fun unblock() = gate.countDown()

    /** 막힌 자리에 들어갈 때까지 기다린다. */
    fun awaitEntered(): Boolean = entered.await(5, TimeUnit.SECONDS)
}
