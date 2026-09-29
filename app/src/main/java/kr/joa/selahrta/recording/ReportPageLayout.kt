package kr.joa.selahrta.recording

/**
 * **리포트를 종이 여러 장에 나누어 앉힌다**(Phase 11 — PDF).
 *
 * ## 왜 안드로이드를 모르는 자리에 두나
 *
 * 그리는 일(`Canvas`·`PdfDocument`)은 기기 몫이다. 그러나 **어디에 무엇이
 * 오는가**는 기기와 상관이 없다 — 경고가 앞인지, 묶음 제목이 쪽 끝에
 * 홀로 남지는 않는지, 쪽이 몇 장인지. 그 규칙을 안드로이드 안에 묻어 두면
 * **기기를 꽂아야만 확인할 수 있는 규칙**이 된다.
 *
 * 그래서 떼어 둔다. `dsp` 를 떼어 둔 것과 같은 까닭이다.
 *
 * ## 글자 폭만 밖에서 받는다
 *
 * 한 줄에 몇 글자가 들어가는지는 글꼴이 안다 — 그것만 [paginate] 의
 * `wrap` 으로 받는다. 시험은 「몇 글자마다 끊는다」는 가짜 자를 넣고,
 * 실제로는 `Paint` 가 잰다.
 */
object ReportPageLayout {

    /**
     * 종이에 앉힌다.
     *
     * @param wrap `(글, 쓸 수 있는 폭pt) -> 줄들`. 폭을 넘으면 나눈다.
     */
    fun paginate(
        titleKo: String,
        warningsKo: List<String>,
        sections: List<ReportSection>,
        metrics: ReportMetrics = ReportMetrics(),
        wrap: (String, Int) -> List<String>,
    ): List<ReportPage> {
        val pages = mutableListOf<MutableList<PlacedItem>>()
        var current = mutableListOf<PlacedItem>()
        var y = metrics.marginPt
        val bottom = metrics.pageHeightPt - metrics.marginPt

        fun newPage() {
            pages += current
            current = mutableListOf()
            y = metrics.marginPt
        }

        /**
         * 하나를 앉힌다. 자리가 모자라면 쪽을 넘긴다.
         *
         * [keepWithNext] 는 **다음 것까지 들어갈 자리가 있어야** 앉힌다 —
         * 묶음 제목이 쪽 끝에 홀로 남는 것을 막는다.
         */
        fun place(item: ReportItem, height: Int, keepWithNext: Int = 0) {
            if (y + height + keepWithNext > bottom && current.isNotEmpty()) newPage()
            y += height
            current += PlacedItem(item, y)
        }

        place(ReportItem.Heading(titleKo), metrics.headingHeightPt)
        y += metrics.headingGapPt

        if (warningsKo.isNotEmpty()) {
            warningsKo.forEach { warning ->
                wrap(warning, metrics.contentWidthPt).forEach { line ->
                    place(ReportItem.Warning(line), metrics.warningLineHeightPt)
                }
            }
            y += metrics.warningGapPt
        }

        sections.forEach { section ->
            // 제목과 **첫 줄**이 같은 쪽에 함께 들어가야 앉힌다.
            place(
                ReportItem.SectionTitle(section.titleKo),
                metrics.sectionTitleHeightPt,
                keepWithNext = metrics.rowHeightPt,
            )
            section.lines.forEach { line ->
                val valueLines = wrap(line.valueKo, metrics.valueWidthPt)
                valueLines.forEachIndexed { i, text ->
                    // **이어지는 줄에는 이름을 다시 적지 않는다.** 같은
                    // 이름이 두 번 보이면 값이 둘인 줄 안다.
                    place(
                        ReportItem.Row(if (i == 0) line.labelKo else "", text),
                        metrics.rowHeightPt,
                    )
                }
            }
            y += metrics.sectionGapPt
        }

        pages += current
        val total = pages.size
        return pages.mapIndexed { i, items -> ReportPage(items, i + 1, total) }
    }
}

/** 종이 한 장. */
data class ReportPage(
    val items: List<PlacedItem>,
    /** 1부터. */
    val number: Int,
    /** 모두 몇 장인가. 「3쪽 중 2쪽」을 적으려면 있어야 한다. */
    val total: Int,
)

/** 어디에 앉았는가. [yPt] 는 글자의 **밑선**이다(`Canvas.drawText` 와 같다). */
data class PlacedItem(val item: ReportItem, val yPt: Int)

/** 종이에 그릴 것 하나. */
sealed interface ReportItem {
    /** 문서 제목. **첫 쪽에만** 온다. */
    data class Heading(val textKo: String) : ReportItem

    /** 믿을 만한가에 대한 경고. **이미 한 줄로 잘려 있다.** */
    data class Warning(val textKo: String) : ReportItem

    data class SectionTitle(val textKo: String) : ReportItem

    /** 이름과 값 한 줄. 이어지는 줄이면 [labelKo] 가 빈 글이다. */
    data class Row(val labelKo: String, val valueKo: String) : ReportItem
}

/**
 * 종이와 글의 치수. 단위는 **pt**(1/72인치) — PDF 가 쓰는 단위다.
 *
 * 기본값은 **A4**(595×842pt)다. 교회에서 뽑는 종이가 A4 다.
 */
data class ReportMetrics(
    val pageWidthPt: Int = 595,
    val pageHeightPt: Int = 842,
    val marginPt: Int = 48,
    val headingHeightPt: Int = 24,
    val headingGapPt: Int = 10,
    val warningLineHeightPt: Int = 15,
    val warningGapPt: Int = 10,
    val sectionTitleHeightPt: Int = 20,
    val sectionGapPt: Int = 10,
    val rowHeightPt: Int = 16,
    /** 이름 칸의 폭. 값은 그 오른쪽에 적는다. */
    val labelWidthPt: Int = 140,
    // ── 글자 크기 ──────────────────────────────────────
    //
    // **줄 높이보다 작게 둔다.** 글자가 줄 높이만 해지면 윗줄의 아랫단과
    // 아랫줄의 윗단이 맞붙어 읽기 어려워진다.
    val headingSizePt: Int = 17,
    val sectionSizePt: Int = 12,
    val bodySizePt: Int = 10,
    val footerSizePt: Int = 8,
) {
    /** 좌우 여백을 뺀 글 폭. */
    val contentWidthPt: Int get() = pageWidthPt - marginPt * 2

    /** 값이 쓸 수 있는 폭. */
    val valueWidthPt: Int get() = contentWidthPt - labelWidthPt
}
