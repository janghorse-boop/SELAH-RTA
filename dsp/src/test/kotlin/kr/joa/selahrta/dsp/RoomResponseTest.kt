package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * 방·PA 응답(FR) 셈.
 *
 * **모양을 되찾는지**를 본다. 아는 기울기를 넣고 그 기울기가 그대로
 * 나오는지 보는 식이다 — 그림이 그럴듯하게 나오는 것과 값이 맞는 것은
 * 다른 일이고, 여기서 틀리면 담당자가 그 곡선을 보고 EQ 를 만진다.
 */
class RoomResponseTest {

    private val n = ThirdOctave.BAND_COUNT

    /** 모든 밴드가 같은 레벨인 장. 「평평한 방」이다. */
    private fun flat(db: Double) = DoubleArray(n) { db }

    /** 아주 조용한 배경. 어느 밴드든 신호가 훨씬 크다. */
    private fun quiet(db: Double = 0.0) = DoubleArray(n) { db }

    private fun frames(count: Int, f: (Int) -> DoubleArray) = List(count) { f(it) }

    @Test
    fun `평평한 방은 평평하게 나온다`() {
        val r = computeRoomResponse(frames(60) { flat(70.0) }, quiet(20.0)).getOrThrow()
        for (i in 0 until n) {
            assertEquals("밴드 $i 는 0dB 이어야 한다", 0.0, r.relativeDb[i], 1e-9)
        }
        assertEquals("버린 장이 없어야 한다", 0, r.framesDropped)
        assertEquals(60, r.framesUsed)
    }

    /**
     * **절대 레벨이 달라져도 모양은 같다.**
     *
     * 소리를 얼마나 크게 틀었는지에 따라 곡선이 통째로 오르내리면 두 번
     * 잰 것을 견줄 수 없다. 정규화가 실제로 그 일을 하는지 본다.
     */
    @Test
    fun `크기를 바꿔도 모양은 그대로다`() {
        fun shaped(base: Double) = DoubleArray(n) { base + it * 0.5 }
        val a = computeRoomResponse(frames(60) { shaped(60.0) }, quiet(0.0)).getOrThrow()
        val b = computeRoomResponse(frames(60) { shaped(80.0) }, quiet(0.0)).getOrThrow()
        for (i in 0 until n) {
            assertEquals(
                "20dB 을 더 틀어도 밴드 $i 의 모양은 같아야 한다",
                a.relativeDb[i],
                b.relativeDb[i],
                1e-9,
            )
        }
    }

    /**
     * **아는 기울기를 되찾는다.**
     *
     * 옥타브당 −6dB 으로 기울인 방을 넣는다. 1/3 옥타브는 한 밴드가
     * 옥타브의 1/3 이므로 밴드당 −2dB 이어야 한다.
     */
    @Test
    fun `옥타브당 6dB 기울기를 되찾는다`() {
        val perBand = -2.0
        val r = computeRoomResponse(
            frames(60) { DoubleArray(n) { i -> 80.0 + i * perBand } },
            quiet(-60.0),
        ).getOrThrow()

        for (i in 1 until n) {
            assertEquals(
                "이웃 밴드 사이의 기울기가 ${perBand}dB 이어야 한다",
                perBand,
                r.relativeDb[i] - r.relativeDb[i - 1],
                1e-9,
            )
        }
    }

    /**
     * **기준 대역이 실제로 0dB 이다.**
     *
     * 250Hz~4kHz 의 에너지 평균이 0 이어야 한다 — 「중역을 0 으로 맞췄다」는
     * 말이 사실인지 확인한다.
     */
    @Test
    fun `기준 대역의 에너지 평균이 0dB 이다`() {
        val r = computeRoomResponse(
            frames(60) { DoubleArray(n) { i -> 70.0 + (i % 5) * 3.0 } },
            quiet(0.0),
        ).getOrThrow()

        var power = 0.0
        var count = 0
        for (i in 0 until n) {
            val hz = ThirdOctave.exactCenter(i)
            if (hz < ROOM_REF_LOW_HZ || hz > ROOM_REF_HIGH_HZ) continue
            power += Math.pow(10.0, r.relativeDb[i] / 10.0)
            count++
        }
        assertEquals("기준 대역 평균은 0dB", 0.0, 10.0 * Math.log10(power / count), 1e-9)
        assertEquals("쓴 밴드 수가 기록돼야 한다", count, r.referenceBands)
    }

    /**
     * **배경에 묻힌 밴드는 못 쓴다고 표시한다.**
     *
     * 저역만 배경이 높은 상황을 만든다 — 예배당의 공조기가 그렇다.
     */
    @Test
    fun `배경에 묻힌 밴드를 가려낸다`() {
        // 20~100Hz(밴드 0~7)만 배경이 신호 바로 아래까지 올라와 있다.
        val q = DoubleArray(n) { if (it < 8) 68.0 else 10.0 }
        val r = computeRoomResponse(frames(60) { flat(70.0) }, q).getOrThrow()

        for (i in 0 until 8) {
            assertFalse("밴드 $i 는 배경에 묻혀 못 쓴다", r.usable[i])
            assertEquals("SNR 은 2dB", 2.0, r.snrDb[i], 1e-9)
        }
        for (i in 8 until n) {
            assertTrue("밴드 $i 는 쓸 수 있다", r.usable[i])
        }
    }

    /**
     * **배경이 없으면 셈하지 않는다.**
     *
     * 어느 밴드를 믿어도 되는지 가릴 근거가 없는데 곡선을 내놓으면,
     * 공조기 소리를 방의 저역 부스트로 읽게 된다.
     */
    @Test
    fun `배경을 안 재면 막는다`() {
        val e = computeRoomResponse(frames(60) { flat(70.0) }, null).exceptionOrNull()
        assertTrue(e is ResponseBlocked)
        assertEquals(ResponseBlock.NoQuiet, (e as ResponseBlocked).block)
    }

    /** 장이 모자라면 막는다. */
    @Test
    fun `장이 모자라면 막는다`() {
        val e = computeRoomResponse(frames(5) { flat(70.0) }, quiet()).exceptionOrNull()
        assertEquals(ResponseBlock.NotEnoughFrames, (e as ResponseBlocked).block)
    }

    /**
     * **기준 대역이 통째로 묻히면 막는다.**
     *
     * 그때 0dB 으로 삼을 것이 없으니, 억지로 정규화하면 곡선 전체가
     * 뜻 없는 자리에 놓인다.
     */
    @Test
    fun `기준 대역이 묻히면 막는다`() {
        val q = DoubleArray(n) { 69.0 } // 신호 70dB 바로 아래
        val e = computeRoomResponse(frames(60) { flat(70.0) }, q).exceptionOrNull()
        assertEquals(ResponseBlock.NotEnoughReferenceBands, (e as ResponseBlocked).block)
    }

    /**
     * **문 닫히는 소리 한 장은 곡선을 흔들지 못한다.**
     *
     * 전력 평균이라 한 장만 크게 튀어도 그쪽으로 끌려간다. 걸러 내는지
     * 본다 — 걸러 내지 않으면 20dB 튄 장 하나가 60장 평균을 1.3dB 올린다.
     */
    @Test
    fun `튄 장 하나는 버린다`() {
        val withBang = frames(60) { i ->
            if (i == 30) flat(90.0) else flat(70.0)
        }
        val r = computeRoomResponse(withBang, quiet(0.0)).getOrThrow()
        assertEquals("튄 장은 버려야 한다", 1, r.framesDropped)
        assertEquals(59, r.framesUsed)
        for (i in 0 until n) {
            assertEquals("남은 장의 평균 그대로여야 한다", 70.0, r.bandsDb[i], 1e-9)
        }
    }

    /**
     * **흔들리는 밴드는 흔들린다고 적는다.**
     *
     * 값 하나만 보면 2dB 흔들리는 밴드와 얌전한 밴드를 구별할 수 없다.
     */
    @Test
    fun `밴드별 흔들림을 남긴다`() {
        val rng = Random(1234)
        // 밴드 20 만 ±3dB 흔들리고 나머지는 얌전하다.
        val fr = frames(120) {
            DoubleArray(n) { i -> if (i == 20) 70.0 + rng.nextDouble(-3.0, 3.0) else 70.0 }
        }
        val r = computeRoomResponse(fr, quiet(0.0)).getOrThrow()
        assertTrue(
            "흔들리는 밴드의 표준편차가 커야 한다: ${r.stdevDb[20]}",
            r.stdevDb[20] > 1.0,
        )
        assertTrue(
            "얌전한 밴드는 거의 0 이어야 한다: ${r.stdevDb[10]}",
            abs(r.stdevDb[10]) < 1e-6,
        )
    }

    /** 쓸 만한 밴드만으로 폭을 잰다 — 묻힌 밴드가 폭을 부풀리면 안 된다. */
    @Test
    fun `폭은 쓸 만한 밴드로만 잰다`() {
        // 밴드 0 이 30dB 낮지만 배경에 묻혀 못 쓴다.
        val sig = DoubleArray(n) { if (it == 0) 40.0 else 70.0 }
        val q = DoubleArray(n) { if (it == 0) 39.0 else 10.0 }
        val r = computeRoomResponse(frames(60) { sig }, q).getOrThrow()
        assertFalse(r.usable[0])
        assertEquals("묻힌 밴드를 빼면 평평하다", 0.0, r.spanDb, 1e-9)
    }
}
