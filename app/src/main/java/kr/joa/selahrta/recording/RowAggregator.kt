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
    /**
     * **그 행에 조각이 하나도 들어오지 않았는가.**
     *
     * 뜻을 좁게 못박는다(독립 검증 RA04). 이것은 「행이 통째로 비었다」는
     * 표시이지 **「행이 빈틈없이 덮였다」의 반대가 아니다.** 행 안에서
     * 일부 구간만 들어오면 `missing = false` 이고, 그 빈틈은 지금 형식에
     * 남지 않는다.
     *
     * 행 안의 덮인 정도(coverage)와 끊김 사건을 따로 적는 일은 **아직
     * 하지 않았다.** 녹음을 화면에 붙이는 단계의 완료 조건으로 남긴다.
     */
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
 * - 조각은 **시간 순서로, 겹치지 않게** 와야 한다.
 *
 * **한 스레드 전용이다.** 잠금이 없고, 쓰는 쪽이 하나임을 전제로
 * 한다. 이 약속을 적어 둔 까닭은, 이 저장소에서 거듭 틀렸던 것이
 * **「이미 들어와 있는 것」과 「새로 시작한 것」이 같은 공용 상태를
 * 만지는 자리**였기 때문이다. 쓰는 쪽이 둘이라면 건네는 지점을
 * 부르는 쪽이 정해야 하며, 여기에 잠금을 더해 그 지점을 가리지 않는다.
 *
 * **모아 두었다가 한꺼번에 준다.** [finish] 까지 모든 행을 메모리에 든다.
 * 긴 녹음에서 완결된 행을 순차로 흘려보내는 경계는 **아직 만들지
 * 않았다.** 화면에 붙이는 단계에서 정한다.
 */
class RowAggregator(
    private val nominalSampleRate: Int,
    private val epochs: EpochTable,
    private val rowMillis: Int = 500,
    private val bandCount: Int = 31,
) {
    init {
        require(nominalSampleRate > 0) { "샘플레이트가 0 이하다: $nominalSampleRate" }
        require(rowMillis > 0) { "행 길이가 0 이하다: $rowMillis" }
        require(bandCount > 0) { "밴드 수가 0 이하다: $bandCount" }
    }

    /** 한 행이 덮는 프레임 수. */
    val framesPerRow: Long = nominalSampleRate.toLong() * rowMillis / 1000L

    init {
        // 재지 않은 조합에서 0 이 될 수 있다(예: 1000Hz · 0.5ms). 그대로
        // 두면 add 의 나눗셈이 터진다(독립 검증 RA04).
        require(framesPerRow > 0) {
            "한 행이 0 프레임이다: fs=$nominalSampleRate rowMillis=$rowMillis"
        }
    }

    private val done = ArrayList<TimelineRow>()

    /**
     * 다음 조각이 시작할 수 있는 가장 이른 프레임.
     *
     * 받은 조각의 **끝(exclusive)** 을 들고 있다. 행 번호만 보면 한 행
     * 안에서 시간이 뒤섞여도 모른다(독립 검증 RA01 의 REVERSE).
     */
    private var nextFrame = 0L

    private var finished = false
    private var result: List<TimelineRow> = emptyList()

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
        // **검사를 먼저 끝낸다.** 거절할 조각 때문에 열린 행을 닫아 버리면,
        // 거절 뒤에 정상 조각을 넣어도 결과가 오염된다(독립 검증 RA01).
        check(!finished) { "이미 finish() 했다. 다시 넣을 수 없다" }
        require(frames > 0) { "조각이 비었다" }
        require(frameStart >= 0) { "프레임이 음수다: $frameStart" }
        require(s.bands.size == bandCount) { "밴드 수가 ${bandCount} 가 아니다: ${s.bands.size}" }

        // 넘치면 음수로 돌아 행 번호가 뒤엉킨다. 조용히 틀리는 것을 막는다.
        val endExclusive = Math.addExact(frameStart, frames.toLong())

        // **뒤로 가거나 겹치지 않는다.** 행 번호만 보던 예전 검사는 한 행
        // 안의 역행·중복을 통과시켰고, 그러면 current 가 과거 값으로
        // 덮인다(독립 검증 RA01 의 REVERSE).
        require(frameStart >= nextFrame) {
            "조각이 뒤로 갔거나 겹친다: 시작 $frameStart < 직전 끝 $nextFrame"
        }

        val first = frameStart / framesPerRow
        val last = (endExclusive - 1) / framesPerRow
        require(first == last) {
            "조각이 행 경계를 걸친다($frameStart..${endExclusive - 1}). 부르는 쪽이 쪼개야 한다"
        }

        // **가리킨 epoch 이 실제로 그 구간에 걸리는가.** 이것을 안 보면
        // epoch 경계를 가로지르는 조각이 통째로 옛 보정을 받는다 —
        // 검증자의 EPOCH_CROSS 가 그것이다(90dB 이 아니라 80dB).
        val startEpoch = epochs.idAt(frameStart)
        val endEpoch = epochs.idAt(endExclusive - 1)
        require(startEpoch != null && endEpoch != null) {
            "$frameStart..${endExclusive - 1} 에 걸린 epoch 이 아직 없다"
        }
        require(startEpoch == epochId && endEpoch == epochId) {
            "조각이 가리킨 epoch($epochId) 과 실제(시작 $startEpoch, 끝 $endEpoch)가 다르다"
        }

        val index = first.toInt()

        // ---- 여기까지 상태를 하나도 바꾸지 않았다 ----

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
        nextFrame = endExclusive
    }

    /**
     * 남은 것을 닫고 전체를 돌려준다. **두 번 불러도 같다.**
     *
     * 예전에는 부를 때마다 열린 행을 다시 닫아 **같은 rowIndex 가 둘**이
     * 되었다(독립 검증 RA02). stop·오류·정리 경로가 거듭 부르면 파일의
     * 레코드 자리와 행 번호가 어긋난다.
     *
     * @param endFrameExclusive 녹음이 끝난 프레임(exclusive). 마지막 조각
     *   뒤로 측정이 없던 구간이 있으면 그만큼 `missing` 행을 채운다.
     *   생략하면 마지막 조각에서 끝난다 — 집계기는 녹음의 끝을 스스로
     *   알 수 없다.
     */
    fun finish(endFrameExclusive: Long = nextFrame): List<TimelineRow> {
        if (finished) return result
        require(endFrameExclusive >= nextFrame) {
            "끝이 마지막 조각보다 앞이다: $endFrameExclusive < $nextFrame"
        }
        closeOpen()
        if (endFrameExclusive > 0) {
            fillMissingUpTo(((endFrameExclusive - 1) / framesPerRow).toInt() + 1)
        }
        finished = true
        result = done.toList()
        return result
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
        // 다시 닫지 않도록 곧바로 비운다(독립 검증 RA02).
        anyData = false
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
                    currentRaw = 0.0, currentEpoch = TimelineFormat.EPOCH_NONE,
                    maxRaw = 0.0, maxEpoch = TimelineFormat.EPOCH_NONE,
                    peakRaw = 0.0, peakEpoch = TimelineFormat.EPOCH_NONE,
                    clipped = false, missing = true,
                    bands = FloatArray(bandCount),
                ),
            )
            i++
        }
    }
}
