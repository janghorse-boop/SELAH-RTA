package kr.joa.selahrta.dsp

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * 연속 스펙트럼을 **화면 칸**으로 줄인다(검토안 3장의 Spectrum).
 *
 * ## 왜 FFT 칸을 그대로 그리지 않는가
 *
 * 4096점 FFT 는 칸이 2049개다. 화면 가로가 그만큼 되지 않으므로 어차피
 * 누군가는 줄여야 하고, 그 일을 **그리는 쪽에 맡기면 매 프레임 2049개를
 * 실어 날라야 한다.** 여기서 줄여 보내면 그 짐이 없다.
 *
 * ## 로그 등간격인 까닭
 *
 * 사람이 듣는 높이는 주파수의 **비**다 — 100→200Hz 와 1k→2kHz 가 똑같이
 * 한 옥타브다. 선형 축으로 그리면 20Hz~1kHz(여섯 옥타브)가 왼쪽 5%에
 * 뭉치고 오른쪽 절반을 10k~20kHz 한 옥타브가 차지한다. 옆에 둘 RTA 도
 * 1/3 옥타브라 로그 축이고, 두 그림의 가로가 어긋나면 견줄 수가 없다.
 *
 * ## 칸마다 **최대**를 집는다 — 평균이 아니다
 *
 * 고역에서는 화면 칸 하나에 FFT 칸이 스무 개 넘게 들어간다. 평균을 내면
 * 좁은 봉우리가 둘레에 묻혀 10dB 넘게 낮아진다. 이 화면을 만드는 까닭이
 * 「그 대역 안에서 정확히 어디가 솟았나」이므로, 뭉개면 제 일을 못한다.
 *
 * 대신 **바닥이 실제보다 높아 보인다** — 잡음의 최대는 평균보다 크다.
 * 절대 레벨은 RTA 가 말하고 여기서는 모양을 본다.
 *
 * ## 저역이 계단으로 보이는 것은 고장이 아니다
 *
 * 화면 칸은 로그라 저역에서 아주 좁아지는데(20Hz 근처 0.6Hz) FFT 칸은
 * 어디서나 [binHz] 로 일정하다. 그래서 한 FFT 칸이 여러 화면 칸에
 * 걸쳐 같은 값으로 나온다 — **그것이 실제 분해능이다.** 숨기지 않고
 * [binHz] 를 화면에 적어 둔다.
 */
class SpectrumAxis(
    /** FFT 칸 수(`fftSize / 2 + 1`). */
    private val binCount: Int,
    sampleRateHz: Int,
    /** 화면 칸 수. */
    val columns: Int = DEFAULT_COLUMNS,
    /** 왼쪽 끝 주파수. */
    val lowHz: Double = 20.0,
    /** 오른쪽 끝 주파수. */
    val highHz: Double = 20_000.0,
) {
    init {
        require(binCount > 1) { "칸 수가 너무 적다: $binCount" }
        require(columns > 1) { "화면 칸이 너무 적다: $columns" }
        require(lowHz > 0.0 && highHz > lowHz) { "주파수 범위가 뒤집혔다" }
    }

    /** FFT 칸 하나의 폭(Hz). 이 화면의 진짜 분해능이다. */
    val binHz: Double = sampleRateHz.toDouble() / ((binCount - 1) * 2)

    private val logLow = ln(lowHz)
    private val logSpan = ln(highHz) - logLow

    /** 화면 칸 하나가 덮는 옥타브. 시험이 「제 칸에 갔는가」를 잴 때 쓴다. */
    val octavesPerColumn: Double = (logSpan / ln(2.0)) / (columns - 1)

    /** 칸 가운데 주파수. 로그 등간격이다. */
    val hz: DoubleArray = DoubleArray(columns) { Math.exp(logLow + logSpan * it / (columns - 1)) }

    /**
     * 칸의 경계. 가운데끼리의 중간(로그 기준)으로 나눈다.
     *
     * 끝 칸은 바깥쪽으로 반 칸만큼 넓힌다 — 안 그러면 20Hz·20kHz 바로 위아래
     * 에너지가 어느 칸에도 안 들어간다.
     */
    private val halfStep = logSpan / (columns - 1) / 2.0

    fun lowEdge(col: Int): Double = Math.exp(logLow + logSpan * col / (columns - 1) - halfStep)

    fun highEdge(col: Int): Double = Math.exp(logLow + logSpan * col / (columns - 1) + halfStep)

    /**
     * 주파수를 화면 가로 자리(0..1)로 옮긴다.
     *
     * **자르지 않는다.** 범위 밖은 0 보다 작거나 1 보다 큰 값으로 돌려주어
     * 그리는 쪽이 「화면 밖」임을 알 수 있게 한다 — 잘라 버리면 20kHz 위의
     * 것들이 오른쪽 끝에 모두 겹쳐 붙어, 있지도 않은 봉우리처럼 보인다.
     */
    fun position(hz: Double): Double {
        if (hz <= 0.0) return Double.NEGATIVE_INFINITY
        return (ln(hz) - logLow) / logSpan
    }

    /**
     * 칸마다 **가장 큰** FFT 칸의 전력을 담는다.
     *
     * 화면 칸이 FFT 칸보다 좁아 제 것이 하나도 없으면 **가장 가까운 칸**을
     * 쓴다. 비워 두면 저역에서 곡선이 끊겨, 소리가 없는 것처럼 보인다.
     */
    fun reduce(power: DoubleArray, out: DoubleArray) {
        require(power.size == binCount) { "power 길이가 $binCount 가 아니다: ${power.size}" }
        require(out.size == columns) { "out 길이가 $columns 가 아니다: ${out.size}" }
        for (c in 0 until columns) {
            val lo = lowEdge(c)
            val hi = highEdge(c)
            var from = Math.ceil(lo / binHz).toInt()
            var to = Math.ceil(hi / binHz).toInt() - 1
            if (from < 0) from = 0
            if (to > binCount - 1) to = binCount - 1
            if (from > to) {
                // 제 것이 없다 — 가장 가까운 칸을 빌린다.
                val near = (hz[c] / binHz).roundToInt().coerceIn(0, binCount - 1)
                out[c] = power[near]
                continue
            }
            var max = 0.0
            for (b in from..to) if (power[b] > max) max = power[b]
            out[c] = max
        }
    }

    companion object {
        /**
         * 화면 칸 기본값.
         *
         * 눕힌 폰 가로가 3000px 남짓이라 칸당 열 몇 픽셀이면 선이 매끄럽고,
         * 프레임마다 옮기는 양도 2049개에서 8분의 1로 줄어든다.
         */
        const val DEFAULT_COLUMNS = 256
    }
}

/** 스펙트럼에서 가장 큰 봉우리 하나. 화면이 숫자로 적는다. */
data class SpectrumPeak(
    /** 칸 사이까지 되찾은 주파수. */
    val hz: Double,
    /** 그 자리의 레벨(dBFS). 보정이 걸렸으면 보정 뒤 값이다. */
    val dbfs: Double,
)

/**
 * 화면에 그릴 스펙트럼 한 장.
 *
 * [hz] 는 [SpectrumAxis] 의 배열을 **그대로 가리킨다** — 장마다 복사하면
 * 바뀌지도 않는 256개를 초당 스물몇 번 새로 만드는 셈이다. 읽기만 한다.
 */
class SpectrumFrame(
    val columnsDbfs: DoubleArray,
    val holdDbfs: DoubleArray,
    val hz: DoubleArray,
    /** FFT 칸 폭(Hz). 화면이 분해능으로 적는다. */
    val binHz: Double,
    /** 가장 큰 봉우리. 아무 소리도 없으면 null. */
    val top: SpectrumPeak?,
    /** 몇 번째 보정 곡선으로 계산했는가. 화면이 제 상태와 견준다. */
    val curveGeneration: Long,
)

/**
 * 가장 큰 봉우리를 찾는다. **칸 번호 × 칸 폭이 아니다.**
 *
 * 검토안 4장이 든 예가 그대로 이 함수의 까닭이다 — 2,713Hz 는 231.5번
 * 칸이라, 칸 번호만 쓰면 2,707Hz 로 6Hz 어긋난다. EQ 에서는 다른 자리다.
 * 되찾는 셈은 [SpectralPeakFinder] 가 하울링 주파수에 쓰는 것과 같다.
 *
 * [power] 는 **화면이 그리는 것과 같은**(보정 뒤) 스펙트럼이어야 한다.
 * 다른 것을 넣으면 곡선 꼭대기와 적어 놓은 숫자가 어긋난다.
 */
fun topSpectrumPeak(
    power: DoubleArray,
    sampleRateHz: Int,
    fftSize: Int,
    lowHz: Double = 20.0,
    highHz: Double = 20_000.0,
): SpectrumPeak? {
    val binHz = sampleRateHz.toDouble() / fftSize
    // **[lowHz] 위의 칸부터 본다.** `toInt()` 로 잘랐더니 20Hz 를 달라 했을 때
    // 1번 칸(48k/4096 이면 11.7Hz)이 들어왔다 — 버림은 **아래쪽** 칸을 집는다.
    // 마이크의 직류 치우침과 초저역 울림이 그 칸에 몰려 있어, 화면에 적히는
    // 「가장 큰 봉우리」가 들리지도 않는 주파수가 될 수 있었다.
    //
    // 0번 칸(직류)은 어느 경우에도 보지 않는다.
    val from = kotlin.math.ceil(lowHz / binHz).toInt().coerceAtLeast(1)
    val to = (highHz / binHz).toInt().coerceAtMost(power.size - 2)
    if (to <= from) return null

    // **보간한 뒤에 범위를 본다**(독립 검토 UA-05).
    //
    // 칸을 고르는 범위만 자르는 것으로는 모자랐다. `refineBinHz` 는 이웃
    // 칸까지 보고 ±0.5칸 옮긴 값을 돌려주므로, 2번 칸(23.44Hz)을 골라도
    // 17.58Hz 가 나올 수 있다 — 검토자가 19Hz 순음에서 18.99Hz 를
    // 재현했다. 범위 밖 초저역의 누설이 화면의 「가장 큰 봉우리」를
    // 차지하던 셈이다.
    //
    // **20Hz 로 끌어당기지 않는다.** 그러면 범위 밖 신호가 20Hz 신호로
    // 둔갑한다. 대신 그 칸을 빼고 **다음으로 큰 것**을 찾는다.
    //
    // 경계에 딱 걸친 신호(정확히 20Hz)는 추정 오차 때문에 빠질 수 있다.
    // 그래도 허용오차를 두지 않는다 — 4096점 FFT 는 20Hz 를 애초에
    // 분해하지 못하고(칸 폭 11.7Hz), 그 자리의 숫자는 EQ 에 쓸 수 없다.
    // 빠지면 화면이 다음 봉우리를 말하고, 그것이 더 정직하다.
    var best = -1
    var bestPower = 0.0
    var bestHz = 0.0
    for (b in from..to) {
        val p = power[b]
        if (p <= bestPower) continue
        val hz = refineBinHz(power, b, binHz)
        if (hz < lowHz || hz > highHz) continue
        best = b
        bestPower = p
        bestHz = hz
    }
    if (best < 0 || bestPower <= 0.0) return null

    return SpectrumPeak(
        hz = bestHz,
        dbfs = (10.0 * log10(bestPower)).coerceAtLeast(SILENCE_DBFS),
    )
}

/** 전력을 dBFS 로. 바닥은 [SILENCE_DBFS] 로 막는다. */
internal fun toDbfs(power: DoubleArray, out: DoubleArray) {
    require(power.size == out.size) { "길이가 맞지 않는다" }
    for (i in power.indices) {
        out[i] = if (power[i] <= 0.0) {
            SILENCE_DBFS
        } else {
            (10.0 * log10(power[i])).coerceAtLeast(SILENCE_DBFS)
        }
    }
}
