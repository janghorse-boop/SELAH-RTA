package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * **어느 조건에서 탈락하는가** — 44.1kHz 검증의 2층.
 *
 * 검증자가 적은 것을 그대로 한다:
 *
 * > 검출 횟수만이 아니라 **프레임별 실제 widthBins, prominence, drift,
 * > continuity** 를 함께 기록한다. 어느 조건 때문에 탈락했는지 분리한다.
 *
 * 1층(`SpectralWidthBoundaryTest`)은 폭 판정만 떼어 봤다. 여기서는 FFT 를
 * 지나는 실제 경로에서 **무엇이 걸러 내는지**를 센다.
 *
 * 그리고 **내가 가설로 남겨 둔 것**을 잰다 — 폭 2~20Hz 에서 44.1kHz 가
 * 더 많이 잡은 까닭이 「분석 창이 92.9ms 로 더 길어서」라는 설명이다.
 * 재 보니 그 방향은 맞았고, **그 가설이 「칸이 좁아서」와 같은 말**이라는
 * 것도 드러났다(`binHz = 1/T`). 자세한 것은 아래 시험의 설명.
 */
class RejectionReasonTest {

    private companion object {
        const val FS_48 = 48_000
        const val FS_44 = 44_100

        /** 프레임마다 재는 것들. */
        const val MIN_PROMINENCE_DB = 10.0
        const val MIN_LEVEL_DBFS = -70.0
    }

    /** 왜 「지속」까지 못 갔는가. */
    private enum class Reason {
        /** 갔다. */
        Detected,

        /** 봉우리 자체를 못 찾았다(솟음·레벨 미달). */
        NoPeak,

        /** 봉우리는 있는데 너무 넓다. */
        TooWide,

        /** 후보는 됐는데 너무 흔들린다. */
        Drift,

        /** 후보는 됐는데 자주 안 보인다. */
        Continuity,

        /** 후보까지는 갔는데 시간이 모자랐다. */
        TooShort,
    }

    /** 한 번 돌린 결과와, 그 동안 본 값들. */
    private class Trace(
        val reason: Reason,
        /** 프레임마다 잰 봉우리 폭(칸). 봉우리가 없던 프레임은 빠진다. */
        val widths: List<Int>,
        val prominences: List<Double>,
        /** 본 후보 가운데 **가장 작은** 흔들림. */
        val bestDrift: Double?,
        /** 본 후보 가운데 **가장 큰** 연속성. */
        val bestContinuity: Double?,
        val framesWithPeak: Int,
        val totalFrames: Int,
    ) {
        fun median(v: List<Double>) = if (v.isEmpty()) Double.NaN else v.sorted()[v.size / 2]
        override fun toString() = buildString {
            append(reason.name)
            append(" 폭중앙=")
            append(if (widths.isEmpty()) "-" else widths.sorted()[widths.size / 2].toString())
            append(" 솟음중앙=")
            append(if (prominences.isEmpty()) "-" else "%.1f".format(median(prominences)))
            append(" 흔들림=")
            append(bestDrift?.let { "%.1f".format(it) } ?: "-")
            append(" 연속=")
            append(bestContinuity?.let { "%.2f".format(it) } ?: "-")
            append(" 봉우리프레임=$framesWithPeak/$totalFrames")
        }
    }

    /**
     * 앱과 같은 배선으로 돌리되, **프레임마다 값을 받아 적는다.**
     *
     * `SpectralPeakFinder` 는 production 의 것을 그대로 쓴다 — 검증용으로
     * 다시 구현하면 무엇을 재는지 알 수 없게 된다.
     */
    private fun trace(
        fs: Int,
        fftSize: Int,
        seconds: Double,
        sample: (Int) -> Double,
    ): Trace {
        val rta = RtaEngine(fs, fftSize = fftSize)
        val detector = FeedbackDetector(fftSize, fs)
        val finder = SpectralPeakFinder(fftSize, fs)

        val widths = ArrayList<Int>()
        val prominences = ArrayList<Double>()
        var frames = 0
        var framesWithPeak = 0
        var everCandidate = false
        // **가장 유리한 후보**를 기준으로 본다. 마지막 후보를 보면 같은
        // 소리가 여러 track 으로 갈렸을 때 엉뚱한 것을 집는다 — 「제일
        // 나은 것조차 못 넘었다」가 탈락 이유로 방어된다.
        var bestDrift: Double? = null
        var bestContinuity: Double? = null
        var detected = false
        var blockMs = 0L

        rta.spectrumSink = SpectrumSink { power ->
            frames++
            // **production 의 finder 로 그 프레임의 값을 잰다.**
            val peak = finder.find(power, MIN_PROMINENCE_DB)
                .filter { abs(it.hz - 1000.0) < 80 }
                .maxByOrNull { it.power }
            if (peak != null) {
                framesWithPeak++
                widths.add(peak.widthBins)
                prominences.add(peak.prominenceDb)
            }
            detector.process(power, blockMs)
        }

        val total = (seconds * fs).toInt()
        var i = 0
        var fed = 0L
        while (i < total) {
            val n = minOf(1024, total - i)
            val buf = FloatArray(n) { sample(i + it).toFloat() }
            fed += n
            blockMs = fed * 1000L / fs
            rta.process(buf, n)
            for (c in detector.candidates) {
                if (abs(c.hz - 1000.0) > 80) continue
                everCandidate = true
                if (bestDrift == null || c.driftCents < bestDrift!!) bestDrift = c.driftCents
                if (bestContinuity == null || c.continuity > bestContinuity!!) bestContinuity = c.continuity
                if (c.state == FeedbackState.Persistent) detected = true
            }
            i += n
        }

        val reason = when {
            detected -> Reason.Detected
            framesWithPeak == 0 -> Reason.NoPeak
            // 봉우리는 있었는데 후보가 아예 안 됐다면 폭에서 걸린 것이다.
            !everCandidate && widths.isNotEmpty() && widths.sorted()[widths.size / 2] > 5 -> Reason.TooWide
            !everCandidate -> Reason.NoPeak
            bestContinuity != null && bestContinuity!! < 0.85 -> Reason.Continuity
            bestDrift != null && bestDrift!! > 35.0 -> Reason.Drift
            else -> Reason.TooShort
        }
        return Trace(reason, widths, prominences, bestDrift, bestContinuity, framesWithPeak, frames)
    }

    /** 1층 시험과 같은 방식으로 빚은 폭 있는 봉우리. */
    private fun bump(hz: Double, bandwidthHz: Double, fs: Int, seed: Int): (Int) -> Double {
        val half = bandwidthHz / 2.0
        val rng = Random(seed)
        val lines = ArrayList<Triple<Double, Double, Double>>()
        var power = 0.0
        var k = -120
        while (k <= 120) {
            val offset = k * 2.0
            val a = 1.0 / (1.0 + (offset / half) * (offset / half))
            lines.add(Triple(hz + offset, kotlin.math.sqrt(a), rng.nextDouble() * 2 * PI))
            power += a
            k++
        }
        val scale = 0.25 / kotlin.math.sqrt(power / 2.0)
        return { i ->
            val t = i.toDouble() / fs
            var y = 0.0
            for ((f, a, ph) in lines) y += a * sin(2 * PI * f * t + ph)
            y * scale
        }
    }

    private fun tone(hz: Double, fs: Int): (Int) -> Double = { i -> 0.2 * sin(2 * PI * hz * i / fs) }

    /**
     * **순음은 두 레이트 모두에서 같은 이유로 통과한다.**
     *
     * 아래 시험들이 뜻을 가지려면 이것이 먼저 성립해야 한다.
     */
    @Test
    fun `순음은 두 레이트에서 모두 통과한다`() {
        val a = trace(FS_48, 4096, 3.0, tone(1000.0, FS_48))
        val b = trace(FS_44, 4096, 3.0, tone(1000.0, FS_44))
        println("[순음] 48k: $a")
        println("[순음] 44.1k: $b")
        assertEquals(Reason.Detected, a.reason)
        assertEquals(Reason.Detected, b.reason)
        // **여기서 내가 틀렸다.** 「순음의 칸 폭은 레이트와 무관하다」고
        // 단언했는데 48kHz 에서 2칸, 44.1kHz 에서 1칸이 나왔다. half-power
        // 이상인 **연속 칸의 개수**는 봉우리가 칸 한가운데에서 얼마나
        // 비켜났느냐에 따라 1칸이 되기도 2칸이 되기도 한다 — 1000Hz 는
        // 48kHz 에서 칸 85.33, 44.1kHz 에서 칸 92.88 로 비켜난 정도가 다르다.
        // 레이트의 법칙이 아니라 **양자화**다. 그래서 「좁다」만 단언한다.
        val wa = a.widths.sorted()[a.widths.size / 2]
        val wb = b.widths.sorted()[b.widths.size / 2]
        assertTrue("순음은 두 레이트 모두에서 좁아야 한다 ($wa / $wb)", wa <= 2 && wb <= 2)
    }

    /**
     * **폭이 있는 소리는 무엇에 걸려 탈락하는가.**
     *
     * 1층에서 폭 문턱이 70.3Hz(48k)·64.6Hz(44.1k) 로 나왔으니, 2~20Hz
     * 짜리는 **폭 때문에 떨어질 리가 없다.** 정말 그런지 센다.
     */
    @Test
    fun `폭이 있는 소리는 폭 때문에 탈락하지 않는다`() {
        val byReason = HashMap<Reason, Int>()
        for (bw in listOf(2.0, 5.0, 10.0, 20.0)) {
            for (seed in 1..4) {
                for (fs in listOf(FS_48, FS_44)) {
                    val t = trace(fs, 4096, 2.5, bump(1000.0, bw, fs, seed))
                    byReason[t.reason] = (byReason[t.reason] ?: 0) + 1
                    if (seed == 1) println("[폭 ${bw}Hz ${fs}] $t")
                }
            }
        }
        println("[탈락 이유] $byReason")
        assertEquals(
            "폭 때문에 떨어진 것이 있으면 1층 결과와 어긋난다",
            0,
            byReason[Reason.TooWide] ?: 0,
        )
        assertTrue("적어도 몇은 잡혀야 시험이 뜻을 갖는다", (byReason[Reason.Detected] ?: 0) > 0)
    }

    /**
     * **분해능을 바꾸면 얼마나 달라지는가.**
     *
     * 처음에 이 시험을 「창 길이만 바꿔 칸 폭 차이를 원인에서 뺀다」고
     * 적었다. **그 말이 틀렸다.**
     *
     * ```
     * binHz = fs / N        T = N / fs        ⇒  binHz = 1 / T
     * ```
     *
     * 창 길이와 칸 폭은 **같은 변수**다. 하나를 바꾸지 않고 다른 하나만
     * 바꿀 수 없다. 그러니 「창이 길어서」와 「칸이 좁아서」는 **두 가설이
     * 아니라 한 가설을 두 가지로 말한 것**이다. 나는 그 둘을 후보로
     * 나란히 적어 두었는데 처음부터 나뉘지 않는 것이었다.
     *
     * 그래서 이 시험이 실제로 말하는 것은 하나다 — **분해능이 좋아지면
     * 이 신호들이 더 잡히는가.** 48kHz 에서 N 만 4096(85.3ms·11.72Hz)과
     * 8192(170.7ms·5.86Hz)로 바꿔 본다. 샘플레이트는 같으니 레이트에
     * 딸린 다른 차이는 빠진다.
     */
    @Test
    fun `분해능을 바꾸면 검출이 얼마나 달라지는지 잰다`() {
        var short4096 = 0
        var long8192 = 0
        val widths4096 = ArrayList<Int>()
        val widths8192 = ArrayList<Int>()

        for (bw in listOf(5.0, 10.0, 20.0)) {
            for (seed in 1..6) {
                val s = trace(FS_48, 4096, 2.5, bump(1000.0, bw, FS_48, seed))
                val l = trace(FS_48, 8192, 2.5, bump(1000.0, bw, FS_48, seed))
                if (s.reason == Reason.Detected) short4096++
                if (l.reason == Reason.Detected) long8192++
                widths4096.addAll(s.widths)
                widths8192.addAll(l.widths)
            }
        }

        println(
            "[분해능] 48kHz N=4096(85.3ms·11.72Hz)=$short4096/18 · " +
                "N=8192(170.7ms·5.86Hz)=$long8192/18 " +
                "· 폭중앙 ${widths4096.sorted().getOrNull(widths4096.size / 2)} vs " +
                "${widths8192.sorted().getOrNull(widths8192.size / 2)}",
        )

        // **어느 쪽이 나오든 단언하지 않는다** — 재려고 있는 시험이지
        // 미리 정한 답을 확인하려고 있는 것이 아니다. 둘 다 0 이면
        // 아무것도 재지 못한 것이라 그것만 막는다.
        assertTrue(
            "둘 다 하나도 안 잡히면 이 시험은 아무 말도 하지 못한다",
            short4096 + long8192 > 0,
        )
    }
}
