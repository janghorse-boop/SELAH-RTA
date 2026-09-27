package kr.joa.selahrta.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.OutputStream

/**
 * 기록하는 동안 **소리를 파일로 담는다**(담당자 지시 2026-09-27).
 *
 * ## 이것은 기본이 아니다
 *
 * 예배 소리를 담는 것은 dB 숫자를 남기는 것과 **성격이 다르다** —
 * 설교와 성도들의 목소리가 그대로 들어간다. 그래서 **기록을 시작할
 * 때마다 묻고**, 사람이 그렇다고 해야 이 자리가 열린다.
 *
 * ## 오디오 스레드를 붙잡지 않는다
 *
 * [write] 는 캡처 스레드에서 불린다. 거기서 한 번이라도 멈추면 **그
 * 자리의 소리가 사라지고 되돌릴 방법이 없다**(명세 17장). 그래서
 * 파일 쓰기는 전용 스레드가 하고, 사이는 [PacketPipe] 가 잇는다 —
 * 자물쇠도 대기도 할당도 없는 길이다.
 *
 * **밀리면 버린다.** 기다리지 않는다. 버린 수는 [dropped] 에 남고
 * 화면이 그것을 적는다 — 조용히 빠진 소리가 있는 것보다 낫다.
 */
class AudioFileRecorder private constructor(
    private val format: AudioFileFormat,
    private val file: File,
    private val sink: Sink,
    private val pipe: PacketPipe,
) {
    /** 실제로 바이트를 쓰는 쪽. 형식마다 다르다. */
    internal interface Sink {
        fun write(samples: FloatArray, frames: Int)

        /** 끝낸다. 되돌아가 머리를 고치는 형식도 있다. */
        fun finish()
    }

    @Volatile
    private var writerFailed: String? = null
    private var thread: Thread? = null

    /** 오디오 스레드가 밀려 버린 덩어리 수. 0 이 아니면 화면이 말한다. */
    @Volatile
    var dropped: Int = 0
        private set

    /** 쓰다가 실패했으면 그 까닭. 성공이면 null. */
    val failureKo: String? get() = writerFailed

    private fun start() {
        thread = Thread({ loop() }, "selah-audio-file").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        val slot = PacketSlot()
        try {
            while (true) {
                if (pipe.poll(slot)) {
                    sink.write(pipe.pool.buffer(slot.bufferIndex), slot.frames)
                    pipe.releaseAfterWrite(slot.bufferIndex)
                } else if (pipe.isClosed) {
                    // 닫혔고 더 나올 것도 없으면 끝이다.
                    if (!pipe.poll(slot)) break
                } else {
                    // **바쁘게 돌지 않는다.** 여기서 조금 자는 것은
                    // 오디오 스레드와 상관없다 — 그쪽은 넣기만 한다.
                    Thread.sleep(5)
                }
            }
        } catch (t: Throwable) {
            writerFailed = "소리를 파일로 쓰다가 실패했습니다: ${t.message}"
            Log.w(TAG, "쓰기 실패", t)
        }
    }

    /**
     * 한 덩어리를 넘긴다. **캡처 스레드가 부른다.**
     *
     * 밀리면 버리고 센다. 기다리지 않는다.
     */
    fun write(samples: FloatArray, frames: Int) {
        if (frames <= 0 || writerFailed != null) return
        val r = pipe.offer(0L, frames, 0) { buf ->
            System.arraycopy(samples, 0, buf, 0, minOf(frames, buf.size))
        }
        if (r != PacketPipe.Offer.Published) dropped++

    }

    /**
     * 끝내고 파일을 남긴다. 실패했으면 **파일을 지운다.**
     *
     * 반쯤 쓴 파일은 플레이어가 못 열거나 뒷부분을 조용히 버린다 —
     * 남겨 두면 사람이 「담겼다」고 여긴다.
     */
    fun finish(): Result? {
        pipe.close()
        thread?.join(FINISH_WAIT_MS)
        pipe.handOverProducerHeld()
        runCatching { sink.finish() }.onFailure {
            writerFailed = "파일을 마무리하지 못했습니다: ${it.message}"
        }
        val why = writerFailed
        if (why != null || !file.isFile || file.length() <= 0L) {
            runCatching { file.delete() }
            return null
        }
        return Result(format, file.name, file.length(), dropped)
    }

    /** 남은 파일에 대해 화면·겉장이 알아야 하는 것. */
    data class Result(
        val format: AudioFileFormat,
        val fileName: String,
        val bytes: Long,
        val droppedBlocks: Int,
    )

    companion object {
        private const val TAG = "AudioFile"

        /** 마무리까지 기다릴 시간. 넘으면 남은 것은 버린다. */
        private const val FINISH_WAIT_MS = 3_000L

        /**
         * 얼마나 담아 둘 것인가.
         *
         * 48kHz 에서 한 칸이 21ms 남짓이라, 64칸이면 1.3초쯤이다. 파일
         * 쓰기가 그보다 오래 막히는 일은 드물고, 그보다 오래 막힌다면
         * 더 담아 둬도 어차피 밀린다.
         */
        private const val BUFFERS = 64

        fun create(
            format: AudioFileFormat,
            file: File,
            sampleRate: Int,
            maxFramesPerBlock: Int,
        ): AudioFileRecorder? = runCatching {
            val pipe = PacketPipe(BUFFERS, maxFramesPerBlock)
            val sink = when (format) {
                AudioFileFormat.Wav -> WavSink(file, sampleRate)
                AudioFileFormat.M4a -> AacSink(file, sampleRate)
            }
            AudioFileRecorder(format, file, sink, pipe).also { it.start() }
        }.getOrElse {
            Log.w(TAG, "소리 파일을 열지 못했다", it)
            null
        }
    }
}

/** 무압축 PCM. */
private class WavSink(private val file: File, sampleRate: Int) : AudioFileRecorder.Sink {
    private val out: OutputStream = file.outputStream().buffered()
    private val wav = WavWriter(out, sampleRate)

    override fun write(samples: FloatArray, frames: Int) = wav.write(samples, frames)

    override fun finish() {
        out.flush()
        out.close()
        // **닫은 뒤에 머리를 고친다.** 버퍼에 남은 바이트가 있으면
        // 적어 넣는 길이가 실제와 어긋난다.
        wav.finish(file)
    }
}

/**
 * AAC 압축(.m4a).
 *
 * **여기는 기기 없이 못 돌린다.** `MediaCodec` 은 안드로이드가 주는
 * 것이라, 시험이 볼 수 있는 것은 WAV 쪽과 이 클래스를 고르는 자리까지다.
 * 그 경계를 [AudioFileFormat] 과 [WavWriter] 로 밀어 두었다.
 */
private class AacSink(file: File, private val sampleRate: Int) : AudioFileRecorder.Sink {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var track = -1
    private var started = false
    private var presentationUs = 0L
    private val info = MediaCodec.BufferInfo()

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    override fun write(samples: FloatArray, frames: Int) {
        var offset = 0
        while (offset < frames) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(endOfStream = false)
                continue
            }
            val buf = codec.getInputBuffer(index) ?: return
            buf.clear()
            val room = buf.remaining() / 2
            val n = minOf(room, frames - offset)
            for (i in 0 until n) buf.putShort(WavWriter.toPcm16(samples[offset + i]))
            codec.queueInputBuffer(index, 0, n * 2, presentationUs, 0)
            presentationUs += n * 1_000_000L / sampleRate
            offset += n
            drain(endOfStream = false)
        }
    }

    override fun finish() {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index >= 0) {
            codec.queueInputBuffer(index, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drain(endOfStream = true)
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // **트랙은 한 번만 만든다.** 두 번 부르면 예외다.
                    if (!started) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                    }
                }

                out < 0 -> return

                else -> {
                    val buf = codec.getOutputBuffer(out)
                    // 설정 바이트는 muxer 가 따로 가져간다 — 트랙에 쓰지 않는다.
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && started && !isConfig && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private companion object {
        /** 말소리·찬양을 다시 들어 보는 데 넉넉하다. 1분에 1MB 쯤. */
        const val BIT_RATE = 128_000
        const val TIMEOUT_US = 10_000L
    }
}
