package kr.joa.selahrta.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kr.joa.selahrta.dsp.ClockSample
import kr.joa.selahrta.dsp.DriftResult
import kr.joa.selahrta.dsp.estimateDrift
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * **출력과 입력의 클럭 드리프트를 실기기에서 잰다** (명세 7장).
 *
 * 간편 모드는 **서로 다른 시계** 둘을 쓴다 — 출력은 USB 오디오 기기의
 * 클럭, 입력은 폰 ADC 의 클럭이다. 그 둘이 얼마나 어긋나는지가
 * Magnitude·Phase 앞의 관문이다(개발지시서 3장).
 *
 * ## 소리를 내지 않는다
 *
 * **출력에 완전한 무음(0)을 써 넣는다.** `getTimestamp()` 의
 * `framePosition` 은 하드웨어가 **소비한 프레임 수**를 따라가므로 내용과
 * 무관하다. 그래서 근무 중에도 조용히 돌릴 수 있다 — 음향으로 재면
 * 클럭 드리프트와 방·온도 변화가 섞이는데, 이 방법은 애초에 소리를 안
 * 쓴다.
 *
 * ## 시간축을 맞춘다
 *
 * `AudioTrack` 쪽은 `MONOTONIC` 이고 `AudioRecord` 는 고를 수 있다.
 * **`TIMEBASE_MONOTONIC` 을 명시**해서 부른다 — 어긋나면 드리프트가
 * 아니라 **두 시계의 기준점 차이**를 재게 된다.
 *
 * ## 못 주는 경우를 0 으로 적지 않는다
 *
 * 안드로이드는 route 에 따라 타임스탬프를 **아예 안 줄 수 있다.** 그때
 * `0 ppm` 으로 적으면 「드리프트 없음」으로 읽힌다. 몇 번 물어서 몇 번
 * 받았는지를 **그대로 적는다.**
 *
 * ## 덤으로 보는 것
 *
 * 2026-10-01 의 PASS 는 **출력 전용 기기**(ADAM D3V)에서 나왔고,
 * 검증 문서가 「입출력 겸용 USB 기기로 일반화하면 안 된다」고 적어 두었다.
 * 이 시험은 **내장 마이크 + UMC404HD 출력**이라 바로 그 미검증 조합이다.
 * 입력이 죽는지도 함께 본다.
 *
 * ## 돌리는 법
 *
 * ```
 * adb shell am instrument -w -r -e minutes 1 \
 *   -e class 'kr.joa.selahrta.audio.ClockDriftMeasurementTest#클럭_드리프트를_잰다' \
 *   kr.joa.selahrta.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * 기록은 `adb logcat -s DRIFT` 에 남는다. **화면이 꺼지면 계측 시험이
 * 죽으므로** 화면 꺼짐을 늘려 두고 돌린다.
 */
class ClockDriftMeasurementTest {

    private val tag = "DRIFT"
    private val rate = 48_000

    private fun say(line: String) = Log.i(tag, line)

    private fun args() = InstrumentationRegistry.getArguments()

    /** 밖에서 `-e minutes 30` 으로 준다. 기본은 짧게 1분. */
    private fun minutes(): Double = args().getString("minutes")?.toDoubleOrNull() ?: 1.0

    private fun audioManager(): AudioManager {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return ctx.getSystemService(AudioManager::class.java)
    }

    private fun outputs() = audioManager().getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    private fun inputs() = audioManager().getDevices(AudioManager.GET_DEVICES_INPUTS)

    private fun kindOf(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB기기"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB헤드셋"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB액세서리"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "폰스피커"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "내장마이크"
        else -> "종류$type"
    }

    private fun isUsb(d: AudioDeviceInfo) = d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
        d.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    @Test
    fun 진단_어떤_기기가_붙어_있나() {
        say("=== 출력 ===")
        outputs().forEach { say("  ${kindOf(it.type)} / ${it.productName} / id=${it.id} / 주소'${it.address}'") }
        say("=== 입력 ===")
        inputs().forEach { say("  ${kindOf(it.type)} / ${it.productName} / id=${it.id} / 주소'${it.address}'") }

        val usbOut = outputs().firstOrNull { isUsb(it) }
        val mic = inputs().firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        say("USB 출력: ${usbOut?.productName ?: "없음"}")
        say("내장 마이크: ${mic?.productName ?: "없음"}")
    }

    /**
     * **본 측정.** 무음을 USB 로 내보내며 내장 마이크로 받고, 두 시계의
     * 프레임 진행 속도를 견준다.
     */
    @Test
    fun 클럭_드리프트를_잰다() {
        val usbOut = outputs().firstOrNull { isUsb(it) }
        assertTrue("USB 출력 기기가 없다 — 인터페이스를 꽂으십시오", usbOut != null)
        val mic = inputs().firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        assertTrue("내장 마이크를 못 찾았다", mic != null)
        say("출력=${usbOut!!.productName}(${kindOf(usbOut.type)}) 입력=${mic!!.productName}")

        // ---- 출력: 무음을 흘린다 ----
        val outChannels = 2
        val outMinBytes = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(outMinBytes * 4)
            .build()
        track.preferredDevice = usbOut

        // ---- 입력: 내장 마이크 ----
        val inMinBytes = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(inMinBytes * 4)
            .build()
        record.preferredDevice = mic

        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        // **입력이 실제로 들어오는지도 센다** — 입출력 겸용 USB 기기에서
        // 입력이 통째로 죽은 전례가 있다(2026-09-30, UMC404HD).
        val readFrames = java.util.concurrent.atomic.AtomicLong(0)
        val silentBlocks = java.util.concurrent.atomic.AtomicLong(0)
        val totalBlocks = java.util.concurrent.atomic.AtomicLong(0)

        track.play()
        record.startRecording()
        say("출력 route=${track.routedDevice?.productName} 입력 route=${record.routedDevice?.productName}")

        // 무음을 계속 써 넣는다. **내용이 0 이라 아무 소리도 안 난다.**
        val silence = FloatArray(1024 * outChannels)
        val writer = thread(name = "drift-out") {
            while (running.get()) {
                val w = track.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                if (w < 0) { say("출력 오류 $w"); break }
            }
        }
        // 읽지 않으면 버퍼가 차서 타임스탬프가 멎는다.
        val buf = FloatArray(1024)
        val reader = thread(name = "drift-in") {
            while (running.get()) {
                val n = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n > 0) {
                    readFrames.addAndGet(n.toLong())
                    totalBlocks.incrementAndGet()
                    var peak = 0f
                    for (i in 0 until n) { val a = kotlin.math.abs(buf[i]); if (a > peak) peak = a }
                    if (peak == 0f) silentBlocks.incrementAndGet()
                } else if (n < 0) { say("입력 오류 $n"); break }
            }
        }

        fun outStamp(): ClockSample? {
            val ts = AudioTimestamp()
            return if (track.getTimestamp(ts)) ClockSample(ts.framePosition, ts.nanoTime) else null
        }
        fun inStamp(): ClockSample? {
            val ts = AudioTimestamp()
            val ok = record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC)
            return if (ok == AudioRecord.SUCCESS) ClockSample(ts.framePosition, ts.nanoTime) else null
        }

        // **자리를 잡을 때까지 기다린다.** 시작 직후의 타임스탬프는 아직
        // 자리를 안 잡아 값이 튄다(안드로이드 문서 권고).
        Thread.sleep(3_000)

        var outAsk = 0; var outGot = 0
        var inAsk = 0; var inGot = 0
        fun ask(): Pair<ClockSample?, ClockSample?> {
            outAsk++; inAsk++
            val o = outStamp()?.also { outGot++ }
            val i = inStamp()?.also { inGot++ }
            return o to i
        }

        val (out0, in0) = ask()
        say("첫 타임스탬프 — 출력=${out0?.let { "frames=${it.frames} t=${it.nanos}" } ?: "**못 받음**"} / 입력=${in0?.let { "frames=${it.frames} t=${it.nanos}" } ?: "**못 받음**"}")

        val totalMs = (minutes() * 60_000).toLong()
        val stepMs = 10_000L
        var elapsed = 0L
        var lastOut = out0
        var lastIn = in0

        while (elapsed < totalMs) {
            Thread.sleep(minOf(stepMs, totalMs - elapsed))
            elapsed += stepMs
            val (o, i) = ask()
            if (o != null) lastOut = o
            if (i != null) lastIn = i

            val now = if (out0 != null && in0 != null && o != null && i != null) {
                estimateDrift(out0, o, in0, i)
            } else {
                DriftResult.Unavailable
            }
            val drift = when (now) {
                is DriftResult.Ppm -> "%.2f ppm".format(now.value)
                DriftResult.Unavailable -> "**못 쟀다**"
            }
            val blocks = totalBlocks.get()
            val silentPct = if (blocks > 0) silentBlocks.get() * 100.0 / blocks else 0.0
            say(
                "[${elapsed / 1000}초] 드리프트=$drift · 입력장=${blocks} 무음장=${silentBlocks.get()}(%.2f%%)".format(silentPct) +
                    " · 타임스탬프 출력 $outGot/$outAsk · 입력 $inGot/$inAsk",
            )
        }

        running.set(false)
        writer.join(2_000); reader.join(2_000)
        record.stop(); record.release()
        track.stop(); track.release()

        // ---- 판정 ----
        val final = if (out0 != null && in0 != null && lastOut != null && lastIn != null) {
            estimateDrift(out0, lastOut, in0, lastIn)
        } else {
            DriftResult.Unavailable
        }
        say("=== 끝 ===")
        say("출력 타임스탬프 $outGot/$outAsk · 입력 타임스탬프 $inGot/$inAsk")
        say("입력 장 ${totalBlocks.get()} · 무음 장 ${silentBlocks.get()} · 받은 프레임 ${readFrames.get()}")
        when (final) {
            is DriftResult.Ppm -> say("드리프트 = %.3f ppm".format(final.value))
            DriftResult.Unavailable -> say("드리프트 = **못 쟀다**(UNAVAILABLE) — 0 ppm 이 아니다")
        }

        // **이 시험은 재는 것이 목적이다.** 드리프트 값 자체로 통과·실패를
        // 가르지 않는다 — 문턱을 정할 근거가 아직 없기 때문이다.
        // 다만 **아무것도 못 쟀으면** 실패로 둔다. 그래야 「돌렸다」가
        // 「쟀다」로 둔갑하지 않는다.
        assertTrue(
            "타임스탬프를 한 번도 못 받았다 — 출력 $outGot/$outAsk, 입력 $inGot/$inAsk",
            outGot > 0 && inGot > 0,
        )
    }
}
