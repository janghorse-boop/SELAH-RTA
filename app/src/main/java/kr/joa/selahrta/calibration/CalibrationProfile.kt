package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.audio.OpenedFormat
import kr.joa.selahrta.domain.CalibrationState
import kr.joa.selahrta.dsp.CalibrationOffset

/**
 * 보정값이 어느 조합에 속하는지 가리키는 열쇠(명세 8장).
 *
 * **기기 하나에 값 하나가 아니다.** 내장 마이크와 USB 마이크는 감도가
 * 수십 dB 다르고, 같은 마이크라도 입력 경로가 달라지면 게인이 달라진다.
 * 하나로 뭉뚱그리면 USB 를 꽂는 순간 내장용 보정값이 그대로 적용돼
 * 완전히 틀린 음압이 표시된다.
 */
data class CalibrationKey(
    /**
     * 실제로 열린 기기의 열쇠. 이름이 아니라 이것을 쓴다 —
     * 갤럭시의 하단·후면 내장 마이크는 이름이 같아서, 이름으로 갈랐다가는
     * 서로 다른 마이크에 같은 보정값이 적용된다(실기기에서 확인).
     */
    val deviceKey: String,
    val source: CaptureSource,
    /**
     * 여러 채널을 주는 기기에서 **어느 입력으로 재고 있는가**.
     *
     * 오디오 인터페이스는 Input 1 과 Input 3 에 서로 다른 마이크가
     * 꽂혀 있을 수 있고, 그러면 보정값도 다르다(USB 오디오 지시서 9.2).
     *
     * **모노면 null 이다.** 그래야 지금까지 저장된 내장 마이크 보정값이
     * 그대로 살아 있다 — 열쇠에 `|ch0` 을 붙이면 전부 잃는다.
     */
    val channelIndex: Int? = null,
) {
    /** 저장소 열쇠 문자열. 사람이 읽을 수 있게 두어 진단에도 쓴다. */
    fun storageKey(): String = buildString {
        append("cal|").append(deviceKey).append('|').append(source.name)
        channelIndex?.let { append("|ch").append(it) }
    }

    companion object {
        fun of(format: OpenedFormat) = CalibrationKey(
            deviceKey = format.deviceKey,
            source = format.audioSource,
            // 모노로 열렸으면 고를 것이 없었다 — 열쇠에 넣지 않는다.
            channelIndex = format.channelIndex.takeIf { format.channelCount > 1 },
        )
    }
}

/**
 * 전대역 보정값 하나.
 *
 * dBFS 에 이 값을 더하면 dB SPL 이 된다. 주파수별 보정은 이것과 별개이며
 * Phase 7 에서 붙는다 — 그때 [CalibrationState] 가 FrequencyCalibrated 가 된다.
 */
/**
 * 이 보정을 **무엇에 맞췄는가.**
 *
 * 등급이 다르다. 음압 교정기는 제 소리를 내는 기구라 **절대값의 근거**가
 * 되지만, 다른 소음계에 맞춘 것은 그 소음계가 맞다는 가정 위에 선다.
 * 그 차이를 적어 두지 않으면 나중에 「이 숫자를 믿어도 되나」에 답할 수
 * 없다(지시서 5장: 절대 SPL 은 별도 절차로 관리한다).
 */
enum class CalibrationSource(val labelKo: String, val trustKo: String) {
    /** 1kHz 음압 교정기. 가중치와 무관하고 절대값의 근거가 된다. */
    Calibrator(
        "음압 교정기",
        "교정기가 내는 1kHz 를 기준으로 맞췄습니다. 1kHz 에서는 A·C 가중이 " +
            "0dB 이라 가중치와 무관합니다.",
    ),

    /** 다른 소음계와 맞춤. 그 소음계가 맞다는 가정 위에 선다. */
    Meter(
        "기준 소음계",
        "다른 소음계가 가리킨 값에 맞췄습니다. 그 소음계가 맞다는 가정 위에 " +
            "서 있고, 두 기기의 가중치가 다르면 그 차이도 섞여 들어갑니다.",
    ),

    /**
     * 기준 마이크에서 **옮겨 온** 값(치환법).
     *
     * 같은 자리에서 같은 소리를 들은 두 마이크의 레벨 차이로 낸다.
     * 소음계를 베끼는 쪽보다 낫다 — 가중이 섞여 들어갈 자리가 없고,
     * 두 마이크가 같은 소리를 듣기 때문이다. 다만 **기준의 정확도를
     * 물려받는다.**
     */
    FromReferenceMic(
        "기준 마이크",
        "기준 마이크와 같은 자리에서 같은 소리를 듣고 그 값을 옮겼습니다. " +
            "기준 마이크의 보정을 물려받으므로, 기준이 틀렸으면 이것도 " +
            "같은 만큼 틀립니다.",
    ),

    /** 예전 기록. 무엇에 맞췄는지 적어 두지 않았다. */
    Unknown(
        "기록 없음",
        "무엇에 맞춘 보정인지 적어 두지 않았습니다. 다시 맞추기를 권합니다.",
    ),
}

/** 음압 교정기가 흔히 내는 레벨. 기구에 적힌 값을 그대로 쓴다. */
enum class CalibratorLevel(val db: Double, val labelKo: String) {
    Db94(94.0, "94 dB"),
    Db114(114.0, "114 dB"),
}

data class GlobalCalibration(
    val offsetDb: Double,
    /** 언제 쟀는가. 오래된 보정은 다시 하라고 권할 수 있다. */
    val savedAtEpochMs: Long,
    /** 그때 기준 소음계가 가리킨 값. 무엇에 맞췄는지 남긴다. */
    val referenceDb: Double,
    /** 그때 우리가 읽은 dBFS. 위 둘의 차이가 offsetDb 다. */
    val measuredDbfs: Double,
    /**
     * 그때의 **입력 잡음 바닥**(dBFS). 없으면 null(옛 기록).
     *
     * 이득의 그림자다 — gain 노브를 돌리면 전기 잡음도 함께 오르내린다.
     * 앱은 노브를 읽을 수 없으므로(독립 검토 R06) 이 값이 그 자리를
     * 대신한다. **증거가 아니라 단서다**: [judgeGainDrift] 참고.
     */
    val noiseFloorDbfs: Double? = null,
    /** 무엇에 맞춘 보정인가. 옛 기록은 [CalibrationSource.Unknown]. */
    val source: CalibrationSource = CalibrationSource.Unknown,
    /**
     * **어느 자리에서 잰 것인가**(`bottom`·`back` 같은 것). 옛 기록은 null.
     *
     * 저장 열쇠에는 자리가 없다 — 내장 마이크의 열쇠에서 주소를 빼기로
     * 했기 때문이다(2026-09-23). 그래서 자리가 바뀌어도 열쇠가 같았고,
     * `bottom` 에서 잰 감도를 `back` 에 그대로 걸면서 **「보정 완료」로
     * 적었다**(독립 재검토 CAR-03).
     *
     * **null 은 「모른다」이지 「같다」가 아니다.** 앱이 지금 주소로 채워
     * 넣지 않는다 — 사람이 확인해야 채워진다([judgeCalibrationRoute]).
     */
    val routeAddress: String? = null,
) {
    fun toOffset() = CalibrationOffset(offsetDb)
}

/**
 * 보정을 한 적이 없을 때 쓰는 값.
 *
 * **이 숫자는 측정이 아니라 짐작이다.** 요즘 폰 마이크는 대체로 0 dBFS 가
 * 120 dB SPL 안팎에서 잘린다. 그 가정으로 눈금을 걸어 두면 화면의 숫자가
 * 「대충 어느 정도인지」는 알려주지만, **실제로 ±10dB 이상 틀릴 수 있다.**
 *
 * 그래서 이 상태에서는 화면이 늘 「미보정 · 참고용」이라고 적고, 숫자도
 * 흐리게 그린다. 명세 1장이 「보정되지 않은 내장 마이크의 절대 SPL 정확도를
 * 보장하지 않는다」고 한 자리가 여기다.
 */
const val ASSUMED_FULL_SCALE_SPL: Double = 120.0

/** 지금 적용할 보정과 그 상태. */
data class ActiveCalibration(
    val offset: CalibrationOffset,
    val state: CalibrationState,
    val saved: GlobalCalibration?,
    /**
     * 저장된 값이 있는데도 걸지 **않은** 까닭. 걸었으면 null.
     *
     * 값은 [saved] 에 그대로 있다 — **지우지 않는다.** 사람이 확인하거나
     * 다시 보정하면 곧바로 걸린다(독립 재검토 CAR-03).
     */
    val holdNoticeKo: String? = null,
) {
    /** 이 값으로 나온 음압을 측정값이라 불러도 되는가. */
    val isReferenceOnly: Boolean get() = state == CalibrationState.Uncalibrated

    /** 저장된 값이 있는데 자리를 확인하지 못해 멈춰 둔 상태인가. */
    val heldForRoute: Boolean get() = holdNoticeKo != null

    /**
     * **지금 이 경로에 실제로 걸려 있는** 보정값. 걸려 있지 않으면 null.
     *
     * ## 왜 [saved] 와 따로 두는가 (독립 재검토 CARF-02)
     *
     * [saved] 는 **저장소에 무엇이 있는가**이고 이것은 **지금 무엇이
     * 걸려 있는가**다. 자리를 확인하지 못해 적용을 보류한 상태에서는
     * 저장값이 남아 있어야 하므로 [saved] 가 null 이 아니다 — 그런데
     * 그 값을 「이 경로의 감도」로 읽어 가는 길이 있었다.
     *
     * 검토자가 잰 것: 화면은 「미보정 · 적용 보류」인데 마법사가 그
     * 110dB 을 기준 마이크의 값으로 받아 **다른 마이크의 절대 보정으로
     * 복제**했다. CAR-03 의 보호가 그 경로에서만 무효였다.
     *
     * 값을 묻는 자리를 하나로 모은다. 둘을 따로 읽으면 한쪽만 고치게 된다.
     */
    val appliedOffsetDb: Double?
        get() = if (isReferenceOnly) null else saved?.offsetDb

    companion object {
        /** 저장된 보정이 없을 때. 짐작한 눈금에 「미보정」을 붙인다. */
        val assumed = ActiveCalibration(
            offset = CalibrationOffset(ASSUMED_FULL_SCALE_SPL),
            state = CalibrationState.Uncalibrated,
            saved = null,
        )

        /**
         * 저장된 값을 **걸어도 되는지 판정한 뒤** 건다(독립 재검토 CAR-03).
         *
         * 예전에는 판정 없이 그대로 걸었다. 그래서 `bottom` 에서 잰
         * +110dB 을 `back` 에서도 「보정 완료」로 썼다. 자리가 다르거나
         * 모르면 **걸지 않고 값만 들고 있는다** — 지우면 사람이 다시
         * 재야 하고, 그대로 걸면 근거 없이 승인하는 것이 된다.
         *
         * @param nowRoute 지금 열린 경로의 자리. 모르면 빈 문자열.
         * @param routeConfirmed 지금 경로를 실제로 확인했는가.
         */
        fun from(
            saved: GlobalCalibration?,
            nowRoute: String = "",
            routeConfirmed: Boolean = false,
        ): ActiveCalibration {
            if (saved == null) return assumed
            val verdict = judgeCalibrationRoute(saved.routeAddress, nowRoute, routeConfirmed)
            if (verdict.mayAutoApply) {
                return ActiveCalibration(
                    saved.toOffset(),
                    CalibrationState.GlobalCalibrated,
                    saved,
                )
            }
            return ActiveCalibration(
                offset = CalibrationOffset(ASSUMED_FULL_SCALE_SPL),
                state = CalibrationState.Uncalibrated,
                saved = saved,
                holdNoticeKo = routeNoticeKo(verdict, saved.routeAddress, nowRoute),
            )
        }
    }
}

/**
 * 간편 보정 계산(명세 8장, 컨셉 화면 5번).
 *
 * 기준 소음계가 가리킨 값에서 우리가 읽은 dBFS 를 빼면 그 차이가 보정값이다.
 * 예: 소음계 82.0 dB SPL, 우리 -38.0 dBFS → 보정값 +120.0 dB.
 */
fun computeOffset(referenceDb: Double, measuredDbfs: Double): Double = referenceDb - measuredDbfs

/**
 * 보정값으로 받아들일 수 있는 범위.
 *
 * 범위 밖이면 대개 잘못 입력했거나(기준값을 dBFS 로 적었다든지) 측정이
 * 안정되기 전에 눌렀다는 뜻이다. 조용히 저장하면 그 뒤의 모든 숫자가
 * 틀린 채로 그럴듯해 보인다.
 */
val PLAUSIBLE_OFFSET_RANGE = 60.0..160.0

/** 기준 소음계 값으로 받아들일 수 있는 범위. */
val PLAUSIBLE_REFERENCE_RANGE = 30.0..140.0
