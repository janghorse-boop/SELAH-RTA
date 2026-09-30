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
        /**
         * 경고들. **전문과 요약을 짝으로 받는다.**
         *
         * 처음에는 목록 둘(`warningsKo`·`warningCoresKo`)로 받고 개수가
         * 같은지 검사했다. 그런데 **부르는 쪽이 하나를 안 넘겨도 조용히
         * 돌았다** — 변이로 확인해 보니 요약을 아예 안 넘기는 실수를
         * **아무 시험도 잡지 못했다.** 검사보다 **못 틀리게 만드는 편**이
         * 낫다(독립 검토 2회차 잔여 권고).
         */
        warnings: List<ReportWarning>,
        sections: List<ReportSection>,
        metrics: ReportMetrics = ReportMetrics(),
        wrap: (String, Int) -> List<String>,
    ): List<ReportPage> {
        val pages = mutableListOf<MutableList<PlacedItem>>()
        var current = mutableListOf<PlacedItem>()
        var y = metrics.marginPt
        val bottom = metrics.pageHeightPt - metrics.marginPt

        // **경고는 쪽마다 되풀이한다**(독립 검토 PND-05).
        //
        // 처음에는 맨 앞에 한 번만 두었다. 그런데 메모가 길어 숫자가 뒤쪽
        // 쪽으로 밀리면, **그 쪽만 인쇄하거나 전달했을 때 해석할 단서가
        // 통째로 떨어져 나간다** — 「미보정이라 참고값」이라는 말이 없는
        // 숫자 한 장이 된다. 종이는 한 장씩 돌아다닌다.
        //
        // **첫 쪽에는 전문, 그다음부터는 핵심 한 줄**(독립 검토 2회차
        // 잔여 권고).
        //
        // 예전에는 전문을 **모든 쪽에** 되풀이하고, 종이 절반을 넘기면
        // **앞쪽만 남기고 잘랐다.** 그러면 긴 경고의 뒷부분이 **어느
        // 쪽에도 안 남는다** — 되풀이하려다 정작 전문을 잃는 셈이다.
        //
        // 이제 전문은 **첫 쪽에 온전히** 남고(넘치면 다음 쪽으로 이어
        // 적는다), 그 뒤 쪽에는 **뜻을 줄이지 않은 한 줄**이 선다.
        //
        // **미리 잘라 둔다.** 쪽을 넘길 때마다 다시 줄바꿈하면 같은 일을
        // 쪽수만큼 되풀이한다.
        // **들여쓴 만큼 좁은 폭으로 끊는다.** 전체 폭으로 끊고 들여 쓰면
        // 그만큼 오른쪽으로 삐져나가 잘린다.
        val fullLines = warnings.flatMap { wrap(it.fullKo, metrics.warningWidthPt) }
        val coreLines = warnings.flatMap { wrap(it.coreKo, metrics.warningWidthPt) }
            // 되풀이하는 쪽은 **본문이 주인**이다. 요약이 종이 절반을
            // 넘기면 본문이 들어갈 자리가 없으므로 그때는 줄인다 —
            // **전문은 첫 쪽에 온전히 있으므로 잃는 것이 없다.**
            .let { lines ->
                val room = ((bottom - metrics.marginPt) / 2) / metrics.warningLineHeightPt
                if (lines.size <= room) lines else lines.take(room - 1) + "(경고 전문은 1쪽에)"
            }

        /** 되풀이용. 둘째 쪽부터 머리에 선다. */
        fun seedCores() {
            if (coreLines.isEmpty()) return
            coreLines.forEach { line ->
                y += metrics.warningLineHeightPt
                current += PlacedItem(ReportItem.Warning(line), y)
            }
            y += metrics.warningGapPt
        }

        fun newPage() {
            pages += current
            current = mutableListOf()
            y = metrics.marginPt
            seedCores()
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

        // **첫 쪽의 전문은 자르지 않는다.** 넘치면 쪽을 넘겨 이어 적는다 —
        // 그래야 전문이 **어딘가에는 온전히** 남는다. 이어진 쪽 머리에
        // 요약을 또 세우면 같은 말이 두 번이라 그동안은 세우지 않는다.
        fun newPageInPreamble() {
            pages += current
            current = mutableListOf()
            y = metrics.marginPt
        }
        fullLines.forEach { line ->
            if (y + metrics.warningLineHeightPt > bottom && current.isNotEmpty()) {
                newPageInPreamble()
            }
            y += metrics.warningLineHeightPt
            current += PlacedItem(ReportItem.Warning(line), y)
        }
        if (fullLines.isNotEmpty()) y += metrics.warningGapPt
        // **여기서 쪽을 넘기지 않는다.** 아래 `place` 가 묶음 제목과 첫
        // 줄이 함께 들어가는지 이미 본다 — 같은 검사를 하나 더 두었다가
        // **변이로 지워도 아무 시험이 안 잡히는** 죽은 줄이 되었다.

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
    /**
     * 경고를 왼쪽에서 얼마나 들여 쓰는가.
     *
     * **재는 폭에서도 빼야 한다**(독립 검토 2회차 잔여 권고). 들여만 쓰고
     * 전체 폭으로 줄을 끊으면 **끊긴 줄이 오른쪽 여백을 넘어 잘린다** —
     * 잘린 글자는 종이 밖이라 **그냥 사라진다.** 미보정 경고의 끝이
     * 사라지는 것이 바로 이 자리다.
     */
    val warningIndentPt: Int = 12,
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

    /** 경고가 쓸 수 있는 폭. **들여쓴 만큼 좁다.** */
    val warningWidthPt: Int get() = contentWidthPt - warningIndentPt
}
