package kr.joa.selahrta.audio

import kr.joa.selahrta.domain.MicKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 시험용 가짜 레코더. **읽기를 멈춰 세울 수 있다.**
 *
 * 독립 재검증이 요구한 것이 이것이다 — 「fake AudioRecord/read barrier 로
 * 500ms 이상 정지시키고 stop→start→old read 반환 순서를 강제한다」.
 * 실제 기기로는 그 순서를 만들 수 없다.
 *
 * [gate] 를 걸면 `read()` 가 거기서 기다린다. 그 사이에 시험이 멈추거나
 * 다시 시작한 뒤 문을 열면, 옛 읽기가 **늦게** 돌아오는 상황이 된다.
 */
class FakeRecorder(
    /** 읽기가 차례로 돌려줄 값들. 다 쓰면 마지막 값을 되풀이한다. */
    private val results: List<Int>,
    private val routed: InputDeviceInfo? = builtIn(),
) : CaptureRecorder {

    /** 몇 번 읽었는가. */
    val reads = AtomicInteger(0)

    /** 걸어 두면 읽기가 여기서 기다린다. 시험이 열어 줄 때까지. */
    @Volatile
    var gate: CountDownLatch? = null

    /** 읽기가 문 앞에 닿았음을 시험에 알린다. */
    val reachedGate = CountDownLatch(1)

    override fun read(into: FloatArray, frames: Int): Int {
        val n = reads.getAndIncrement()
        gate?.let {
            reachedGate.countDown()
            // 시험이 열어 주기를 기다린다. 오래 걸리면 시험이 잘못된 것이다.
            it.await(5, TimeUnit.SECONDS)
        }
        val r = results.getOrElse(n) { results.last() }
        if (r > 0) {
            // 값이 있는 덩어리임을 알 수 있게 조금 채운다.
            for (i in 0 until minOf(r, into.size)) into[i] = 0.25f
        }
        return r
    }

    override fun routedDevice(): InputDeviceInfo? = routed

    companion object {
        fun builtIn(addr: String = "bottom") =
            InputDeviceInfo(1, "SM-S918N", MicKind.BuiltIn, "내장 마이크", listOf(48_000), addr)
    }
}

/** 루프가 알려 온 것을 그대로 모아 둔다. */
class RecordingCallbacks : CaptureLoopCallbacks {
    val blocks = mutableListOf<Int>()
    val confirmed = mutableListOf<InputDeviceInfo>()
    val ended = mutableListOf<CaptureEnd>()

    @Synchronized
    override fun onBlock(block: AudioBlock, stats: kr.joa.selahrta.dsp.BlockStats) {
        blocks.add(block.frames)
    }

    @Synchronized
    override fun onRouteConfirmed(device: InputDeviceInfo) {
        confirmed.add(device)
    }

    @Synchronized
    override fun onEnded(end: CaptureEnd) {
        ended.add(end)
    }
}
