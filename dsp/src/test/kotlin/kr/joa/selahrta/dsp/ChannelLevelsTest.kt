package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **어느 채널에 소리가 들어오는가**(USB 오디오 지시서 5·6장).
 *
 * 4채널 인터페이스를 꽂았을 때 마이크가 몇 번에 꽂혀 있는지 알려면,
 * 채널을 하나씩 골라 가며 레벨이 움직이는지 봐야 했다. 채널마다 레벨을
 * 함께 재면 한 화면에서 갈린다.
 *
 * ## 고른 채널만 재는 것과 다르다
 *
 * 측정에 쓰는 것은 **고른 채널 하나**다. 여기서 내는 값은 「어디에
 * 꽂혀 있나」를 찾는 데만 쓴다 — 음압이 아니라 dBFS 이고, 보정도 걸지
 * 않는다.
 */
class ChannelLevelsTest {

    /**
     * 재는 여유(dB).
     *
     * **1e-9 로 뒀다가 깨졌다.** 버퍼가 `Float` 라 0.5 를 넣었다 꺼내면
     * 소수점 일곱째 자리쯤이 달라지고, dB 로 옮기면 1e-7 쯤 어긋난다.
     * 구현의 잘못이 아니라 시험의 여유가 좁았던 것이다 — 1e-4 dB 도
     * 뜻 있는 차이보다 한참 작다.
     */
    private val TOL = 1e-4

    /** `[ch0f0, ch1f0, ch0f1, ch1f1, ...]` 꼴로 엮는다. */
    private fun interleave(vararg channels: DoubleArray): Pair<FloatArray, Int> {
        val ch = channels.size
        val frames = channels[0].size
        require(channels.all { it.size == frames })
        val out = FloatArray(frames * ch)
        for (f in 0 until frames) {
            for (c in 0 until ch) out[f * ch + c] = channels[c][f].toFloat()
        }
        return out to out.size
    }

    @Test
    fun `채널마다 제 peak 을 낸다`() {
        val (buf, n) = interleave(
            doubleArrayOf(0.5, -0.5, 0.25),
            doubleArrayOf(0.1, -0.05, 0.02),
        )
        val lv = ChannelLevels(2)
        lv.update(buf, n)
        assertEquals(dbfs(0.5), lv.peakDbfs[0], TOL)
        assertEquals(dbfs(0.1), lv.peakDbfs[1], TOL)
    }

    /** **부호를 잃지 않는다.** 절대값을 봐야 음의 봉우리도 잡힌다. */
    @Test
    fun `음의 봉우리도 peak 으로 잡는다`() {
        val (buf, n) = interleave(doubleArrayOf(0.1, -0.9, 0.2))
        val lv = ChannelLevels(1)
        lv.update(buf, n)
        assertEquals(dbfs(0.9), lv.peakDbfs[0], TOL)
    }

    @Test
    fun `채널마다 제 RMS 를 낸다`() {
        // 한쪽은 ±0.5 사각파(RMS 0.5), 다른 쪽은 무음.
        val (buf, n) = interleave(
            doubleArrayOf(0.5, -0.5, 0.5, -0.5),
            doubleArrayOf(0.0, 0.0, 0.0, 0.0),
        )
        val lv = ChannelLevels(2)
        lv.update(buf, n)
        assertEquals(dbfs(0.5), lv.rmsDbfs[0], TOL)
        assertEquals(SILENCE_FLOOR_DBFS, lv.rmsDbfs[1], TOL)
    }

    /**
     * **이것이 이 파일의 한가운데다.** 채널이 한 칸 밀리면 「3번에 꽂혀
     * 있다」고 적어 놓고 실제로는 2번을 재게 된다.
     */
    @Test
    fun `한 채널에만 소리가 있으면 그 채널만 큰다`() {
        val quiet = DoubleArray(8) { 0.0 }
        val loud = DoubleArray(8) { if (it % 2 == 0) 0.4 else -0.4 }
        val (buf, n) = interleave(quiet, quiet, loud, quiet)

        val lv = ChannelLevels(4)
        lv.update(buf, n)

        assertEquals(dbfs(0.4), lv.peakDbfs[2], TOL)
        listOf(0, 1, 3).forEach {
            assertEquals("채널 $it 에 없던 소리가 생겼다", SILENCE_FLOOR_DBFS, lv.peakDbfs[it], TOL)
        }
    }

    /**
     * **덩어리마다 새로 잰다.** 쌓으면 한 번 크게 난 소리가 계속 남아
     * 「지금 소리가 들어온다」로 읽힌다.
     */
    @Test
    fun `앞 덩어리의 값이 남지 않는다`() {
        val lv = ChannelLevels(1)
        val (loud, n1) = interleave(doubleArrayOf(0.9, -0.9))
        lv.update(loud, n1)
        val (quiet, n2) = interleave(doubleArrayOf(0.001, -0.001))
        lv.update(quiet, n2)
        assertEquals(dbfs(0.001), lv.peakDbfs[0], TOL)
    }

    /** 읽은 것이 없으면 **조용한 것이 아니라 잰 것이 없다.** */
    @Test
    fun `빈 덩어리는 바닥으로 둔다`() {
        val lv = ChannelLevels(2)
        lv.update(FloatArray(8), 0)
        lv.peakDbfs.forEach { assertEquals(SILENCE_FLOOR_DBFS, it, TOL) }
        lv.rmsDbfs.forEach { assertEquals(SILENCE_FLOOR_DBFS, it, TOL) }
    }

    /**
     * **덜 찬 덩어리를 그대로 받는다.**
     *
     * `AudioRecord` 는 바란 만큼 못 줄 때가 있다. 남은 자리에 든 옛 값을
     * 함께 세면 없던 소리가 생긴다.
     */
    @Test
    fun `읽은 만큼만 센다`() {
        val buf = FloatArray(8)
        // 앞 4개(두 프레임)만 실제 값, 뒤는 옛 쓰레기라고 친다.
        buf[0] = 0.2f; buf[1] = 0.0f
        buf[2] = 0.2f; buf[3] = 0.0f
        buf[4] = 0.9f; buf[5] = 0.9f
        buf[6] = 0.9f; buf[7] = 0.9f

        val lv = ChannelLevels(2)
        lv.update(buf, 4)
        assertEquals(dbfs(0.2), lv.peakDbfs[0], TOL)
        assertEquals(SILENCE_FLOOR_DBFS, lv.peakDbfs[1], TOL)
    }

    /** 프레임에 딱 안 떨어지는 길이는 **온전한 프레임까지만** 본다. */
    @Test
    fun `반쯤 걸친 프레임은 세지 않는다`() {
        val buf = floatArrayOf(0.2f, 0.3f, 0.9f)
        val lv = ChannelLevels(2)
        lv.update(buf, 3)
        assertEquals(dbfs(0.2), lv.peakDbfs[0], TOL)
        assertEquals(dbfs(0.3), lv.peakDbfs[1], TOL)
    }

    @Test
    fun `채널 수가 0 이하면 만들 수 없다`() {
        listOf(0, -1).forEach { n ->
            var threw = false
            try {
                ChannelLevels(n)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("채널 수 $n 을 받아들였다", threw)
        }
    }

    /** 1.0 을 넘는 값이 와도 셈이 무너지지 않아야 한다(0dBFS 위). */
    @Test
    fun `풀스케일을 넘겨도 유한하다`() {
        val (buf, n) = interleave(doubleArrayOf(1.5, -1.5))
        val lv = ChannelLevels(1)
        lv.update(buf, n)
        assertTrue(lv.peakDbfs[0].isFinite())
        assertTrue("0dBFS 위여야 한다: ${lv.peakDbfs[0]}", lv.peakDbfs[0] > 0.0)
    }
}
