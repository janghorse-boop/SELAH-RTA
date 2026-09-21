package kr.joa.selahrta.recording

/**
 * 타임라인 한 행(기본 500ms). **값마다 제 epoch 를 지닌다.**
 *
 * **왜 값마다인가** — 한 행 안에서 보정이 바뀔 수 있다. 그때 raw 극값
 * 하나와 epoch 하나로 합치면 뜻이 무너진다(독립 검증 M32):
 *
 * ```
 * 옛 epoch: -20dBFS + 100 =  80dB
 * 새 epoch: -30dBFS + 120 =  90dB   ← 실제 최대
 * raw 최대 -20 만 남기면 → 80 또는 100
 * ```
 *
 * 그래서 극값을 **보정한 값으로 견주고**, 이긴 쪽의 **raw 와 그 epochId**
 * 를 함께 적는다. 읽을 때 되살리면 정확히 90dB 다. 재분석은 raw 를
 * 그대로 쓰므로 뜻이 보존된다.
 */
data class TimelineRow(
    val rowIndex: Int,
    /** 그 구간 **마지막** 값(화면의 큰 숫자와 같은 뜻). */
    val currentRaw: Double,
    val currentEpoch: Int,
    /** 그 구간 안의 **최대**(시간가중). */
    val maxRaw: Double,
    val maxEpoch: Int,
    /** 그 구간 안의 **최대 파형값**. */
    val peakRaw: Double,
    val peakEpoch: Int,
    /** **이 구간에서** 잘렸는가. 세션 누적이 아니다. */
    val clipped: Boolean,
    /** 측정이 없던 구간인가(끊김·일시정지). */
    val missing: Boolean,
    /** 31밴드. 이미 **곡선이 걸린** 값이다(독립 검증 S04). */
    val bands: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TimelineRow) return false
        return rowIndex == other.rowIndex &&
            currentRaw == other.currentRaw && currentEpoch == other.currentEpoch &&
            maxRaw == other.maxRaw && maxEpoch == other.maxEpoch &&
            peakRaw == other.peakRaw && peakEpoch == other.peakEpoch &&
            clipped == other.clipped && missing == other.missing &&
            bands.contentEquals(other.bands)
    }

    override fun hashCode(): Int = rowIndex * 31 + bands.contentHashCode()
}

/**
 * 한 조각이 가져오는 값. **이미 행·epoch 경계에서 쪼개진 것**이다.
 *
 * 쪼개도 DSP 결과가 같다는 것은 실측으로 확인했다
 * (`SplitProcessingTest`, 오차 0).
 */
data class RowSample(
    /** 조각 마지막의 시간가중 레벨(dBFS, 보정 전). */
    val currentRaw: Double,
    /** 조각 안의 최대 시간가중 레벨(dBFS). */
    val maxRaw: Double,
    /** 조각 안의 최대 파형값(dBFS). */
    val peakRaw: Double,
    val clipped: Boolean,
    val bands: FloatArray,
)

/**
 * 조각을 받아 **고정 격자 행**으로 쌓는다(녹음 설계 2차 ②).
 *
 * - 행 번호는 `frameFromStart / (공칭fs × 초/2)` — **공칭** 샘플레이트로
 *   나눈다. 그래야 WAV 재생 위치와 나눗셈 한 번으로 맞는다.
 * - **빠진 행을 만들지 않는다.** 측정이 없던 구간도 `missing` 행을 채운다.
 * - 한 번에 **한 행·한 epoch** 만 받는다. 걸치는 조각은 부르는 쪽이
 *   미리 쪼갠다 — 그 계약을 여기서 확인한다.
 */
class RowAggregator(
    private val nominalSampleRate: Int,
    private val epochs: EpochTable,
    private val rowMillis: Int = 500,
    private val bandCount: Int = 31,
) {
    /** 한 행이 덮는 프레임 수. */
    val framesPerRow: Long = nominalSampleRate.toLong() * rowMillis / 1000L

    private val done = ArrayList<TimelineRow>()

    private var openIndex = -1
    private var curRaw = 0.0
    private var curEpoch = -1
    private var maxRaw = Double.NEGATIVE_INFINITY
    private var maxEpoch = -1
    private var maxCal = Double.NEGATIVE_INFINITY
    private var peakRaw = Double.NEGATIVE_INFINITY
    private var peakEpoch = -1
    private var peakCal = Double.NEGATIVE_INFINITY
    private var clipped = false
    private var bands = FloatArray(bandCount)
    private var anyData = false

    /**
     * 조각 하나를 넣는다.
     *
     * @param frameStart 녹음 기준 시작 프레임
     * @param frames 조각 길이(1 이상)
     */
    fun add(frameStart: Long, frames: Int, epochId: Int, s: RowSample) {
        require(frames > 0) { "조각이 비었다" }
        require(s.bands.size == bandCount) { "밴드 수가 ${bandCount} 가 아니다: ${s.bands.size}" }
        val first = frameStart / framesPerRow
        val last = (frameStart + frames - 1) / framesPerRow
        require(first == last) {
            "조각이 행 경계를 걸친다($frameStart..${frameStart + frames - 1}). 부르는 쪽이 쪼개야 한다"
        }
        val index = first.toInt()
        require(index >= openIndex) { "행이 뒤로 갔다: $index < $openIndex" }

        if (index != openIndex) {
            closeOpen()
            fillMissingUpTo(index)
            openIndex = index
            resetOpen()
        }

        // **극값은 보정한 값으로 견준다**(M32). 이긴 쪽의 raw 와 epoch 를 둔다.
        val mCal = epochs.calibrated(s.maxRaw, epochId)
        if (mCal > maxCal) {
            maxCal = mCal; maxRaw = s.maxRaw; maxEpoch = epochId
        }
        val pCal = epochs.calibrated(s.peakRaw, epochId)
        if (pCal > peakCal) {
            peakCal = pCal; peakRaw = s.peakRaw; peakEpoch = epochId
        }
        // current 는 **마지막** 조각의 값이다.
        curRaw = s.currentRaw
        curEpoch = epochId
        clipped = clipped || s.clipped
        bands = s.bands.copyOf()
        anyData = true
    }

    /** 남은 것을 닫고 전체를 돌려준다. */
    fun finish(): List<TimelineRow> {
        closeOpen()
        return done.toList()
    }

    private fun resetOpen() {
        curRaw = 0.0; curEpoch = -1
        maxRaw = Double.NEGATIVE_INFINITY; maxEpoch = -1; maxCal = Double.NEGATIVE_INFINITY
        peakRaw = Double.NEGATIVE_INFINITY; peakEpoch = -1; peakCal = Double.NEGATIVE_INFINITY
        clipped = false
        bands = FloatArray(bandCount)
        anyData = false
    }

    private fun closeOpen() {
        if (openIndex < 0 || !anyData) return
        done.add(
            TimelineRow(
                rowIndex = openIndex,
                currentRaw = curRaw, currentEpoch = curEpoch,
                maxRaw = maxRaw, maxEpoch = maxEpoch,
                peakRaw = peakRaw, peakEpoch = peakEpoch,
                clipped = clipped, missing = false,
                bands = bands,
            ),
        )
    }

    /**
     * 사이에 빠진 행을 **`missing` 으로 채운다.**
     *
     * 비워 두면 `행 번호 = 위치 / 500ms` 가 깨진다. 그리고 측정이 없던
     * 구간을 **잰 무음으로 읽으면 안 되므로** 표시를 남긴다.
     */
    private fun fillMissingUpTo(index: Int) {
        var i = openIndex + 1
        if (openIndex < 0) i = 0
        while (i < index) {
            done.add(
                TimelineRow(
                    rowIndex = i,
                    currentRaw = 0.0, currentEpoch = -1,
                    maxRaw = 0.0, maxEpoch = -1,
                    peakRaw = 0.0, peakEpoch = -1,
                    clipped = false, missing = true,
                    bands = FloatArray(bandCount),
                ),
            )
            i++
        }
    }
}
