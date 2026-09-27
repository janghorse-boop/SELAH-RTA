package kr.joa.selahrta.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * **float PCM 을 16비트 WAV 로.**
 *
 * 여기서 지키는 두 줄:
 *
 * 1. **±1 을 넘는 값이 뒤집히지 않는다.** 그냥 곱해서 자르면 한 바퀴
 *    돌아 큰 소리가 조용한 소리가 된다 — 들어 보면 바로 알지만 숫자만
 *    보면 그럴듯하다.
 * 2. **머리의 길이가 실제와 맞는다.** 안 맞으면 플레이어가 아예 열지
 *    못하거나 뒷부분을 버린다.
 */
class WavWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(samples: FloatArray, rate: Int = 48_000): java.io.File {
        val f = tmp.newFile("a.wav")
        // **스트림을 닫은 뒤에 마무리한다.** 버퍼에 남은 바이트가 있으면
        // 머리에 적는 길이가 실제와 어긋난다.
        val w = f.outputStream().buffered().use { out ->
            WavWriter(out, rate).also { it.write(samples, samples.size) }
        }
        w.finish(f)
        return f
    }

    private fun le(f: java.io.File) = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)

    // ── 값이 뒤집히지 않는가 ──────────────────────────────

    @Test
    fun `정상 범위는 그대로 옮긴다`() {
        // **`.toInt()` 을 빼면 안 된다.** JUnit 이 Short 와 Int 를
        // 다른 것으로 보아 0 == 0 도 실패한다.
        assertEquals(0, WavWriter.toPcm16(0f).toInt())
        assertEquals(32767, WavWriter.toPcm16(1f).toInt())
        assertEquals(-32767, WavWriter.toPcm16(-1f).toInt())
        assertEquals(16384, WavWriter.toPcm16(0.5f).toInt())
    }

    /**
     * **이것이 이 파일의 한가운데다.**
     *
     * `1.5f * 32767 = 49150` 은 Short 에 안 들어간다. 그대로 자르면
     * `-16386` — **가장 큰 소리가 큰 음수**가 되어 뒤집힌다.
     */
    @Test
    fun `범위를 넘는 값은 끝에 붙인다`() {
        assertEquals(32767, WavWriter.toPcm16(1.5f).toInt())
        assertEquals(32767, WavWriter.toPcm16(99f).toInt())
        assertEquals(-32768, WavWriter.toPcm16(-1.5f).toInt())
        assertEquals(-32768, WavWriter.toPcm16(-99f).toInt())
        assertTrue("양수가 음수로 뒤집혔다", WavWriter.toPcm16(1.5f) > 0)
    }

    @Test
    fun `NaN 은 0 으로 둔다`() {
        assertEquals(0, WavWriter.toPcm16(Float.NaN).toInt())
    }

    // ── 머리가 맞는가 ─────────────────────────────────────

    @Test
    fun `머리가 RIFF WAVE 다`() {
        val b = le(write(FloatArray(10)))
        val riff = ByteArray(4).also { b.get(it) }
        assertEquals("RIFF", String(riff, Charsets.US_ASCII))
        b.int
        val wave = ByteArray(4).also { b.get(it) }
        assertEquals("WAVE", String(wave, Charsets.US_ASCII))
    }

    @Test
    fun `길이가 실제 자료와 맞는다`() {
        val frames = 1000
        val f = write(FloatArray(frames))
        val b = le(f)
        b.position(4)
        val riffSize = b.int
        b.position(40)
        val dataSize = b.int

        assertEquals("데이터 크기가 프레임 수와 다르다", frames * 2, dataSize)
        assertEquals("RIFF 크기가 어긋난다", dataSize + WavWriter.HEADER_BYTES - 8, riffSize)
        assertEquals(
            "파일 크기가 머리와 다르다",
            (WavWriter.HEADER_BYTES + dataSize).toLong(),
            f.length(),
        )
    }

    /** 길이를 안 고치면 0 이 남는다 — 플레이어가 못 연다. */
    @Test
    fun `마무리하기 전에는 길이가 0 이다`() {
        val f = tmp.newFile("b.wav")
        f.outputStream().buffered().use { out ->
            WavWriter(out, 48_000).write(FloatArray(500), 500)
        }
        val b = le(f)
        b.position(40)
        assertEquals("마무리도 안 했는데 길이가 적혔다", 0, b.int)
        assertNotEquals("자료가 안 써졌다", 0L, f.length() - WavWriter.HEADER_BYTES)
    }

    @Test
    fun `형식 칸이 48kHz 모노 16비트다`() {
        val b = le(write(FloatArray(4)))
        b.position(20)
        assertEquals("PCM 이 아니다", 1, b.short.toInt())
        assertEquals("모노가 아니다", 1, b.short.toInt())
        assertEquals(48_000, b.int)
        assertEquals("초당 바이트가 다르다", 48_000 * 2, b.int)
        assertEquals("프레임 바이트가 다르다", 2, b.short.toInt())
        assertEquals("비트 수가 다르다", 16, b.short.toInt())
    }

    // ── 덩어리 밖을 읽지 않는가 ──────────────────────────

    /**
     * 버퍼는 돌려 쓰므로 [frames] 밖에는 **지난 덩어리의 찌꺼기**가
     * 남아 있다. 그것까지 쓰면 소리에 옛 조각이 섞인다.
     */
    @Test
    fun `프레임 밖의 찌꺼기를 쓰지 않는다`() {
        val f = tmp.newFile("c.wav")
        val buf = FloatArray(100) { 1f }
        f.outputStream().buffered().use { out ->
            WavWriter(out, 48_000).write(buf, 10)
        }
        assertEquals(
            "10 프레임만 써야 한다",
            (WavWriter.HEADER_BYTES + 20).toLong(),
            f.length(),
        )
    }

    @Test
    fun `빈 덩어리는 아무것도 쓰지 않는다`() {
        val f = tmp.newFile("d.wav")
        f.outputStream().buffered().use { out ->
            val w = WavWriter(out, 48_000)
            w.write(FloatArray(10), 0)
        }
        assertEquals(WavWriter.HEADER_BYTES.toLong(), f.length())
    }

    // ── 크기 어림 ─────────────────────────────────────────

    /** 고르는 자리에 적는 숫자다. 실제와 크게 어긋나면 안 된다. */
    @Test
    fun `WAV 크기 어림이 실제와 맞는다`() {
        // 48kHz · 16비트 · 모노 = 초당 96,000 바이트 = 분당 약 5.49MiB
        val oneMinuteBytes = 48_000.0 * 2 * 60
        val estimated = AudioFileFormat.Wav.megabytesPerMinute * 1_000_000
        val ratio = estimated / oneMinuteBytes
        assertTrue("어림이 실제와 10% 넘게 다르다: $ratio", ratio in 0.9..1.1)
    }
}
