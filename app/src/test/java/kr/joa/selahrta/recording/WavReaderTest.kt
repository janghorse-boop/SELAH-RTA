package kr.joa.selahrta.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * **저장한 소리를 다시 읽는다**(명세 Recording-E).
 *
 * 여기서 지키는 것:
 *
 * 1. **쓴 것과 읽은 것이 같다.** [WavWriter] 와 짝이 맞아야 재분석이
 *    원본과 같은 소리를 본다.
 * 2. **모르는 형식은 열지 않는다.** 짐작으로 풀면 조용히 틀린 소리가
 *    나오고, 그 소리로 낸 숫자는 그럴듯해 보인다.
 * 3. **꼬리가 잘린 파일은 거기까지 읽는다.** 녹음 중에 앱이 죽으면
 *    머리에 적힌 길이보다 짧다 — 통째로 버리면 두 시간이 날아간다.
 */
class WavReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** [values] 를 담은 진짜 WAV 파일. [WavWriter] 가 쓴 것이다. */
    private fun wav(values: FloatArray, rate: Int = 48_000, channels: Int = 1): File {
        val f = tmp.newFile("a-${System.nanoTime()}.wav")
        val out = f.outputStream()
        val w = WavWriter(out, rate, channels)
        w.write(values, values.size / channels)
        out.close()
        w.finish(f)
        return f
    }

    private fun readAll(f: File): FloatArray {
        val got = ArrayList<Float>()
        f.inputStream().use { input ->
            val r = WavReader(input)
            val buf = FloatArray(256)
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                for (i in 0 until n * r.format.channels) got += buf[i]
            }
        }
        return got.toFloatArray()
    }

    // ── 1. 쓴 것과 읽은 것이 같다 ────────────────────────

    @Test
    fun `쓴 값을 그대로 돌려준다`() {
        // 16비트 격자에 정확히 앉는 값들로 고른다 — 그래야 「같다」를
        // 엄밀하게 말할 수 있다.
        val values = floatArrayOf(0f, 0.5f, -0.5f, 0.25f, -0.25f, 1f)
        val got = readAll(wav(values))

        assertEquals(values.size, got.size)
        for (i in values.indices) {
            // 16비트로 한 번 눌린 값이라 정확히 같지는 않다. 한 칸(1/32767)
            // 안이면 같은 값이다.
            assertEquals("$i 번째", values[i], got[i], 1f / 32767f)
        }
    }

    @Test
    fun `머리의 샘플레이트와 채널을 읽는다`() {
        val f = wav(FloatArray(8), rate = 44_100, channels = 2)
        f.inputStream().use {
            val r = WavReader(it)
            assertEquals(44_100, r.format.sampleRate)
            assertEquals(2, r.format.channels)
        }
    }

    @Test
    fun `두 채널이 섞이지 않는다`() {
        // L=0.5, R=-0.5 를 네 프레임.
        val values = FloatArray(8) { if (it % 2 == 0) 0.5f else -0.5f }
        val got = readAll(wav(values, channels = 2))
        assertEquals(8, got.size)
        for (i in got.indices) {
            assertEquals(if (i % 2 == 0) 0.5f else -0.5f, got[i], 1f / 32767f)
        }
    }

    @Test
    fun `±1 을 넘는 값은 끝에 붙어 돌아온다`() {
        // 쓰는 쪽이 한 바퀴 돌지 않게 잡아 준다. 읽는 쪽도 그 값이어야 한다.
        val got = readAll(wav(floatArrayOf(2f, -2f)))
        assertEquals(1f, got[0], 1f / 32767f)
        assertTrue("음수 쪽이 뒤집혔다: ${got[1]}", got[1] <= -1f)
    }

    // ── 2. 모르는 형식은 열지 않는다 ─────────────────────

    private fun failure(bytes: ByteArray): IOException? =
        runCatching { WavReader(ByteArrayInputStream(bytes)) }
            .exceptionOrNull() as? IOException

    /** 머리를 손으로 짓는다. 고칠 자리를 정확히 집으려는 것이다. */
    private fun header(
        riff: String = "RIFF",
        wave: String = "WAVE",
        codec: Short = 1,
        channels: Short = 1,
        rate: Int = 48_000,
        bits: Short = 16,
        dataLen: Int = 0,
    ): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put(riff.toByteArray(Charsets.US_ASCII))
        b.putInt(36 + dataLen)
        b.put(wave.toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(codec)
        b.putShort(channels)
        b.putInt(rate)
        b.putInt(rate * channels * bits / 8)
        b.putShort((channels * bits / 8).toShort())
        b.putShort(bits)
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataLen)
        return b.array()
    }

    @Test
    fun `WAV 가 아니면 거절한다`() {
        assertNotNull(failure(header(riff = "RIFX")))
        assertNotNull(failure(header(wave = "AVI ")))
    }

    @Test
    fun `PCM 이 아니면 거절한다`() {
        // 3 = IEEE float. 풀 수는 있지만 **우리가 쓰는 형식이 아니다** —
        // 16비트로 읽으면 소리가 통째로 다르다.
        val e = failure(header(codec = 3))
        assertTrue("$e", e?.message?.contains("PCM") == true)
    }

    @Test
    fun `16비트가 아니면 거절한다`() {
        val e = failure(header(bits = 24))
        assertTrue("$e", e?.message?.contains("16비트") == true)
    }

    @Test
    fun `말이 안 되는 샘플레이트를 거절한다`() {
        assertNotNull(failure(header(rate = 0)))
        assertNotNull(failure(header(rate = 10_000_000)))
    }

    @Test
    fun `채널이 0 이면 거절한다`() {
        assertNotNull(failure(header(channels = 0)))
    }

    // ── 모르는 덩어리는 건너뛴다 ─────────────────────────

    @Test
    fun `모르는 덩어리가 사이에 있어도 읽는다`() {
        // 다른 프로그램이 만든 파일에는 LIST 같은 것이 흔하다. 그것은
        // 「모르는 형식」이 아니라 **안 쓰는 정보**다.
        val body = byteArrayOf(0x00, 0x40) // 0x4000 = 16384 ≈ 0.5
        // 넉넉히 잡고 쓴 만큼만 잘라 쓴다 — 손으로 센 크기가 틀려
        // 시험이 터졌다(60바이트인데 56 으로 셌다).
        val b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt(0)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)); b.putInt(16)
        b.putShort(1); b.putShort(1); b.putInt(48_000)
        b.putInt(96_000); b.putShort(2); b.putShort(16)
        // **홀수 길이 덩어리.** 뒤에 채움 바이트가 하나 붙는다 — 그것을
        // 안 건너뛰면 다음 이름이 한 칸 밀려 파일이 통째로 안 읽힌다.
        b.put("LIST".toByteArray(Charsets.US_ASCII)); b.putInt(5)
        b.put("abcde".toByteArray(Charsets.US_ASCII)); b.put(0)
        b.put("data".toByteArray(Charsets.US_ASCII)); b.putInt(body.size)
        b.put(body)

        val r = WavReader(ByteArrayInputStream(b.array().copyOf(b.position())))
        val buf = FloatArray(4)
        assertEquals(1, r.read(buf))
        assertEquals(0.5f, buf[0], 1f / 32767f)
    }

    // ── 3. 꼬리가 잘려도 거기까지 읽는다 ─────────────────

    @Test
    fun `머리보다 짧은 파일도 있는 만큼 읽는다`() {
        // 머리에는 100프레임이라 적고 실제로는 3프레임만 있는 파일.
        // 녹음 중에 앱이 죽으면 이렇게 된다.
        val out = ByteArrayOutputStream()
        out.write(header(dataLen = 200))
        val b = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        repeat(3) { b.putShort(16384) }
        out.write(b.array())

        val r = WavReader(ByteArrayInputStream(out.toByteArray()))
        val buf = FloatArray(100)
        assertEquals("있는 만큼 읽어야 한다", 3, r.read(buf))
        assertEquals(-1, r.read(buf))
    }

    @Test
    fun `반 토막 프레임은 버린다`() {
        // 한 프레임은 두 바이트다. 한 바이트만 남으면 소리가 아니다.
        val out = ByteArrayOutputStream()
        out.write(header(channels = 2, dataLen = 8))
        out.write(byteArrayOf(0, 0x40, 0, 0x40, 0)) // 한 프레임 + 한 바이트
        val r = WavReader(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, r.read(FloatArray(8)))
        assertEquals(-1, r.read(FloatArray(8)))
    }

    @Test
    fun `소리가 없는 파일은 곧바로 끝이다`() {
        val r = WavReader(ByteArrayInputStream(header(dataLen = 0)))
        assertEquals(-1, r.read(FloatArray(16)))
    }
}
