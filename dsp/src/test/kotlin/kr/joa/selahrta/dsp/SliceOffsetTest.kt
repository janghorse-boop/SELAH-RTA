package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * **자리를 가리켜 쪼개도 결과가 같은가**(기록 저장의 전제).
 *
 * 기록은 500ms 격자에 값을 남기므로, 행 경계를 걸친 덩어리를 **DSP 에
 * 넣기 전에** 쪼개야 한다. 쪼개어 넣어도 결과가 같다는 것은
 * `SplitProcessingTest` 가 이미 확인했지만, 그쪽은 **배열을 복사해서**
 * 쟀다. 여기서는 새로 더한 `offset` 으로 **복사 없이** 쪼갠다 — 초당
 * 쉰 번 복사하면 GC 가 캡처를 멈추던 원래 문제로 돌아간다.
 *
 * 오차를 허용하지 않는다 — **세션 Leq 만 빼고.** 그것은 누적 합이라
 * 더하는 차례가 달라지면 마지막 비트가 갈린다. 녹음 설계 4차(M33)가
 * 이미 그 계약을 정해 두었다(1e-9dB).
 */
class SliceOffsetTest {

    private val fs = 48_000

    private fun signal(n: Int) = FloatArray(n) {
        // 순음 하나로는 경계 효과가 드러나지 않는다. 배음과 잡음을 섞는다.
        val t = it.toDouble() / fs
        (0.3 * sin(2 * PI * 440.0 * t) +
            0.1 * sin(2 * PI * 1997.0 * t) +
            0.05 * sin(2 * PI * 37.0 * t)).toFloat()
    }

    /** [cuts] 자리에서 쪼개 넣는다. */
    private fun splEngineOver(data: FloatArray, cuts: List<Int>): SplFrame? {
        val e = SplEngine(fs, Weighting.A, TimeWeight.Fast)
        var at = 0
        var last: SplFrame? = null
        for (cut in cuts + data.size) {
            if (cut > at) last = e.process(data, cut - at, at)
            at = cut
        }
        return last
    }

    @Test
    fun `SPL 은 쪼개어 넣어도 같다`() {
        val data = signal(48_000)
        val whole = SplEngine(fs, Weighting.A, TimeWeight.Fast).process(data, data.size)
        val split = splEngineOver(data, listOf(1, 999, 24_000, 24_001, 47_999))

        assertNotNull(split)
        assertEquals("현재값", whole.currentDbfs.value, split!!.currentDbfs.value, 0.0)
        assertEquals("최대", whole.maxDbfs.value, split.maxDbfs.value, 0.0)
        assertEquals("PEAK", whole.peakDbfs.value, split.peakDbfs.value, 0.0)
        // **세션 Leq 만 오차를 허용한다.**
        //
        // 누적 합이라 **더하는 차례**가 달라지면 마지막 비트가 갈린다.
        // 녹음 설계 4차(M33)가 그 계약을 이미 정해 두었다 — 다른 값은
        // 오차 0, 세션 Leq 는 1e-9dB. 실제로 잰 차이는 4e-14dB 다.
        assertEquals(
            "세션 Leq",
            whole.leqSessionDbfs!!.value,
            split.leqSessionDbfs!!.value,
            1e-9,
        )
    }

    @Test
    fun `RTA 는 쪼개어 넣어도 같다`() {
        val data = signal(48_000)
        val whole = RtaEngine(fs).also { it.process(data, data.size) }.frame()
        val e = RtaEngine(fs)
        var at = 0
        for (cut in listOf(1, 999, 24_000, 24_001, 47_999, data.size)) {
            if (cut > at) e.process(data, cut - at, at)
            at = cut
        }
        val split = e.frame()

        assertNotNull(whole)
        assertNotNull(split)
        for (b in 0 until ThirdOctave.BAND_COUNT) {
            assertEquals(
                "${ThirdOctave.label(b)} 밴드",
                whole!!.bandsDbfs[b],
                split!!.bandsDbfs[b],
                0.0,
            )
        }
    }

    @Test
    fun `한 덩어리씩 넣는 것과 통째로 넣는 것이 같다`() {
        val data = signal(12_000)
        val whole = SplEngine(fs, Weighting.A, TimeWeight.Fast).process(data, data.size)
        // 2880 프레임(60ms) 덩어리로 나눠 넣는다 — 실제 캡처와 같은 크기다.
        val split = splEngineOver(data, (2880 until data.size step 2880).toList())
        assertEquals(whole.currentDbfs.value, split!!.currentDbfs.value, 0.0)
    }

    @Test
    fun `범위를 벗어난 자리는 막는다`() {
        val e = RtaEngine(fs)
        val data = FloatArray(100)
        for (bad in listOf(-1 to 10, 0 to 101, 95 to 10)) {
            var blocked = false
            try {
                e.process(data, bad.second, bad.first)
            } catch (x: IllegalArgumentException) {
                blocked = true
            }
            assertTrue("offset=${bad.first} frames=${bad.second} 를 그냥 받았다", blocked)
        }
    }

    @Test
    fun `빈 조각은 아무 일도 하지 않는다`() {
        val e = SplEngine(fs, Weighting.A, TimeWeight.Fast)
        val data = signal(1000)
        e.process(data, 1000)
        val before = e.process(data, 0, 1000)
        assertEquals(
            "빈 조각이 값을 바꿨다",
            before.currentDbfs.value,
            e.process(data, 0, 500).currentDbfs.value,
            0.0,
        )
    }
}
