package kr.joa.selahrta.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * **덩어리를 쪼개 넣어도 결과가 같은가.**
 *
 * 녹음 설계의 전제다(보완 설계 3차 A02). 한 블록(1024프레임·21.3ms)이
 * 500ms 행 경계나 보정 변경 지점을 **걸치면**, 그 블록의 극값 하나로는
 * 행별 MAX·Peak 를 맞게 구할 수 없다.
 *
 * ## 처음 쓴 시험이 약속보다 좁았다 (독립 검증 M33)
 *
 * 나는 「오차 0.0, live 결과 전부 동일」이라고 적었는데, 시험은 하울링
 * 기록을 **`hz.toInt()` 목록으로만** 견주고 SPL 은 MAX·Peak·현재만 봤다.
 * 시각·지속 시간·Leq 가 모두 지워진 비교였다. 검증자가 넓게 재니 차이가
 * 있었다:
 *
 * ```
 * startMs 85 vs 93 · durationMs 2901 vs 2907
 * C session Leq …842537 vs …842534
 * ```
 *
 * **이 프로젝트에서 되풀이되는 내 실수다** — 결론이 단언보다 앞선다.
 *
 * ## 그래서 계약을 먼저 적는다
 *
 * 1. **시각은 캡처 블록이 정한다.** 한 블록을 몇 조각으로 나누든 그
 *    조각들은 **같은 블록 시각**을 쓴다 — 실제 `CaptureController` 가
 *    `session.spectrumMs` 를 블록마다 한 번 세우는 것과 같다.
 * 2. **비트 단위로 같아야 하는 것**: current·MAX·Peak·peakClipped·
 *    leqShort·leqLong·leqLongFull·settled, 31밴드, Peak Hold, 하울링
 *    기록 **전체**.
 * 3. **오차를 허용하는 것**: `leqSession` 만. `EnergyAverage` 가 블록별
 *    RMS 를 다시 제곱해 더하므로 **더하는 묶음이 달라지면 부동소수점
 *    끝자리가 흔들린다.** 허용치는 **재기 전에** 정했다.
 */
class SplitProcessingTest {

    private companion object {
        const val FS = 48_000

        /** 캡처 한 덩어리. `CaptureLoop` 와 같다. */
        const val BLOCK = 1024

        /**
         * `leqSession` 에만 허용하는 오차(dB). **재기 전에 정했다.**
         *
         * 부동소수점 끝자리 몇 개가 흔들리는 것이라 1e-9dB 면 넉넉하다 —
         * 화면은 소수 첫째 자리까지만 쓴다.
         */
        const val SESSION_LEQ_TOLERANCE_DB = 1e-9
    }

    /** 1kHz 순음에 가끔 임펄스가 섞인 신호. 극값이 생기게 한다. */
    private fun signal(n: Int): FloatArray = FloatArray(n) { i ->
        val base = 0.2 * sin(2 * PI * 1000 * i / FS)
        val spike = if (i % 7919 == 0) 0.9 else 0.0
        (base + spike).toFloat()
    }

    /** 한 블록을 이 크기들로 쪼갠다. 경계를 어중간하게 걸치는 값들이다. */
    private val cuts = listOf(1, 13, 448, 1023, 300, 700, 512)

    private class Run(
        val frames: List<MultiWeightFrame>,
        val rta: RtaFrame?,
        val events: List<FeedbackEvent>,
    )

    /**
     * **실제 배선과 같은 방식으로 돌린다.**
     *
     * 캡처 블록 단위로 돌되, [split] 이면 블록 안을 다시 쪼개 넣는다.
     * **시각은 블록마다 한 번만 세운다** — 위 계약 1번.
     */
    private fun run(x: FloatArray, split: Boolean): Run {
        val spl = MultiWeightEngine(sampleRate = FS, timeWeight = TimeWeight.Fast)
        val rta = RtaEngine(FS)
        val det = FeedbackDetector(rta.fftSize, FS)
        var blockMs = 0L
        rta.addSpectrumSink { p -> det.process(p, blockMs) }

        val frames = ArrayList<MultiWeightFrame>()
        var i = 0
        var fed = 0L
        var c = 0
        while (i < x.size) {
            val blockFrames = minOf(BLOCK, x.size - i)
            // **블록 시각을 먼저 세운다.** 조각들이 이 값을 함께 쓴다.
            fed += blockFrames
            blockMs = fed * 1000L / FS

            if (!split) {
                frames.add(spl.process(x.copyOfRange(i, i + blockFrames), blockFrames))
                rta.process(x.copyOfRange(i, i + blockFrames), blockFrames)
            } else {
                var off = 0
                while (off < blockFrames) {
                    val take = minOf(cuts[c % cuts.size], blockFrames - off)
                    val from = i + off
                    frames.add(spl.process(x.copyOfRange(from, from + take), take))
                    rta.process(x.copyOfRange(from, from + take), take)
                    off += take
                    c++
                }
            }
            i += blockFrames
        }
        return Run(frames, rta.frame(), det.events)
    }

    /** 약속한 대로 **전부** 견준다. */
    private fun assertSameSpl(a: SplFrame, b: SplFrame, tag: String) {
        assertEquals("$tag 현재", a.currentDbfs.value, b.currentDbfs.value, 0.0)
        assertEquals("$tag MAX", a.maxDbfs.value, b.maxDbfs.value, 0.0)
        assertEquals("$tag Peak", a.peakDbfs.value, b.peakDbfs.value, 0.0)
        assertEquals("$tag peakClipped", a.peakClipped, b.peakClipped)
        assertEquals("$tag leqLongFull", a.leqLongFull, b.leqLongFull)
        assertEquals("$tag settled", a.settled, b.settled)
        assertEquals("$tag leqShort 유무", a.leqShortDbfs == null, b.leqShortDbfs == null)
        assertEquals("$tag leqLong 유무", a.leqLongDbfs == null, b.leqLongDbfs == null)
        a.leqShortDbfs?.let { assertEquals("$tag leqShort", it.value, b.leqShortDbfs!!.value, 0.0) }
        a.leqLongDbfs?.let { assertEquals("$tag leqLong", it.value, b.leqLongDbfs!!.value, 0.0) }
        a.leqSessionDbfs?.let {
            val d = abs(it.value - b.leqSessionDbfs!!.value)
            assertTrue(
                "$tag leqSession 차이가 허용치를 넘는다: ${it.value} vs ${b.leqSessionDbfs!!.value} (차 $d)",
                d <= SESSION_LEQ_TOLERANCE_DB,
            )
        }
    }

    /** **SPL 의 모든 필드를 견준다.** */
    @Test
    fun `쪼개 넣어도 SPL 의 모든 필드가 약속대로다`() {
        val x = signal(FS * 3)
        val whole = run(x, split = false)
        val split = run(x, split = true)

        val a = whole.frames.last()
        val b = split.frames.last()
        for (w in Weighting.entries) {
            val fa = a.of(w)
            val fb = b.of(w)
            println(
                "[SPL ${w.name}] max ${"%.12f".format(fa.maxDbfs.value)} / ${"%.12f".format(fb.maxDbfs.value)}" +
                    " · session ${fa.leqSessionDbfs?.value} / ${fb.leqSessionDbfs?.value}",
            )
            assertSameSpl(fa, fb, w.name)
        }
    }

    /**
     * **하울링 기록을 통째로 견준다.**
     *
     * 예전에는 `hz.toInt()` 목록만 봐서 시각·지속·솟음이 지워졌다.
     */
    @Test
    fun `쪼개 넣어도 하울링 기록이 통째로 같다`() {
        val x = signal(FS * 3)
        val whole = run(x, split = false)
        val split = run(x, split = true)

        println("[하울링] 한번에 ${whole.events}")
        println("[하울링] 쪼개서 ${split.events}")
        assertEquals("기록 개수", whole.events.size, split.events.size)
        assertEquals("기록 전체가 같아야 한다", whole.events, split.events)
    }

    /** RTA 31밴드와 Peak Hold. */
    @Test
    fun `쪼개 넣어도 31밴드가 같다`() {
        val x = signal(FS * 3)
        val a = run(x, split = false).rta
        val b = run(x, split = true).rta
        assertNotNull(a)
        assertNotNull(b)
        println("[RTA] 첫 밴드 ${"%.12f".format(a!!.bandsDbfs[0])} vs ${"%.12f".format(b!!.bandsDbfs[0])}")
        assertArrayEquals("31밴드", a.bandsDbfs, b.bandsDbfs, 0.0)
        assertArrayEquals("Peak Hold", a.holdDbfs, b.holdDbfs, 0.0)
    }

    /** 잡음으로도 확인한다. 순음만으로는 우연히 맞을 수 있다. */
    @Test
    fun `잡음으로도 쪼개 넣기가 결과를 바꾸지 않는다`() {
        val rng = Random(42)
        val x = FloatArray(FS * 2) { (rng.nextDouble() * 2 - 1).toFloat() * 0.3f }
        val whole = run(x, split = false)
        val split = run(x, split = true)
        for (w in Weighting.entries) {
            assertSameSpl(whole.frames.last().of(w), split.frames.last().of(w), "잡음 ${w.name}")
        }
        assertArrayEquals("31밴드", whole.rta!!.bandsDbfs, split.rta!!.bandsDbfs, 0.0)
    }

    /**
     * **시각 계약이 왜 필요한지 보인다.**
     *
     * 조각마다 시각을 다시 세면 하울링의 시작·지속이 달라진다 — 검증자가
     * 잰 85 vs 93, 2901 vs 2907 이 그것이다. 위 시험이 「계약을 지키면
     * 같다」를 보이고, 이 시험이 **「어기면 달라진다」**를 보인다.
     */
    @Test
    fun `조각마다 시각을 다시 세면 하울링 판정이 달라진다`() {
        val x = signal(FS * 3)

        fun runWrong(): List<FeedbackEvent> {
            val rta = RtaEngine(FS)
            val det = FeedbackDetector(rta.fftSize, FS)
            var ms = 0L
            rta.addSpectrumSink { p -> det.process(p, ms) }
            var i = 0
            var fed = 0L
            var c = 0
            while (i < x.size) {
                val take = minOf(cuts[c % cuts.size], x.size - i)
                // **조각마다** 시각을 센다 — 계약 위반.
                fed += take
                ms = fed * 1000L / FS
                rta.process(x.copyOfRange(i, i + take), take)
                i += take
                c++
            }
            return det.events
        }

        val right = run(x, split = false).events
        val wrong = runWrong()
        println("[시각 계약] 지킴 ${right.map { it.startMs to it.durationMs }}")
        println("[시각 계약] 어김 ${wrong.map { it.startMs to it.durationMs }}")
        assertTrue("둘 다 기록이 있어야 한다", right.isNotEmpty() && wrong.isNotEmpty())
        assertTrue(
            "계약을 어기면 달라진다는 것이 이 시험의 요지다",
            right.first().startMs != wrong.first().startMs ||
                right.first().durationMs != wrong.first().durationMs,
        )
    }
}
