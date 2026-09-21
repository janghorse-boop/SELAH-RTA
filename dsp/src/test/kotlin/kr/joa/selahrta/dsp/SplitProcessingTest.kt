package kr.joa.selahrta.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * **덩어리를 쪼개 넣어도 결과가 같은가.**
 *
 * 녹음 설계의 전제다(보완 설계 3차 A02). 한 블록(1024프레임·21.3ms)이
 * 500ms 행 경계나 보정 변경 지점을 **걸치면**, 그 블록의 극값 하나로는
 * 행별 MAX·Peak 를 맞게 구할 수 없다 — 23999 의 임펄스와 24000 의
 * 임펄스가 같은 블록 극값을 내지만 서로 다른 행에 속한다.
 *
 * 그래서 경계에서 블록을 쪼개 넣으려는데, **그러면 결과가 달라지는가**를
 * 먼저 알아야 한다. 시간가중이 표본마다 도는 1차 필터라 같을 것으로
 * 보지만, **재지 않고 적으면 그것이 바로 이 프로젝트에서 여러 번
 * 틀렸던 자리**다.
 */
class SplitProcessingTest {

    private val fs = 48_000

    /** 1kHz 순음에 가끔 임펄스가 섞인 신호. 극값이 생기게 한다. */
    private fun signal(n: Int): FloatArray = FloatArray(n) { i ->
        val base = 0.2 * sin(2 * PI * 1000 * i / fs)
        val spike = if (i % 7919 == 0) 0.9 else 0.0
        (base + spike).toFloat()
    }

    /** 나눌 지점들. 경계를 어중간하게 걸치는 값들로 고른다. */
    private val cuts = listOf(1, 13, 448, 1023, 1024, 2000, 3001)

    /**
     * **SPL 엔진** — 쪼개 넣어도 A·C·Z 의 모든 값이 같아야 한다.
     */
    @Test
    fun `SPL 엔진은 쪼개 넣어도 같은 값을 낸다`() {
        val n = 24_576
        val x = signal(n)

        val whole = MultiWeightEngine(sampleRate = fs, timeWeight = TimeWeight.Fast)
        val split = MultiWeightEngine(sampleRate = fs, timeWeight = TimeWeight.Fast)

        var wholeFrame: MultiWeightFrame? = null
        var splitFrame: MultiWeightFrame? = null

        // 한 번에
        var i = 0
        while (i < n) {
            val take = minOf(1024, n - i)
            wholeFrame = whole.process(x.copyOfRange(i, i + take), take)
            i += take
        }

        // 쪼개서 — 같은 표본을 같은 순서로, 경계만 다르게
        i = 0
        var c = 0
        while (i < n) {
            val take = minOf(cuts[c % cuts.size], n - i)
            splitFrame = split.process(x.copyOfRange(i, i + take), take)
            i += take
            c++
        }

        assertNotNull(wholeFrame)
        assertNotNull(splitFrame)
        for (w in Weighting.entries) {
            val a = wholeFrame!!.of(w)
            val b = splitFrame!!.of(w)
            println("[SPL ${w.name}] 한번에 max=${"%.9f".format(a.maxDbfs.value)} peak=${"%.9f".format(a.peakDbfs.value)} / 쪼개서 max=${"%.9f".format(b.maxDbfs.value)} peak=${"%.9f".format(b.peakDbfs.value)}")
            assertEquals("${w.name} MAX", a.maxDbfs.value, b.maxDbfs.value, 0.0)
            assertEquals("${w.name} Peak", a.peakDbfs.value, b.peakDbfs.value, 0.0)
            assertEquals("${w.name} 현재", a.currentDbfs.value, b.currentDbfs.value, 0.0)
        }
    }

    /**
     * **RTA 엔진** — 쪼개 넣어도 31밴드가 같아야 한다.
     *
     * 원형 버퍼에 모았다가 hop 마다 FFT 를 돌리므로, 넣는 단위가 달라도
     * 같은 자리에서 같은 FFT 가 돌아야 한다.
     */
    @Test
    fun `RTA 엔진은 쪼개 넣어도 같은 밴드를 낸다`() {
        val n = 24_576
        val x = signal(n)

        val whole = RtaEngine(fs)
        val split = RtaEngine(fs)

        var i = 0
        while (i < n) {
            val take = minOf(1024, n - i)
            whole.process(x.copyOfRange(i, i + take), take)
            i += take
        }
        i = 0
        var c = 0
        while (i < n) {
            val take = minOf(cuts[c % cuts.size], n - i)
            split.process(x.copyOfRange(i, i + take), take)
            i += take
            c++
        }

        val a = whole.frame()
        val b = split.frame()
        assertNotNull("한 번에 넣은 쪽에 프레임이 있어야 한다", a)
        assertNotNull("쪼개 넣은 쪽에도 있어야 한다", b)
        println("[RTA] 첫 밴드 ${"%.9f".format(a!!.bandsDbfs[0])} vs ${"%.9f".format(b!!.bandsDbfs[0])}")
        assertArrayEquals("31밴드가 같아야 한다", a.bandsDbfs, b.bandsDbfs, 0.0)
        assertArrayEquals("Peak Hold 도 같아야 한다", a.holdDbfs, b.holdDbfs, 0.0)
    }

    /**
     * **하울링 탐지기까지** 같은 스펙트럼을 본다.
     *
     * RTA 가 같으면 따라오는 것이지만, 실제로 확인해 둔다.
     */
    @Test
    fun `하울링 탐지도 쪼개 넣기에 영향받지 않는다`() {
        val n = 48_000 * 3
        val x = signal(n)

        fun run(chunks: List<Int>): List<Int> {
            val rta = RtaEngine(fs)
            val det = FeedbackDetector(rta.fftSize, fs)
            var ms = 0L
            rta.spectrumSink = SpectrumSink { p -> det.process(p, ms) }
            var i = 0
            var c = 0
            var fed = 0L
            while (i < n) {
                val take = minOf(chunks[c % chunks.size], n - i)
                fed += take
                ms = fed * 1000L / fs
                rta.process(x.copyOfRange(i, i + take), take)
                i += take
                c++
            }
            return det.events.map { it.hz.toInt() }.sorted()
        }

        val a = run(listOf(1024))
        val b = run(cuts)
        println("[하울링] 한번에 $a / 쪼개서 $b")
        assertEquals("같은 기록이 나와야 한다", a, b)
    }

    /**
     * **잡음으로도 확인한다.** 순음만으로는 우연히 맞을 수 있다.
     */
    @Test
    fun `잡음으로도 쪼개 넣기가 결과를 바꾸지 않는다`() {
        val n = 16_384
        val rng = Random(42)
        val x = FloatArray(n) { (rng.nextDouble() * 2 - 1).toFloat() * 0.3f }

        val whole = MultiWeightEngine(sampleRate = fs, timeWeight = TimeWeight.Fast)
        val split = MultiWeightEngine(sampleRate = fs, timeWeight = TimeWeight.Fast)
        var i = 0
        while (i < n) {
            val take = minOf(1024, n - i)
            whole.process(x.copyOfRange(i, i + take), take)
            i += take
        }
        i = 0
        var c = 0
        var last: MultiWeightFrame? = null
        while (i < n) {
            val take = minOf(cuts[c % cuts.size], n - i)
            last = split.process(x.copyOfRange(i, i + take), take)
            i += take
            c++
        }
        val a = whole.process(FloatArray(0), 0) ?: error("프레임이 없다")
        assertNotNull(last)
        assertEquals("A 가중 MAX", a.a.maxDbfs.value, last!!.a.maxDbfs.value, 0.0)
        assertEquals("A 가중 Peak", a.a.peakDbfs.value, last.a.peakDbfs.value, 0.0)
    }
}
