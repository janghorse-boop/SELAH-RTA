package kr.joa.selahrta.dsp

import kotlin.math.log10
import kotlin.math.pow

/**
 * **기준 마이크의 절대 레벨을 대상 마이크로 옮긴다**(치환법).
 *
 * ## 무엇을 하는가
 *
 * 기준(EMM-6)과 대상(폰)을 **같은 자리에서 차례로** 재면, 둘이 들은
 * 음압은 같다. 그러면 이렇게 된다:
 *
 * ```
 * dBFS_기준 + 보정값_기준 = dBFS_대상 + 보정값_대상
 * 따라서  보정값_대상 = 보정값_기준 + (dBFS_기준 − dBFS_대상)
 * ```
 *
 * 소음계를 옆에 두고 눈으로 맞추는 것보다 낫다 — **두 마이크가 같은
 * 소리를 듣기** 때문이다. 소음계를 베끼는 쪽은 그 소음계가 맞다는 가정
 * 위에 서고, 두 기기의 가중이 다르면 그 차이까지 섞여 들어간다.
 *
 * ## 조심한 것 — CAL 을 건 값과 안 건 값
 *
 * **기준 쪽은 CAL 을 걸기 전 값이라야 한다.**
 *
 * 간편 보정이 잡는 `보정값_기준` 은 **시간영역 음압계**에서 재는데, 그
 * 경로에는 마이크 보정 곡선이 걸리지 않는다([kr.joa.selahrta.dsp.SplEngine]).
 * 그런데 교정 마법사의 기준 스펙트럼은 CAL 이 칸마다 걸린 값이다. 둘을
 * 그냥 빼면 **CAL 의 대역 평균만큼이 통째로 대상의 보정값에 들어간다.**
 *
 * 「걸린 값에서 되돌려 빼면 되지 않나」 — 안 된다. 밴드 안에서 응답이
 * 변하면 걸린 뒤의 밴드 평균이 원시 평균과 다르다(독립 검증 R05 가 바로
 * 그 이야기다). 그래서 잴 때 원시 값을 **함께 남겨** 두고 여기서 쓴다.
 *
 * ## 가중은 상관없다
 *
 * 보정값은 `dBFS → dB SPL` 의 상수라 A·C·Z 와 무관하다.
 */
data class LevelTransfer(
    /** 대상 마이크에 저장할 보정값(dB). */
    val targetOffsetDb: Double,
    /** 그 근거가 된 두 경로의 레벨 차이(dB). `기준 − 대상` 이다. */
    val pathDifferenceDb: Double,
    /** 어느 대역에서 견주었는가. */
    val bandLowHz: Double,
    val bandHighHz: Double,
    /** 그 대역에서 **둘 다** 쓸 만했던 밴드 수. 적으면 못 믿는다. */
    val bandsUsed: Int,
)

/**
 * 옮길 수 없는 까닭. 옮길 수 있으면 null 이다.
 *
 * **까닭을 값으로 돌려준다.** 조용히 null 만 주면 화면이 「왜 안 되는지」를
 * 지어내야 한다.
 */
enum class TransferBlock(val reasonKo: String) {
    NoReferenceOffset(
        "기준 마이크 경로가 아직 보정되지 않았습니다. " +
            "그 경로를 먼저 보정해야 그 값을 대상으로 옮길 수 있습니다.",
    ),
    NoRawReference(
        "기준 마이크의 원시(CAL 전) 레벨이 없습니다. " +
            "이 값을 남기기 전에 잰 교정입니다 — 다시 재면 옮길 수 있습니다.",
    ),
    NotEnoughBands(
        "레벨을 견줄 만한 대역이 모자랍니다. " +
            "소리를 키우거나 조용한 때에 다시 재십시오.",
    ),
}

/** 레벨을 견주는 대역. 모양 정규화와 같은 자리를 쓴다. */
const val TRANSFER_BAND_LOW_HZ = 300.0
const val TRANSFER_BAND_HIGH_HZ = 3_000.0

/** 이 수보다 적으면 옮기지 않는다. */
const val TRANSFER_MIN_BANDS = 5

/**
 * 옮길 값을 셈한다. 못 옮기면 [TransferBlock] 을 돌려준다.
 *
 * @param referenceRawMeanDb 기준 마이크의 **CAL 전** 밴드 평균
 *   ([SessionResult.referenceRawMeanDb]). null 이면 못 옮긴다.
 * @param targetMeanDb 대상 마이크의 밴드 평균(원래 CAL 이 없다).
 * @param referenceOffsetDb 기준 **경로**에 저장된 보정값. null 이면 못 옮긴다.
 * @param usable 그 밴드를 믿어도 되는가. 길이는 밴드 수와 같아야 한다.
 */
fun computeLevelTransfer(
    referenceRawMeanDb: DoubleArray?,
    targetMeanDb: DoubleArray,
    referenceOffsetDb: Double?,
    usable: BooleanArray,
): Result<LevelTransfer> {
    if (referenceOffsetDb == null) {
        return Result.failure(TransferBlocked(TransferBlock.NoReferenceOffset))
    }
    if (referenceRawMeanDb == null) {
        return Result.failure(TransferBlocked(TransferBlock.NoRawReference))
    }
    require(referenceRawMeanDb.size == targetMeanDb.size) {
        "밴드 수가 다르다: ${referenceRawMeanDb.size} != ${targetMeanDb.size}"
    }
    require(usable.size == targetMeanDb.size) {
        "쓸 수 있는 자리 표시의 길이가 다르다: ${usable.size} != ${targetMeanDb.size}"
    }

    // **에너지로 더한다.** dB 를 그냥 평균하면 큰 밴드가 묻힌다.
    var refPower = 0.0
    var tgtPower = 0.0
    var n = 0
    for (i in targetMeanDb.indices) {
        if (!usable[i]) continue
        val hz = ThirdOctave.exactCenter(i)
        if (hz < TRANSFER_BAND_LOW_HZ || hz > TRANSFER_BAND_HIGH_HZ) continue
        refPower += 10.0.pow(referenceRawMeanDb[i] / 10.0)
        tgtPower += 10.0.pow(targetMeanDb[i] / 10.0)
        n++
    }
    if (n < TRANSFER_MIN_BANDS) {
        return Result.failure(TransferBlocked(TransferBlock.NotEnoughBands))
    }

    val diff = 10.0 * log10(refPower / tgtPower)
    return Result.success(
        LevelTransfer(
            targetOffsetDb = referenceOffsetDb + diff,
            pathDifferenceDb = diff,
            bandLowHz = TRANSFER_BAND_LOW_HZ,
            bandHighHz = TRANSFER_BAND_HIGH_HZ,
            bandsUsed = n,
        ),
    )
}

/** 못 옮긴 까닭을 실어 나르는 예외. 화면이 [block] 의 문구를 그대로 쓴다. */
class TransferBlocked(val block: TransferBlock) : Exception(block.reasonKo)
