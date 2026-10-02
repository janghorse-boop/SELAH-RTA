package kr.joa.selahrta.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.DriftLogAnalyzer
import kr.joa.selahrta.dsp.DriftObservation
import kr.joa.selahrta.dsp.MeasureOutcome
import kr.joa.selahrta.dsp.ObservationKind
import kr.joa.selahrta.dsp.TransferEngine
import kr.joa.selahrta.transfer.DriftArgs
import kr.joa.selahrta.transfer.DriftSessionGuard
import kr.joa.selahrta.transfer.InputSnapshot
import kr.joa.selahrta.transfer.TappedSink
import kr.joa.selahrta.transfer.driftSessionReport
import kr.joa.selahrta.transfer.parseDriftArgs
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * **음향 드리프트 기록** — 소리를 내서, 기준과 측정의 지연이 시간에 따라
 * 어떻게 움직이는지 원자료를 모은다.
 *
 * 설계와 **재기 전에 정한 판정 규칙**은
 * `docs/superpowers/specs/2026-10-02-acoustic-drift-recording-design.md` 에 있다.
 *
 * ## 판정은 이 파일 밖에 있다 (9회차)
 *
 * 경로 감시([DriftSessionGuard]), 인자 읽기([parseDriftArgs]), 보고와 통과·실패
 * ([driftSessionReport])는 `transfer/DriftSession.kt` 에 있고 JVM 시험
 * (`DriftSessionGuardTest`)이 지킨다. 9회차 반례가 모두 이 파일 안의 상태
 * 관리에서 나왔는데, 여기는 기기 없이 돌릴 수 없어서다. **이 파일은 안드로이드
 * 값을 읽어 넘기기만 한다.**
 *
 * ## 소리가 난다
 *
 * **핑크 잡음이 USB 출력으로 나간다.** 근무 시간에 돌리지 않는다.
 *
 * ## 앱의 실제 경로를 쓴다
 *
 * - 신호: [SignalPlayer] 의 핑크 잡음, **양쪽(Both)**. 매번 새로 생성하며
 *   되감지 않는다(seed 는 SignalPlayer 가 정하므로 재현은 안 된다).
 * - 기준: [TappedSink] — **실제로 쓰인 칸만** 평균해 엔진에 넣는다.
 * - 입력: [MicSource] — 앱이 화면에서 쓰는 것과 같은 열기·가공 끄기.
 * - 출력 장치만 이 시험의 껍데기([CountingTrackSink])다. **언더런 수와 실제
 *   출력 경로**를 직접 읽어야 해서다. `AudioTrackSink` 는 그것을 밖에 내지 않는다.
 *
 * ## 돌리는 법
 *
 * ```
 * # 시운전(5분 이하) — 장비·경로·신호가 맞는지만 본다. 결론을 내지 않는다.
 * adb shell am instrument -w -r -e mode trial -e minutes 2 -e amplitude 0.1 \
 *   -e class 'kr.joa.selahrta.audio.AcousticDriftRecordingTest#음향_드리프트를_기록한다' \
 *   kr.joa.selahrta.test/androidx.test.runner.AndroidJUnitRunner
 *
 * # 기록(11분 이상 — 30분 권장)
 * adb shell am instrument -w -r -e mode record -e minutes 30 -e amplitude 0.1 \
 *   -e class 'kr.joa.selahrta.audio.AcousticDriftRecordingTest#음향_드리프트를_기록한다' \
 *   kr.joa.selahrta.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * 기록은 `adb logcat -s ADRIFT`. **화면이 꺼지면 계측 시험이 죽는다** —
 * 화면 꺼짐을 늘려 두고 돌린다.
 */
class AcousticDriftRecordingTest {

    private val tag = "ADRIFT"
    private val rate = 48_000
    private val intervalMs = 10_000L

    private fun say(line: String) = Log.i(tag, line)
    private fun args() = InstrumentationRegistry.getArguments()
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun amplitude(): Double = args().getString("amplitude")?.toDoubleOrNull() ?: 0.1

    /**
     * **USB 출력만.** `OutputKind.Wired` 는 유선 헤드폰·헤드셋도 받아 주므로
     * 여기서는 쓰지 않는다(8회차 R8-03).
     */
    private fun isUsb(type: Int) = type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun usbOut(): AudioDeviceInfo? =
        context().getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { isUsb(it.type) }

    /** 같은 기기인지 견줄 열쇠 — id 는 다시 꽂으면 바뀌므로 종류·이름·주소. */
    private fun keyOf(d: AudioDeviceInfo?): String? =
        d?.let { sameOutputKey(it.type, it.productName.toString(), it.address) }

    private fun builtInMic() = InputDeviceScanner(context()).listAll().firstOrNull { it.kind == MicKind.BuiltIn }

    private fun describe(d: AudioDeviceInfo?): String =
        if (d == null) "모름" else "${d.productName}(종류${d.type}, 주소'${d.address}', id=${d.id})"

    /**
     * 시험용 출력 껍데기. [AudioTrackSink] 와 같은 설정으로 열되 **언더런 수와
     * 실제 경로**를 밖에서 읽을 수 있게 한다.
     */
    private class CountingTrackSink(
        private val wanted: AudioDeviceInfo,
        private val onRouted: () -> Unit,
    ) : SignalSink {
        @Volatile var track: AudioTrack? = null
            private set
        val writeErrors = AtomicLong()

        override fun open(sampleRate: Int, frames: Int, channels: Int): Boolean {
            val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBytes = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_FLOAT)
            if (minBytes <= 0) return false
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .build(),
                )
                .setBufferSizeInBytes(minBytes * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); return false }
            // **play() 까지 끝나야 게시한다.** 그 전에 터지면 track 필드가 비어
            // 있어 부르는 쪽의 stop/release 가 이것을 못 본다(8회차 R8-05).
            try {
                t.setPreferredDevice(wanted)
                t.addOnRoutingChangedListener({ _ -> onRouted() }, null)
                t.play()
            } catch (e: Throwable) {
                runCatching { t.release() }
                throw e
            }
            track = t
            return true
        }

        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            val n = track?.write(buf, offset, frames, AudioTrack.WRITE_BLOCKING) ?: AudioTrack.ERROR_INVALID_OPERATION
            if (n < 0) writeErrors.incrementAndGet()
            return n
        }

        override fun stop() { track?.let { runCatching { it.stop() } } }

        override fun release(): Boolean {
            val t = track ?: return true
            track = null
            return runCatching { t.release() }.isSuccess
        }
    }

    @Test
    fun 음향_드리프트를_기록한다() {
        val parsed = parseDriftArgs(args().getString("mode"), args().getString("minutes"))
        if (parsed is DriftArgs.Rejected) fail(parsed.why)
        val (mode, minutes) = parsed as DriftArgs.Accepted

        val out = usbOut()
        assertTrue("USB 출력 기기가 없다 — 인터페이스를 꽂으십시오", out != null)
        val mic = builtInMic()
        assertTrue("내장 마이크를 못 찾았다", mic != null)

        val session = UUID.randomUUID().toString().take(8)
        val engine = TransferEngine(sampleRate = rate)
        val guard = DriftSessionGuard()
        val sink = CountingTrackSink(out!!) { guard.outputRouted() }
        val player = SignalPlayer(
            onEnded = { _, why -> say("!! 소리가 스스로 끊겼다: $why"); sink.writeErrors.incrementAndGet() },
            openSink = { TappedSink(sink) { buf, off, n -> engine.offerReference(buf, off, n) } },
        )

        val inErrors = AtomicLong()
        val peakBits = AtomicLong(0)   // 지난 관측 뒤 입력 최대 절대값(Float bits)
        val src = MicSource(
            context(),
            mic,
            onRoutingChanged = {
                guard.inputRouted("${it?.productName}")
                say("!! 입력 경로 통지 → ${it?.productName}")
            },
            onRouteConfirmed = { f ->
                guard.inputConfirmed(InputSnapshot.of(f))
                say("입력 확인 ${f.deviceLabel} kind=${f.micKind} 활성마이크=${activeMicComboKo(f.activeMics)}")
            },
            onCaptureEnded = { inErrors.incrementAndGet(); say("!! 캡처가 끝났다: $it") },
        )

        val observations = mutableListOf<DriftObservation>()
        var verdict: kr.joa.selahrta.transfer.SessionVerdict? = null
        try {
            val r = src.open(RequestedFormat(sampleRate = rate))
            assertTrue("입력을 못 열었다: $r", r is OpenResult.Opened)
            val opened = (r as OpenResult.Opened).format
            assertTrue("입력이 ${opened.sampleRate}Hz 로 열렸다 — ${rate}Hz 라야 한다", opened.sampleRate == rate)
            assertTrue("입력이 ${opened.channelCount}채널로 열렸다 — 모노라야 한다", opened.channelCount == 1)

            src.start { block, _ ->
                engine.offerMeasurement(block.samples, 0, block.frames)
                var p = 0f
                for (i in 0 until block.frames) { val a = kotlin.math.abs(block.samples[i]); if (a > p) p = a }
                while (true) {
                    val cur = peakBits.get()
                    if (p <= java.lang.Float.intBitsToFloat(cur.toInt())) break
                    if (peakBits.compareAndSet(cur, p.toRawBits().toLong())) break
                }
            }

            val id = player.start(SignalRequest(TestSignal.Pink, amplitude = amplitude(), channels = SignalChannels.Both))
            assertTrue("소리를 못 열었다", id != SignalPlayer.NONE)

            // 경로는 재생이 붙은 뒤에야 잡힌다.
            Thread.sleep(1_500)
            val outRoute = sink.track?.routedDevice
            assertTrue("출력 경로를 알 수 없다", outRoute != null)
            assertTrue(
                "출력이 고른 USB 기기로 안 갔다 — 고른 ${describe(out)} / 실제 ${describe(outRoute)}",
                isUsb(outRoute!!.type) && keyOf(outRoute) == keyOf(out),
            )
            // **기준을 잡는 일과 감시 시작을 한 번에** — 돌려받은 값으로 단언한다.
            // 따로 읽고 나중에 비우던 틈이 9회차 R9-01 ② 였다.
            val baseline = guard.arm(keyOf(outRoute)!!)
            say(
                "HEAD session=$session model=${Build.MODEL} build=${Build.ID}/${Build.VERSION.INCREMENTAL} " +
                    "sdk=${Build.VERSION.SDK_INT} rate=$rate fft=8192 avg=16 maxLag=24000 intervalMs=$intervalMs " +
                    "mode=$mode minutes=$minutes amplitude=${amplitude()} signal=Pink/Both(seed=SignalPlayer)",
            )
            say("HEAD 출력 요청=${describe(out)} 실제=${describe(outRoute)}")
            say(
                "HEAD 입력 요청=${mic!!.productName}(주소'${mic.address}') 기준=${baseline?.deviceKey ?: "없음"} " +
                    "내장=${baseline?.builtIn} 활성조합='${baseline?.comboKey}' source=${opened.audioSource} " +
                    "— 주소·활성 마이크는 안드로이드의 표기일 뿐 물리 위치의 증거가 아니다",
            )
            assertTrue("입력 확인을 아직 못 받았다 — 기준을 못 잡는다", baseline != null)
            assertTrue("입력이 내장 마이크로 확인되지 않았다 — ${baseline!!.deviceKey}", baseline.builtIn)

            val endAt = SystemClock.elapsedRealtime() + (minutes * 60_000).toLong()
            while (SystemClock.elapsedRealtime() < endAt) {
                Thread.sleep(intervalMs)
                val now = SystemClock.elapsedRealtimeNanos()
                val o = engine.measureOutcome()
                val track = sink.track
                val underruns = track?.underrunCount?.toLong() ?: -1L
                val routeNow = track?.routedDevice
                guard.outputPolled(keyOf(routeNow))
                val routeChanged = guard.takeChanged()
                val peak = java.lang.Float.intBitsToFloat(peakBits.getAndSet(0).toInt())
                val ob = when (o) {
                    is MeasureOutcome.Measured -> DriftObservation(
                        session, ObservationKind.Measured, o.measurement.epoch, o.measurement.windowEnd,
                        o.measurement.delay.samples, o.measurement.delay.found, o.measurement.delay.sharpness,
                        underruns, sink.writeErrors.get(), inErrors.get(), routeChanged,
                    )
                    else -> DriftObservation(
                        session, kindOf(o), epochOf(o), 0, 0, false, 0.0,
                        underruns, sink.writeErrors.get(), inErrors.get(), routeChanged,
                    )
                }
                observations += ob
                say(
                    "OBS session=$session kind=${ob.kind} epoch=${ob.epoch} windowEnd=${ob.windowEnd} " +
                        "lag=${ob.lag} found=${ob.found} sharpness=%.3f mono=$now ".format(ob.sharpness) +
                        "underruns=$underruns outErr=${ob.outErrors} inErr=${ob.inErrors} " +
                        "routeChanged=$routeChanged outRoute=${routeNow?.id} peak=%.4f".format(peak) +
                        (if (peak >= 0.99f) " !!클리핑" else "") + countsOf(o),
                )
            }
            // 끝의 출력 경로를 **멈추기 전에** 읽고, **그 자리에서 판정을 닫는다**.
            // 마지막 관측 뒤의 변경도 넣고(9회차 R9-01 ③), 멈추고 닫는 동안 오는
            // 통지는 「도중 변경」이 아니라 뒤늦은 통지로만 센다(10회차 R10-03 —
            // 예전에는 정리한 뒤에 닫아, 정리 중 통지로 정상 수집이 무효가 됐다).
            verdict = guard.finish(keyOf(sink.track?.routedDevice))
        } finally {
            runCatching { player.stop() }
            runCatching { src.close() }
        }

        // ---- 판정을 먼저 확정하고, 그다음에 적는다 (9회차 R9-03) ----
        val closed = checkNotNull(verdict) { "판정을 닫기 전에 끝났다" }
        val analysis = DriftLogAnalyzer(sampleRate = rate).analyze(observations)
        val report = driftSessionReport(mode, closed, analysis, observations)
        report.lines.forEach(::say)
        if (guard.lateEvents > 0) say("끝낸 뒤 통지 ${guard.lateEvents} 건 — 판정에 넣지 않았다")
        if (!report.passed) fail(report.failure)
    }

    private fun kindOf(o: MeasureOutcome) = when (o) {
        MeasureOutcome.Busy -> ObservationKind.Busy
        is MeasureOutcome.InsufficientData -> ObservationKind.InsufficientData
        is MeasureOutcome.RetentionExceeded -> ObservationKind.RetentionExceeded
        is MeasureOutcome.Measured -> ObservationKind.Measured
    }

    private fun epochOf(o: MeasureOutcome) = when (o) {
        is MeasureOutcome.InsufficientData -> o.epoch
        is MeasureOutcome.RetentionExceeded -> o.epoch
        else -> -1L
    }

    private fun countsOf(o: MeasureOutcome) = when (o) {
        is MeasureOutcome.InsufficientData -> " refCount=${o.referenceCount} measCount=${o.measurementCount} need=${o.needed}"
        is MeasureOutcome.RetentionExceeded ->
            " refCount=${o.referenceCount} measCount=${o.measurementCount} refSlack=${o.referenceSlack} measSlack=${o.measurementSlack}"
        else -> ""
    }
}
