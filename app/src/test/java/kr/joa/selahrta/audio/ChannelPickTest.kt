package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * **프레임과 표본을 옮기는 자리가 맞는가.**
 *
 * `AudioRecord` 는 **표본**으로 주고받고 읽는 쪽은 **프레임**으로
 * 생각한다. 4채널이면 프레임 하나가 표본 넷이다. 여기가 어긋나면
 * 4분의 1만 읽거나 네 배를 달라고 하고, 둘 다 조용히 틀린다 —
 * 화면의 숫자는 멀쩡해 보인다.
 *
 * `deinterleave` 자체는 `InterleaveTest` 가 본다. 여기서 보는 것은
 * **배선**이다.
 */
class ChannelPickTest {

    /** 요청한 표본 수를 적어 두고, 인터리브된 값을 채워 주는 가짜 읽기. */
    private class FakeRead(
        private val channelCount: Int,
        /** 이 표본 수만큼만 준다. −1 이면 요청한 만큼 다 준다. */
        private val give: Int = -1,
    ) {
        var lastWant = -1
            private set

        val read: (FloatArray, Int) -> Int = { dst, want ->
            lastWant = want
            val n = if (give < 0) want else give
            for (i in 0 until n) {
                val f = i / channelCount
                val c = i % channelCount
                dst[i] = (10 * (c + 1) + f).toFloat()
            }
            n
        }
    }

    @Test
    fun `모노면 그대로 읽는다`() {
        val fake = FakeRead(1)
        val into = FloatArray(8)
        val n = readOneChannel(into, 8, 1, 0, null, fake.read)

        assertEquals("모노는 프레임 수가 곧 표본 수다", 8, fake.lastWant)
        assertEquals(8, n)
        assertEquals(10f, into[0], 0f)
        assertEquals(17f, into[7], 0f)
    }

    @Test
    fun `네 채널이면 네 배를 요청하고 프레임 수를 돌려준다`() {
        val frames = 6
        val ch = 4
        val fake = FakeRead(ch)
        val into = FloatArray(frames)
        val mixed = FloatArray(frames * ch)

        val n = readOneChannel(into, frames, ch, 2, mixed, fake.read)

        assertEquals("표본은 프레임의 네 배를 요청해야 한다", frames * ch, fake.lastWant)
        assertEquals("돌려주는 것은 프레임 수다", frames, n)
        for (f in 0 until frames) {
            assertEquals("ch2 frame$f", (30 + f).toFloat(), into[f], 0f)
        }
    }

    /** 덜 읽히면 **읽힌 만큼만** 프레임으로 센다. */
    @Test
    fun `덜 읽히면 그만큼만 센다`() {
        val ch = 2
        // 프레임 셋 분량(6표본)만 준다.
        val fake = FakeRead(ch, give = 6)
        val into = FloatArray(10)
        val mixed = FloatArray(20)

        val n = readOneChannel(into, 10, ch, 1, mixed, fake.read)

        assertEquals(20, fake.lastWant)
        assertEquals(3, n)
        assertEquals(20f, into[0], 0f)
        assertEquals(22f, into[2], 0f)
    }

    /**
     * **오류 부호를 뭉개지 않는다.**
     *
     * 음수는 오류, 0 은 멈추는 중이다. 여기서 채널 수로 나누면 −3 이
     * 0 이 돼 **「그냥 조용한 덩어리」로 둔갑한다** — 읽기 루프는 그걸
     * 오류로 보지 않아 캡처가 끝난 줄 모른다.
     */
    @Test
    fun `오류와 멈춤은 그대로 넘긴다`() {
        val into = FloatArray(4)
        val mixed = FloatArray(16)
        assertEquals(-3, readOneChannel(into, 4, 4, 0, mixed) { _, _ -> -3 })
        assertEquals(0, readOneChannel(into, 4, 4, 0, mixed) { _, _ -> 0 })
        // 모노 경로도 마찬가지다.
        assertEquals(-3, readOneChannel(into, 4, 1, 0, null) { _, _ -> -3 })
    }

    @Test
    fun `여러 채널인데 받을 자리가 없으면 막는다`() {
        val into = FloatArray(4)
        assertNotNull(
            runCatching { readOneChannel(into, 4, 2, 0, null) { _, _ -> 8 } }.exceptionOrNull(),
        )
        // 자리가 작아도 막는다.
        assertNotNull(
            runCatching {
                readOneChannel(into, 4, 2, 0, FloatArray(4)) { _, _ -> 8 }
            }.exceptionOrNull(),
        )
    }
}
