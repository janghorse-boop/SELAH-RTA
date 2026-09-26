package kr.joa.selahrta.dsp

/**
 * 보정 파일의 **둘째 열이 무엇인가** — 응답인가 보정값인가.
 *
 * ## 왜 이것이 중요한가
 *
 * [CalibrationCurve] 는 둘째 열을 **마이크의 응답**으로 읽고 측정값에서
 * **뺀다.** 널리 쓰이는 `.cal`/`.frd` 가 그 규약이기 때문이다.
 *
 * 그런데 어떤 제조사는 **이미 뒤집힌 값**(correction)을 준다. 그 파일을
 * 응답으로 읽으면 **보정이 정확히 두 배로, 반대 방향으로 걸린다** —
 * +2dB 여야 할 자리가 −2dB 가 되어 4dB 가 틀어진다.
 *
 * ## 이 파일이 하는 일과 하지 않는 일
 *
 * **부호를 정하지 않는다.** 실제 Dayton EMM-6 파일을 보기 전에는 정할
 * 수 없고, 추측해서 고치면 측정값이 통째로 틀어진다(USB 오디오 지시서
 * 9.4: *「불명확하면 임의 부호로 측정값을 변경하지 않는다」*).
 *
 * 하는 일은 **증거를 모아 사람에게 보이는 것**뿐이다. 파일 머리글에
 * 열 이름이 적혀 있는 경우가 많고, 거기에 우리 가정과 어긋나는 말이
 * 있으면 알린다.
 *
 * 기존 경고(보정량 ±30dB 초과)로는 못 잡는다 — 실제 측정 마이크 파일은
 * 대개 ±5dB 안쪽이라 **부호가 반대여도 조용히 통과한다.**
 */
enum class SignEvidence {
    /** 머리글이 「응답」 쪽을 가리킨다. 지금 가정과 같다. */
    LooksLikeResponse,

    /** 머리글이 「보정값」 쪽을 가리킨다. **지금 가정과 반대다.** */
    LooksLikeCorrection,

    /** 단서가 없다. 대부분의 파일이 여기다. */
    Unknown,
}

/**
 * 우리 가정(응답)과 같은 쪽을 가리키는 말 — **열 이름이 될 수 있는 것만.**
 *
 * `measured` 를 뺐다(독립 검토 CA-10). 그 말은 **잰 조건**을 적을 때도
 * 쓰이고, 그때는 둘째 열이 응답인지 보정값인지 아무 말도 하지 않는다.
 * 검토자가 `# Measured at 94 dB SPL` 한 줄로 기준 CAL 의 부호가 사람에게
 * 묻지도 않고 확정되는 것을 보였다.
 */
private val RESPONSE_WORDS = listOf("spl", "response", "magnitude", "응답")

/**
 * 「94 dB SPL」처럼 **잰 조건**을 적은 토막.
 *
 * 그 안의 `SPL` 은 열 이름이 아니라 소리 크기의 단위다. 세어 버리면 잰
 * 조건을 적어 둔 파일이 전부 「응답」으로 확정된다 — 실제로 그랬다.
 */
private val LEVEL_PHRASE = Regex("""\d+(?:\.\d+)?\s*db(?:\s*spl)?""")

/** 주파수 열임을 알리는 말. 열 선언이라면 반드시 하나는 있다. */
private val FREQ_WORDS = listOf("freq", "hz", "주파수")

/**
 * 머리글의 **열 선언**이 둘째 열을 무엇이라 부르는가(독립 재검토 CAR-04).
 *
 * ## 왜 Boolean 이면 안 되는가
 *
 * 「선언이 있는가」로 뭉개 두었더니, 선언이 있다는 사실만으로 **다른
 * 줄의 낱말**이 둘째 열의 뜻으로 확정됐다. 검토자가 셋을 보였다:
 *
 * | 머리글 | 예전 결과 | 무엇이 틀렸나 |
 * |---|---|---|
 * | `# Frequency range: 20 Hz, reference SPL: 94 dB` | 확정 | 조건 문장을 열 선언으로 읽었다 |
 * | `# Reference SPL: 94 dB` + `Frequency,Value` | 확정 | 다른 줄의 SPL 과 뜻 모를 `Value` 를 붙였다 |
 * | `Frequency,Phase,SPL` | 확정 | 둘째 열은 Phase 인데 셋째 열의 SPL 로 정했다 |
 *
 * 마지막 것이 가장 나쁘다. 파서는 **언제나 둘째 값**을 응답으로 읽으므로,
 * `1000,0,6` 에서 SPL 6 이 아니라 **위상 0** 을 보정에 쓴다.
 *
 * ## 그래서 자리를 본다
 *
 * 파서가 지원하는 것은 **첫 열 = Hz, 둘째 열 = dB** 하나뿐이다. 그
 * 순서의 **알려진 이름**일 때만 확정하고 나머지는 사람에게 묻는다.
 * 길이 제한 같은 어림으로 가르지 않는다 — 검토자의 반례는 모두 짧았다.
 */
sealed interface ColumnDeclaration {
    /** 열 선언이 없다. 설명문뿐이다. */
    data object None : ColumnDeclaration

    /** 선언은 있는데 **둘째 열을 모른다**(Phase 등). 사람에게 묻는다. */
    data class Unsupported(val secondKo: String) : ColumnDeclaration

    /** 둘째 열이 무엇인지 선언이 **직접** 말한다. */
    data class Second(val reading: CurveReading) : ColumnDeclaration
}

/**
 * 머리글 줄들에서 열 선언을 읽는다.
 *
 * 선언으로 보는 줄은 **첫 칸이 주파수 열 이름**인 줄뿐이다. 선언이
 * 여럿이고 서로 어긋나면 [ColumnDeclaration.Unsupported] — 그때는 어느
 * 쪽도 믿을 수 없다.
 */
fun columnDeclarationOf(headerLines: List<String>): ColumnDeclaration {
    val found = headerLines.mapNotNull { declarationIn(it) }.distinct()
    if (found.isEmpty()) return ColumnDeclaration.None
    if (found.size > 1) {
        return ColumnDeclaration.Unsupported(
            found.filterIsInstance<ColumnDeclaration.Unsupported>()
                .firstOrNull()?.secondKo ?: "서로 다른 선언이 여럿",
        )
    }
    return found.first()
}

private fun declarationIn(line: String): ColumnDeclaration? {
    val fields = line
        .removePrefix("#").removePrefix("*").removePrefix(";")
        .split(',', '\t')
        .flatMap { it.split(Regex(" {2,}")) }
        .map { it.trim().trim('"').trim() }
        .filter { it.isNotEmpty() }
    if (fields.size < 2) return null
    // **첫 칸이 주파수 열 이름이어야 한다.** 파서가 Hz 를 읽는 자리가 거기다.
    if (normalizeColumn(fields[0]) !in FREQ_COLUMNS) return null
    return when (normalizeColumn(fields[1])) {
        in RESPONSE_COLUMNS -> ColumnDeclaration.Second(CurveReading.Response)
        in CORRECTION_COLUMNS -> ColumnDeclaration.Second(CurveReading.Correction)
        else -> ColumnDeclaration.Unsupported(fields[1])
    }
}

/**
 * 열 이름을 견줄 수 있는 꼴로 만든다.
 *
 * 괄호 안(단위)을 떼고 소문자로 바꿔 공백을 하나로 줄인다 —
 * `Frequency (Hz)` · `Frequency(Hz)` · `FREQUENCY` 가 모두 `frequency` 다.
 * **낱말을 찾지 않고 통째로 견준다**: 「Frequency range: 20 Hz」 같은
 * 문장은 어느 이름과도 같지 않다.
 */
private fun normalizeColumn(raw: String): String = raw
    .replace(Regex("""[(\[][^)\]]*[)\]]"""), " ")
    .lowercase()
    .replace(Regex("""\s+"""), " ")
    .trim()

/** 파서가 첫 열로 받아들이는 이름. */
private val FREQ_COLUMNS = setOf("frequency", "freq", "hz", "f", "주파수", "frequency hz")

/** 둘째 열이 **마이크의 응답**임을 뜻하는 이름. */
private val RESPONSE_COLUMNS = setOf(
    "spl", "db", "db spl", "dbspl", "magnitude", "mag", "level", "response",
    "amplitude", "amp", "응답", "레벨",
)

/** 둘째 열이 **이미 뒤집힌 보정값**임을 뜻하는 이름. */
private val CORRECTION_COLUMNS = setOf(
    "correction", "corr", "compensation", "comp", "보정", "보정값",
)

/** 우리 가정과 **반대**를 가리키는 말. */
private val CORRECTION_WORDS = listOf("correction", "compensation", "보정값", "보정 값")

/**
 * 머리글 줄들에서 단서를 찾는다.
 *
 * **둘 다 나오면 모름이다.** 「Correction (from measured response)」 같은
 * 문장이 실제로 있고, 그런 파일은 사람이 봐야 한다.
 */
fun signEvidenceOf(headerLines: List<String>): SignEvidence {
    // **잰 조건 토막을 먼저 걷어낸다**(독립 검토 CA-10). 「94 dB SPL」의
    // SPL 은 둘째 열이 무엇인지 말하지 않는다.
    val text = LEVEL_PHRASE.replace(headerLines.joinToString(" ").lowercase(), " ")
    val response = RESPONSE_WORDS.any { text.contains(it) }
    val correction = CORRECTION_WORDS.any { text.contains(it) }
    return when {
        response && correction -> SignEvidence.Unknown
        correction -> SignEvidence.LooksLikeCorrection
        response -> SignEvidence.LooksLikeResponse
        else -> SignEvidence.Unknown
    }
}

/**
 * 사람에게 할 말. 없으면 null.
 *
 * **막지 않는다.** 단서일 뿐이고, 틀릴 수 있다. 판단은 사람이 한다 —
 * 다만 모르고 지나가지는 않게 한다.
 */
fun signNoticeKo(evidence: SignEvidence): String? = when (evidence) {
    SignEvidence.LooksLikeResponse, SignEvidence.Unknown -> null
    // **화면에 그대로 나가는 문장이다.** 마크다운 강조(`**`)를 쓰지 않는다 —
    // 이 자리는 서식 없는 Text 라 별표가 글자 그대로 보인다. 기기에서
    // 확인하기 전에는 몰랐다(2026-09-22 실기기).
    SignEvidence.LooksLikeCorrection ->
        "이 파일의 머리글이 「보정값(correction)」으로 읽힙니다. 앱은 둘째 열을 " +
            "「마이크의 응답」으로 보고 측정값에서 빼는데, 이미 뒤집힌 값이라면 " +
            "보정이 반대로 두 배 걸립니다. 제조사 설명을 확인하십시오."
}

/**
 * 둘째 열을 **무엇으로 읽을 것인가.**
 *
 * 증거([SignEvidence])는 단서일 뿐이고, 이것은 **정해진 값**이다.
 * 어느 쪽으로 읽었는지 프로파일에 남아야 나중에 되짚을 수 있다
 * (독립 검토 R04: 「response/correction 을 명시적으로 선택·기록하고」).
 */
enum class CurveReading(val labelKo: String, val explainKo: String) {
    /** 마이크의 **응답**. 측정값에서 뺀다. `.cal`·`.frd` 의 일반 규약이다. */
    Response(
        "마이크 응답",
        "둘째 열이 마이크가 실제로 낸 값입니다. 측정값에서 뺍니다. " +
            ".cal·.frd 파일은 대개 이쪽입니다.",
    ),

    /** 이미 뒤집힌 **보정값**. 측정값에 더한다. */
    Correction(
        "보정값",
        "둘째 열이 이미 뒤집힌 값입니다. 측정값에 더합니다. " +
            "이쪽을 응답으로 읽으면 보정이 반대로 두 배 걸립니다.",
    ),
    ;

    /** 응답 규약으로 옮길 때 곱할 값. */
    val toResponseSign: Double get() = if (this == Response) 1.0 else -1.0
}

/**
 * 이 곡선을 **무엇에 쓰는가.** 같은 모호함이라도 걸린 것이 다르다.
 */
enum class ReadingStakes {
    /**
     * RTA 막대에 거는 표시용 곡선.
     *
     * 틀리면 화면의 막대가 어긋나고, 보고 있으면 알아챌 여지가 있다.
     */
    DisplayCurve,

    /**
     * **교정의 기준**이 되는 EMM-6 CAL.
     *
     * 틀리면 이 기준으로 만든 **모든 프로파일이 그만큼 틀어진 채로
     * 굳는다.** 그러고도 곡선은 멀쩡해 보인다. 그래서 여기서는 모르는
     * 것을 관례로 때우지 않는다.
     */
    ReferenceForCalibration,
}

/** 읽는 법을 정했는가, 사람에게 물어야 하는가. */
sealed interface ReadingDecision {
    val reading: CurveReading
    val whyKo: String

    /** 정해졌다. 그대로 걸어도 된다. */
    data class Settled(
        override val reading: CurveReading,
        override val whyKo: String,
    ) : ReadingDecision

    /**
     * **사람이 정해야 한다.** [reading] 은 제안일 뿐이고, 확인 전에는
     * 자동으로 걸지 않는다.
     */
    data class NeedsPerson(
        override val reading: CurveReading,
        override val whyKo: String,
    ) : ReadingDecision

    val settled: Boolean get() = this is Settled
}

/**
 * 증거와 용도로 읽는 법을 정한다(독립 검토 R04).
 *
 * ## 왜 용도에 따라 다른가
 *
 * 단서가 없는 파일이 대부분이다. 그것을 전부 막으면 아무 파일도 못
 * 쓰고, 전부 통과시키면 기준이 뒤집힌 채로 굳는다. 그래서 **걸린 것의
 * 크기로 가른다.**
 *
 * - 표시용 곡선은 관례(`.cal`·`.frd` = 응답)를 따르고 지나간다. 틀려도
 *   화면에서 드러날 여지가 있고, 되돌리기 쉽다.
 * - **교정의 기준**은 그렇지 않다. 그 파일로 만든 프로파일이 전부 같은
 *   방향으로 틀어지고, 나중에 봐도 알 수 없다. 그래서 모르면 묻는다.
 *
 * 머리글이 **우리 가정과 반대**를 가리키면 용도와 상관없이 묻는다 —
 * 그때는 「모르는」 것이 아니라 「어긋나는」 것이다.
 */
fun decideReading(
    evidence: SignEvidence,
    stakes: ReadingStakes,
    /**
     * 머리글의 **열 선언**(독립 재검토 CA-R05 · CAR-04).
     *
     * 설명문에서 낱말을 찾는 것만으로는 둘째 열이 무엇인지 알 수 없다 —
     * `# Reference SPL: 94 dB` 도, `# For frequency response measurements`
     * 도 「응답」으로 확정됐다. 앞의 것은 잰 세기이고 뒤의 것은 쓰임새다.
     *
     * 처음에는 「선언이 있는가」(Boolean)로 고쳤는데 그것도 모자랐다.
     * `Frequency,Phase,SPL` 은 선언이 맞지만 **둘째 열은 Phase** 이고,
     * 파서는 언제나 둘째 값을 읽는다 — SPL 이 아니라 위상을 보정에 쓴다.
     *
     * 그래서 **둘째 열의 이름 자체**를 받는다. 그 이름이 말해 주면 그것이
     * 가장 센 증거이고, 설명문과 어긋나면 사람에게 묻는다.
     *
     * 표시용 곡선은 예전대로다 — 틀려도 화면에서 드러나고 되돌리기 쉽다.
     */
    columns: ColumnDeclaration = ColumnDeclaration.None,
): ReadingDecision {
    // **둘째 열의 이름이 곧 답이다.** 설명문보다 세다 — 파서가 읽는
    // 바로 그 자리를 가리키기 때문이다.
    if (columns is ColumnDeclaration.Second) {
        val named = columns.reading
        val text = when (evidence) {
            SignEvidence.LooksLikeResponse -> CurveReading.Response
            SignEvidence.LooksLikeCorrection -> CurveReading.Correction
            SignEvidence.Unknown -> null
        }
        if (text != null && text != named && stakes == ReadingStakes.ReferenceForCalibration) {
            return ReadingDecision.NeedsPerson(
                named,
                "둘째 열은 「${named.labelKo}」 으로 선언됐는데 설명문은 " +
                    "「${text.labelKo}」 쪽으로 읽힙니다. 이 파일은 교정의 기준이 되므로 " +
                    "확인이 필요합니다 — 잘못 읽으면 보정이 반대로 걸립니다.",
            )
        }
        if (named == CurveReading.Correction) {
            return ReadingDecision.NeedsPerson(
                named,
                "둘째 열이 「보정값(correction)」으로 선언됐습니다. 앱의 기본 가정과 " +
                    "반대라 확인이 필요합니다 — 잘못 읽으면 보정이 반대로 두 배 걸립니다.",
            )
        }
        return ReadingDecision.Settled(named, "둘째 열이 「${named.labelKo}」 으로 선언돼 있습니다.")
    }

    // **선언이 있는데 둘째 열을 모르면 묻는다**(CAR-04). 다른 열의
    // 이름으로 채우지 않는다 — 파서는 둘째 값만 읽는다.
    if (columns is ColumnDeclaration.Unsupported &&
        stakes == ReadingStakes.ReferenceForCalibration
    ) {
        return ReadingDecision.NeedsPerson(
            CurveReading.Response,
            "둘째 열이 「${columns.secondKo}」 로 선언돼 있어 무엇인지 알 수 없습니다. " +
                "앱은 둘째 값을 마이크의 응답(dB)으로 읽습니다 — 이 파일이 그 꼴이 " +
                "맞는지 확인해 주십시오.",
        )
    }

    return decideFromProse(evidence, stakes)
}

private fun decideFromProse(
    evidence: SignEvidence,
    stakes: ReadingStakes,
): ReadingDecision = when (evidence) {
    SignEvidence.LooksLikeResponse -> if (
        stakes == ReadingStakes.ReferenceForCalibration
    ) {
        ReadingDecision.NeedsPerson(
            CurveReading.Response,
            // **별표를 쓰지 않는다.** 이 자리는 서식 없는 Text 라
            // 마크다운 강조가 글자 그대로 보인다(2026-09-22 실기기).
            "머리글이 「응답」 쪽으로 읽히지만, 둘째 열이 무엇인지 선언하지는 않았습니다. " +
                "이 파일은 교정의 기준이 되므로 확인이 필요합니다 — 잘못 읽으면 이 기준으로 " +
                "만든 프로파일이 전부 같은 방향으로 틀어집니다.",
        )
    } else {
        ReadingDecision.Settled(
            CurveReading.Response,
            "파일 머리글이 「응답」으로 읽힙니다.",
        )
    }

    SignEvidence.LooksLikeCorrection -> ReadingDecision.NeedsPerson(
        CurveReading.Correction,
        "파일 머리글이 「보정값」으로 읽힙니다. 앱의 기본 가정과 반대라 " +
            "확인이 필요합니다. 잘못 읽으면 보정이 반대로 두 배 걸립니다.",
    )

    SignEvidence.Unknown -> when (stakes) {
        ReadingStakes.DisplayCurve -> ReadingDecision.Settled(
            CurveReading.Response,
            "머리글에 단서가 없어 관례대로 「응답」으로 읽습니다(.cal·.frd).",
        )

        ReadingStakes.ReferenceForCalibration -> ReadingDecision.NeedsPerson(
            CurveReading.Response,
            "머리글에 단서가 없습니다. 이 파일은 교정의 기준이 되므로, " +
                "잘못 읽으면 이 기준으로 만든 프로파일이 전부 같은 방향으로 " +
                "틀어집니다. 제조사 설명을 보고 정해 주십시오.",
        )
    }
}

/**
 * 곡선의 **모양**에서 오는 단서(독립 검토 R04 보강).
 *
 * 머리글에 단서가 없는 파일이 대부분이라 [signEvidenceOf] 만으로는
 * 거의 언제나 「모름」이 된다. 그런데 값 자체가 말해 주는 것이 있다.
 *
 * 작은 측정용 캡슐은 대체로 **저역이 조금 모자라고 고역이 솟는다**
 * (다이어프램 앞의 압력 상승과 캡슐 공진 때문이다). 그 마이크의 **응답**을
 * 적은 파일은 그 모양 그대로이고, 이미 뒤집은 **보정값** 파일은 거울상
 * 이다 — 저역이 솟고 고역이 내려간다.
 *
 * **이것으로 정하지 않는다.** 마이크마다 다르고, 정규화 자리도 다르다.
 * 사람이 고를 때 곁에 놓는 단서일 뿐이다.
 */
data class CurveShape(
    val lowDb: Double,
    val midDb: Double,
    val highDb: Double,
) {
    /** 고역이 저역보다 높은가. 측정 캡슐 응답의 흔한 모양이다. */
    val risesToHigh: Boolean get() = highDb > lowDb

    /** 1kHz 언저리를 0 으로 맞춰 둔 파일인가. */
    val normalizedAtMid: Boolean get() = kotlin.math.abs(midDb) < 0.5
}

/** 곡선에서 저·중·고 세 점을 뽑는다. */
fun shapeOf(curve: CalibrationCurve): CurveShape = CurveShape(
    lowDb = curve.gainDbAt(curve.lowestHz.coerceAtLeast(20.0)),
    midDb = curve.gainDbAt(1_000.0),
    highDb = curve.gainDbAt(curve.highestHz.coerceAtMost(20_000.0)),
)

/**
 * 모양을 사람 말로. **판단하지 않고 보여만 준다.**
 *
 * 숫자를 먼저 적고, 그 모양이 흔히 무엇을 뜻하는지를 뒤에 덧붙인다 —
 * 순서를 바꾸면 앱이 정해 준 것처럼 읽힌다.
 */
fun describeShapeKo(shape: CurveShape): String {
    val head = "이 파일은 저역 %.1fdB · 1kHz %.1fdB · 고역 %.1fdB 입니다."
        .format(shape.lowDb, shape.midDb, shape.highDb)
    val tail = if (shape.risesToHigh) {
        "저역이 낮고 고역이 솟는 모양인데, 작은 측정용 캡슐의 응답이 대체로 " +
            "이렇습니다. 보정값 파일이라면 대개 그 거울상(저역이 솟고 고역이 " +
            "내려감)입니다."
    } else {
        "고역이 낮고 저역이 솟는 모양입니다. 측정용 캡슐의 응답은 대체로 그 " +
            "반대라, 이미 뒤집힌 보정값일 수 있습니다."
    }
    return "$head $tail 어느 쪽인지는 제조사 설명으로 확인하십시오."
}
