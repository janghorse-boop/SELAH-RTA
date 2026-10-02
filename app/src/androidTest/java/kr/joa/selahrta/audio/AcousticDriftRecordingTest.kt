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
import kr.joa.selahrta.transfer.TappedSink
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * **음향 드리프트 기록** — 소리를 내서, 기준과 측정의 지연이 시간에 따라
 * 밀리는지 본다.
 *
 * 설계와 **재기 전에 정한 판정 규칙**은
 * `docs/superpowers/specs/2026-10-02-acoustic-drift-recording-design.md` 에 있다.
 * 이 시험은 그것을 그대로 따른다 — 기록하고, [DriftLogAnalyzer] 로 자르고,
 * 정한 말만 적는다.
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
 * ## 통과·실패 (6회차 R6-05 의 교훈)
 *
 * **잰 값으로 가르지 않는다.** 재지 못했으면 실패다:
 * 실제 출력이 USB 가 아님 · 실제 입력이 내장 마이크가 아님 · (10분 이상
 * 돌렸는데) 쓸 수 있는 구간이 하나도 없음.
 *
 * ## 돌리는 법
 *
 * ```
 * adb shell am instrument -w -r -e minutes 30 -e amplitude 0.1 \
 *   -e class 'kr.joa.selahrta.audio.AcousticDriftRecordingTest#음향_드리프트를_기록한다' \
 *   kr.joa.selahrta.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * 기록은 `adb logcat -s ADRIFT`. **화면이 꺼지면 계측 시험이 죽는다** —
 * 화면 꺼짐을 늘려 두고 돌린다. 시운전은 `-e minutes 1`.
 */
class AcousticDriftRecordingTest {

    private val tag = "ADRIFT"
    private val rate = 48_000
    private val intervalMs = 10_000L

    private fun say(line: String) = Log.i(tag, line)
    private fun args() = InstrumentationRegistry.getArguments()
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun minutes(): Double = args().getString("minutes")?.toDoubleOrNull() ?: 1.0
    private fun amplitude(): Double = args().getString("amplitude")?.toDoubleOrNull() ?: 0.1

    private fun usbOut(): AudioDeviceInfo? =
        context().getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { SignalOutputChoice.matches(OutputKind.Wired, it.type) }

    private fun builtInMic() = InputDeviceScanner(context()).listAll().firstOrNull { it.kind == MicKind.BuiltIn }

    private fun describe(d: AudioDeviceInfo?): String =
        if (d == null) "모름" else "${d.productName}(종류${d.type}, 주소'${d.address}', id=${d.id})"

    /**
     * 시험용 출력 껍데기. [AudioTrackSink] 와 같은 설정으로 열되 **언더런 수와
     * 실제 경로**를 밖에서 읽을 수 있게 한다.
     */
    private class CountingTrackSink(private val wanted: AudioDeviceInfo) : SignalSink {
        @Volatile var track: AudioTrack? = null
            private set
        val writeErrors = AtomicLong()
        val routeChanged = AtomicBoolean(false)

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
            t.setPreferredDevice(wanted)
            t.addOnRoutingChangedListener({ _ -> routeChanged.set(true) }, null)
            t.play()
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
        val out = usbOut()
        assertTrue("USB 출력 기기가 없다 — 인터페이스를 꽂으십시오", out != null)
        val mic = builtInMic()
        assertTrue("내장 마이크를 못 찾았다", mic != null)

        val session = UUID.randomUUID().toString().take(8)
        val engine = TransferEngine(sampleRate = rate)
        val sink = CountingTrackSink(out!!)
        val player = SignalPlayer(
            onEnded = { _, why -> say("!! 소리가 스스로 끊겼다: $why"); sink.writeErrors.incrementAndGet() },
            openSink = { TappedSink(sink) { buf, off, n -> engine.offerReference(buf, off, n) } },
        )

        val inErrors = AtomicLong()
        val inRouteChanged = AtomicBoolean(false)
        val confirmed = AtomicReference<OpenedFormat?>(null)
        val peakBits = AtomicLong(0)   // 지난 관측 뒤 입력 최대 절대값(Float bits)
        val src = MicSource(
            context(),
            mic,
            onRoutingChanged = { inRouteChanged.set(true); say("!! 입력 경로가 바뀌었다 → ${it?.productName}") },
            onRouteConfirmed = { confirmed.set(it) },
            onCaptureEnded = { inErrors.incrementAndGet(); say("!! 캡처가 끝났다: $it") },
        )

        val observations = mutableListOf<DriftObservation>()
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
            val inFmt = confirmed.get()
            say(
                "HEAD session=$session model=${Build.MODEL} build=${Build.ID}/${Build.VERSION.INCREMENTAL} " +
                    "sdk=${Build.VERSION.SDK_INT} rate=$rate fft=8192 avg=16 maxLag=24000 intervalMs=$intervalMs " +
                    "minutes=${minutes()} amplitude=${amplitude()} signal=Pink/Both(seed=SignalPlayer)",
            )
            say("HEAD 출력 요청=${describe(out)} 실제=${describe(outRoute)}")
            say(
                "HEAD 입력 요청=${mic!!.productName}(주소'${mic.address}') 확인=${inFmt?.deviceLabel ?: "아직"} " +
                    "kind=${inFmt?.micKind} source=${inFmt?.audioSource ?: opened.audioSource} " +
                    "— 주소는 안드로이드의 표기일 뿐 물리 위치가 아니다",
            )
            assertTrue("출력 경로를 알 수 없다", outRoute != null)
            assertTrue(
                "출력이 USB 로 안 갔다 — 실제 ${describe(outRoute)}",
                SignalOutputChoice.matches(OutputKind.Wired, outRoute!!.type),
            )
            assertTrue(
                "입력이 내장 마이크로 확인되지 않았다 — ${inFmt?.deviceLabel}/${inFmt?.micKind}",
                inFmt != null && inFmt.micKind == MicKind.BuiltIn,
            )
            val outRouteId = outRoute.id
            sink.routeChanged.set(false)
            inRouteChanged.set(false)

            val endAt = SystemClock.elapsedRealtime() + (minutes() * 60_000).toLong()
            while (SystemClock.elapsedRealtime() < endAt) {
                Thread.sleep(intervalMs)
                val now = SystemClock.elapsedRealtimeNanos()
                val o = engine.measureOutcome()
                val track = sink.track
                val underruns = track?.underrunCount?.toLong() ?: -1L
                val routeNow = track?.routedDevice?.id
                val routeChanged = sink.routeChanged.getAndSet(false) or inRouteChanged.getAndSet(false) ||
                    routeNow != outRouteId
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
                        "routeChanged=$routeChanged outRoute=$routeNow peak=%.4f".format(peak) +
                        (if (peak >= 0.99f) " !!클리핑" else "") + countsOf(o),
                )
            }
        } finally {
            runCatching { player.stop() }
            runCatching { src.close() }
        }

        // ---- 분석: 재기 전에 정한 규칙 그대로 ----
        val a = DriftLogAnalyzer(sampleRate = rate).analyze(observations)
        say("=== 끝 === 관측 ${observations.size} · 버림 ${a.skipped} · 못 찾음 ${a.notFound} · 중복 ${a.duplicates}")
        a.segments.forEachIndexed { i, s ->
            say(
                "SEG #$i epoch=${s.epoch} 시작까닭=${s.startReason ?: "처음"} 관측=${s.points} " +
                    "%.1f분 기울기=%.4f±%.4f ppm 최대잔차=%.2f 계단의심=${s.stepSuspected} 씀=${s.usable}"
                        .format(s.minutes, s.ppm, s.ci95, s.maxResidual),
            )
            say("SEG #$i ${DriftLogAnalyzer.conclusion(s)}")
        }

        // **재지 못했으면 실패.** 짧은 시운전은 건너뛰고 그렇다고 적는다.
        if (minutes() >= 10.0) {
            assertTrue(
                "쓸 수 있는 구간이 하나도 없다 — 「돌렸다」를 「쟀다」로 적지 않는다",
                a.segments.any { it.usable },
            )
        } else {
            say("시운전(${minutes()}분) — 「쓸 수 있는 구간」 단언을 건너뛴다. 이 결과로 결론을 내지 않는다")
        }
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
