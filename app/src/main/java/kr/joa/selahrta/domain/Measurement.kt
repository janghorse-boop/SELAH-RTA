package kr.joa.selahrta.domain

/**
 * 측정 상태.
 *
 * **「아직 재지 않았다」와 「재는 중인데 값이 낮다」는 전혀 다른 일이다.**
 * 둘을 같은 자료형으로 다루면 화면이 구별할 수 없고, 결국 재지도 않은
 * 숫자가 측정값처럼 뜬다. 명세 0장이 금지한 바로 그 일이다.
 */
sealed interface MeasureState {
    /** 아직 시작하지 않았다. 화면에는 값 대신 「—」가 뜬다. */
    data object Idle : MeasureState

    /** 권한 확인·기기 열기 중. 잠깐이지만 값은 아직 없다. */
    data object Starting : MeasureState

    /** 재는 중. [sinceElapsedMs] 는 단조 시계 기준 시작 시각이다. */
    data class Running(val sinceElapsedMs: Long) : MeasureState

    /** 멈췄다. 왜 멈췄는지 반드시 화면에 드러낸다. */
    data class Failed(val reason: FailureReason) : MeasureState
}

/**
 * 측정이 멈춘 까닭.
 *
 * 「오류가 났습니다」로 뭉뚱그리지 않는다 — 담당자가 할 수 있는 일이
 * 저마다 다르다. 권한은 설정에서 켜고, 기기 분리는 다시 꽂으면 된다.
 */
enum class FailureReason {
    /** 마이크 권한이 없다. 설정으로 보내야 한다. */
    PermissionDenied,

    /** 쓸 수 있는 입력 기기가 없다. */
    NoInputDevice,

    /** 쓰던 기기가 빠졌다(USB 분리 등). */
    DeviceLost,

    /** 다른 앱이 마이크를 가져갔다(통화 등). */
    Preempted,

    Unknown,
}

/**
 * 보정 상태(명세 8장).
 *
 * 이 값은 화면에서 **늘 보여야 한다.** 미보정 상태의 숫자를 측정값처럼
 * 내보내면 앱이 거짓말을 하는 셈이다.
 */
enum class CalibrationState(val labelKo: String, val shortKo: String) {
    Uncalibrated("보정 안 함", "미보정"),
    GlobalCalibrated("전대역 보정", "보정됨"),
    FrequencyCalibrated("주파수 보정", "주파수 보정"),
}

/** 입력 기기 종류. 내장은 USB 의 대체품이 아니라 정식 입력이다(명세 2장). */
enum class MicKind(val labelKo: String, val badgeKo: String) {
    BuiltIn("내장 마이크", "PHONE MIC"),
    Usb("USB 마이크", "USB MIC"),
}

/**
 * 지금 실제로 열려 있는 입력.
 *
 * 세션에 무엇으로 쟀는지 남겨야 하므로(명세 2장) 샘플레이트·인코딩까지
 * 함께 들고 다닌다. **「48kHz 로 요청했다」와 「48kHz 로 열렸다」는 다르다** —
 * 여기 담기는 것은 열린 쪽이다.
 */
data class ActiveInput(
    val kind: MicKind,
    val deviceLabel: String,
    val sampleRate: Int,
    val bitDepth: Int,
    val calibration: CalibrationState,
)

/**
 * 교회 모드(명세 10장).
 *
 * 참고 범위는 **보편적 표준이 아니다.** 예배당마다 다르고 사용자가 고칠 수
 * 있어야 한다. 화면에서도 「참고」라고 적는다.
 */
enum class ChurchMode(val labelKo: String) {
    Sermon("설교"),
    Worship("찬양"),
    Free("자유 측정"),
}

/**
 * 모드별 권장 범위(dBA). 컨셉 화면 4번과 명세 10장의 초기값이다.
 *
 * [avg] 는 시간평균(LAeq) 기준, [peak] 는 순간 피크 기준이다.
 * 자유 측정은 판정하지 않으므로 범위가 없다.
 */
data class ReferenceRange(
    val avg: ClosedFloatingPointRange<Double>,
    val peak: ClosedFloatingPointRange<Double>,
    val noteKo: String,
)

object ReferenceRanges {
    val sermon = ReferenceRange(68.0..75.0, 78.0..82.0, "또렷하고 편안하게 들리는 수준입니다.")
    val worship = ReferenceRange(78.0..85.0, 88.0..95.0, "풍성하고 몰입감 있는 수준입니다.")
    val prayer = ReferenceRange(65.0..72.0, 75.0..80.0, "차분하고 명료한 수준입니다.")

    fun forMode(mode: ChurchMode): ReferenceRange? = when (mode) {
        ChurchMode.Sermon -> sermon
        ChurchMode.Worship -> worship
        ChurchMode.Free -> null
    }
}

/**
 * 참고 범위에 견준 판정.
 *
 * 색으로만 알리지 않는다(명세 11장) — 색각 이상이 있는 사람에게는 색이
 * 아무 뜻이 없다. 늘 [labelKo] 를 함께 띄운다.
 */
enum class RangeVerdict(val labelKo: String) {
    Low("낮음"),
    InRange("적정"),
    High("높음"),
    /** 잴 값이 없거나 판정하지 않는 모드다. 「적정」이 아니다. */
    Unknown("—"),
}

fun ReferenceRange?.verdictFor(dba: Double?): RangeVerdict {
    if (this == null || dba == null) return RangeVerdict.Unknown
    return when {
        dba < avg.start -> RangeVerdict.Low
        dba > avg.endInclusive -> RangeVerdict.High
        else -> RangeVerdict.InRange
    }
}
