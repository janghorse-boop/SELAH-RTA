package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 스펙트로그램 — 지난 스펙트럼 장들을 쌓아 **시간을 보이게** 한다.
 *
 * 여기서 지켜야 할 것은 둘이다. **밀려난 장을 돌려주지 말 것**(가로축의
 * 「−30초」가 틀리면 언제 났는지를 잘못 읽는다), 그리고 **색이 레벨을
 * 뒤집지 말 것**(큰 소리가 덜 눈에 띄면 그림이 거짓말을 한다).
 */
class SpectrogramTest {

    @Test
    fun `방금 넣은 장이 가장 앞이다`() {
        val t = SpectrogramTimeline(capacity = 4)
        t.push(100L)
        t.push(200L)

        assertEquals(2, t.size)
        assertEquals(200L, t.timeAt(0))
        assertEquals(100L, t.timeAt(1))
        assertEquals("화면에 든 폭", 100L, t.spanMs)
    }

    @Test
    fun `가득 차면 가장 오래된 것부터 밀려난다`() {
        val t = SpectrogramTimeline(capacity = 3)
        for (i in 1..5) t.push(i * 10L)

        assertEquals("용량을 넘지 않는다", 3, t.size)
        // 50·40·30 만 남는다. 10·20 은 사라졌다.
        assertEquals(50L, t.timeAt(0))
        assertEquals(30L, t.timeAt(2))
        assertEquals("밀려난 뒤에도 폭은 남은 것으로 잰다", 20L, t.spanMs)
    }

    @Test
    fun `한 장뿐이면 폭이 없다`() {
        val t = SpectrogramTimeline(capacity = 4)
        assertEquals(0L, t.spanMs)
        t.push(100L)
        assertEquals("한 장으로는 시간을 잴 수 없다", 0L, t.spanMs)
    }

    @Test
    fun `비우면 아무것도 남지 않는다`() {
        val t = SpectrogramTimeline(capacity = 4)
        t.push(10L)
        t.clear()
        assertEquals(0, t.size)
        assertEquals(0, t.head)
    }

    @Test
    fun `범위 밖을 물으면 막는다`() {
        val t = SpectrogramTimeline(capacity = 4)
        t.push(10L)
        var blocked = false
        try {
            t.timeAt(1)
        } catch (e: IllegalArgumentException) {
            blocked = true
        }
        assertTrue("없는 장을 그냥 돌려줬다", blocked)
    }

    private fun sweep(): List<Int> =
        (0..100).map { spectrogramColor(10.0 + 80.0 * it / 100.0, 10.0, 90.0) }

    /**
     * **붉은 기운은 자라기만 한다.**
     *
     * 색 지도는 눈에 예쁘라고 있는 것이 아니라 「여기가 더 크다」를 말하는
     * 것이다. 사람은 이 그림에서 빨간 자리를 먼저 찾으므로, 붉은 기운이
     * 중간에서 되돌아가면 **더 큰 소리가 덜 눈에 띄게** 된다.
     *
     * 원래 jet 은 빨강 뒤에 검붉은 꼬리가 있어 이 성질을 어긴다. 그 꼬리를
     * 빼기로 한 까닭이 이 시험이다.
     */
    @Test
    fun `레벨이 오르면 붉은 기운이 되돌아가지 않는다`() {
        var prev = Int.MIN_VALUE
        sweep().forEachIndexed { i, argb ->
            val r = (argb shr 16) and 0xFF
            assertTrue("${i}번째에서 붉은 기운이 줄었다", r >= prev)
            prev = r
        }
    }

    /**
     * **두 레벨이 같은 색이면 안 된다.**
     *
     * 이 그림에서 색은 유일한 숫자다(높이와 자리는 주파수·시간이 이미
     * 쓴다). 같은 색이 두 레벨을 뜻하면 그림에서 읽은 것을 되돌릴 수 없다.
     */
    @Test
    fun `레벨마다 색이 다르다`() {
        val seen = sweep()
        assertEquals("겹치는 색이 있다", seen.size, seen.toSet().size)
    }

    @Test
    fun `바닥과 천장은 양 끝 색으로 막힌다`() {
        val floorColor = spectrogramColor(10.0, 10.0, 90.0)
        val ceilColor = spectrogramColor(90.0, 10.0, 90.0)
        assertEquals("바닥 아래", floorColor, spectrogramColor(-40.0, 10.0, 90.0))
        assertEquals("천장 위", ceilColor, spectrogramColor(200.0, 10.0, 90.0))
        assertNotEquals("두 끝이 같은 색이면 그림이 없다", floorColor, ceilColor)
    }

    @Test
    fun `색은 모두 불투명하다`() {
        for (i in 0..20) {
            val argb = spectrogramColor(10.0 + 4.0 * i, 10.0, 90.0)
            assertEquals("알파", 0xFF, (argb shr 24) and 0xFF)
        }
    }

    @Test
    fun `범위가 뒤집히면 막는다`() {
        var blocked = false
        try {
            spectrogramColor(50.0, 90.0, 10.0)
        } catch (e: IllegalArgumentException) {
            blocked = true
        }
        assertTrue("뒤집힌 범위를 그냥 받았다", blocked)
    }
}
