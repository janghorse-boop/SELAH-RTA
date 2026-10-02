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
 * ## 통과·실패 (6회차 R6-05, 8회차 R8-02·03·04 의 교훈)
 *
 * **잰 값으로 가르지 않는다.** 재지 못했으면 실패다:
 *
 * - `mode`·`minutes` 가 없거나 잘못됨 — 기본값으로 조용히 돌지 않는다.
 * - 실제 출력이 **고른 그 USB 기기**가 아님 — 유선 헤드폰도 안 된다.
 * - 입력이 내장 마이크로 확인되지 않음.
 * - **도는 동안 출력·입력 경로나 활성 마이크 조합이 한 번이라도 바뀜** —
 *   구간만 끊고 넘어가면 바뀐 뒤 다른 입력에서 잰 구간이 「쟀다」로 채택된다.
 * - `trial`: 지연을 찾은 관측이 [TRIAL_MIN_FOUND] 개 미만.
 * - `record`: 쓸 수 있는 구간이 하나도 없음.
 *
 * ## 돌리는 법
 *
 * ```
 * # 시운전(5분 이하) — 장비·경로·신호가 맞는지만 본다. 결론을 내지 않는다.
 * adb shell am instrument -w -r -e mode trial -e minutes 2 -e amplitude 0.1 \
 *   -e class 'kr.joa.selahrta.audio.AcousticDriftRecordingTest#음향_드리프트를_기록한다' \
 *   kr.joa.selahrta.test/androidx.test.runner.AndroidJUnitRunner
 *
 * # 기록(10분 이상)
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

    /** 시운전과 기록을 **이름으로** 가른다 — 기본값으로 조용히 돌지 않는다(R8-04). */
    private enum class Mode { trial, record }

    private fun mode(): Mode? = args().getString("mode")?.let { m -> Mode.entries.firstOrNull { it.name == m } }

    /** 유한하고 0 보다 큰 값만. NaN·음수·0 은 관측 0개로 「통과」하던 자리다(R8-04). */
    private fun minutes(): Double? =
        args().getString("minutes")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

    private fun amplitude(): Double = args().getString("amplitude")?.toDoubleOrNull() ?: 0.1

    /**
     * **USB 출력만.** `OutputKind.Wired` 는 유선 헤드폰·헤드셋도 받아 주므로
     * 여기서는 쓰지 않는다(R8-03).
     */
    private fun isUsb(type: Int) = type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun usbOut(): AudioDeviceInfo? =
        context().getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { isUsb(it.type) }

    /** 같은 기기인가 — id 는 다시 꽂으면 바뀌므로 종류·이름·주소로 견준다. */
    private fun sameDevice(a: AudioDeviceInfo, b: AudioDeviceInfo) =
        sameOutputKey(a.type, a.productName.toString(), a.address) ==
            sameOutputKey(b.type, b.productName.toString(), b.address)

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
            // **play() 까지 끝나야 게시한다.** 그 전에 터지면 track 필드가 비어
            // 있어 부르는 쪽의 stop/release 가 이것을 못 본다(R8-05).
            try {
                t.setPreferredDevice(wanted)
                t.addOnRoutingChangedListener({ _ -> routeChanged.set(true) }, null)
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
        val modeArg = mode()
        assertTrue("-e mode trial|record 를 정하십시오 — 기본값으로 돌지 않는다", modeArg != null)
        val mode: Mode = modeArg!!
        val minutesArg = minutes()
        assertTrue("-e minutes 에 0 보다 큰 수를 주십시오: ${args().getString("minutes")}", minutesArg != null)
        val minutes: Double = minutesArg!!
        when (mode) {
            Mode.trial -> assertTrue("시운전은 5분 이하: $minutes", minutes <= 5.0)
            Mode.record -> assertTrue("기록은 10분 이상: $minutes", minutes >= 10.0)
        }
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
        // 시작 때 확인한 입력. 그 뒤에 온 확인이 다른 기기·다른 활성 조합이면
        // 이 측정은 무효다 — 구간만 끊으면 바뀐 뒤의 구간이 채택된다(R8-02).
        val baseline = AtomicReference<OpenedFormat?>(null)
        val inputChanges = java.util.concurrent.CopyOnWriteArrayList<String>()
        val peakBits = AtomicLong(0)   // 지난 관측 뒤 입력 최대 절대값(Float bits)
        val src = MicSource(
            context(),
            mic,
            onRoutingChanged = {
                inRouteChanged.set(true)
                inputChanges += "입력 경로가 바뀌었다 → ${it?.productName}"
                say("!! 입력 경로가 바뀌었다 → ${it?.productName}")
            },
            onRouteConfirmed = { f ->
                confirmed.set(f)
                val b = baseline.get()
                if (b != null) {
                    val why = when {
                        f.micKind != MicKind.BuiltIn -> "입력이 내장 마이크가 아니게 됐다(${f.deviceLabel}/${f.micKind})"
                        f.deviceKey != b.deviceKey -> "입력 기기가 바뀌었다(${b.deviceKey} → ${f.deviceKey})"
                        else -> activeMicChangeKo(b.activeMics, f.activeMics)
                    }
                    if (why != null) {
                        inputChanges += why
                        inRouteChanged.set(true)
                        say("!! $why")
                    }
                }
            },
            onCaptureEnded = { inErrors.incrementAndGet(); say("!! 캡처가 끝났다: $it") },
        )

        val observations = mutableListOf<DriftObservation>()
        var outputChanges = 0
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
                    "mode=$mode minutes=$minutes amplitude=${amplitude()} signal=Pink/Both(seed=SignalPlayer)",
            )
            say("HEAD 출력 요청=${describe(out)} 실제=${describe(outRoute)}")
            say(
                "HEAD 입력 요청=${mic!!.productName}(주소'${mic.address}') 확인=${inFmt?.deviceLabel ?: "아직"} " +
                    "kind=${inFmt?.micKind} source=${inFmt?.audioSource ?: opened.audioSource} " +
                    "활성마이크=${activeMicComboKo(inFmt?.activeMics.orEmpty())} " +
                    "— 주소·활성 마이크는 안드로이드의 표기일 뿐 물리 위치의 증거가 아니다",
            )
            assertTrue("출력 경로를 알 수 없다", outRoute != null)
            assertTrue(
                "출력이 고른 USB 기기로 안 갔다 — 고른 ${describe(out)} / 실제 ${describe(outRoute)}",
                isUsb(outRoute!!.type) && sameDevice(outRoute, out),
            )
            assertTrue(
                "입력이 내장 마이크로 확인되지 않았다 — ${inFmt?.deviceLabel}/${inFmt?.micKind}",
                inFmt != null && inFmt.micKind == MicKind.BuiltIn,
            )
            val outRouteId = outRoute.id
            baseline.set(inFmt)
            sink.routeChanged.set(false)
            inRouteChanged.set(false)
            inputChanges.clear()

            val endAt = SystemClock.elapsedRealtime() + (minutes * 60_000).toLong()
            while (SystemClock.elapsedRealtime() < endAt) {
                Thread.sleep(intervalMs)
                val now = SystemClock.elapsedRealtimeNanos()
                val o = engine.measureOutcome()
                val track = sink.track
                val underruns = track?.underrunCount?.toLong() ?: -1L
                val routeNow = track?.routedDevice?.id
                val outChanged = sink.routeChanged.getAndSet(false) || routeNow != outRouteId
                if (outChanged) outputChanges++
                val routeChanged = inRouteChanged.getAndSet(false) || outChanged
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
                    "%.1f분 기울기=%.4f ppm(기술 통계) 정수1표본=%.4f ppm 최대잔차=%.2f 계단의심=${s.stepSuspected} 씀=${s.usable}"
                        .format(s.minutes, s.ppm, s.resolutionPpm, s.maxResidual),
            )
            say("SEG #$i ${DriftLogAnalyzer.conclusion(s)}")
        }

        // **도는 동안 경로가 바뀌었으면 이 측정은 못 쓴다** — 구간을 끊는 것으로
        // 끝내면 바뀐 뒤 다른 입력의 구간이 채택된다(R8-02).
        assertTrue("도는 동안 출력 경로가 바뀌었다 — 이 측정은 못 쓴다", outputChanges == 0)
        assertTrue("도는 동안 입력이 바뀌었다 — 이 측정은 못 쓴다: $inputChanges", inputChanges.isEmpty())

        // **재지 못했으면 실패.**
        val found = observations.count { it.kind == ObservationKind.Measured && it.found }
        when (mode) {
            Mode.trial -> {
                say("시운전 — 지연을 찾은 관측 $found 개. 이 결과로 결론을 내지 않는다")
                assertTrue(
                    "시운전에서 지연을 찾은 관측이 $found 개 — ${TRIAL_MIN_FOUND}개는 있어야 장비·경로·신호가 맞다고 본다",
                    found >= TRIAL_MIN_FOUND,
                )
            }
            Mode.record -> assertTrue(
                "쓸 수 있는 구간이 하나도 없다 — 「돌렸다」를 「쟀다」로 적지 않는다",
                a.segments.any { it.usable },
            )
        }
    }

    private companion object {
        /** 시운전이 「돈다」고 볼 최소 관측. 10초 간격이라 1분이면 5개 남짓이다. */
        const val TRIAL_MIN_FOUND = 3
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
