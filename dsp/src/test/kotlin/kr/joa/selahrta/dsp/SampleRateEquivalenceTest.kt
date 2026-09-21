package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 같은 소리를 44.1kHz 와 48kHz 에서 재면 같게 판정되는가.
 *
 * **왜 이 시험이 있는가** — 독립 검증자가 ADR 로 갈음하는 것을 거절했다:
 *
 * > 44.1 kHz에서도 검증하는 것은 필요하다. 48 kHz 대비 폭은 약 8.1%
 * > 작아지며, 이는 현재 잠정 판정의 샘플레이트 의존성이다. 두
 * > 샘플레이트에서 같은 PCM 신호군의 검출 여부·지연·오탐을 비교하고
 * > 허용 차이를 정한다. **ADR에 한계를 적는 것만으로 이 시험을 대체할 수
 * > 없다.**
 *
 * **무엇이 샘플레이트에 매여 있는가.** FFT 길이는 4096 으로 고정이므로
 * 칸 폭이 바뀐다 — 48kHz 에서 11.72Hz, 44.1kHz 에서 10.77Hz(8.1% 좁다).
 * 판정 기준 가운데 **칸으로 적힌 것**은 그만큼 좁은 주파수를 가리키게
 * 된다:
 *
 * | 기준 | 값 | 48kHz | 44.1kHz |
 * |---|---|---|---|
 * | `maxWidthBins` | 5칸 | **70.3Hz 직전까지** | **64.6Hz 직전까지** |
 * | 둘레 안쪽(`skirtBins`) | 4칸 | ±46.9Hz 바깥 | ±43.1Hz 바깥 |
 * | 둘레 바깥(`neighbourhoodBins`) | 48칸 | ±562Hz 까지 | ±517Hz 까지 |
 *
 * 시간 기준(`suspectMs`·`persistentMs`·`gapMs`)과 cent 기준은 매여 있지
 * 않다. 다만 **FFT 가 나오는 간격**이 다르다 — 겹침 절반이라 2048표본마다
 * 한 장이고, 그것이 48kHz 에서 42.7ms, 44.1kHz 에서 46.4ms 다. 그래서
 * 「지속」이 되는 시각은 더 굵은 눈금에 걸린다.
 *
 * **앱과 같은 배선으로 잰다.** 다른 시험들은 프레임 간격을 시험이 정한
 * 상수로 두는데, 여기서는 그러면 안 된다 — 재려는 것이 바로 그 간격의
 * 차이이기 때문이다. [RtaEngine] 이 제 규칙대로 FFT 를 돌리고 그
 * 스펙트럼을 탐지기가 본다([CaptureViewModel] 과 같다).
 */
class SampleRateEquivalenceTest {

    private companion object {
        /** 실제로 쓰이는 두 값. 44.1kHz 는 옛 기기와 일부 USB 인터페이스다. */
        const val FS_48 = 48_000
        const val FS_44 = 44_100

        /** 한 덩어리는 1024 프레임이다(`CaptureLoop` 와 같다). */
        const val BLOCK = 1024
    }

    // ---- 앱과 같은 배선 ----

    /**
     * [RtaEngine] → [SpectrumSink] → [FeedbackDetector].
     *
     * 시각은 **덩어리를 받은 시각**으로 준다 — `CaptureViewModel.spectrumMs`
     * 와 같다. 표본 수에서 내므로 기계 속도에 좌우되지 않는다.
     */
    private class Pipeline(val fs: Int) {
        val rta = RtaEngine(fs)
        val detector = FeedbackDetector(rta.fftSize, fs)
        private var blockMs = 0L
        private var fed = 0L

        init {
            rta.spectrumSink = SpectrumSink { power -> detector.process(power, blockMs) }
        }

        /** 지금까지 흘려 넣은 시간(ms). */
        val nowMs: Long get() = blockMs

        /** 한 덩어리를 넣는다. 시각을 **먼저** 세운다 — sink 가 이 안에서 불린다. */
        fun feed(block: FloatArray) {
            fed += block.size
            blockMs = fed * 1000L / fs
            rta.process(block, block.size)
        }
    }

    /** 한 번 돌린 결과. 검증자가 견주라고 한 세 가지를 담는다. */
    private class Run(
        /** 「지속」까지 간 것이 있었는가. */
        val detected: Boolean,
        /** 그 주파수(Hz). 없으면 null. */
        val hz: Double?,
        /** 신호가 시작된 뒤 「지속」이 되기까지(ms). 없으면 null. */
        val latencyMs: Long?,
        /** 「지속」까지 간 **모든** 주파수. 잡음 신호에서는 이것이 곧 오탐이다. */
        val persistentHz: List<Int>,
        /** FFT 가 나오는 간격(ms). */
        val hopMs: Double,
    ) {
        override fun toString() =
            "detected=$detected hz=${hz?.let { "%.1f".format(it) }} " +
                "latency=${latencyMs}ms hop=${"%.1f".format(hopMs)}ms 지속=$persistentHz"
    }

    /**
     * [seconds] 초 동안 [sample] 이 내는 소리를 [fs] 로 재생해 넣는다.
     *
     * [sample] 은 **표본 번호**를 받는다. 같은 소리를 두 샘플레이트로
     * 그리려면 시간이 `i / fs` 로 들어가야 한다.
     */
    private fun run(fs: Int, seconds: Double, sample: (Int) -> Double): Run {
        val p = Pipeline(fs)
        val total = (seconds * fs).toInt()
        val seen = LinkedHashSet<Int>()
        var latency: Long? = null
        var hz: Double? = null
        var i = 0
        while (i < total) {
            val n = min(BLOCK, total - i)
            val block = FloatArray(n) { sample(i + it).toFloat() }
            p.feed(block)
            for (c in p.detector.candidates) {
                if (c.state != FeedbackState.Persistent) continue
                seen.add(c.hz.toInt())
                if (latency == null) {
                    latency = p.nowMs
                    hz = c.hz
                }
            }
            i += n
        }
        return Run(latency != null, hz, latency, seen.toList(), 2048.0 * 1000.0 / fs)
    }

    // ---- 신호. 소리로 적는다(Hz·초). 표본은 샘플레이트가 정한다 ----

    private fun tone(hz: Double, amp: Double = 0.2, fs: Int): (Int) -> Double =
        { i -> amp * sin(2 * PI * hz * i / fs) }

    /**
     * **폭이 있는** 소리. 순음보다 넓게 퍼진 공진을 흉내 낸다.
     *
     * [bandwidthHz] 짜리 로렌츠 모양으로 진폭을 준 순음들을
     * [LINE_SPACING_HZ] 간격으로 쌓는다. 위상은 고정 씨앗에서 뽑는다 —
     * 전부 0 으로 두면 마루가 한 점에 겹쳐 잘린다.
     *
     * **한계를 있는 그대로 적는다.** 이것으로 「폭 문턱이 어디인가」를
     * 정밀하게 재려 했지만 그럴 수 없었다. 분석 창이 85ms 라 분해능이
     * 11.7Hz 인데, 그보다 넓은 구조는 **한 창 안에서 잡음과 구별되지
     * 않는다** — 줄들의 간섭 무늬가 프레임마다 달라져 봉우리가 들쭉날쭉해
     * 진다. 실제로 140Hz 짜리 봉우리의 칸 수가 48kHz 에서 2칸, 44.1kHz 에서
     * 4칸으로 나왔는데, 이것은 칸 폭 비(1.088)가 아니라 **우연**이다.
     *
     * 앞서 백색 잡음을 띠통과로 걸러 썼을 때는 더 나빴다 — 봉우리가 몇 Hz
     * 씩 떠돌아 한 소리가 track 일곱 개로 쪼개졌다(999·997·998·1001·
     * 1000·1002·1003Hz).
     *
     * 그래서 이 신호로는 **「두 레이트의 판정이 갈리는가」만** 본다. 문턱
     * 자체는 계산으로 못박는다(`칸으로 적힌 기준이 Hz 로 얼마나 다른지`).
     */
    private fun bump(hz: Double, bandwidthHz: Double, fs: Int, seed: Int = 9, amp: Double = 0.25): (Int) -> Double {
        val half = bandwidthHz / 2.0
        val lines = ArrayList<Triple<Double, Double, Double>>()
        val rng = Random(seed)
        var k = -LINE_COUNT
        var power = 0.0
        while (k <= LINE_COUNT) {
            val offset = k * LINE_SPACING_HZ
            // 로렌츠: 반폭에서 전력이 절반(−3dB)이 된다.
            val a = 1.0 / (1.0 + (offset / half) * (offset / half))
            lines.add(Triple(hz + offset, kotlin.math.sqrt(a), rng.nextDouble() * 2 * PI))
            power += a
            k++
        }
        // 전체 실효값을 순음과 비슷하게 맞춘다. 폭이 달라도 레벨은 같게.
        val scale = amp / kotlin.math.sqrt(power / 2.0)
        return { i ->
            val t = i.toDouble() / fs
            var y = 0.0
            for ((f, a, ph) in lines) y += a * sin(2 * PI * f * t + ph)
            y * scale
        }
    }

    /**
     * 비브라토가 걸린 목소리 흉내. **잡으면 안 되는** 신호다.
     *
     * 실제 노래는 ±50~100cent 흔들린다. 하울링은 거의 안 흔들린다.
     */
    private fun vibrato(hz: Double, cents: Double, rateHz: Double, fs: Int): (Int) -> Double {
        var phase = 0.0
        return { i ->
            val t = i.toDouble() / fs
            val f = hz * Math.pow(2.0, cents / 1200.0 * sin(2 * PI * rateHz * t))
            phase += 2 * PI * f / fs
            0.2 * sin(phase)
        }
    }

    // ---- 1. 검출 여부와 지연 ----

    /**
     * 하울링다운 순음은 **두 샘플레이트 모두에서** 잡힌다.
     *
     * 지연 차이의 허용치는 재서 정했다 — 아래 `허용 지연 차이` 를 보라.
     */
    @Test
    fun `순음은 두 샘플레이트에서 똑같이 잡힌다`() {
        for (f in listOf(250.0, 1000.0, 4000.0, 8000.0)) {
            val a = run(FS_48, 3.0) { i -> tone(f, fs = FS_48)(i) }
            val b = run(FS_44, 3.0) { i -> tone(f, fs = FS_44)(i) }
            println("[순음 ${f.toInt()}Hz] 48k=$a | 44.1k=$b")

            assertTrue("${f.toInt()}Hz 가 48kHz 에서 안 잡힌다: $a", a.detected)
            assertTrue("${f.toInt()}Hz 가 44.1kHz 에서 안 잡힌다: $b", b.detected)
            assertEquals("주파수가 어긋난다", a.hz!!, b.hz!!, f * 0.005)
            assertTrue(
                "지연 차이가 허용치를 넘는다: 48k=${a.latencyMs}ms 44.1k=${b.latencyMs}ms",
                abs(a.latencyMs!! - b.latencyMs!!) <= MAX_LATENCY_DIFF_MS,
            )
        }
    }

    // ---- 2. 오탐 ----

    /** 핑크 잡음은 **두 샘플레이트 모두에서** 아무것도 만들지 않는다. */
    @Test
    fun `핑크 잡음은 두 샘플레이트에서 오탐을 내지 않는다`() {
        for (fs in listOf(FS_48, FS_44)) {
            val pink = PinkNoise(Random(7))
            val r = run(fs, 5.0) { pink.next() * 0.3 }
            println("[핑크 ${fs}] $r")
            assertTrue("$fs 에서 오탐이 났다: ${r.persistentHz}", r.persistentHz.isEmpty())
        }
    }

    /** 비브라토 목소리도 **두 샘플레이트 모두에서** 넘어가지 않는다. */
    @Test
    fun `비브라토는 두 샘플레이트에서 오탐을 내지 않는다`() {
        for (fs in listOf(FS_48, FS_44)) {
            val r = run(fs, 5.0, vibrato(440.0, 70.0, 5.5, fs))
            println("[비브라토 ${fs}] $r")
            assertTrue("$fs 에서 오탐이 났다: ${r.persistentHz}", r.persistentHz.isEmpty())
        }
    }

    // ---- 3. 폭이 있는 소리에서 판정이 갈리는가 ----

    /**
     * 씨앗마다 짝지은 결과. **어느 쪽으로** 갈렸는지를 담는다.
     *
     * 총 검출 수만 견주면 **서로 다른 씨앗에서 같은 수만큼 잡은 경우**와
     * 「똑같이 잡은 경우」를 구별하지 못한다(독립 검증 M01).
     */
    private class Paired(val both: Int, val only48: Int, val only44: Int, val neither: Int) {
        val n get() = both + only48 + only44 + neither
        val total48 get() = both + only48
        val total44 get() = both + only44

        /** 짝이 어긋난 씨앗 수. 총 수가 같아도 이것이 크면 같은 판정이 아니다. */
        val discordant get() = only48 + only44

        /**
         * **동등하다고 말할 수 있는가.**
         *
         * 두 조건을 **모두** 넘겨야 한다 — 한쪽으로 쏠리지 않을 것, 그리고
         * 어긋난 짝 자체가 적을 것. 뒤엣것이 없으면 6 대 6 으로 완전히
         * 엇갈린 결과도 「동등」이 된다.
         */
        fun equivalent(maxSkew: Int, maxDiscordant: Int) =
            abs(only48 - only44) <= maxSkew && discordant <= maxDiscordant

        override fun toString() =
            "48k=$total48/$n 44.1k=$total44/$n (둘다=$both 48k만=$only48 44.1k만=$only44 어긋남=$discordant)"
    }

    /** 폭 [bandwidthHz] 에서 씨앗 [seeds] 개를 두 레이트에 똑같이 넣는다. */
    private fun pairedRuns(bandwidthHz: Double, seeds: Int): Paired {
        var both = 0
        var only48 = 0
        var only44 = 0
        var neither = 0
        for (seed in 1..seeds) {
            val a = run(FS_48, 2.0, bump(1000.0, bandwidthHz, FS_48, seed)).detected
            val b = run(FS_44, 2.0, bump(1000.0, bandwidthHz, FS_44, seed)).detected
            when {
                a && b -> both++
                a -> only48++
                b -> only44++
                else -> neither++
            }
        }
        return Paired(both, only48, only44, neither)
    }

    /**
     * **동등성 판정기가 제 일을 하는가.**
     *
     * 검증자가 요구한 회귀다(M01):
     *
     * > 한쪽만 모두 검출하는 결과가 동등성 판정을 통과하지 않는지, 동일
     * > 총 검출 수이지만 서로 다른 seed 에서만 검출하는 경우를 구별하는지
     * > 검사한다.
     *
     * 판정기를 먼저 시험해 두지 않으면 아래 실제 자료에 대한 판정을 믿을
     * 근거가 없다.
     */
    @Test
    fun `동등성 판정은 한쪽으로 쏠린 결과와 엇갈린 결과를 거른다`() {
        val oneSided = Paired(both = 0, only48 = 0, only44 = 12, neither = 0)
        assertEquals("총 수는 0 대 12", 0, oneSided.total48)
        assertFalse(
            "한쪽만 다 잡은 것을 동등이라 하면 안 된다",
            oneSided.equivalent(MAX_SKEW, MAX_DISCORDANT),
        )

        // 총 수는 6 대 6 으로 같지만, 같은 씨앗에서 함께 잡은 적이 한 번도 없다.
        val crossed = Paired(both = 0, only48 = 6, only44 = 6, neither = 0)
        assertEquals("총 수는 같다", crossed.total48, crossed.total44)
        assertFalse(
            "서로 다른 씨앗에서만 잡은 것을 동등이라 하면 안 된다",
            crossed.equivalent(MAX_SKEW, MAX_DISCORDANT),
        )

        val same = Paired(both = 9, only48 = 0, only44 = 0, neither = 3)
        assertTrue("정말 같으면 통과해야 한다", same.equivalent(MAX_SKEW, MAX_DISCORDANT))
    }

    /**
     * **이 신호 집합에서 44.1kHz 가 덜 잡지 않는다.**
     *
     * 이름이 단언보다 앞서지 않게 적는다 — 이것은 **한 방향 점검**이지
     * 「검출률이 같다」가 아니다(독립 검증 M01). 처음에는 「검출률이 같다」고
     * 이름 붙였는데 단언은 `n44 < n48 - 2` 하나뿐이라, 48kHz 가 0/12 이고
     * 44.1kHz 가 12/12 여도 통과하는 시험이었다.
     *
     * 왜 한 방향만 단언하는가: `maxWidthBins` 가 칸으로 적혀 있어 44.1kHz
     * 에서 8.1% 좁은 주파수를 가리키므로, 걱정한 위험은 **44.1kHz 가 덜
     * 잡는 것**이었다. 그 위험만 여기서 막는다.
     *
     * **더 잡는 쪽이 옳다는 뜻이 아니다.** 이 합성 신호에는 정답 레이블이
     * 없어, 늘어난 검출이 참인지 오탐인지 이 시험으로는 알 수 없다.
     * 동등성 판정 결과도 함께 찍되 **단언하지 않는다** — 실제 자료는 짝이
     * 어긋나 있어 동등하다고 말할 수 없다.
     */
    @Test
    fun `폭이 있는 소리에서 44_1kHz 가 덜 잡지 않는다`() {
        val fewer = ArrayList<String>()
        for (bw in listOf(2.0, 5.0, 10.0, 20.0)) {
            val p = pairedRuns(bw, SEEDS)
            println("[폭 ${bw}Hz] $p 동등=${p.equivalent(MAX_SKEW, MAX_DISCORDANT)}")
            if (p.total44 < p.total48 - SEED_NOISE) fewer.add("${bw}Hz($p)")
        }
        assertTrue("44.1kHz 가 체계적으로 덜 잡는다: $fewer", fewer.isEmpty())
    }

    /** 폭마다 돌리는 씨앗 수. 한 번 돌린 결과는 간섭 무늬의 우연에 좌우된다. */
    private val SEEDS = 12

    /**
     * 한 방향 점검에서 이만큼은 우연으로 본다.
     *
     * **재서 정했다.** 12개 씨앗으로 잰 값은 폭 2Hz 에서 11 대 12, 5Hz 에서
     * 6 대 9, 10Hz 에서 3 대 3, 20Hz 에서 0 대 1 이었다 — 갈리는 방향이
     * **44.1kHz 쪽이 더 많이 잡는** 쪽이라 문턱 가설과 반대다.
     *
     * **왜 그런지는 아직 모른다.** 44.1kHz 의 분석 창이 92.9ms 로 더 길어
     * 주파수가 또렷해지기 때문이라는 것은 **가설이고 분리 실험으로 확인하지
     * 않았다**(독립 검증 게이트2 답변 3번). 합성 신호의 위상 간섭이나
     * 프레임 눈금 차이일 수도 있다.
     */
    private val SEED_NOISE = 2

    /**
     * 동등성 판정의 문턱. **사전에 정하고, 자료를 보고 늘리지 않는다.**
     *
     * 12개 씨앗에서 한쪽으로 2 를 넘게 쏠리거나 어긋난 짝이 4 를 넘으면
     * 「같은 판정」이라고 말하지 않는다. 실제 자료가 이 문턱을 넘으면
     * **문턱이 아니라 주장을 접는다** — 늘리면 시험이 아무 말도 하지 않게
     * 된다.
     */
    private val MAX_SKEW = 2
    private val MAX_DISCORDANT = 4

    // ---- 4. 무엇이 실제로 다른가 — 숫자로 못박는다 ----

    /**
     * **차이의 뿌리는 분석 창 길이다.**
     *
     * FFT 길이가 4096 표본으로 **고정**이라, 창이 담는 시간이 다르다 —
     * 48kHz 에서 85.3ms, 44.1kHz 에서 92.9ms. 주파수 분해능은 곧 창 길이의
     * 역수(1/T)이므로 칸 폭도 그만큼 다르고, **칸으로 적힌 판정 기준은
     * 전부 이 비를 물려받는다.**
     *
     * 이 시험은 그 비를 숫자로 붙들어 둔다. FFT 길이나 겹침을 바꾸면서
     * 이 문서를 안 고치면 여기서 걸린다.
     */
    @Test
    fun `칸으로 적힌 기준이 Hz 로 얼마나 다른지 못박는다`() {
        val n = 4096
        val bin48 = SpectralPeakFinder(n, FS_48).binHz
        val bin44 = SpectralPeakFinder(n, FS_44).binHz
        val window48 = n * 1000.0 / FS_48
        val window44 = n * 1000.0 / FS_44

        println(
            "[분해능] 48k: 창 ${"%.1f".format(window48)}ms 칸 ${"%.2f".format(bin48)}Hz | " +
                "44.1k: 창 ${"%.1f".format(window44)}ms 칸 ${"%.2f".format(bin44)}Hz",
        )
        // **`5 × 칸폭` 이 아니다.** widthBins 는 half-power 이상인 연속
        // 칸의 **개수**라, 대칭 봉우리에서 5개(±2칸) 다음은 7개(±3칸)다.
        // 그래서 떨어지는 자리는 `6 × 칸폭` 직전이다(독립 검증 답변 2번).
        println(
            "[폭 문턱] 48k=${"%.1f".format(6 * bin48)}Hz 직전 " +
                "44.1k=${"%.1f".format(6 * bin44)}Hz 직전 " +
                "(${"%.1f".format((1 - bin44 / bin48) * 100)}% 좁다)",
        )

        assertEquals("48kHz 칸 폭", 11.72, bin48, 0.01)
        assertEquals("44.1kHz 칸 폭", 10.77, bin44, 0.01)
        // 검증자가 적은 8.1% 와 같은 수인지 확인한다.
        assertEquals("좁아지는 비율(%)", 8.1, (1 - bin44 / bin48) * 100, 0.1)
    }

    /**
     * 봉우리를 빚을 때 쓰는 순음 줄 간격과 개수.
     *
     * 간격은 칸 폭(10~12Hz)보다 촘촘해야 한 덩어리로 보인다. 개수는 가장
     * 넓은 폭(120Hz)의 치마까지 덮을 만큼 잡는다.
     */
    private val LINE_SPACING_HZ = 2.0
    private val LINE_COUNT = 120

    /**
     * 허용 지연 차이(ms).
     *
     * **재서 정했다, 고르지 않았다.** 실제로 잰 값은 순음 네 개 모두에서
     * **4ms**(48kHz 1621ms · 44.1kHz 1625ms)였다. 이론상 두 가지가 더해질
     * 수 있어 그보다 넉넉히 둔다:
     *
     * - **첫 FFT 까지 채우는 시간**: 4096표본이라 48kHz 에서 85.3ms,
     *   44.1kHz 에서 92.9ms — 7.6ms 차이가 늘 깔린다.
     * - **프레임 눈금**: FFT 가 48kHz 에서 42.7ms, 44.1kHz 에서 46.4ms
     *   마다 나오므로 문턱을 넘는 프레임이 한 장 밀릴 수 있다.
     *
     * 7.6 + 46.4 ≈ 54ms 가 그 상한이고, 100ms 는 그 위의 여유다.
     */
    private val MAX_LATENCY_DIFF_MS = 100L
}
