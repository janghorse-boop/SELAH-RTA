package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 명세 15장 「1/3 octave band mapping」. */
class ThirdOctaveTest {

    @Test
    fun `밴드는 31개이고 명세의 중심주파수와 같다`() {
        assertEquals(31, ThirdOctave.BAND_COUNT)
        assertEquals(20.0, ThirdOctave.CENTERS_HZ.first(), 0.0)
        assertEquals(1000.0, ThirdOctave.CENTERS_HZ[17], 0.0)
        assertEquals(20000.0, ThirdOctave.CENTERS_HZ.last(), 0.0)
    }

    @Test
    fun `정확한 중심은 1kHz 를 기준으로 10의 n십분의일 제곱이다`() {
        assertEquals(1000.0, ThirdOctave.exactCenter(17), 1e-9)
        // 한 밴드 위는 정확히 10^(1/10) 배다.
        assertEquals(1258.925, ThirdOctave.exactCenter(18), 1e-3)
        // 열 밴드(= 1옥타브 세 번) 위는 정확히 10배다.
        assertEquals(10000.0, ThirdOctave.exactCenter(27), 1e-6)
    }

    @Test
    fun `호칭값과 정확값은 가깝지만 같지 않다`() {
        // 31.5Hz 는 실제로 31.623Hz 다. 이 차이를 알고 쓰는 것과
        // 모르고 섞어 쓰는 것은 다르다.
        assertEquals(31.5, ThirdOctave.CENTERS_HZ[2], 0.0)
        assertEquals(31.623, ThirdOctave.exactCenter(2), 1e-3)
        for (i in 0 until ThirdOctave.BAND_COUNT) {
            val nominal = ThirdOctave.CENTERS_HZ[i]
            val exact = ThirdOctave.exactCenter(i)
            val errorPercent = kotlin.math.abs(exact - nominal) / nominal * 100.0
            assertTrue("밴드 $i: 호칭 $nominal vs 정확 $exact", errorPercent < 2.0)
        }
    }

    @Test
    fun `이웃 밴드의 경계가 정확히 맞물린다`() {
        // 틈이 있으면 그만큼의 에너지가 어느 밴드에도 안 잡히고,
        // 겹치면 같은 에너지가 두 번 세어진다. 둘 다 총합을 틀리게 한다.
        for (i in 0 until ThirdOctave.BAND_COUNT - 1) {
            assertEquals(
                "밴드 $i 위끝과 ${i + 1} 아래끝",
                ThirdOctave.upperEdge(i),
                ThirdOctave.lowerEdge(i + 1),
                1e-9,
            )
        }
    }

    @Test
    fun `경계는 중심을 사이에 두고 1대3 옥타브 폭이다`() {
        for (i in 0 until ThirdOctave.BAND_COUNT) {
            val c = ThirdOctave.exactCenter(i)
            assertTrue(ThirdOctave.lowerEdge(i) < c)
            assertTrue(c < ThirdOctave.upperEdge(i))
            // 위끝/아래끝 = 10^(1/10) ≈ 1.2589 — 한 밴드가 1/3 옥타브다.
            assertEquals(1.258925, ThirdOctave.upperEdge(i) / ThirdOctave.lowerEdge(i), 1e-5)
        }
    }

    @Test
    fun `표시 문자열은 콘솔 EQ 와 같은 표기를 쓴다`() {
        assertEquals("20", ThirdOctave.label(0))
        assertEquals("31.5", ThirdOctave.label(2))
        assertEquals("1k", ThirdOctave.label(17))
        assertEquals("1.25k", ThirdOctave.label(18))
        assertEquals("20k", ThirdOctave.label(30))
    }

    @Test
    fun `범위를 벗어난 밴드는 거부한다`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ThirdOctave.exactCenter(-1)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ThirdOctave.exactCenter(31)
        }
    }
}
