package kr.joa.selahrta.domain.instrument

/**
 * 악기 EQ·다이내믹스 안내의 자료형(명세 §10·§18.6).
 *
 * **이것은 처방이 아니라 「어디를 들어볼지」다.** 명세 §1 이 못박았다 —
 * 고정 EQ 처방도, 자동 음질 판정도, 자동 믹서 제어도 아니다. 같은
 * 주파수가 바디를 만들기도 하고 먹먹함을 만들기도 한다.
 *
 * **숫자가 없으면 null 이다.** 0ms 나 임의 Threshold 로 채우지 않는다
 * (명세 §18.6). 비어 있다는 것이 「사용 안 함」이거나 「명세가 값을 주지
 * 않았다」는 뜻이고, 그 사실을 화면이 그대로 적는다.
 *
 * 값의 앞뒤·단위·부호는 **생성자에서 막는다.** 카탈로그가 커서 눈으로는
 * 놓친다(명세 §12.1 T11, §18.7 2번).
 */

/** 주파수 구간(Hz). 아래끝 < 위끝, 둘 다 유한한 양수. */
data class HzRange(val lowHz: Double, val highHz: Double) {
    init {
        require(lowHz.isFinite() && highHz.isFinite()) { "주파수가 유한하지 않다: $lowHz..$highHz" }
        require(lowHz > 0.0) { "주파수는 양수여야 한다: $lowHz" }
        require(lowHz < highHz) { "아래끝이 위끝보다 작아야 한다: $lowHz..$highHz" }
    }

    /** 화면에 적는 문자열. 1000 이상은 k 로 줄인다. */
    fun labelKo(): String = "${hz(lowHz)}~${hz(highHz)} Hz"

    private fun hz(v: Double): String = when {
        v >= 1000.0 -> {
            val k = v / 1000.0
            if (k == k.toInt().toDouble()) "${k.toInt()}k" else "${k}k"
        }
        v == v.toInt().toDouble() -> v.toInt().toString()
        else -> v.toString()
    }
}

/** 시간 범위(ms). 0 이상, 앞이 뒤보다 작거나 같다. */
data class MsRange(val minMs: Double, val maxMs: Double) {
    init {
        require(minMs.isFinite() && maxMs.isFinite()) { "시간이 유한하지 않다: $minMs..$maxMs" }
        require(minMs >= 0.0) { "시간은 0 이상이어야 한다: $minMs" }
        require(minMs <= maxMs) { "범위 순서가 뒤집혔다: $minMs..$maxMs" }
    }

    fun labelKo(): String = "${num(minMs)}~${num(maxMs)} ms"

    private fun num(v: Double) = if (v == v.toInt().toDouble()) v.toInt().toString() else v.toString()
}

/**
 * dB 범위.
 *
 * **게이트의 Range 는 이 앱에서 양수 「감쇠량」이다**(명세 §18.2).
 * 믹서의 음수 표기와 섞지 않는다.
 */
data class DbRange(val minDb: Double, val maxDb: Double) {
    init {
        require(minDb.isFinite() && maxDb.isFinite()) { "dB 가 유한하지 않다: $minDb..$maxDb" }
        require(minDb >= 0.0) { "감쇠량·GR 은 양수로 적는다: $minDb" }
        require(minDb <= maxDb) { "범위 순서가 뒤집혔다: $minDb..$maxDb" }
    }

    fun labelKo(): String = "${num(minDb)}~${num(maxDb)} dB"

    private fun num(v: Double) = if (v == v.toInt().toDouble()) v.toInt().toString() else v.toString()
}

/** 압축비. 1 이상(1:1 이 무압축). */
data class RatioRange(val min: Double, val max: Double) {
    init {
        require(min.isFinite() && max.isFinite()) { "ratio 가 유한하지 않다: $min..$max" }
        require(min >= 1.0) { "ratio 는 1 이상이어야 한다: $min" }
        require(min <= max) { "범위 순서가 뒤집혔다: $min..$max" }
    }

    fun labelKo(): String = "${num(min)}:1~${num(max)}:1"

    private fun num(v: Double) = if (v == v.toInt().toDouble()) v.toInt().toString() else v.toString()
}

enum class InstrumentType(val nameKo: String) {
    SYNTH("신디사이저"),
    ACOUSTIC_GUITAR("어쿠스틱 기타"),
    ELECTRIC_GUITAR("일렉기타"),
    BASS_GUITAR("베이스기타"),
    ELECTRONIC_DRUMS("전자드럼"),
    ACOUSTIC_DRUMS("리얼드럼"),
}

/**
 * 한 주파수 영역.
 *
 * **성격과 증상을 함께 지닌다.** 같은 구간이 「바디」이면서 「먹먹함」일
 * 수 있고, 그것이 이 가이드의 요점이다. 영역은 **의도적으로 겹친다**
 * (명세 §3).
 */
data class FrequencyRegion(
    val id: String,
    val range: HzRange,
    /** 성격(바디·펀치·명료도·어택·존재감·공기감 …). */
    val characterTags: List<String>,
    /** 이 구간을 조사하게 되는 증상(먹먹함·붕붕거림·날카로움 …). */
    val symptomTags: List<String>,
    val explanationKo: String,
    /** 어떤 조건에서 들어볼 것인가. */
    val listenConditionKo: String,
    /** 건드렸을 때의 부작용. **빈칸을 허용하지 않는다**(명세 §15). */
    val cautionKo: String,
    /** 자동 후보 규칙과 잇는 열쇠. 없으면 수동 안내만 한다. */
    val detectorRuleId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "영역 id 가 비었다" }
        require(characterTags.isNotEmpty() || symptomTags.isNotEmpty()) {
            "$id: 성격도 증상도 없으면 무엇을 들어야 할지 알 수 없다"
        }
        require(explanationKo.isNotBlank()) { "$id: 설명이 비었다" }
        require(listenConditionKo.isNotBlank()) { "$id: 들어볼 조건이 비었다" }
        require(cautionKo.isNotBlank()) { "$id: 주의사항이 비었다" }
    }
}

enum class FilterType(val labelKo: String) { HPF("HPF"), LPF("LPF") }

/**
 * HPF/LPF 의 **청취 시작점**.
 *
 * **활성 필터도, 실제 적용 EQ 곡선도 아니다**(명세 §6.1). 차단주파수가
 * 완전 제거 경계인 것처럼 그리지 않는다.
 */
data class FilterGuidance(
    val filterType: FilterType,
    /** 기본이 bypass 인가. 대부분의 LPF 가 그렇다. */
    val bypassDefault: Boolean,
    /** 시작점 범위. **없으면 null** — 임의 숫자로 채우지 않는다. */
    val rangeHz: HzRange?,
    /** 두 번째 시작점(예: 독주와 합주). 없으면 null. */
    val altRangeHz: HzRange? = null,
    /** 안내 기울기(dB/oct). 기본 12, 필요하면 6/18/24 를 설명한다. */
    val slopeOptionsDbOct: List<Int> = listOf(12, 6, 18, 24),
    val conditionKo: String,
    val cautionKo: String,
) {
    init {
        require(conditionKo.isNotBlank()) { "$filterType: 조건이 비었다" }
        require(cautionKo.isNotBlank()) { "$filterType: 주의사항이 비었다" }
        require(slopeOptionsDbOct.isNotEmpty()) { "$filterType: 기울기가 없다" }
        require(slopeOptionsDbOct.all { it > 0 }) { "$filterType: 기울기가 양수가 아니다" }
        require(altRangeHz == null || rangeHz != null) { "$filterType: 두 번째 범위만 있을 수 없다" }
    }
}

enum class ProcessorType(val labelKo: String) {
    GATE("게이트"),
    EXPANDER("익스팬더"),
    COMPRESSOR("컴프레서"),
}

/**
 * 게이트·익스팬더 출발점(명세 §18.3).
 *
 * **Threshold 숫자는 주지 않는다.** 입력 게인·장비 기준에 따라 달라지므로
 * 외부 믹서에서 맞추는 **방법**을 적는다.
 */
data class GateGuidance(
    val attackMs: MsRange?,
    val holdMs: MsRange?,
    val releaseMs: MsRange?,
    /** 닫혔을 때의 **감쇠량**(양수). */
    val attenuationDb: DbRange?,
    val hysteresisDb: DbRange?,
    val thresholdProcedureKo: String,
) {
    init {
        require(thresholdProcedureKo.isNotBlank()) { "게이트: Threshold 맞추는 방법이 비었다" }
    }

    /** 숫자 프리셋이 없는가(패드·전체 키트·심벌 등). */
    val hasNumbers: Boolean
        get() = attackMs != null || holdMs != null || releaseMs != null || attenuationDb != null
}

/**
 * 컴프레서 출발점(명세 §18.4).
 *
 * [grReferenceDb] 는 **외부 믹서의 GR 미터에서 사용자가 확인할 참고 범위**다.
 * 앱의 실측값이 아니다 — 객석 마이크 하나로는 처리 전후 신호를 동시에
 * 가질 수 없다(명세 §18.1).
 */
data class CompressorGuidance(
    val ratio: RatioRange?,
    val attackMs: MsRange?,
    val releaseMs: MsRange?,
    val kneeHintKo: String,
    val grReferenceDb: DbRange?,
    val thresholdProcedureKo: String,
    val makeupProcedureKo: String,
) {
    init {
        require(kneeHintKo.isNotBlank()) { "컴프레서: knee 안내가 비었다" }
        require(thresholdProcedureKo.isNotBlank()) { "컴프레서: Threshold 맞추는 방법이 비었다" }
        require(makeupProcedureKo.isNotBlank()) { "컴프레서: Makeup 안내가 비었다" }
    }

    val hasNumbers: Boolean
        get() = ratio != null || attackMs != null || releaseMs != null || grReferenceDb != null
}

/**
 * 한 악기·세부유형의 다이내믹스 안내 하나.
 *
 * **[gate] 와 [compressor] 중 정확히 하나만 채운다** — [processorType] 과
 * 짝이 맞아야 한다.
 */
data class DynamicsGuidance(
    val processorType: ProcessorType,
    val bypassDefault: Boolean,
    /** 언제 써 볼 것인가. bypass 가 기본이면 그 까닭. */
    val usageConditionKo: String,
    val cautionsKo: List<String>,
    val gate: GateGuidance? = null,
    val compressor: CompressorGuidance? = null,
) {
    init {
        require(usageConditionKo.isNotBlank()) { "$processorType: 사용 조건이 비었다" }
        require(cautionsKo.isNotEmpty() && cautionsKo.none { it.isBlank() }) {
            "$processorType: 주의사항이 비었다"
        }
        when (processorType) {
            ProcessorType.GATE, ProcessorType.EXPANDER -> {
                require(gate != null && compressor == null) { "$processorType: 게이트 값만 있어야 한다" }
            }
            ProcessorType.COMPRESSOR -> {
                require(compressor != null && gate == null) { "$processorType: 컴프레서 값만 있어야 한다" }
            }
        }
    }
}

/**
 * 악기 하나의 한 세부유형.
 *
 * 「신디사이저」 전체에 단일 프리셋을 두지 않는다(명세 §4.1). 피아노와
 * 패드는 들을 곳이 다르다.
 */
data class InstrumentProfile(
    /** 저장·비교에 쓰는 안정된 열쇠. 화면 문구가 바뀌어도 그대로다. */
    val stableId: String,
    val type: InstrumentType,
    val subtypeId: String,
    val nameKo: String,
    /** 합주에서 맡는 역할(예: 저역 담당, 화성 채움). */
    val roles: List<String>,
    val regions: List<FrequencyRegion>,
    val hpf: FilterGuidance,
    val lpf: FilterGuidance,
    /** 이 악기에서 **EQ 보다 먼저 볼 것**들. */
    val cautions: List<String>,
    val sourceIds: List<String>,
    val dynamics: List<DynamicsGuidance>,
) {
    init {
        require(stableId.isNotBlank() && subtypeId.isNotBlank()) { "열쇠가 비었다" }
        require(nameKo.isNotBlank()) { "$stableId: 이름이 비었다" }
        require(regions.isNotEmpty()) { "$stableId: 영역이 없다" }
        require(regions.map { it.id }.distinct().size == regions.size) { "$stableId: 영역 id 가 겹친다" }
        require(hpf.filterType == FilterType.HPF) { "$stableId: hpf 자리에 LPF 가 들어 있다" }
        require(lpf.filterType == FilterType.LPF) { "$stableId: lpf 자리에 HPF 가 들어 있다" }
        require(cautions.isNotEmpty() && cautions.none { it.isBlank() }) { "$stableId: 주의사항이 비었다" }
        require(sourceIds.isNotEmpty()) { "$stableId: 근거 자료가 비었다" }
        // 게이트/익스팬더 하나 + 컴프레서 하나. 명세 §15 가 6종 전부에
        // 게이트·컴프레서 안내를 요구한다.
        require(dynamics.count { it.processorType == ProcessorType.COMPRESSOR } == 1) {
            "$stableId: 컴프레서 안내가 하나여야 한다"
        }
        require(
            dynamics.count {
                it.processorType == ProcessorType.GATE || it.processorType == ProcessorType.EXPANDER
            } == 1,
        ) {
            "$stableId: 게이트(또는 익스팬더) 안내가 하나여야 한다"
        }
    }

    val gate: DynamicsGuidance
        get() = dynamics.first {
            it.processorType == ProcessorType.GATE || it.processorType == ProcessorType.EXPANDER
        }

    val compressor: DynamicsGuidance
        get() = dynamics.first { it.processorType == ProcessorType.COMPRESSOR }
}
