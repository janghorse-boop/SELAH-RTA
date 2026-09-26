package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * 독립 검토(2026-09-25, Codex)가 낸 회귀 시험을 그대로 옮긴 것.
 *
 * 출처: `docs/review/2026-09-25-ui-and-analysis-independent-review.md`
 * (UA-01, UA-05) · 검토자 원본
 * `IndependentUiAnalysisRegressionTest.kt`
 *
 * **재는 방법은 손대지 않았다.** 신호를 만드는 식, 프레임 크기, 시각을
 * 세는 방법 모두 검토자가 쓴 그대로다 — 우리 쪽 편의에 맞춰 고치면 같은
 * 것을 재는지 알 수 없게 된다. 한국어 이름과 설명만 붙였고, 검토자가
 * 「함께 단언하라」고 적은 경우를 덧붙였다.
 *
 * 고치기 전에 **둘 다 실패하는 것을 먼저 확인했다.**
 */
class CodexUiAnalysisRegressionTest {

    private val fs = 48_000

    /** 줄바꿈. 파일 본문을 줄로 이어 붙일 때 쓴다. */
    private val LF = "\n"

    /**
     * 검토자의 신호 생성기를 그대로 옮겼다.
     *
     * 1kHz 에서 시작해 [driftCents] 만큼 [driftSeconds] 동안 올라간 뒤
     * 그 자리에 머문다. **위상이 이어진다** — 프레임마다 위상을 끊으면
     * 그 자체가 넓은 스펙트럼을 만들어, 재려는 것과 다른 것을 재게 된다.
     */
    private fun feedRamp(
        detector: FeedbackDetector,
        engine: RtaEngine,
        totalSeconds: Int,
        driftCents: Double,
        driftSeconds: Double,
    ) {
        var phase = 0.0
        var cursor = 0
        var now: Long
        repeat(fs * totalSeconds / 2048) {
            val pcm = FloatArray(2048) {
                val seconds = cursor++.toDouble() / fs
                val frequency =
                    1000.0 * 2.0.pow(driftCents * (seconds / driftSeconds).coerceAtMost(1.0) / 1200.0)
                val v = (0.25 * sin(phase)).toFloat()
                phase = (phase + 2 * PI * frequency / fs) % (2 * PI)
                v
            }
            now = cursor * 1000L / fs
            detectorTime = now
            engine.process(pcm, pcm.size)
        }
    }

    /** 싱크 람다가 읽을 시각. 검토자 시험의 `var now` 와 같은 자리다. */
    private var detectorTime = 0L

    private fun rig(): Pair<RtaEngine, FeedbackDetector> {
        val engine = RtaEngine(fs)
        val detector = FeedbackDetector(4096, fs)
        engine.addSpectrumSink { detector.process(it, detectorTime) }
        return engine to detector
    }

    /**
     * **UA-01** — 올라오면서 주파수가 움직인 하울링을, 자리를 잡은 뒤에도
     * 계속 놓친다.
     *
     * 트랙의 `minHz`/`maxHz` 가 수명 내내 쌓이기만 하고 비워지지 않아,
     * 한 번 35cent 를 넘으면 그 뒤로 아무리 오래 고정돼도 영영
     * `None` 이었다. 무음으로 트랙이 사라져야 풀린다.
     *
     * **이것은 내가 만든 회귀다.** 안정성 관문을 「의심」까지 올리기 전에는
     * 적어도 후보로는 떴다.
     */
    @Test
    fun `2초 동안 40cent 올라간 뒤 자리를 잡으면 다시 잡힌다`() {
        val (engine, detector) = rig()
        feedRamp(detector, engine, totalSeconds = 10, driftCents = 40.0, driftSeconds = 2.0)
        assertEquals(
            "2초 흔들린 뒤 8초 가까이 고정된 순음",
            FeedbackState.Persistent,
            detector.state,
        )
    }

    /** 검토자가 함께 재라고 한 더 큰 이동. 100cent 는 반음이다. */
    @Test
    fun `2초 동안 100cent 올라간 뒤 자리를 잡아도 다시 잡힌다`() {
        val (engine, detector) = rig()
        feedRamp(detector, engine, totalSeconds = 10, driftCents = 100.0, driftSeconds = 2.0)
        assertEquals(FeedbackState.Persistent, detector.state)
    }

    /** 처음부터 고정된 순음. 이것이 깨지면 위 둘은 뜻이 없다. */
    @Test
    fun `처음부터 고정된 순음은 그대로 잡힌다`() {
        val (engine, detector) = rig()
        feedRamp(detector, engine, totalSeconds = 10, driftCents = 0.0, driftSeconds = 2.0)
        assertEquals(FeedbackState.Persistent, detector.state)
    }

    /**
     * **반대쪽 대조군** — 계속 흔들리는 소리는 여전히 안 잡혀야 한다.
     *
     * 최근 창으로 보게 고치면서 「흔들려도 잠깐씩은 안정해 보인다」로
     * 오탐이 되살아날 수 있다. 이 시험이 그것을 막는다.
     */
    @Test
    fun `쉬지 않고 흔들리는 소리는 지속까지 가지 않는다`() {
        val (engine, detector) = rig()
        var phase = 0.0
        var cursor = 0
        repeat(fs * 10 / 2048) {
            val pcm = FloatArray(2048) {
                val t = cursor++.toDouble() / fs
                // ±60cent, 5.5Hz 비브라토.
                val hz = 440.0 * 2.0.pow(60.0 * sin(2 * PI * 5.5 * t) / 1200.0)
                val v = (0.25 * sin(phase)).toFloat()
                phase = (phase + 2 * PI * hz / fs) % (2 * PI)
                v
            }
            detectorTime = cursor * 1000L / fs
            engine.process(pcm, pcm.size)
        }
        assertNotEquals(
            "비브라토가 지속으로 잡혔다 — 오탐이 되살아났다",
            FeedbackState.Persistent,
            detector.state,
        )
    }

    /**
     * **UA-05** — 하한을 `ceil` 로 고친 뒤에도 보간 결과가 범위 밖으로
     * 나간다.
     *
     * `refineBinHz` 는 이웃 칸까지 보고 ±0.5칸 옮긴 값을 돌려준다.
     * 48kHz·4096 에서 2번 칸은 23.44Hz 지만 보간 뒤에는 17.58Hz 까지
     * 내려갈 수 있다. 검토자가 19Hz 순음에서 **18.987Hz** 를 재현했다.
     */
    @Test
    fun `보간한 뒤에도 봉우리가 요청한 범위 안에 있다`() {
        for (hz in listOf(17.0, 19.0, 20.0)) {
            val power = DoubleArray(2049)
            PowerSpectrum(4096).compute(DoubleArray(4096) { sin(2 * PI * hz * it / fs) }, 0, power)
            val peak = topSpectrumPeak(power, fs, 4096, 20.0, 20_000.0)
            assertTrue(
                "${hz}Hz 에서 범위 밖 봉우리: $peak",
                peak == null || peak.hz in 20.0..20_000.0,
            )
        }
    }

    /** 검토자가 든 합성 전력 배열. 같은 하한 이탈을 다른 길로 확인한다. */
    @Test
    fun `아래 칸이 아무리 커도 범위 밖으로 끌려가지 않는다`() {
        val power = DoubleArray(2049)
        power[1] = 100.0
        power[2] = 1.0
        power[3] = 0.0001
        val peak = topSpectrumPeak(power, fs, 4096, 20.0, 20_000.0)
        assertTrue("범위 밖 봉우리: $peak", peak == null || peak.hz >= 20.0)
    }

    /** 위쪽 끝도 같은 계약이어야 한다. 검토자가 「상한 부근」을 적었다. */
    @Test
    fun `위쪽 끝에서도 범위를 넘지 않는다`() {
        val power = DoubleArray(2049)
        PowerSpectrum(4096).compute(
            DoubleArray(4096) { sin(2 * PI * 19_990.0 * it / fs) },
            0,
            power,
        )
        val peak = topSpectrumPeak(power, fs, 4096, 20.0, 20_000.0)
        assertTrue("범위 밖 봉우리: $peak", peak == null || peak.hz <= 20_000.0)
    }

    /**
     * **CA-09** — 제외한 봉우리의 **경사면**을 다음 봉우리로 보고했다.
     *
     * 범위 밖 신호가 크면 그 치마가 범위 안까지 흘러 들어온다. 「범위
     * 안에서 가장 큰 칸」을 집으면 그 경사면의 첫 칸이 뽑힌다 — 검토자가
     * 19Hz + 약한 1kHz 에서 **29.3Hz** 를 쟀다. 있지도 않은 봉우리다.
     *
     * 봉우리는 양옆보다 높아야 한다.
     */
    @Test
    fun `제외한 봉우리의 경사면을 봉우리라 하지 않는다`() {
        for (fs2 in listOf(48_000, 44_100)) {
            val power = DoubleArray(2049)
            PowerSpectrum(4096).compute(
                DoubleArray(4096) {
                    sin(2 * PI * 19.0 * it / fs2) + 0.01 * sin(2 * PI * 1000.0 * it / fs2)
                },
                0,
                power,
            )
            val top = topSpectrumPeak(power, fs2, 4096)
            assertNotNull("${fs2}Hz 에서 봉우리를 못 찾았다", top)
            assertEquals("${fs2}Hz — 19Hz 는 빠지고 진짜 봉우리는 1kHz 다", 1000.0, top!!.hz, 2.0)
        }
    }

    /**
     * **CA-10** — 잰 조건을 적은 한 줄로 기준 CAL 의 부호가 확정됐다.
     *
     * `# Measured at 94 dB SPL` 은 **잰 조건**이다. 둘째 열이 응답인지
     * 보정값인지는 아무 말도 하지 않는데, 그것만으로 사람에게 묻지도 않고
     * 정해졌다. 부호가 뒤집히면 고쳐야 할 만큼을 정확히 거꾸로 민다.
     */
    @Test
    fun `잰 조건을 적은 줄은 부호를 정하지 못한다`() {
        val loaded = CalibrationFile
            .load("# Measured at 94 dB SPL" + LF + "20 -1" + LF + "1000 0" + LF + "20000 2" + LF)
            .getOrThrow()
        val decision = decideReading(loaded.signEvidence, ReadingStakes.ReferenceForCalibration)
        assertFalse("잰 세기는 열의 뜻이 아니다: $decision", decision.settled)
    }

    /**
     * **CA-R05** — 잰 조건·쓰임새를 적은 설명문도 부호를 정하지 못한다.
     *
     * 지난번에는 `# Measured at 94 dB SPL` 한 줄만 막았다. 검토자가 같은
     * 부류 셋을 더 보였다 — 낱말을 지워 나가는 방식은 새 문구마다 뚫린다.
     *
     * 이제 **열 이름을 선언한 머리글만** 저절로 정한다.
     */
    @Test
    fun `설명문은 기준 CAL 의 부호를 정하지 못한다`() {
        val prose = listOf(
            "# Measured at 94 dB SPL",
            "# Reference SPL: 94 dB",
            "# Measured at 94 dB(SPL)",
            "# For frequency response measurements",
        )
        for (line in prose) {
            val declared = declaresColumns(listOf(line))
            val d = decideReading(
                signEvidenceOf(listOf(line)),
                ReadingStakes.ReferenceForCalibration,
                declared,
            )
            assertFalse("「$line」 만으로 정해졌다: $d", d.settled)
        }
    }

    /** 대조군 — **열 선언**은 그대로 정해져야 한다. */
    @Test
    fun `열 선언은 기준 CAL 의 부호를 정한다`() {
        val declarations = listOf(
            "Frequency,SPL,Phase",
            "Frequency(Hz)  Response(dB)",
            "주파수,응답",
        )
        for (line in declarations) {
            assertTrue("「$line」 을 열 선언으로 못 읽었다", declaresColumns(listOf(line)))
        }
        val d = decideReading(
            SignEvidence.LooksLikeResponse,
            ReadingStakes.ReferenceForCalibration,
            columnDeclared = true,
        )
        assertTrue("열 선언인데 묻는다", d.settled)
    }

    /** 표시용 곡선은 예전대로 — 틀려도 화면에서 드러나고 되돌리기 쉽다. */
    @Test
    fun `표시용 곡선은 설명문으로도 지나간다`() {
        val d = decideReading(
            SignEvidence.LooksLikeResponse,
            ReadingStakes.DisplayCurve,
            columnDeclared = false,
        )
        assertTrue(d.settled)
    }

    /** 대조군 — **열 이름**은 그대로 단서가 돼야 한다. */
    @Test
    fun `열 이름은 그대로 단서가 된다`() {
        assertEquals(
            SignEvidence.LooksLikeResponse,
            signEvidenceOf(listOf("Frequency,SPL,Phase")),
        )
        assertEquals(
            SignEvidence.LooksLikeCorrection,
            signEvidenceOf(listOf("Frequency(Hz)  Correction(dB)")),
        )
    }

    /** 검토자가 「44.1kHz 도 포함하라」고 적었다. */
    @Test
    fun `44_1kHz 에서도 범위 계약이 지켜진다`() {
        val fs441 = 44_100
        val power = DoubleArray(2049)
        PowerSpectrum(4096).compute(
            DoubleArray(4096) { sin(2 * PI * 18.0 * it / fs441) },
            0,
            power,
        )
        val peak = topSpectrumPeak(power, fs441, 4096, 20.0, 20_000.0)
        assertTrue("범위 밖 봉우리: $peak", peak == null || peak.hz >= 20.0)
    }
}
