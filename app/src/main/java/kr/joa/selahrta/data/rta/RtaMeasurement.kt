package kr.joa.selahrta.data.rta

/**
 * **한 번 저장한 RTA 측정**(담당자 지시 2026-09-29, 지시서 §7).
 *
 * > 저장 목적은 같은 위치에서 좌우와 양쪽 출력을 비교하고, 이후 조정 전후
 * > 결과까지 다시 확인하는 것이다.
 *
 * ## 그림이 아니라 값을 담는다
 *
 * 그래프를 그림으로 남기면 **나중에 겹쳐 보거나 좌우 차이를 셈할 수
 * 없다.** 31칸 값을 그대로 담는다.
 *
 * ## 한 장이 아니라 평균이다
 *
 * [bandsSpl] 은 [averagedFrames] 장을 에너지로 평균 낸 값이다. 핑크 노이즈는
 * 장마다 밴드 값이 흔들려서, 한 장만 담으면 같은 자리에서 두 번 재도 몇
 * dB 씩 다르게 나온다 — 그 둘을 겹쳐 놓고 「좌우가 다르다」고 읽으면
 * **없는 차이를 보는 것**이다.
 */
data class RtaMeasurement(
    val id: String,
    /** 어느 비교 세트에 드는가. 세트는 이 값으로만 묶인다. */
    val setId: String,
    /** 사람이 붙인 이름. 예: 「본당 중앙 · L」 */
    val nameKo: String,
    /**
     * 어떻게 잰 것인가.
     *
     * **지금은 `rta` 하나뿐인데도 적어 둔다.** 일반 RTA 곡선과 Sweep 분석으로
     * 얻은 응답은 **재는 방식이 다르므로 이름만 보고 같은 자료로 다루면
     * 안 된다**(담당자 지시 4항). 나중에 FR·Sweep 이 같은 저장소에 들어오면
     * 이 칸이 둘을 가른다. 그때 가서 붙이면 **옛 기록에는 없다.**
     */
    val method: String,
    /** 주파수별 측정 레벨(dB SPL). 1/3 옥타브 31칸. */
    val bandsSpl: DoubleArray,
    /** 무슨 신호를 틀고 쟀나. `TestSignal` 의 이름. */
    val signal: String,
    /** 어느 쪽으로 냈나. `SignalChannels` 의 이름. */
    val channel: String,
    /** 낸 세기(dBFS). */
    val outputDbfs: Double,
    /** 몇 장을 평균 냈나. */
    val averagedFrames: Int,
    val conditions: RtaConditions,
    val measuredAtEpochMs: Long,
    /**
     * **이 평균이 실제로 얼마나 채워졌나**(0~1) — 독립 검토 PND-03.
     *
     * 분석이 낸 창들이 덮은 **입력 구간의 합집합**을 10초로 나눈 값이다.
     * 창은 서로 겹치므로 **「창 수 × 창 길이」로 세면 이중으로 셀해진다.**
     *
     * **아직 판정에 쓰지 않는다**(설계 단계 A). 문턱값을 정할 근거가
     * 없어 먼저 모으는 중이다.
     *
     * **조건이 아니라 진단값이다** — coverage 가 다르다고 두 측정을
     * 견줄 수 없는 것은 아니다. 그래서 [RtaConditions] 에 안 넣었다.
     */
    val coverage: Double? = null,
    /** 받아들인 창 수. 진단용 — **시간을 이것으로 세지 않는다.** */
    val windows: Int? = null,
    /** 분석이 건너뛰는 폭(표본). 나중에 다시 셀하려면 있어야 한다. */
    val hopFrames: Int? = null,

    val memoKo: String = "",
) {
    // 배열을 든 data class 의 equals 는 참조 비교라 헷갈린다. 값으로 견준다.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RtaMeasurement) return false
        return id == other.id &&
            setId == other.setId &&
            nameKo == other.nameKo &&
            method == other.method &&
            bandsSpl.contentEquals(other.bandsSpl) &&
            signal == other.signal &&
            channel == other.channel &&
            outputDbfs == other.outputDbfs &&
            averagedFrames == other.averagedFrames &&
            conditions == other.conditions &&
            measuredAtEpochMs == other.measuredAtEpochMs &&
            memoKo == other.memoKo
    }

    override fun hashCode(): Int = id.hashCode() * 31 + bandsSpl.contentHashCode()
}

/**
 * 잰 값을 **실제로 움직이는** 조건만 담는다.
 *
 * ## 왜 `MeasurementConditions` 를 통째로 담지 않는가
 *
 * 그쪽에는 잰 값과 무관한 것이 많고(기기 이름·처리 플래그 등), 늘어날
 * 때마다 겉장 모양이 흔들린다. **여기 들어오는 것은 「다르면 견주기 전에
 * 알려야 하는 것」뿐이다**(설계 7-3).
 *
 * **출력 채널과 레벨은 여기 없다** — 그것이 다른 것이 바로 비교하려는
 * 까닭이므로, 달라도 알릴 일이 아니다. 그 둘은 [RtaMeasurement] 가 든다.
 */
data class RtaConditions(
    /** 어느 마이크로 쟀나. 마이크가 다르면 곡선이 통째로 다르다. */
    val inputKey: String?,
    /** 보정 상태. 보정 안 된 값과 된 값은 같은 축이 아니다. */
    val calibrationState: String?,
    /** 보정이 어디서 왔나. */
    val calibrationSource: String?,
    /**
     * 주파수 보정 곡선 이름.
     *
     * **「안 걸렸다」와 「모른다」는 다르다.** 안 걸렸으면 빈 글자,
     * 겉장에 아예 없었으면 null 이다.
     */
    val curveName: String?,
    val fftSize: Int?,
    val sampleRate: Int?,
    /**
     * **분석 가중**(Z·A·C) — 독립 검토 RMS-03.
     *
     * 이것이 빠져 있어서 **같은 소리를 Z 로 잰 것과 A 로 잰 것이 「같은
     * 조건」이었다.** 100Hz 에서 그 차이는 **19dB** 다 — 좌우 차이라며
     * 19dB 을 내밀 수 있었다.
     */
    val analysisWeighting: String? = null,
    /**
     * **보정값 그 자체**(dB) — 독립 검토 RMS-03.
     *
     * 「보정됨·교정기」까지만 적고 **수치를 안 적었다.** 100dB 로 맞춘 것과
     * 106dB 로 맞춘 것이 같은 조건으로 저장되어 6dB 이 좌우 차이로 읽혔다.
     */
    val offsetDb: Double? = null,
    /**
     * **곡선 내용의 지문** — 독립 검토 RMS-03.
     *
     * 파일 이름만으로는 모자란다. 같은 이름으로 **다른 곡선**을 가져올 수
     * 있고, 그러면 곡선이 바뀐 줄 모르고 견준다.
     *
     * 곡선이 안 걸렸으면 빈 글자다 — 「모른다」가 아니다.
     */
    val curveHash: String? = null,
    /**
     * 입력 소스 — 독립 검토 RMS-03.
     *
     * 같은 기기라도 무엇으로 열었는지에 따라 가공이 다르다.
     */
    val inputSource: String? = null,
    /**
     * 입력 채널 — 독립 검토 RMS-03.
     *
     * 같은 USB 인터페이스라도 **어느 채널을 들었는지**에 따라 다른
     * 마이크다. 보정 쪽 열쇠는 이미 이것을 갈라 쓰고 있었는데 여기만
     * 빠져 있었다.
     */
    val inputChannel: Int? = null,
    /**
     * **무슨 소리를 넣어 잰 것인가** — 독립 검토 PND-02.
     *
     * 신호의 **이름만으로는 모자란다.** 「주파수 지정」은 1kHz 일 수도
     * 2kHz 일 수도 있고, 「1/3 옥타브 대역」은 중심이 어디냐에 따라 전혀
     * 다른 소리다. 그것이 안 적히면 **다른 주파수로 잰 두 곡선이 같은
     * 조건**이 되고, 그 차이가 방의 차이로 읽힌다.
     *
     * 적는 꼴:
     * - `hz:1000.0` — 주파수를 고르는 신호(순음 지정)
     * - `band:1000.0:third-octave-butterworth-pair-v1` — 대역 잡음.
     *   **폭은 사람이 고르는 값이 아니라 규칙**이라 그 규칙의 이름을 적는다.
     * - `fixed` — 핑크·화이트·정해진 순음. **이름이 곧 조건**이다.
     * - `none` — 소리를 안 틀고 잰 것.
     *
     * 옛 파일에는 이 칸이 없어 **null(미확인)** 이다 — 지금 설정으로
     * 채우지 않는다.
     */
    val signalSpec: String? = null,
    /**
     * **평균을 어떻게 낸 것인가**의 이름(독립 검토 PND-03).
     *
     * 설계 단계 B 에서 평균 정의가 바뀜다 — 화면용으로 평활된 값을
     * 다시 평균하던 것에서, 분석 스레드의 생값을 모으는 것으로.
     *
     * **그 앞뒤 값을 섞으면 안 된다.** 정의가 다른 두 곡선의 차이는
     * 방의 차이가 아니다 — `signalSpec` 과 같은 까닭으로 조건에 둔다.
     */
    val averageVersion: String? = null,
) {
    /**
     * 겉장에 없던 것은 **null 로 남는다**(담당자 지시 2026-09-29 기준 5).
     *
     * **지금 설정이나 기본값으로 채우지 않는다.** 채우면 그 자리에서
     * 「이 기록은 이 조건으로 쟀다」는 거짓이 만들어지고, 다음에 견줄 때
     * **다른 조건인데 같다고** 읽힌다. 옛 판으로 저장한 기록이 특히
     * 그렇다.
     */
    val hasUnknown: Boolean
        get() = signalSpec == null || averageVersion == null ||
            analysisWeighting == null || offsetDb == null || curveHash == null ||
            inputSource == null || inputChannel == null ||
            inputKey == null || calibrationState == null ||
            calibrationSource == null || curveName == null ||
            fftSize == null || sampleRate == null

    /** 화면에 적을 때 쓴다. 모르는 것은 「미확인」이라고 적는다. */
    fun textOf(value: Any?): String = value?.toString()?.ifBlank { "없음" } ?: UNKNOWN_KO

    companion object {
        const val UNKNOWN_KO = "미확인"
    }
}

/**
 * 한 자리·한 조건에서 잰 것들의 묶음.
 *
 * **세 채널을 다 재야만 저장되는 구조가 아니다**(담당자 지시 2항). 한
 * 채널만 있어도 그대로 쓸모가 있다 — 나중에 나머지를 더하면 된다.
 */
data class RtaComparisonSet(
    val id: String,
    val nameKo: String,
    val createdAtEpochMs: Long,
)

/**
 * 목록과 **못 읽은 것의 수**(독립 검토 12회차 4장).
 *
 * 「읽을 수 없다」와 「없다」는 다른 말이다. 조용히 건너뛰면 사람은
 * 저장이 안 된 줄 알고 **다시 재다.**
 */
data class RtaListing(
    val items: List<RtaMeasurement>,
    /** 격장을 못 읽어 빠진 수. 보통 **더 새 판**으로 저장된 것이다. */
    val unreadable: Int,
)
