package kr.joa.selahrta.dsp

import kotlin.math.log10
import kotlin.math.pow

/**
 * 핑크 잡음으로 잰 **방·PA 의 크기 응답**(1/3 옥타브).
 *
 * ## 이것은 전달함수가 아니다
 *
 * 이 말을 맨 앞에 둔다. 전달함수(transfer function)는 **내보낸 신호와
 * 들어온 신호를 함께 알아야** 구할 수 있고, 크기뿐 아니라 **위상**과
 * 임펄스 응답을 준다. 여기서는 들어온 쪽만 안다.
 *
 * 그래서 이 값이 말해 주지 **않는** 것:
 *
 * - **위상·시간**. 스피커가 몇 ms 늦는지, 두 스피커가 어긋났는지 모른다.
 * - **무엇의 응답인지.** 잰 것은 「소리원 × 방 × 마이크」를 한 덩어리로
 *   합친 결과다. 방만 떼어 낼 수 없다 — 폰 스피커로 내면 폰 스피커의
 *   기울기가 그대로 섞이고, PA 로 내면 PA 의 기울기가 섞인다.
 * - **다른 자리에서도 같은지.** 방의 응답은 자리마다 다르다. 저역은
 *   특히 심해서 한 걸음만 옮겨도 10dB 이 오간다.
 *
 * 말할 수 있는 것은 **「이 자리에서 이 마이크로 들은 소리의 대역 균형」**
 * 까지다. 그것만으로도 쓸모가 있다 — 어느 대역이 묻히고 어느 대역이
 * 솟는지는 보이고, EQ 를 만지기 전에 볼 값이 그것이기 때문이다.
 *
 * ## 왜 핑크 잡음인가
 *
 * 1/3 옥타브 밴드마다 에너지가 **고르다.** 그래서 밴드 레벨을 그대로
 * 읽으면 그것이 곧 응답이다 — 내보낸 쪽을 몰라도 되는 까닭이 이것이다.
 * 백색 잡음은 옥타브당 +3dB 기울어져 있어 그 기울기를 다시 빼야 한다.
 */
data class RoomResponse(
    /** 밴드별 에너지 평균(dB). 정규화 전 값이다. */
    val bandsDb: DoubleArray,
    /**
     * 기준 대역을 0dB 으로 맞춘 편차(dB). **화면이 그리는 값이다.**
     *
     * 절대 레벨은 소리를 얼마나 크게 틀었는지에 따라 통째로 오르내리므로,
     * 그대로 그리면 두 번 잰 것을 견줄 수 없다. 모양만 남긴다.
     */
    val relativeDb: DoubleArray,
    /** 그 밴드를 믿어도 되는가 — 배경 잡음보다 충분히 큰가. */
    val usable: BooleanArray,
    /** 배경 대비 여유(dB). 밴드마다. */
    val snrDb: DoubleArray,
    /**
     * 밴드별 흔들림(dB 표준편차).
     *
     * 크면 아직 덜 모였거나 재는 동안 소리가 변한 것이다. 화면이 「이
     * 밴드는 아직 출렁인다」를 말할 수 있어야 한다.
     */
    val stdevDb: DoubleArray,
    /** 튄 장을 버리고 **실제로 쓴** 장 수. */
    val framesUsed: Int,
    /** 튀어서 버린 장 수. */
    val framesDropped: Int,
    /** 0dB 으로 삼은 대역. */
    val referenceLowHz: Double,
    val referenceHighHz: Double,
    /** 그 대역에서 실제로 쓴 밴드 수. 적으면 기준 자체가 흔들린다. */
    val referenceBands: Int,
) {
    /** 쓸 만한 밴드 가운데 가장 높은 곳과 가장 낮은 곳의 차(dB). */
    val spanDb: Double
        get() {
            val v = relativeDb.indices.filter { usable[it] }.map { relativeDb[it] }
            return if (v.isEmpty()) 0.0 else v.max() - v.min()
        }
}

/**
 * 못 잰 까닭. **값을 돌려준다** — 조용히 null 을 주면 화면이 까닭을
 * 지어내야 한다.
 */
enum class ResponseBlock(val reasonKo: String) {
    NoQuiet(
        "배경 잡음을 먼저 재야 합니다. " +
            "그것이 없으면 어느 대역을 믿어도 되는지 가릴 수 없습니다.",
    ),
    NotEnoughFrames(
        "모은 장이 모자랍니다. 소리를 더 오래 틀어 주십시오.",
    ),
    TooUnstable(
        "재는 동안 소리가 너무 많이 변했습니다. " +
            "조용한 때에, 소리 크기를 바꾸지 말고 다시 재십시오.",
    ),
    NotEnoughReferenceBands(
        "기준 대역(250Hz~4kHz)에서 배경보다 충분히 큰 밴드가 모자랍니다. " +
            "소리를 키우거나 더 조용한 때에 재십시오.",
    ),
}

/** 못 잰 까닭을 실어 나르는 예외. 화면이 [block] 의 문구를 그대로 쓴다. */
class ResponseBlocked(val block: ResponseBlock) : Exception(block.reasonKo)

/**
 * 밴드가 배경보다 이만큼은 커야 믿는다(dB).
 *
 * **셈해서 고른 값이다.** 관계없는 배경이 10dB 아래에 깔려 있으면 합쳐진
 * 레벨은 `10·log10(1 + 10^(−10/10))` = **0.41dB** 만큼 부풀어 오른다.
 * 응답 곡선을 ±1dB 로 읽고 싶다면 그 절반 아래여야 하므로 10dB 이 경계다.
 * 6dB 이면 1.0dB 이 부풀어 곡선의 모양 자체를 바꾼다.
 */
const val ROOM_MIN_SNR_DB = 10.0

/**
 * 0dB 으로 삼을 대역.
 *
 * 말소리가 사는 자리이고, 이 앱이 다른 곳에서도 「250Hz~4kHz 가 묻히지
 * 않는지 보라」고 말하는 그 대역이다. 저역을 기준으로 삼으면 자리를 한
 * 걸음 옮길 때마다 곡선 전체가 위아래로 뛴다 — 방의 저역이 자리마다
 * 10dB 씩 다르기 때문이다.
 */
const val ROOM_REF_LOW_HZ = 250.0
const val ROOM_REF_HIGH_HZ = 4_000.0

/**
 * 기준 대역에서 이만큼은 쓸 수 있어야 한다.
 *
 * 250Hz~4kHz 에는 1/3 옥타브 밴드가 13개 들어간다. 그 절반이 안 되면
 * 「중역을 0dB 으로 맞췄다」는 말이 흔들린다.
 */
const val ROOM_MIN_REF_BANDS = 6

/** 이보다 적은 장으로는 셈하지 않는다. 48kHz·FFT4096 이면 1초쯤이다. */
const val ROOM_MIN_FRAMES = 20

/**
 * 응답을 셈한다. 못 셈하면 [ResponseBlock] 을 실은 실패를 돌려준다.
 *
 * @param signalFrames 소리를 트는 동안 모은 밴드 dB 장들.
 * @param quietMeanDb 배경 잡음의 밴드 평균. **없으면 셈하지 않는다** —
 *   어느 밴드를 믿어도 되는지 가릴 근거가 사라진다.
 * @param maxDeviationDb 광대역 레벨이 중앙값에서 이만큼 넘게 벗어난 장은
 *   버린다(문 닫히는 소리·기침).
 */
fun computeRoomResponse(
    signalFrames: List<DoubleArray>,
    quietMeanDb: DoubleArray?,
    minSnrDb: Double = ROOM_MIN_SNR_DB,
    maxDeviationDb: Double = 3.0,
): Result<RoomResponse> {
    if (quietMeanDb == null) {
        return Result.failure(ResponseBlocked(ResponseBlock.NoQuiet))
    }
    if (signalFrames.size < ROOM_MIN_FRAMES) {
        return Result.failure(ResponseBlocked(ResponseBlock.NotEnoughFrames))
    }
    val bandCount = quietMeanDb.size
    require(signalFrames.all { it.size == bandCount }) {
        "밴드 수가 다른 장이 섞여 있다"
    }

    // **튄 장을 먼저 버린다.** 전력 평균이라 한 장만 크게 튀어도 그쪽으로
    // 끌려간다.
    val keep = keepStableFrames(signalFrames, maxDeviationDb)
    if (keep.size < ROOM_MIN_FRAMES) {
        return Result.failure(ResponseBlocked(ResponseBlock.TooUnstable))
    }

    val acc = BandAccumulator(bandCount)
    for (i in keep) acc.add(signalFrames[i])
    val mean = acc.meanDb() ?: return Result.failure(ResponseBlocked(ResponseBlock.NotEnoughFrames))
    val stdev = acc.stdDevDb() ?: DoubleArray(bandCount)

    val snr = DoubleArray(bandCount) { mean[it] - quietMeanDb[it] }
    val usable = BooleanArray(bandCount) { snr[it] >= minSnrDb }

    // **기준은 에너지로 더한다.** dB 를 그냥 평균하면 큰 밴드가 묻힌다.
    var refPower = 0.0
    var refCount = 0
    for (i in 0 until bandCount) {
        if (!usable[i]) continue
        val hz = ThirdOctave.exactCenter(i)
        if (hz < ROOM_REF_LOW_HZ || hz > ROOM_REF_HIGH_HZ) continue
        refPower += 10.0.pow(mean[i] / 10.0)
        refCount++
    }
    if (refCount < ROOM_MIN_REF_BANDS) {
        return Result.failure(ResponseBlocked(ResponseBlock.NotEnoughReferenceBands))
    }
    val refDb = 10.0 * log10(refPower / refCount)

    return Result.success(
        RoomResponse(
            bandsDb = mean,
            relativeDb = DoubleArray(bandCount) { mean[it] - refDb },
            usable = usable,
            snrDb = snr,
            stdevDb = stdev,
            framesUsed = keep.size,
            framesDropped = signalFrames.size - keep.size,
            referenceLowHz = ROOM_REF_LOW_HZ,
            referenceHighHz = ROOM_REF_HIGH_HZ,
            referenceBands = refCount,
        ),
    )
}
