package kr.joa.selahrta.recording

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * **저장한 소리를 다시 읽는다**(명세 Recording-E, 재분석).
 *
 * ## 왜 필요한가
 *
 * 예배가 끝난 뒤에 보정을 한다. 그러면 **이미 녹음해 둔 소리**를 새
 * 보정으로 다시 셈할 수 있어야 한다 — 그러지 못하면 보정 전에 담은
 * 기록은 영영 「참고용」으로 남는다.
 *
 * ## 짐작해서 읽지 않는다
 *
 * [TimelineReader] 와 같은 태도다. 모르는 형식이면 **열지 않고 그렇게
 * 말한다.** 짐작으로 풀면 **조용히 틀린 소리**가 나오고, 그 소리로 낸
 * 숫자는 그럴듯해 보인다.
 *
 * 받아들이는 것은 [WavWriter] 가 쓰는 것과 같은 **16비트 PCM** 뿐이다.
 *
 * ## 덩어리를 건너뛸 줄 안다
 *
 * WAV 는 `fmt `·`data` 말고도 덩어리가 더 있을 수 있다(`LIST`·`fact` 등).
 * 다른 프로그램이 만든 파일에는 흔하다. **모르는 덩어리는 건너뛴다** —
 * 그것은 「모르는 형식」이 아니라 「안 쓰는 정보」다.
 *
 * **홀수 길이 덩어리 뒤에는 채움 바이트가 하나 붙는다**(RIFF 규약).
 * 그것을 안 건너뛰면 그다음 덩어리 이름이 한 칸 밀려 **파일이 통째로
 * 안 읽힌다.**
 */
class WavReader(private val input: InputStream) {

    val format: WavFormat

    /** `data` 덩어리에 적힌 길이(바이트). 0 이면 「모른다」로 본다. */
    private var remainingBytes: Long

    init {
        expectTag("RIFF")
        readInt() // RIFF 크기. **믿지 않는다** — 꼬리가 잘린 파일이 흔하다.
        expectTag("WAVE")

        var fmt: WavFormat? = null
        var dataLen = -1L
        while (dataLen < 0) {
            val tag = readTag()
            val size = readInt().toLong() and 0xFFFF_FFFFL
            when (tag) {
                "fmt " -> {
                    fmt = readFmt(size)
                    skipPadded(size - 16)
                }
                "data" -> dataLen = size
                // **모르는 덩어리는 건너뛴다.** 안 쓰는 정보일 뿐이다.
                else -> skipPadded(size)
            }
        }
        format = fmt ?: throw IOException("fmt 덩어리가 없습니다.")
        remainingBytes = dataLen
    }

    /**
     * 다음 프레임들을 [out] 에 담는다. **-1 이면 끝**이다.
     *
     * @param out `프레임 × 채널` 만큼 담을 자리.
     * @return 읽은 **프레임** 수(채널 수로 나눈 것).
     */
    fun read(out: FloatArray): Int {
        val bytesPerFrame = format.channels * 2
        val wantFrames = out.size / format.channels
        if (wantFrames <= 0) return 0
        val wantBytes = minOf(wantFrames.toLong() * bytesPerFrame, remainingBytes)
        if (wantBytes <= 0L) return -1

        val raw = ByteArray(wantBytes.toInt())
        var n = 0
        while (n < raw.size) {
            val r = input.read(raw, n, raw.size - n)
            // **꼬리가 잘린 파일은 거기까지 읽는다.** 녹음 중에 앱이 죽으면
            // 머리에 적힌 길이보다 짧다 — 그때 통째로 버리면 두 시간이 날아간다.
            if (r < 0) break
            n += r
        }
        // 프레임 중간에서 끊긴 꼬리는 버린다. 반 토막 프레임은 소리가 아니다.
        val frames = n / bytesPerFrame
        if (frames <= 0) return -1
        remainingBytes -= frames.toLong() * bytesPerFrame

        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames * format.channels) out[i] = fromPcm16(b.short)
        return frames
    }

    // ── 머리 읽기 ────────────────────────────────────────

    private fun readFmt(size: Long): WavFormat {
        if (size < 16) throw IOException("fmt 덩어리가 너무 짧습니다: $size")
        val b = ByteBuffer.wrap(readFully(16)).order(ByteOrder.LITTLE_ENDIAN)
        val codec = b.short.toInt()
        val channels = b.short.toInt()
        val rate = b.int
        b.int // byteRate — 다시 셈할 수 있으므로 믿지 않는다
        b.short // blockAlign
        val bits = b.short.toInt()

        // **모르는 형식이면 열지 않는다.** 짐작으로 풀면 조용히 틀린 소리가 난다.
        if (codec != PCM) throw IOException("PCM 이 아닙니다(형식 $codec).")
        if (bits != 16) throw IOException("16비트가 아닙니다($bits 비트).")
        if (channels !in 1..MAX_CHANNELS) throw IOException("채널 수가 이상합니다: $channels")
        if (rate !in TimelineFormat.SAMPLE_RATE_RANGE) {
            throw IOException("샘플레이트가 범위를 벗어납니다: $rate")
        }
        return WavFormat(rate, channels)
    }

    private fun expectTag(want: String) {
        val got = readTag()
        if (got != want) throw IOException("WAV 파일이 아닙니다($want 자리에 '$got').")
    }

    private fun readTag() = String(readFully(4), Charsets.US_ASCII)

    private fun readInt() =
        ByteBuffer.wrap(readFully(4)).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readFully(n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) throw EOFException("파일이 중간에 끝났습니다.")
            read += r
        }
        return buf
    }

    /** [n] 바이트를 건너뛴다. **홀수면 채움 바이트 하나를 더** 건너뛴다. */
    private fun skipPadded(n: Long) {
        if (n <= 0L) return
        var left = n + (n and 1L)
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                // skip 이 0 을 돌려주는 스트림이 있다. 읽어서 버린다.
                if (input.read() < 0) throw EOFException("파일이 중간에 끝났습니다.")
                left--
            } else {
                left -= s
            }
        }
    }

    companion object {
        private const val PCM = 1
        private const val MAX_CHANNELS = 32

        /**
         * 16비트 하나를 float 로. [WavWriter.toPcm16] 의 반대다.
         *
         * **나누는 값이 같아야 한다**(32767). -32768 만 -1 을 살짝 넘는데,
         * 그것이 원래 값에 가장 가깝다 — 여기서 자르면 쓴 것과 읽은 것이
         * 달라진다.
         */
        fun fromPcm16(v: Short): Float = v / 32767f
    }
}

/** WAV 머리에서 읽은 것. */
data class WavFormat(val sampleRate: Int, val channels: Int)
