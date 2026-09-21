import kr.joa.selahrta.dsp.*
import kotlin.math.*

private const val FS = 48000
private const val N = 4096
private fun power(vararg frequencies: Double): DoubleArray {
    val p = DoubleArray(N / 2 + 1)
    PowerSpectrum(N).compute(DoubleArray(N) { i ->
        frequencies.sumOf { f -> 0.2 * sin(2 * PI * f * i / FS) }
    }, 0, p)
    return p
}
fun main() {
    val tone = power(1000.0)
    val gap = FeedbackDetector(N, FS)
    gap.process(tone, 0)
    gap.process(tone, 2000)
    println("GAP_TWO_FRAMES candidates=${gap.candidates} events=${gap.events}")
    check(gap.candidates.any { it.state == FeedbackState.Persistent })

    val close = FeedbackDetector(N, FS)
    val pair = power(8000.0, 8080.0)
    println("PAIR_PEAKS ${SpectralPeakFinder(N, FS).find(pair)}")
    for (i in 0..50) close.process(pair, i * 43L)
    println("PAIR_TRACKS ${close.candidates}")
    check(close.candidates.any { it.continuity > 1.0 })
    val intermittent = FeedbackDetector(N, FS)
    for (i in 0..60) intermittent.process(if (i % 2 == 0) pair else DoubleArray(N / 2 + 1), i * 43L)
    println("HALF_FRAMES_PAIR ${intermittent.candidates}")
    check(intermittent.candidates.any { it.state == FeedbackState.Persistent && it.continuity > 1.0 })

    val log = FeedbackDetector(N, FS)
    for (i in 0..60) log.process(tone, i * 43L)
    // A small pitch change stays within matching tolerance but extends total drift > 35 cents.
    for (i in 61..90) log.process(power(1012.0), i * 43L)
    for (i in 91..120) log.process(power(1024.0), i * 43L)
    for (i in 121..140) log.process(DoubleArray(N / 2 + 1), i * 43L)
    println("DRIFT_EVENT ${log.events}")
    check(log.events.any { it.endMs != null && it.durationMs != it.endMs!! - it.startMs })

    // Speech-test formula is independent white noise times a deterministic carrier.
    // No claim of real speech/venue accuracy is made by this probe.
    println("PROBES confirmed three residual detector defects; not Android/device tests")
}

