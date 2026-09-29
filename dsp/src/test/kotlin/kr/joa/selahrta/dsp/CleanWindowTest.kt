package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * **보정에 쓸 값이 큰 소리를 기억하지 않는가**(독립 재검증 UISRF-01).
 *
 * 검토자가 실기기 없이 재현한 것: 진폭 1.0 으로 5초(잘림) 넣은 뒤
 * 진폭 0.01 로 **3.1초를 기다려도** 화면의 현재값은 −16.49 dBFS 였다.
 * 충분히 안정된 값은 −43.01 dBFS 이니 **+26.52 dB** 가 남아 있었고,
 * 그 값으로 보정이 저장됐다.
 *
 * 까닭은 지수 시간가중이 과거를 오래 기억하기 때문이다 — 3τ 뒤에도
 * 옛 에너지의 5%가 남는데, 진폭이 100배 바뀌면 에너지는 10,000배
 * 바뀌므로 그 5%가 새 에너지보다 훨씬 크다.
 *
 * 유한 창은 그 문제가 없다. 창을 벗어난 소리의 기여는 **0 이다.**
 */
class CleanWindowTest {

    private val fs = 48_000
    private val blockFrames = 960          // 20ms
    private val blockNs = 20_000_000L

    private fun tone(amp: Double) = FloatArray(blockFrames) {
        (amp * sin(2 * PI * 1000 * it / fs)).toFloat()
    }

    /** 그 진폭이 충분히 오래 이어졌을 때의 참값. 견줄 기준이다. */
    private fun settled(amp: Double): Double {
        val e = MultiWeightEngine(fs, TimeWeight.Slow)
        repeat(1000) { e.process(tone(amp), blockFrames) }
        return e.process(tone(amp), blockFrames).a.currentDbfs.value
    }

    private class Feeder(val w: CleanWindow) {
        var atNs = 1_000_000_000L
        fun feed(block: FloatArray, count: Int, clipped: Boolean, times: Int) {
            repeat(times) {
                atNs += 20_000_000L
                w.observe(block, count, atNs, clipped)
            }
        }
        fun skip(ms: Long) { atNs += ms * 1_000_000L }
    }

    // ── 검토자가 재현한 그 순서 ───────────────────────────

    /**
     * **이것이 이 파일의 한가운데다.** 잘린 큰 소리 뒤 3.1초면 값이
     * 나오고, 그 값은 안정된 값과 거의 같아야 한다.
     */
    @Test
    fun `잘린 큰 소리 뒤의 값이 옛 소리를 기억하지 않는다`() {
        val w = CleanWindow(fs)
        val f = Feeder(w)

        // 진폭 1.0 은 잘린다. 5초.
        f.feed(tone(1.0), blockFrames, clipped = true, times = 250)
        assertNull("잘리는 동안 값을 내놓는다", w.value(f.atNs, Weighting.A))

        // 진폭 0.01 로 낮춘다. 창이 차기 전에는 아직 안 준다.
        f.feed(tone(0.01), blockFrames, clipped = false, times = 149)
        assertNull("창이 차기 전에 값을 내놓는다", w.value(f.atNs, Weighting.A))

        // 3초를 넘기면 값이 나온다.
        f.feed(tone(0.01), blockFrames, clipped = false, times = 6)
        val got = w.value(f.atNs, Weighting.A)
        assertNotNull("창이 찼는데 값이 없다", got)

        val ref = settled(0.01)
        assertTrue(
            "옛 큰 소리가 남아 있다: %.2f vs %.2f (차 %.2f dB)".format(got!!, ref, got - ref),
            abs(got - ref) < 0.05,
        )
    }

    /**
     * **입력이 끊긴 시간은 조용했던 시간이 아니다**(검토자 재현 B).
     *
     * 소리가 안 들어오면 시간가중은 감쇠하지도 않는다 — 큰 소리 상태
     * 그대로 멈춰 있다. 벽시계만 보고 「3초 조용했다」고 세면 그 상태가
     * 그대로 승인된다.
     */
    @Test
    fun `공백은 깨끗한 시간으로 세지 않는다`() {
        val w = CleanWindow(fs)
        val f = Feeder(w)
        f.feed(tone(1.0), blockFrames, clipped = true, times = 250)
        f.skip(3_100)                                   // 콜백 없이 시계만
        f.feed(tone(0.01), blockFrames, clipped = false, times = 1)   // 20ms 한 덩어리
        assertNull("공백을 깨끗한 시간으로 셌다", w.value(f.atNs, Weighting.A))
    }

    // ── 버려야 할 것들 ────────────────────────────────────

    @Test
    fun `중간에 한 덩어리만 잘려도 처음부터 다시 센다`() {
        val w = CleanWindow(fs)
        val f = Feeder(w)
        f.feed(tone(0.01), blockFrames, clipped = false, times = 155)
        assertNotNull(w.value(f.atNs, Weighting.A))

        f.feed(tone(1.0), blockFrames, clipped = true, times = 1)
        assertNull("잘린 덩어리 뒤에도 값을 내놓는다", w.value(f.atNs, Weighting.A))
        assertEquals(0L, w.cleanMs)
    }

    @Test
    fun `빈 덩어리(읽기 오류)도 처음부터 다시 센다`() {
        val w = CleanWindow(fs)
        val f = Feeder(w)
        f.feed(tone(0.01), blockFrames, clipped = false, times = 155)
        assertNotNull(w.value(f.atNs, Weighting.A))

        f.feed(FloatArray(0), 0, clipped = false, times = 1)
        assertNull("읽기 오류 뒤에도 값을 내놓는다", w.value(f.atNs, Weighting.A))
    }

    /** 중간에 길게 끊겼으면 그 앞뒤를 이어 붙이지 않는다. */
    @Test
    fun `긴 공백 뒤에는 처음부터 다시 센다`() {
        val w = CleanWindow(fs)
        val f = Feeder(w)
        f.feed(tone(0.01), blockFrames, clipped = false, times = 155)
        assertTrue(w.ready)

        f.skip(3_000)
        f.feed(tone(0.01), blockFrames, clipped = false, times = 1)
        assertFalse("긴 공백을 이어 붙였다", w.ready)
    }

    /** 마지막 덩어리가 오래됐으면 값을 주지 않는다 — 지금 소리가 아니다. */
    @Test
    fun `찬 뒤에도 소리가 끊기면 값을 주지 않는다`() {
        val w = CleanWindow(fs)
        val f = Feeder(w)
        f.feed(tone(0.01), blockFrames, clipped = false, times = 155)
        assertNotNull(w.value(f.atNs, Weighting.A))
        assertNull(
            "오래된 값을 내놓는다",
            w.value(f.atNs + 2_000_000_000L, Weighting.A),
        )
    }

    // ── 값 자체가 맞는가 ──────────────────────────────────

    /** 레벨이 바뀌면 창이 다시 찬 뒤 그 값을 따라가야 한다. */
    @Test
    fun `레벨이 바뀌면 그만큼 따라간다`() {
        val w = CleanWindow(fs)
        val f = Feeder(w)
        f.feed(tone(0.01), blockFrames, clipped = false, times = 155)
        val low = w.value(f.atNs, Weighting.A)!!

        // 20dB 올린다(진폭 10배). 창을 완전히 갈아 치울 만큼 넣는다.
        f.feed(tone(0.1), blockFrames, clipped = false, times = 155)
        val high = w.value(f.atNs, Weighting.A)!!
        assertEquals(20.0, high - low, 0.05)
    }

    /** 1kHz 순음이라 A·C 가중이 0dB 다 — 교정기 카드의 설명 그대로다. */
    @Test
    fun `1kHz 에서는 가중치가 값을 바꾸지 않는다`() {
        val w = CleanWindow(fs)
        val f = Feeder(w)
        f.feed(tone(0.01), blockFrames, clipped = false, times = 155)
        val a = w.value(f.atNs, Weighting.A)!!
        val c = w.value(f.atNs, Weighting.C)!!
        val z = w.value(f.atNs, Weighting.Z)!!
        assertEquals(a, c, 0.1)
        assertEquals(a, z, 0.1)
    }

    // ── 48kHz·고른 덩어리만이 아니다 ──────────────────────

    /**
     * **44.1kHz 에서도 같아야 한다**(독립 재검증 요청서 6장에 「미확인」
     * 으로 적어 둔 것).
     *
     * 창 길이를 프레임 수로 셈하므로 샘플레이트가 바뀌면 필요한 프레임
     * 수도 바뀐다. 그 셈이 틀리면 **어떤 기기에서만** 창이 일찍 차거나
     * 영영 안 차는데, 48kHz 로만 시험하면 드러나지 않는다.
     */
    @Test
    fun `44100Hz 에서도 3초를 채워야 값이 나온다`() {
        val fs2 = 44_100
        val frames = 441                       // 10ms
        fun t(amp: Double) = FloatArray(frames) {
            (amp * sin(2 * PI * 1000 * it / fs2)).toFloat()
        }
        val w = CleanWindow(fs2)
        var at = 1_000_000_000L
        // 2.99초 — 아직 모자라다
        repeat(299) { at += 10_000_000L; w.observe(t(0.01), frames, at, false) }
        assertFalse("2.99초인데 찼다고 한다 (cleanMs=${w.cleanMs})", w.ready)
        // 3.00초 — 찬다
        repeat(2) { at += 10_000_000L; w.observe(t(0.01), frames, at, false) }
        assertTrue("3초를 넣었는데 안 찼다 (cleanMs=${w.cleanMs})", w.ready)

        val got = w.value(at, Weighting.A)!!
        val ref = MultiWeightEngine(fs2, TimeWeight.Slow).let { e ->
            repeat(1000) { e.process(t(0.01), frames) }
            e.process(t(0.01), frames).a.currentDbfs.value
        }
        assertTrue("44.1kHz 에서 값이 틀리다: %.2f vs %.2f".format(got, ref), abs(got - ref) < 0.1)
    }

    /**
     * **덩어리 크기가 들쑥날쑥해도 같아야 한다.**
     *
     * USB 기기는 늘 같은 크기로 주지 않는다. 프레임 수로 세므로
     * 문제없어야 하는데, 「덩어리 수」로 세는 실수를 하면 여기서 갈린다.
     */
    @Test
    fun `덩어리 크기가 달라도 프레임으로 센다`() {
        val w = CleanWindow(fs)
        var at = 1_000_000_000L
        var total = 0L
        // 128·960·2048 프레임을 섞어 3초를 넘긴다
        val sizes = listOf(128, 960, 2048)
        var i = 0
        while (total < fs * 3L) {
            val n = sizes[i++ % sizes.size]
            val block = FloatArray(n) { (0.01 * sin(2 * PI * 1000 * it / fs)).toFloat() }
            at += n * 1_000_000_000L / fs
            w.observe(block, n, at, false)
            total += n
        }
        assertTrue("프레임으로 3초를 넣었는데 안 찼다 (cleanMs=${w.cleanMs})", w.ready)
        assertNotNull(w.value(at, Weighting.A))
    }

    /**
     * **덩어리보다 짧게 넣어도 그만큼만 센다.**
     *
     * `frameCount` 가 배열보다 작을 수 있다(마지막 조각). 배열 크기로
     * 세면 실제보다 빨리 찬다.
     */
    @Test
    fun `배열이 아니라 frameCount 로 센다`() {
        val w = CleanWindow(fs)
        val big = FloatArray(4800) { (0.01 * sin(2 * PI * 1000 * it / fs)).toFloat() }
        var at = 1_000_000_000L
        // 4800짜리 배열에 480프레임(10ms)씩만 넣는다 → 3초에 300번
        repeat(299) { at += 10_000_000L; w.observe(big, 480, at, false) }
        assertFalse("frameCount 가 아니라 배열 크기로 셌다", w.ready)
        repeat(2) { at += 10_000_000L; w.observe(big, 480, at, false) }
        assertTrue(w.ready)
    }

    /**
     * **칸 근사로는 「창 밖은 0」을 못 지킨다**(독립 재검증 UISRFF-01).
     *
     * `RollingLeq` 의 기본 계산은 100ms 칸이라, 3초로 설정해도 실제
     * 평균 구간이 **3.0~3.1초**를 오간다. 창이 0.1초 더 뻗으면 그 안에
     * 든 큰 소리가 섞인다.
     *
     * 검토자가 잰 값: 진폭 0.9 로 5.02초 → 0.009 로 3.02초에서
     * **18.27 dB** 가 틀렸다. 여기서는 같은 순서를 짧게 재현한다.
     */
    @Test
    fun `칸 경계에 걸쳐도 창 밖의 큰 소리가 안 섞인다`() {
        val w = CleanWindow(fs)
        val f = Feeder(w)
        // 잘리지 않는 큰 소리(0.9) — 클리핑이 아니라 창이 계속 찬다
        f.feed(tone(0.9), blockFrames, clipped = false, times = 251)
        // 40dB 낮춘다. 3.02초 — 칸 근사였다면 앞의 큰 소리가 남는다
        f.feed(tone(0.009), blockFrames, clipped = false, times = 151)

        val got = w.value(f.atNs, Weighting.Z)!!
        val ref = run {
            val e = MultiWeightEngine(fs, TimeWeight.Slow)
            repeat(1000) { e.process(tone(0.009), blockFrames) }
            e.process(tone(0.009), blockFrames).z.currentDbfs.value
        }
        assertTrue(
            "창 밖의 큰 소리가 섞였다: %.2f vs %.2f (차 %.2f dB)".format(got, ref, got - ref),
            abs(got - ref) < 0.1,
        )
    }

    @Test
    fun `아무것도 안 넣으면 값이 없다`() {
        val w = CleanWindow(fs)
        assertNull(w.value(1_000_000_000L, Weighting.A))
        assertFalse(w.ready)
        assertEquals(0L, w.cleanMs)
    }
}
