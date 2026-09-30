package kr.joa.selahrta.data.rta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ── 2회차 검토(R2-01·02·03)로 더한 것 ──────────────

    /**
     * **전력 0 을 무한대로 적지 않는다**(독립 검토 R2-01).
     *
     * 새 자리는 `toBandDbfs` 앞이라 **옛 길이 걸던 무음 바닥값을 지나지
     * 않는다.** 디지털 무음이나 전력 0 인 대역이 하나만 있어도
     * `-Infinity` 가 되고, 그 값은 **저장은 성공하는데 다시 열리지 않는다.**
     */
    @Test
    fun `무음은 바닥값으로 적는다`() {
        val c = armed(1000)
        c.add(0, 0, 500, power(0.0))
        val mean = c.meanDb(118.0)!!
        assertTrue("무한대가 나왔다: ${mean[0]}", mean.all { it.isFinite() })
        // 옛 길과 **같은 바닥값**이어야 한다. 임의의 0dB 로 바꾸면
        // 「아주 조용했다」가 「보통 크기였다」가 된다.
        assertEquals(kr.joa.selahrta.dsp.SILENCE_DBFS + 118.0, mean[0], 1e-9)
    }

    /** 한 대역만 0 이어도 나머지는 그대로다. */
    @Test
    fun `한 대역만 무음이어도 나머지는 그대로다`() {
        val c = armed(1000)
        val p = power(1.0).also { it[5] = 0.0 }
        c.add(0, 0, 500, p)
        val mean = c.meanDb(0.0)!!
        assertEquals(kr.joa.selahrta.dsp.SILENCE_DBFS, mean[5], 1e-9)
        assertEquals(0.0, mean[0], 1e-9)
    }

    /**
     * **구간과 한 표본도 겹치지 않는 창은 평균에도 안 들어간다**
     * (독립 검토 R2-02).
     *
     * 예전에는 더하기와 세기를 먼저 하고 자르기는 구간 셈에서만 했다.
     * 그래서 **재기가 끝난 뒤의 큰 소리가 평균을 통째로 끌어올렸다.**
     */
    @Test
    fun `구간 밖의 창은 평균에 안 들어간다`() {
        val c = armed(100)
        c.add(1, 0, 100, power(1e-6))
        c.add(2, 100, 200, power(1.0)) // 완전히 밖
        assertEquals(1, c.windows)
        assertEquals(-60.0, c.meanDb(0.0)!![0], 1e-9)
    }

    /**
     * **닫은 뒤에 온 창은 답을 못 바꾼다**(독립 검토 R2-02).
     *
     * 떼어 내는 명령은 줄에 들어갈 뿐 곧바로 듣지 않는다. 「끝났다」와
     * 「더는 안 들어온다」가 같은 순간이 아니므로 **여기서 닫는다.**
     */
    @Test
    fun `닫은 뒤의 창은 결과를 바꾸지 못한다`() {
        val c = armed(1000)
        c.add(1, 0, 500, power(1e-6))
        val closed = c.close(0.0)
        c.add(2, 500, 1000, power(1.0))

        assertEquals(1, closed.windows)
        assertEquals(-60.0, closed.meanDb!![0], 1e-9)
        // 닫은 뒤의 셈에도 안 들어간다 — 살아 있는 값도 그대로다.
        assertEquals(1, c.windows)
        assertEquals(-60.0, c.meanDb(0.0)!![0], 1e-9)
    }

    /**
     * **평균과 그 곁의 숫자가 같은 순간의 것이다**(독립 검토 R2-03).
     *
     * 따로 읽으면 창 수는 N+1, 합은 N 장의 것일 수 있다. 그러면 저장한
     * 기록을 나중에 가지고 따질 수가 없다.
     */
    @Test
    fun `닫으면 평균과 창 수와 구간이 한 덩어리로 온다`() {
        val c = armed(1000)
        c.add(1, 100, 600, power(1.0))
        c.add(2, 600, 1100, power(1.0))
        val r = c.close(3.0)

        assertEquals(2, r.windows)
        assertEquals(1000L, r.coveredFrames)
        assertEquals(1.0, r.coverage, 1e-12)
        assertEquals(100L, r.originFrame)
        assertEquals(1000L, r.lastWindowEndFrame)
        assertEquals(3.0, r.meanDb!![0], 1e-9)
    }

    /** 한 장도 못 받고 닫으면 평균은 null 이다 — 0 으로 채우지 않는다. */
    @Test
    fun `한 장도 없이 닫으면 평균이 없다`() {
        val r = armed(1000).close(0.0)
        assertNull(r.meanDb)
        assertEquals(0, r.windows)
        assertEquals(0.0, r.coverage, 1e-12)
    }

    /**
     * **시작에서 놓친 시간이 드러난다**(독립 검토 R2-02).
     *
     * 켜고 나서 첫 창이 오기까지 걸린 시간을 **원점째로 밀면** 그 지연이
     * 사라진다 — 반쯤 놓친 측정이 「10초를 다 덮었다」로 보인다.
     * 번호를 박아 두면 앞이 비어 보이고, **그것이 사실이다.**
     */
    @Test
    fun `원점을 박으면 늦게 시작한 만큼 비어 보인다`() {
        val c = RtaCoverage(4096).also { it.armAt(1000) }
        // 첫 창이 한 hop 늦게 왔다. 원점 기준 2048 부터다.
        c.add(0, 3048, 7144, power(1.0))
        assertEquals(2048L, c.coveredFrames)
        assertEquals(0.5, c.coverage, 1e-12)
    }

    /**
     * **첫 창에서 원점을 잡으면 그 지연이 감춰진다** — 같은 입력을 옛
     * 방식으로 받아 견준다. 이 대조가 없으면 위 시험이 무엇을 막는지
     * 알 수 없다.
     */
    @Test
    fun `첫 창에서 원점을 잡으면 지연이 감춰진다`() {
        val c = armed(4096) // arm() — 원점을 첫 창에서 잡는다
        c.add(0, 3048, 7144, power(1.0))
        assertEquals(4096L, c.coveredFrames)
        assertEquals("감춰지지 않는다면 두 방식이 같다는 뜻이다", 1.0, c.coverage, 1e-12)
    }

    /** 두 번 켜도 처음 자리가 남는다 — 「될 때까지 다시」 부를 수 있어야 한다. */
    @Test
    fun `두 번 켜도 원점은 처음 것이다`() {
        val c = RtaCoverage(4096)
        c.armAt(1000)
        c.armAt(9999)
        c.add(0, 3048, 7144, power(1.0))
        assertEquals(2048L, c.coveredFrames)
    }
}
