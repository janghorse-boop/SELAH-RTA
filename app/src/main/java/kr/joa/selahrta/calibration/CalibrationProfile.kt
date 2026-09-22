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
data class GlobalCalibration(
    val offsetDb: Double,
    /** 언제 쟀는가. 오래된 보정은 다시 하라고 권할 수 있다. */
    val savedAtEpochMs: Long,
    /** 그때 기준 소음계가 가리킨 값. 무엇에 맞췄는지 남긴다. */
    val referenceDb: Double,
    /** 그때 우리가 읽은 dBFS. 위 둘의 차이가 offsetDb 다. */
    val measuredDbfs: Double,
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
) {
    /** 이 값으로 나온 음압을 측정값이라 불러도 되는가. */
    val isReferenceOnly: Boolean get() = state == CalibrationState.Uncalibrated

    companion object {
        /** 저장된 보정이 없을 때. 짐작한 눈금에 「미보정」을 붙인다. */
        val assumed = ActiveCalibration(
            offset = CalibrationOffset(ASSUMED_FULL_SCALE_SPL),
            state = CalibrationState.Uncalibrated,
            saved = null,
        )

        fun from(saved: GlobalCalibration?) = saved?.let {
            ActiveCalibration(it.toOffset(), CalibrationState.GlobalCalibrated, it)
        } ?: assumed
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
