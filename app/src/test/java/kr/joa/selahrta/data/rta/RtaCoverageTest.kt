package kr.joa.selahrta.data.rta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **10초를 제대로 쟀는가**(독립 검토 PND-03).
 *
 * ## 왜 창 수로는 못 세나
 *
 * FFT 창은 **서로 겹친다.** 4096칸·50% 겹침이면 창 하나가 4096표본을
 * 덮지만 **새로 들어온 것은 2048표본**뿐이다. 「창 수 × 창 길이」로 세면
 * **시간이 두 배로 늘어난다** — 5초를 재고도 10초를 쟀다고 말하게 된다.
 *
 * 그래서 **창이 덮은 구간의 합집합**을 센다.
 */
class RtaCoverageTest {

    private fun power(v: Double) = DoubleArray(31) { v }

    /** 모든 시험은 **셈을 켠 채** 시작한다. 켜는 것 자체는 따로 본다. */
    private fun armed(totalFrames: Long) = RtaCoverage(totalFrames).also { it.arm() }

    /** 겹치는 창 셋. 새로 덮은 것만 세어야 한다. */
    @Test
    fun `겹친 창을 두 번 세지 않는다`() {
        val c = armed(8192)
        c.add(0, 0, 4096, power(1.0))
        c.add(1, 2048, 6144, power(1.0))
        c.add(2, 4096, 8192, power(1.0))

        // 합집합은 0..8192 — 창 길이를 더하면 12288 이 된다.
        assertEquals(8192L, c.coveredFrames)
        assertEquals(1.0, c.coverage, 1e-12)
        assertEquals(3, c.windows)
    }

    /** 창 수만 보면 못 가르는 자리다. */
    @Test
    fun `창 수가 같아도 덮은 구간이 다르면 다르다`() {
        val dense = armed(8192)
        dense.add(0, 0, 4096, power(1.0))
        dense.add(1, 2048, 6144, power(1.0))

        val sparse = armed(8192)
        sparse.add(0, 0, 4096, power(1.0))
        sparse.add(1, 0, 4096, power(1.0)) // 같은 자리를 또 덮었다

        assertEquals(dense.windows, sparse.windows)
        assertTrue(
            "덮은 구간이 다른데 같다고 한다",
            dense.coveredFrames > sparse.coveredFrames,
        )
    }

    /** 끊긴 자리는 **메우지 않는다.** 없는 소리를 있다고 하면 안 된다. */
    @Test
    fun `끊긴 구간은 안 센다`() {
        val c = armed(10_000)
        c.add(0, 0, 2000, power(1.0))
        c.add(1, 6000, 8000, power(1.0))
        assertEquals(4000L, c.coveredFrames)
        assertEquals(0.4, c.coverage, 1e-12)
    }

    /** 구간 밖으로 삐져나간 창은 **겹치는 만큼만** 센다. */
    @Test
    fun `구간 밖은 잘라서 센다`() {
        // **첫 창이 0 이다.** 그 뒤로 10초치를 넘어가는 꼬리는 잘라 낸다 —
        // 측정 구간 밖의 소리를 그 구간에 넣으면 안 된다.
        val c = armed(1000)
        c.add(0, 0, 400, power(1.0))
        c.add(1, 800, 1500, power(1.0)) // 800~1000 만 든다
        assertEquals(600L, c.coveredFrames)
    }

    /** 같은 번호가 두 번 오면 **한 번만** 센다(중복 발행 대비). */
    @Test
    fun `같은 번호는 한 번만 센다`() {
        val c = armed(4096)
        c.add(7, 0, 4096, power(1.0))
        c.add(7, 0, 4096, power(1.0))
        assertEquals(1, c.windows)
    }

    // ── 평균 ────────────────────────────────────────────

    /**
     * **선형 전력으로 모은다.** dB 를 산술평균하면 큰 쪽이 눌린다 —
     * 60dB 와 80dB 의 평균은 70dB 이 아니라 **77.0dB** 다.
     */
    @Test
    fun `선형 전력으로 모은다`() {
        val c = armed(100)
        c.add(0, 0, 50, power(1e-6))   // -60dB
        c.add(1, 50, 100, power(1e-4)) // -40dB
        val mean = c.meanDb(offsetDb = 0.0)!!
        // (1e-6 + 1e-4) / 2 = 5.05e-5 → 10·log10 = -42.967dB.
        //
        // 처음에 -43.0103 이라고 적었다가 틀렸다. 5.05e-5 를 **5e-5 로
        // 반올림해** 머리로 셈한 값이다 — 0.04dB 차이지만, 시험의 기대값은
        // 셈해서 적는 것이지 어림잡는 것이 아니다.
        assertEquals(-42.9671, mean[0], 1e-4)
    }

    /** 한 장도 없으면 **없다고 한다.** 0 을 돌려주면 「아주 작다」로 읽힌다. */
    @Test
    fun `한 장도 없으면 null 이다`() {
        assertEquals(null, armed(100).meanDb(0.0))
        assertEquals(0.0, armed(100).coverage, 0.0)
    }

    /** 유한하지 않은 값은 버린다. 하나가 NaN 이면 평균 전체가 NaN 이 된다. */
    @Test
    fun `유한하지 않은 장은 버린다`() {
        val c = armed(100)
        c.add(0, 0, 50, power(1e-6))
        c.add(1, 50, 100, DoubleArray(31) { Double.NaN })
        assertEquals(1, c.windows)
        assertEquals(-60.0, c.meanDb(0.0)!![0], 1e-9)
    }

    /** 스칼라 보정은 **마지막에 한 번** 건다. */
    @Test
    fun `보정은 마지막에 한 번 건다`() {
        val c = armed(100)
        c.add(0, 0, 100, power(1e-6))
        assertEquals(58.0, c.meanDb(offsetDb = 118.0)!![0], 1e-9)
    }

    // ── 어디부터 세나 ──

    /**
     * **켜기 전의 창은 안 센다.**
     *
     * 안정화 구간의 소리는 평균에 안 들어가므로 coverage 에도
     * 안 들어가야 한다.
     */
    @Test
    fun `켜기 전의 창은 안 센다`() {
        val c = RtaCoverage(totalFrames = 4096)
        c.add(0, 0, 4096, power(1.0))
        assertEquals(0, c.windows)
        c.arm()
        c.add(1, 0, 4096, power(1.0))
        assertEquals(1, c.windows)
    }

    /**
     * **번호가 아무리 커도 책장만큼은 센다.**
     *
     * 분석이 주는 번호는 **엔진이 켜진 뒤로 센 것**이다. 예배
     * 내내 재고 있다가 RTA 한 번을 잡으면 그 번호가 수억이 된다 —
     * 그것을 그대로 쓰면 **아무것도 안 센다.** 기기에서 재 보고 알았다.
     */
    @Test
    fun `예배 내내 재다 시작해도 센다`() {
        val c = armed(4096)
        val far = 48_000L * 3600 // 한 시간째 재고 있는 중
        c.add(0, far, far + 2048, power(1.0))
        c.add(1, far + 2048, far + 4096, power(1.0))
        assertEquals(4096L, c.coveredFrames)
        assertEquals(1.0, c.coverage, 1e-12)
    }
}
