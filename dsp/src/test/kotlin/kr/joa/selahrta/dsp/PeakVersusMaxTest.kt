package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **PEAK 와 MAX 가 서로 다른 것을 재고 있는가**(2026-09-25 검토안 5장).
 *
 * 검토안이 이렇게 적었다:
 *
 * > MAX 84.8 dBA 와 PEAK 113.9 dB 사이에 약 29 dB 의 차이가 있다. … 차이가
 * > 상당히 크므로 개발 단계에서 PEAK 계산 로직을 검증하는 것이 좋다.
 *
 * 맞는 요구다. 다만 **크다는 것만으로는 틀렸다는 근거가 못 된다** — 두
 * 값은 정의가 달라서 벌어지는 것이 정상이다. 그러니 「얼마나 벌어져야
 * 정상인가」를 이 파일이 숫자로 못박는다. 검토안이 확인하라고 적은 네
 * 가지를 그대로 시험으로 옮겼다:
 *
 * 1. PEAK 가 PCM 표본의 **실제 최대 진폭**을 기준으로 셈되는가
 * 2. dBFS → SPL 변환이 올바른가
 * 3. 보정값이 PEAK 에도 **같은 원칙**으로 걸리는가
 * 4. MAX 와 PEAK 의 **시간 특성·가중치**가 의도대로 갈라져 있는가
 *
 * 이 시험들이 통과하는 한 29dB 차이는 고장이 아니다. 깨지면 그때는 정말
 * 고장이다.
 */
class PeakVersusMaxTest {

    private val fs = 48_000

    private fun engine(w: Weighting = Weighting.Z, t: TimeWeight = TimeWeight.Fast) =
        SplEngine(fs, w, t)

    private fun tone(f: Double, amp: Double, ms: Int): FloatArray {
        val n = fs * ms / 1000
        val d = SignalGenerator.sine(f, fs, n, amp)
        return FloatArray(n) { d[it].toFloat() }
    }

    /**
     * **1. PEAK 는 진폭의 최대, MAX 는 RMS 다.**
     *
     * 사인파의 파고율은 √2 이고 20·log10(√2) = 3.0103dB 다. Z 가중·정상
     * 상태에서 둘의 차이가 정확히 그 값이어야 한다 — 하나라도 다른 것을
     * 재고 있으면 이 숫자가 안 나온다.
     */
    @Test
    fun `풀스케일 사인파에서 PEAK 는 MAX 보다 3_01dB 높다`() {
        val f = engine().let { it.process(tone(1000.0, 1.0, 2000).let { x -> x }, fs * 2) }
        assertEquals("PEAK 는 풀스케일이므로 0 dBFS", 0.0, f.peakDbfs.value, 0.01)
        assertEquals("MAX 는 RMS 라 −3.01 dBFS", -3.0103, f.maxDbfs.value, 0.05)
        assertEquals(
            "둘의 차이는 사인파의 파고율",
            3.0103,
            f.peakDbfs.value - f.maxDbfs.value,
            0.06,
        )
    }

    /** 진폭을 반으로 줄이면 **둘 다** 6dB 내려간다. 한쪽만 움직이면 틀린 것이다. */
    @Test
    fun `진폭을 반으로 줄이면 PEAK 와 MAX 가 함께 6dB 내려간다`() {
        val full = engine().process(tone(1000.0, 1.0, 1000), fs)
        val half = engine().process(tone(1000.0, 0.5, 1000), fs)
        assertEquals("PEAK", -6.02, half.peakDbfs.value - full.peakDbfs.value, 0.05)
        assertEquals("MAX", -6.02, half.maxDbfs.value - full.maxDbfs.value, 0.05)
    }

    /**
     * **4. 가중은 MAX 만 깎고 PEAK 는 건드리지 않는다.**
     *
     * 이것이 두 값이 벌어지는 **첫째** 까닭이다. 125Hz 순음에서 A 가중은
     * 16dB 을 깎지만 PEAK 는 꿈쩍도 하지 않는다 — PEAK 는 가중 **전**
     * 파형에서 재기 때문이다(클리핑은 입력단의 사건이라 그래야 한다).
     *
     * 예배당의 배경에는 공조기·발소리 같은 저역이 늘 깔려 있다. 그 저역이
     * PEAK 에는 그대로 들어가고 dBA 에는 거의 안 들어간다.
     */
    @Test
    fun `A 가중은 MAX 만 깎고 PEAK 는 그대로다`() {
        val x = tone(125.0, 0.5, 3000)
        val z = engine(Weighting.Z).process(x, x.size)
        val a = engine(Weighting.A).process(x, x.size)

        assertEquals("PEAK 는 가중 전이라 같아야 한다", z.peakDbfs.value, a.peakDbfs.value, 1e-9)
        assertEquals("MAX 는 A 가중만큼 깎인다", -16.1, a.maxDbfs.value - z.maxDbfs.value, 0.3)

        val gapZ = z.peakDbfs.value - z.maxDbfs.value
        val gapA = a.peakDbfs.value - a.maxDbfs.value
        println("[PEAK-MAX] 125Hz 순음 · Z 에서 %.1fdB · A 에서 %.1fdB".format(gapZ, gapA))
        assertTrue("A 에서 차이가 더 벌어져야 한다", gapA > gapZ + 15.0)
    }

    /**
     * **4. 시간가중이 PEAK 를 따라오지 못한다.**
     *
     * 이것이 벌어지는 **둘째** 까닭이고, 보통 더 크다. Fast 는 125ms 로
     * 지수평균하므로 1ms 짜리 충격음은 통째로 눌린다. PEAK 는 표본 하나만
     * 커도 그 값을 그대로 받는다.
     *
     * 박수 한 번, 마이크를 스치는 소리, 드럼 타격이 전부 여기 해당한다.
     */
    @Test
    fun `짧은 충격음에서 PEAK 와 MAX 가 크게 벌어진다`() {
        // 조용한 배경 위에 1ms 짜리 풀스케일 충격을 하나 얹는다.
        val n = fs * 2
        val x = FloatArray(n) { 0.001f }
        val hit = fs / 1000 // 1ms
        for (i in 0 until hit) x[fs + i] = 1.0f

        val f = engine(Weighting.Z).process(x, n)
        val gap = f.peakDbfs.value - f.maxDbfs.value
        println(
            "[PEAK-MAX] 1ms 충격 · PEAK %.1f dBFS · MAX %.1f dBFS · 차이 %.1fdB"
                .format(f.peakDbfs.value, f.maxDbfs.value, gap),
        )
        assertEquals("PEAK 는 충격을 그대로 받는다", 0.0, f.peakDbfs.value, 0.01)
        assertTrue("Fast 가 눌러 10dB 넘게 벌어져야 한다: $gap", gap > 10.0)
    }

    /**
     * **예배당에서 실제로 나오는 차이를 재 본다.**
     *
     * 저역이 실린 배경 + 말소리 대역 + 이따금 튀는 충격. 담당자가 본
     * 25~29dB 이 이 조합에서 나오는 값인지 숫자로 확인한다.
     *
     * **판정하지 않고 찍는다**(probe). 「정상 범위」를 못박으면 다음 사람이
     * 그 숫자를 규격으로 읽는다.
     */
    @Test
    fun `예배당을 흉내 낸 신호의 PEAK-MAX 차이`() {
        val n = fs * 3
        val rng = kotlin.random.Random(20260925)
        val x = FloatArray(n) { i ->
            val t = i.toDouble() / fs
            // 공조기 저역 + 말소리 대역 + 잡음
            val low = 0.08 * kotlin.math.sin(2 * Math.PI * 63.0 * t)
            val mid = 0.05 * kotlin.math.sin(2 * Math.PI * 500.0 * t)
            val noise = (rng.nextDouble() - 0.5) * 0.01
            (low + mid + noise).toFloat()
        }
        // 2초 자리에 박수 한 번(2ms).
        for (i in 0 until fs / 500) x[fs * 2 + i] = 0.9f

        val a = engine(Weighting.A).process(x, n)
        println(
            "[PEAK-MAX] 예배당 흉내 · PEAK %.1f dBFS · MAX %.1f dBFS · 차이 %.1fdB"
                .format(a.peakDbfs.value, a.maxDbfs.value, a.peakDbfs.value - a.maxDbfs.value),
        )
    }

    /**
     * **2·3. dBFS → SPL 변환과 보정값이 PEAK 에도 같은 원칙으로 걸린다.**
     *
     * 보정값은 「dBFS 를 dB SPL 로 옮기는 상수」라 어느 지표에든 똑같이
     * 더해진다. 그래서 **보정을 걸어도 PEAK−MAX 차이는 변하지 않는다** —
     * 변한다면 한쪽에만 걸렸거나 곱셈으로 걸린 것이다.
     */
    @Test
    fun `보정값은 PEAK 와 MAX 에 똑같이 걸린다`() {
        val f = engine().process(tone(1000.0, 1.0, 1000), fs)
        val offset = CalibrationOffset(94.0)

        val peakSpl = f.peakDbfs.toSpl(offset).value
        val maxSpl = f.maxDbfs.toSpl(offset).value

        assertEquals("PEAK 에 그대로 더해진다", f.peakDbfs.value + 94.0, peakSpl, 1e-9)
        assertEquals("MAX 에도 그대로 더해진다", f.maxDbfs.value + 94.0, maxSpl, 1e-9)
        assertEquals(
            "보정을 걸어도 둘의 차이는 그대로다",
            f.peakDbfs.value - f.maxDbfs.value,
            peakSpl - maxSpl,
            1e-9,
        )
    }

    /**
     * **PEAK 가 풀스케일에 닿으면 그 값은 측정값이 아니라 하한이다.**
     *
     * 화면이 「≥」를 붙이는 근거다. 잘린 뒤에는 실제 음압이 얼마나 더
     * 높았는지 알 길이 없다 — 이것이 PEAK 를 화면에서 치우면 안 되는
     * 까닭이기도 하다. 잘렸다는 사실을 말해 주는 지표가 이것뿐이다.
     */
    @Test
    fun `잘리면 잘렸다고 표시한다`() {
        val n = fs / 10
        val x = FloatArray(n) { 0.01f }
        x[n / 2] = 1.0f

        val f = engine().process(x, n)
        assertTrue("풀스케일이면 잘림으로 본다", f.peakClipped)
        assertEquals(0.0, f.peakDbfs.value, 0.01)

        val quiet = engine().process(FloatArray(n) { 0.01f }, n)
        assertTrue("작으면 잘림이 아니다", !quiet.peakClipped)
    }
}

/**
 * **MIN 이 시작 구간으로 굳지 않는가**(2026-09-25 담당자 지시로 MIN 을
 * 넣으며 만든 시험).
 *
 * 시간가중은 0 에서 올라온다. 그 구간을 세면 MIN 은 **늘 시작 직후의 값**이
 * 되고, 이후 아무리 조용해도 바뀌지 않는다 — 변하지 않는 값은 지표가
 * 아니다. 그래서 `settled` 뒤부터만 센다.
 */
class MinLevelTest {

    private val fs = 48_000

    private fun engine(w: Weighting = Weighting.Z, t: TimeWeight = TimeWeight.Fast) =
        SplEngine(fs, w, t)

    private fun tone(f: Double, amp: Double, ms: Int): FloatArray {
        val n = fs * ms / 1000
        val d = SignalGenerator.sine(f, fs, n, amp)
        return FloatArray(n) { d[it].toFloat() }
    }

    /** 자리를 잡기 전에는 MIN 이 없다. 「모르는 값」을 0 으로 적지 않는다. */
    @Test
    fun `자리를 잡기 전에는 MIN 이 없다`() {
        // Fast 의 τ 는 125ms, 자리 잡는 데 그 3배가 든다. 100ms 만 넣는다.
        val f = engine().process(tone(1000.0, 0.5, 100), fs / 10)
        assertTrue("아직 자리를 안 잡았다", !f.settled)
        assertTrue("MIN 은 아직 없어야 한다", f.minDbfs == null)
    }

    /**
     * **시작 구간이 MIN 을 잡아먹지 않는다.**
     *
     * 앞은 크고 뒤는 작은 신호를 넣는다. 올바르면 MIN 은 **뒤의 작은
     * 구간**이다. 시작 구간을 셌다면 0 에 가까운 값이 나온다.
     */
    @Test
    fun `MIN 은 뒤의 조용한 구간을 잡는다`() {
        val loud = tone(1000.0, 0.5, 2000)
        val quiet = tone(1000.0, 0.05, 2000) // 20dB 아래
        val x = FloatArray(loud.size + quiet.size)
        loud.copyInto(x)
        quiet.copyInto(x, loud.size)

        val f = engine().process(x, x.size)
        val min = f.minDbfs!!.value
        val max = f.maxDbfs.value
        println("[MIN] 큰 구간 → 작은 구간 · MAX %.1f · MIN %.1f dBFS".format(max, min))

        // 작은 구간의 정상 상태는 −0.5 진폭 대비 20dB 아래다.
        assertEquals("MIN 은 뒤 구간의 레벨이어야 한다", max - 20.0, min, 1.0)
    }

    /** 조용하다가 커지면 MIN 은 앞의 조용한 구간에 머문다. */
    @Test
    fun `커진 뒤에도 MIN 은 조용했던 값을 지킨다`() {
        val quiet = tone(1000.0, 0.05, 2000)
        val loud = tone(1000.0, 0.5, 2000)
        val x = FloatArray(quiet.size + loud.size)
        quiet.copyInto(x)
        loud.copyInto(x, quiet.size)

        val f = engine().process(x, x.size)
        assertEquals("MIN 은 앞 구간", f.maxDbfs.value - 20.0, f.minDbfs!!.value, 1.0)
    }

    /** MIN 은 MAX 를 넘지 않는다 — 같은 시간가중 레벨을 양끝에서 본 값이다. */
    @Test
    fun `MIN 은 MAX 보다 크지 않다`() {
        val f = engine().process(tone(1000.0, 0.3, 3000), fs * 3)
        assertTrue("MIN ≤ MAX", f.minDbfs!!.value <= f.maxDbfs.value + 1e-9)
    }
}
