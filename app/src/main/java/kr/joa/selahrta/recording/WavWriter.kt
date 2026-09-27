package kr.joa.selahrta.recording

import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * float PCM 을 **16비트 WAV** 로 쓴다.
 *
 * ## 왜 16비트인가
 *
 * 마이크는 32비트 float 로 들어온다. 그대로 담으면 파일이 두 배인데
 * 얻는 것이 거의 없다 — 들어오는 소리의 다이내믹 레인지가 16비트
 * (약 96dB)를 넘지 않는다. 2시간 예배가 1.4GB 와 690MB 로 갈린다.
 *
 * ## 길이는 마지막에 적는다
 *
 * WAV 의 머리에는 **전체 길이**가 들어간다. 재기 전에는 모르므로 0 을
 * 적어 두고 [finish] 에서 되돌아가 고친다. 그 전에 앱이 죽으면 머리가
 * 0 인 파일이 남는데, 그런 파일은 플레이어가 열지 못한다 — 그래서
 * [finish] 를 반드시 부르고, 못 불렀으면 그 파일은 **버린다.**
 *
 * ## 잘라내지 않는다
 *
 * float 는 ±1 을 넘을 수 있다. 그것을 그냥 곱하면 16비트에서 **한
 * 바퀴 돌아** 큰 소리가 조용한 소리로 뒤집힌다. 넘는 값은 끝에 붙인다
 * (clamp) — 찌그러지되 뒤집히지는 않는다. 찌그러졌다는 사실은 행
 * (`TimelineRow.clipped`)과 겉장(`clippedRows`)이 따로 적는다.
 */
class WavWriter(
    private val out: OutputStream,
    private val sampleRate: Int,
    private val channels: Int = 1,
) {
    private var dataBytes: Long = 0
    private var headerWritten = false

    /** 한 덩어리를 쓴다. [frames] 밖은 지난 덩어리의 찌꺼기다 — 읽지 않는다. */
    fun write(samples: FloatArray, frames: Int) {
        if (!headerWritten) {
            out.write(header(0))
            headerWritten = true
        }
        if (frames <= 0) return
        val n = minOf(frames * channels, samples.size)
        val bytes = ByteArray(n * 2)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) b.putShort(toPcm16(samples[i]))
        out.write(bytes)
        dataBytes += bytes.size
    }

    /**
     * 길이를 머리에 적어 마무리한다. **[file] 은 이 writer 가 쓰던 그 파일**이다.
     *
     * 스트림을 닫은 뒤에 부른다 — 아직 버퍼에 남은 바이트가 있으면
     * 길이가 어긋난다.
     */
    fun finish(file: java.io.File) {
        RandomAccessFile(file, "rw").use { f ->
            f.seek(0)
            f.write(header(dataBytes))
        }
    }

    /** 지금까지 쓴 소리의 길이(ms). 화면이 크기를 어림하는 데 쓴다. */
    val writtenMs: Long
        get() = if (sampleRate <= 0) 0L else dataBytes * 1000L / (sampleRate.toLong() * channels * 2)

    private fun header(dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * channels * 2
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        // **머리 자신은 빼고 센다.** RIFF 크기는 그 뒤부터다.
        b.putInt((dataLen + HEADER_BYTES - 8).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16) // PCM 은 fmt 덩어리가 16바이트
        b.putShort(1) // 1 = PCM
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(byteRate)
        b.putShort((channels * 2).toShort()) // 한 프레임의 바이트
        b.putShort(16) // 비트
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataLen.toInt())
        return b.array()
    }

    companion object {
        const val HEADER_BYTES = 44

        /**
         * float 하나를 16비트로.
         *
         * **±1 을 넘는 값은 끝에 붙인다.** 그냥 곱해서 자르면 한 바퀴
         * 돌아 큰 소리가 조용한 소리로 **뒤집힌다** — 소리를 들어 보면
         * 바로 아는 종류의 고장이지만, 숫자만 보면 그럴듯하다.
         *
         * 음수 쪽이 한 칸 더 넓다(-32768 ~ 32767). 그래서 양쪽에 같은
         * 32767 을 곱하고 음수는 -32768 까지 허용한다.
         */
        fun toPcm16(v: Float): Short {
            if (v.isNaN()) return 0
            val scaled = (v * 32767f).roundToInt()
            return scaled.coerceIn(-32768, 32767).toShort()
        }
    }
}
