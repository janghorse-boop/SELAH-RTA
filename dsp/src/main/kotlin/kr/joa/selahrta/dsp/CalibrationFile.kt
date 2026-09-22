package kr.joa.selahrta.dsp

/**
 * 보정 파일 파서(.cal / .frd / .txt).
 *
 * 형식이 표준화돼 있지 않다. 실제로 돌아다니는 파일들은 이렇다:
 *
 *     * Dayton Audio iMM-6C          ← 주석(별표)
 *     "Frequency","SPL","Phase"      ← 머리글(따옴표·쉼표)
 *     20.000  -1.23   0.0            ← 공백 구분, 위상 열 있음
 *     20,0.5                         ← 쉼표 구분, 두 열
 *     # comment / ; comment          ← 다른 주석 기호
 *
 * 그래서 **관대하게 읽되, 무엇을 버렸는지 알린다.** 조용히 절반만 읽으면
 * 곡선이 엉뚱해지고 담당자는 이유를 알 수 없다.
 */
object CalibrationFile {

    /** 한 파일을 읽은 결과. */
    data class ParseResult(
        val points: List<CurvePoint>,
        /**
         * 숫자로 읽히지 않아 건너뛴 줄 수. **주석(`*` `#` `;` `//`)은 빼고 센다.**
         *
         * 주석은 건너뛰라고 있는 것이라 세면 수만 부풀고, 「파일의 절반을
         * 조용히 버렸는가」라는 이 값의 물음이 흐려진다.
         *
         * 예전 주석에는 「주석·머리글 포함」이라고 적혀 있었는데 **코드는
         * 그러지 않았다.** 이 값을 쓰는 곳이 아직 없어 드러나지 않았을
         * 뿐이다(2026-09-22 표본 파일 시험에서 발견). 코드가 맞고 주석이
         * 틀렸다.
         */
        val skippedLines: Int,
        /** 건너뛴 줄의 예. 무엇 때문에 실패했는지 보여 준다. */
        val skippedSamples: List<String>,
        /**
         * 숫자가 아닌 줄 중 **앞에 있는 몇 줄**. 열 이름이 여기 적혀 있다.
         *
         * 보정 부호를 가를 유일한 단서일 때가 있어 버리지 않고 들고 온다
         * ([signEvidenceOf]). 버리면 「응답인가 보정값인가」를 물을 근거가
         * 아무 데도 안 남는다.
         */
        val headerLines: List<String> = emptyList(),
    )

    /** 머리글로 볼 줄 수의 상한. 이보다 길면 열 이름이 아니라 설명문이다. */
    private const val MAX_HEADER_LINES = 8

    private val COMMENT_PREFIXES = listOf("*", "#", ";", "//")

    /** 구분자: 공백·탭·쉼표·세미콜론 무엇이든 받는다. */
    private val SEPARATOR = Regex("[\\s,;]+")

    fun parse(text: String): ParseResult {
        val points = mutableListOf<CurvePoint>()
        var skipped = 0
        val samples = mutableListOf<String>()
        // 머리글은 앞에만 있다. 숫자 줄이 한 번이라도 나오면 더 모으지 않는다 —
        // 파일 끝의 주석까지 끌어모으면 어느 것이 열 이름인지 흐려진다.
        val headers = mutableListOf<String>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim().removeSurrounding("﻿")
            if (line.isEmpty()) continue
            if (COMMENT_PREFIXES.any { line.startsWith(it) }) {
                if (points.isEmpty() && headers.size < MAX_HEADER_LINES) headers += line
                continue
            }

            val parts = line
                .replace("\"", "")
                .split(SEPARATOR)
                .filter { it.isNotBlank() }

            if (parts.size < 2) {
                if (points.isEmpty() && headers.size < MAX_HEADER_LINES) headers += line
                skipped++
                if (samples.size < 3) samples += line
                continue
            }

            // 셋째 열(위상)은 무시한다. 우리가 고치는 것은 크기뿐이다 —
            // 위상 보정은 FIR 필터가 필요한 별개 작업이고, 음압·RTA 값에는
            // 영향이 없다.
            val hz = parts[0].toDoubleOrNull()
            val db = parts[1].toDoubleOrNull()
            if (hz == null || db == null || hz <= 0.0) {
                if (points.isEmpty() && headers.size < MAX_HEADER_LINES) headers += line
                skipped++
                if (samples.size < 3) samples += line
                continue
            }
            points += CurvePoint(hz, db)
        }

        return ParseResult(points, skipped, samples, headers)
    }

    /**
     * 파일을 읽어 곡선을 만든다. 실패하면 **왜** 실패했는지 말한다.
     *
     * 「파일을 읽을 수 없습니다」만 띄우면 담당자는 파일을 바꿔 볼 수도 없다.
     */
    fun load(text: String): Result<Loaded> {
        val parsed = parse(text)
        if (parsed.points.isEmpty()) {
            return Result.failure(
                IllegalArgumentException(
                    "숫자로 된 줄을 찾지 못했습니다. " +
                        "「주파수 값」 형식의 파일인지 확인해 주십시오." +
                        parsed.skippedSamples.firstOrNull()?.let { "\n예: $it" }.orEmpty(),
                ),
            )
        }
        val curve = CalibrationCurve.of(parsed.points).getOrElse {
            return Result.failure(it)
        }
        return Result.success(
            Loaded(
                curve = curve,
                pointCount = parsed.points.size,
                skippedLines = parsed.skippedLines,
                headerLines = parsed.headerLines,
            ),
        )
    }

    data class Loaded(
        val curve: CalibrationCurve,
        val pointCount: Int,
        val skippedLines: Int,
        /** 파일 앞의 머리글. 보정 부호를 가를 단서다. */
        val headerLines: List<String> = emptyList(),
    ) {
        /**
         * 파일이 수상한지 알린다. 거부하지는 않는다 —
         * 특이한 마이크가 있을 수 있고, 판단은 사람이 한다.
         */
        /** 부호 규약에 대한 단서. 정하지 않고 보이기만 한다. */
        val signEvidence: SignEvidence get() = signEvidenceOf(headerLines)

        val warningKo: String?
            get() = when {
                // **부호가 먼저다.** 아래 ±30dB 검사로는 못 잡는다 —
                // 실제 측정 마이크 파일은 대개 ±5dB 안쪽이라 부호가
                // 반대여도 조용히 통과한다.
                signNoticeKo(signEvidence) != null -> signNoticeKo(signEvidence)
                curve.maxAbsGainDb > 30.0 ->
                    "보정량이 최대 ${"%.1f".format(curve.maxAbsGainDb)}dB 입니다. " +
                        "보통 마이크 보정 파일은 ±10dB 안쪽입니다 — " +
                        "부호 규약이 반대이거나 다른 종류의 파일일 수 있습니다."
                curve.lowestHz > 100.0 ->
                    "파일이 ${curve.lowestHz.toInt()}Hz 부터 시작합니다. " +
                        "그 아래 대역은 끝점 값을 늘여 씁니다."
                curve.highestHz < 10_000.0 ->
                    "파일이 ${(curve.highestHz / 1000).toInt()}kHz 까지만 있습니다. " +
                        "그 위 대역은 끝점 값을 늘여 씁니다."
                else -> null
            }
    }
}
