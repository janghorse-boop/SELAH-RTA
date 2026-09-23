package kr.joa.selahrta.dsp

import kotlin.math.abs

/**
 * **이 측정을 보정으로 써도 되는가**
 * (S23 개별 교정 지시서 4장 「측정 신뢰도」·7.5).
 *
 * > 허용 기준을 통과하지 못하면 **저장·자동 적용을 막고** 재측정을
 * > 안내한다.
 *
 * ## 왜 관문이 먼저인가
 *
 * 2026-09-23 사무실에서 실제로 재 보니 **가장 센 대역에서도 SNR 이
 * 9dB** 였다(125Hz: 신호 82.8 / 배경 73.8dB). 에어컨·선풍기의 저역
 * 럼블 때문이다. 그 조건에서 나온 보정 곡선은 **저역이 통째로 배경
 * 소음의 모양**이 된다 — 그런데 그래프는 멀쩡해 보인다.
 *
 * 관문이 없으면 그 곡선이 저장되고, 그 뒤 모든 측정에 걸린다. 그래서
 * 그리는 것보다 막는 것을 먼저 만든다.
 *
 * ## 문턱은 **재서 고른 값이 아니다**
 *
 * 아래 기본값은 정책이다. 지시서 7.5 가 *「허용 오차·주파수 범위·SNR
 * 기준은 사전 정의하고 측정 결과와 함께 보고한다」* 고 해서 [Policy] 로
 * 묶어 결과와 함께 저장한다. 실측으로 고칠 일이 생기면 그때 고친다.
 */

/** 한 주파수에서 신호와 배경을 함께 잰 값. */
data class BandNoise(
    val hz: Double,
    /** 신호를 낼 때의 레벨(dB). */
    val signalDb: Double,
    /** 신호 없이 잰 배경(dB). */
    val noiseDb: Double,
) {
    /**
     * 신호 대 잡음(dB).
     *
     * **배경을 빼지 않고 그냥 차를 쓴다.** 「신호」라고 잰 값에는 배경이
     * 이미 섞여 있으므로 엄밀히는 (S+N)/N 이다. 그 차이는 SNR 이 낮을수록
     * 커지는데, 그쪽이 **보수적인** 방향이라 그대로 둔다 — 실제보다
     * 좋게 보이지 않는다.
     */
    val snrDb: Double get() = signalDb - noiseDb
}

/** 통과 여부. **올라가는 쪽으로 기울이지 않는다.** */
enum class QualityVerdict(
    /**
     * 판정을 **가리키는** 짧은 이름. 괄호 안이나 목록에 쓴다.
     *
     * 화면의 판정 막대가 쓰는 「쓸 수 있습니다」 같은 문장과는 쓰임이
     * 다르다 — 그쪽은 사람에게 말을 거는 자리고, 이쪽은 어느 판정인지
     * 가리키는 자리다.
     */
    val labelKo: String,
) {
    /** 저장하고 자동 적용해도 된다. */
    Pass("통과"),

    /** 저장은 하되 **자동 적용은 막는다.** 사람이 보고 켜야 한다. */
    Degraded("제한"),

    /** 저장하지 않는다. 다시 재야 한다. */
    Fail("미달"),
}

/** 미리 정한 허용 기준. **결과와 함께 저장한다.** */
data class QualityPolicy(
    /** 이 아래인 대역은 보정에서 뺀다. */
    val minBandSnrDb: Double = 12.0,
    /** 쓸 수 있는 대역이 이 비율 아래면 측정을 버린다. */
    val minUsableBandRatio: Double = 0.6,
    /** 반복 측정이 이보다 벌어지면 버린다. */
    val maxRepeatSpreadDb: Double = 2.0,
    /** 기준 전후 재측정의 **광대역** 차이가 이보다 크면 버린다. */
    val maxReferenceDriftDb: Double = 1.0,
    /**
     * 기준 전후 재측정의 **대역별** 차이가 이보다 크면 버린다.
     *
     * **광대역만 보면 모양 변화가 상쇄된다**(독립 검증 CP01). 20번 밴드가
     * 오르고 24번 밴드가 내리면 광대역 차이는 0 인데, 그 모양 변화가
     * 그대로 마이크 보정으로 기록된다 — 반례에서 **6.73dB** 의 보정이
     * 생겼다.
     *
     * 광대역보다 느슨한 것은 대역 하나의 우연한 흔들림이 광대역보다 크기
     * 때문이다.
     */
    val maxReferenceBandDriftDb: Double = 2.0,
    /**
     * 한 단계에 적어도 이만큼의 장이 있어야 반복성을 말할 수 있다.
     *
     * **장이 하나면 벌어짐이 0 으로 나오는데, 그것은 「흔들리지 않았다」가
     * 아니라 「모른다」다**(독립 검증 CP01). 이 값은 **바닥이지 충분함의
     * 증명이 아니다** — 실제 세션은 수초 분량으로 훨씬 많이 모은다.
     */
    val minFramesPerStep: Int = 8,
)

/** 한 번의 교정 측정에서 나온 품질 지표들. */
data class QualityReport(
    val bands: List<BandNoise>,
    /**
     * 같은 조건을 되풀이해 잰 값들의 벌어짐(dB).
     *
     * **세 단계 중 가장 나쁜 것**이다 — 기준이 흔들렸으면 대상이
     * 얌전해도 그 세션은 못 쓴다(독립 검증 CP01). `null` 은 「흔들리지
     * 않았다」가 아니라 **「알 수 없다」**(장이 모자랐다).
     */
    val repeatSpreadDb: Double? = null,
    /** 기준 측정 → 대상 측정 → 기준 재측정 의 **광대역** 차이(dB). 안 쟀으면 null. */
    val referenceDriftDb: Double? = null,
    /**
     * 앞뒤 기준의 **대역별** 차이 중 가장 큰 것(dB).
     *
     * 광대역 차이가 0 이어도 이 값은 클 수 있다 — 그때 변한 것은 음량이
     * 아니라 **모양**이고, 그 모양이 마이크 보정으로 기록된다(CP01).
     */
    val referenceBandDriftDb: Double? = null,
    /**
     * 어느 단계에서든 **안정된 장을 하나도 못 골랐는가**.
     *
     * 그때 세션은 걸러내기를 포기하고 전부 쓴다. 기록에는 「버린 장 0」
     * 으로 남지만 실제로 일어난 일은 정반대다 — 그래서 따로 적는다.
     */
    val noStableFrames: Boolean = false,
    /**
     * 한 단계에서 **실제로 평균에 쓴** 장 수 중 가장 적은 것. 모르면 null.
     *
     * **넣은 수가 아니다**(독립 검증 RCP03). 여덟 장을 넣고 걸러내기가
     * 둘만 남겼으면 둘로 판단한 것이다.
     */
    val minFramesPerStep: Int? = null,
    /**
     * **기준 경로**의 대역별 신호·배경. 안 쟀으면 null.
     *
     * 대상 경로의 SNR 은 기준 경로의 신뢰도를 증명하지 않는다(독립 검증
     * RCP02). 기준 마이크의 잡음 바닥이 대상 마이크의 주파수 특성으로
     * 기록될 수 있고, 앞뒤 기준이 **둘 다 같은 배경**이면 흐름 검사도
     * 그것을 잡지 못한다.
     *
     * null 은 「기준 경로가 조용했다」가 아니라 **「모른다」**다 —
     * 그때는 검증 완료(Pass)를 주지 않는다.
     */
    val referenceBands: List<BandNoise>? = null,
    /** 재는 동안 잘린 적이 있는가. */
    val clipped: Boolean = false,
    /**
     * 잔여 DSP 를 **신호로** 확인했는가(지시서 2장).
     *
     * 설정값만 보고 「AGC 가 꺼져 있다」고 하는 것과, 일정 레벨 신호의
     * 시간별 이득 변화를 재서 확인하는 것은 다르다. 후자를 안 했으면
     * false 다.
     */
    val dspVerifiedBySignal: Boolean = false,
    val policy: QualityPolicy = QualityPolicy(),
) {
    /** **대상 경로** 대역마다 보정에 쓸 수 있는가. */
    val usable: List<Boolean> get() = bands.map { it.snrDb >= policy.minBandSnrDb }

    /**
     * **기준 경로** 대역마다 믿을 수 있는가. 안 쟀으면 전부 false 다 —
     * 「모른다」를 「괜찮다」로 바꾸지 않는다(독립 검증 RCP02).
     */
    val referenceUsable: List<Boolean>
        get() = referenceBands?.map { it.snrDb >= policy.minBandSnrDb }
            ?: List(bands.size) { false }

    /** 기준 경로의 배경을 쟀는가. */
    val referenceSnrKnown: Boolean get() = referenceBands != null

    /**
     * 두 경로가 **함께** 믿을 만한 대역. 보정에 쓸 수 있는 자리다.
     *
     * 기준을 안 쟀으면 비어 있다 — 그 상태로는 보정을 만들지 않는다.
     */
    val bothUsable: List<Boolean>
        get() = usable.indices.map { usable[it] && referenceUsable[it] }

    val usableCount: Int get() = usable.count { it }

    val usableRatio: Double
        get() = if (bands.isEmpty()) 0.0 else usableCount.toDouble() / bands.size

    /** 쓸 수 있는 대역이 덮는 주파수 범위. 없으면 null. */
    val usableRangeHz: ClosedFloatingPointRange<Double>?
        get() {
            val ok = bands.filterIndexed { i, _ -> usable[i] }
            if (ok.isEmpty()) return null
            return ok.minOf { it.hz }..ok.maxOf { it.hz }
        }

    val worstSnrDb: Double? get() = bands.minOfOrNull { it.snrDb }
    val bestSnrDb: Double? get() = bands.maxOfOrNull { it.snrDb }
}

/** 판정과 **왜 그렇게 판정했는지.** 화면과 보고서에 그대로 쓴다. */
data class QualityResult(
    val verdict: QualityVerdict,
    val reasonsKo: List<String>,
    val report: QualityReport,
) {
    val mayAutoApply: Boolean get() = verdict == QualityVerdict.Pass
    val maySave: Boolean get() = verdict != QualityVerdict.Fail
}

/**
 * 판정한다. **막는 쪽이 여러 개면 전부 적는다** — 하나만 고치고 다시
 * 막히면 사람이 지친다.
 */
fun judgeQuality(report: QualityReport): QualityResult {
    val p = report.policy
    val fails = mutableListOf<String>()
    val degrades = mutableListOf<String>()

    if (report.bands.isEmpty()) {
        return QualityResult(
            QualityVerdict.Fail,
            listOf("잰 대역이 없습니다."),
            report,
        )
    }

    // **잘린 측정은 쓸 수 없다.** 잘린 구간의 값은 실제보다 낮고,
    // 없던 고조파가 생긴다.
    if (report.clipped) {
        fails += "재는 동안 입력이 잘렸습니다. GAIN 을 낮추고 다시 재십시오."
    }

    if (report.usableRatio < p.minUsableBandRatio) {
        fails += buildString {
            append("쓸 수 있는 대역이 ")
            append("${report.usableCount}/${report.bands.size}")
            append("(${"%.0f".format(report.usableRatio * 100)}%)뿐입니다 — ")
            append("SNR ${"%.0f".format(p.minBandSnrDb)}dB 이상인 대역이 ")
            append("${"%.0f".format(p.minUsableBandRatio * 100)}% 는 되어야 합니다. ")
            append("배경 소음을 줄이거나 신호를 키우십시오.")
        }
    }

    report.repeatSpreadDb?.let {
        if (it > p.maxRepeatSpreadDb) {
            fails += "반복 측정이 ${"%.1f".format(it)}dB 벌어졌습니다" +
                "(허용 ${"%.1f".format(p.maxRepeatSpreadDb)}dB). " +
                "마이크·스피커·사람이 움직이지 않았는지 보십시오."
        }
    }

    report.referenceDriftDb?.let {
        if (abs(it) > p.maxReferenceDriftDb) {
            fails += "기준 재측정이 앞과 ${"%.1f".format(abs(it))}dB 다릅니다" +
                "(허용 ${"%.1f".format(p.maxReferenceDriftDb)}dB). " +
                "재는 동안 스피커나 환경이 변했습니다."
        }
    }

    // **광대역이 같아도 모양은 변했을 수 있다**(독립 검증 CP01).
    // 이것을 보지 않으면 환경 변화가 마이크 특성으로 기록된다.
    report.referenceBandDriftDb?.let {
        if (it > p.maxReferenceBandDriftDb) {
            fails += "기준 재측정이 어떤 대역에서 앞과 ${"%.1f".format(it)}dB 다릅니다" +
                "(허용 ${"%.1f".format(p.maxReferenceBandDriftDb)}dB). " +
                "전체 음량은 같아도 소리의 「모양」이 변했다는 뜻입니다 — " +
                "스피커·마이크 위치나 주변 소리가 달라졌는지 보십시오."
        }
    }

    // **안정된 장을 하나도 못 골랐다면 반복성을 말할 수 없다.**
    // 걸러내기를 포기하고 전부 쓴 것이므로, 그 평균은 「잰 값」이 아니다.
    if (report.noStableFrames) {
        fails += "안정된 구간을 하나도 찾지 못했습니다. 재는 동안 소리가 " +
            "계속 변했다는 뜻입니다 — 조용해진 뒤 다시 재십시오."
    }

    // **장이 하나면 벌어짐이 0 으로 나오지만 그것은 「모른다」다.**
    when (val n = report.minFramesPerStep) {
        null -> degrades += "모은 장 수를 기록하지 않아 반복성을 말할 수 없습니다."
        else -> if (n < p.minFramesPerStep) {
            fails += "한 단계에 모인 장이 ${n}개뿐입니다" +
                "(적어도 ${p.minFramesPerStep}개). 더 길게 재십시오."
        }
    }

    // 벌어짐을 아예 재지 못한 경우도 **통과로 넘기지 않는다.**
    if (report.repeatSpreadDb == null) {
        degrades += "반복 측정의 벌어짐을 재지 못했습니다. 흔들리지 않았다는 뜻이 아닙니다."
    }

    // **기준 경로의 SNR 을 모르면 검증 완료를 주지 않는다**(RCP02).
    // 대상이 조용한 것은 기준이 조용했다는 증명이 아니다.
    if (!report.referenceSnrKnown) {
        degrades += "기준 경로의 배경 소음을 재지 않았습니다. 기준 마이크가 " +
            "실제로 신호를 잡았는지 확인되지 않아, 이 보정은 자동으로 걸리지 않습니다."
    } else {
        val refUsable = report.referenceUsable.count { it }
        val refRatio = refUsable.toDouble() / report.bands.size
        if (refRatio < p.minUsableBandRatio) {
            fails += "기준 경로에서 쓸 수 있는 대역이 " +
                "$refUsable/${report.bands.size}" +
                "(${"%.0f".format(refRatio * 100)}%)뿐입니다. " +
                "기준 마이크 쪽 배경 소음이 너무 큽니다."
        }
    }

    // **DSP 를 신호로 확인하지 않았으면 자동 적용까지는 못 간다.**
    // 설정값만 보고 「꺼져 있다」고 하는 것으로는 모자란다(지시서 2장).
    if (!report.dspVerifiedBySignal) {
        degrades += "잔여 DSP 를 신호로 확인하지 않았습니다. 설정값만 보고 " +
            "판단한 것이라, 이 보정은 저장은 되지만 자동으로 걸리지 않습니다."
    }

    // 쓸 수 있는 대역이 있어도 **범위가 좁으면** 그 사실을 적는다.
    report.usableRangeHz?.let { r ->
        if (fails.isEmpty() && (r.start > 100.0 || r.endInclusive < 8000.0)) {
            degrades += "믿을 수 있는 범위가 " +
                "${r.start.toInt()}Hz ~ ${(r.endInclusive / 1000).toInt()}kHz 뿐입니다. " +
                "그 밖에서는 보정이 걸리지 않습니다."
        }
    }

    return when {
        fails.isNotEmpty() -> QualityResult(QualityVerdict.Fail, fails + degrades, report)
        degrades.isNotEmpty() -> QualityResult(QualityVerdict.Degraded, degrades, report)
        else -> QualityResult(
            QualityVerdict.Pass,
            listOf(
                "쓸 수 있는 대역 ${report.usableCount}/${report.bands.size}, " +
                    "가장 나쁜 SNR ${"%.0f".format(report.worstSnrDb ?: 0.0)}dB.",
            ),
            report,
        )
    }
}
