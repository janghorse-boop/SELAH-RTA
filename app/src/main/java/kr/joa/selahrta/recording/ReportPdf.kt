package kr.joa.selahrta.recording

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/**
 * **기록 하나를 종이 한 장짜리 리포트로 내보낸다**(Phase 11 — PDF).
 *
 * ## 왜 CSV 말고 이것이 필요한가
 *
 * CSV 는 **표 계산기로 여는 것**이다. 목사님이나 장로님께 「지난주 예배는
 * 이랬습니다」를 건넬 때 쓰는 물건이 아니다. 그 자리에 필요한 것은
 * **한눈에 읽히고 그대로 인쇄되는 한 장**이다.
 *
 * ## 화면과 **같은 문장**을 쓴다
 *
 * 글은 [buildReport] 와 [reportWarningsKo] 에서 그대로 온다. 여기서 새로
 * 짓지 않는다 — 자리마다 따로 적으면 같은 기록이 자리마다 다른 말을 한다.
 *
 * ## 종이에서는 경고가 더 중요하다
 *
 * 화면에서는 경고 띠가 눈에 들어오고, 못 보고 넘겨도 다시 열면 또 보인다.
 * **종이는 아니다.** 한 번 손을 떠나면 그 장이 혼자 돌아다니고, 받는 사람은
 * 「미보정으로 쟀다」는 말을 들은 적이 없다. 숫자만 남고 단서가 사라지면
 * **참고값이 측정값으로 읽힌다** — 이 저장소가 거듭 경계해 온 바로 그 일이다.
 *
 * 그래서 경고를 **제목 바로 아래, 숫자보다 먼저** 놓고 왼쪽에 굵은 막대를
 * 세운다(색이 빠지는 흑백 인쇄에서도 남는다). 자리는
 * [ReportPageLayout] 이 강제한다.
 *
 * ## 나누는 일과 그리는 일을 갈라 두었다
 *
 * 어디에 무엇이 오는지는 [ReportPageLayout] 이 정한다 — 안드로이드를 모르는
 * 순수 코드라 기기 없이 시험한다. 여기서는 **받아 적기만** 한다.
 *
 * ## 글자는 그림으로 박힌다 — 복사·검색이 안 된다
 *
 * 안드로이드의 `PdfDocument` 는 글자마다 **그리는 절차**를 파일에 박는다
 * (`Type3`·`CharProcs`). 덕분에 받는 쪽에 한글 글꼴이 없어도 똑같이
 * 보이고 인쇄도 그대로 된다 — 대신 **그 글자를 끌어 복사하거나 찾기로
 * 검색할 수는 없다.** 한 장에 200KB 안팎이 되는 까닭도 이것이다.
 *
 * 숫자를 **다시 셈에 쓰려는 사람**에게는 CSV 가 있다. 이 파일은
 * **읽고 인쇄하라고** 만든 것이다. 바꾸려면 글꼴을 직접 박는 PDF 쓰개를
 * 따로 들여야 하는데, 그만한 값을 하는 일인지 아직 모른다.
 */
object ReportPdf {

    /** 문서 제목. 파일을 열었을 때 맨 위에 오는 말. */
    const val TITLE_KO = "예배 측정 기록"

    /** 내보낼 파일 이름. CSV·소리와 **같은 줄기**를 쓴다. */
    fun fileName(meta: SessionMeta): String =
        "${SessionExport.stem(meta.startedAtEpochMs)}.pdf"

    /**
     * 써 넣는다.
     *
     * @return 몇 장이 되었나.
     */
    fun write(
        meta: SessionMeta,
        out: OutputStream,
        metrics: ReportMetrics = ReportMetrics(),
    ): Int {
        val body = paint(metrics.bodySizePt, bold = false, color = INK)
        val label = paint(metrics.bodySizePt, bold = true, color = INK)
        val heading = paint(metrics.headingSizePt, bold = true, color = INK)
        val sectionTitle = paint(metrics.sectionSizePt, bold = true, color = ACCENT)
        val warning = paint(metrics.bodySizePt, bold = false, color = WARN_INK)
        val footer = paint(metrics.footerSizePt, bold = false, color = FAINT)
        val bar = Paint().apply { color = WARN_BAR; isAntiAlias = true }
        val rule = Paint().apply { color = FAINT; strokeWidth = 0.5f; isAntiAlias = true }

        val pages = ReportPageLayout.paginate(
            titleKo = TITLE_KO,
            warningsKo = reportWarningsKo(meta),
            sections = buildReport(meta),
            metrics = metrics,
            // **재는 붓과 그리는 붓이 같아야 한다.** 다른 굵기로 재면
            // 줄이 폭을 넘고, 넘은 만큼은 종이 밖이라 **그냥 사라진다.**
            wrap = { text, widthPt -> wrap(body, text, widthPt) },
        )

        val doc = PdfDocument()
        pages.forEach { page ->
            val info = PdfDocument.PageInfo
                .Builder(metrics.pageWidthPt, metrics.pageHeightPt, page.number)
                .create()
            val pdfPage = doc.startPage(info)
            val canvas = pdfPage.canvas
            val left = metrics.marginPt.toFloat()

            drawWarningBar(canvas, page, metrics, bar)

            page.items.forEach { placed ->
                val y = placed.yPt.toFloat()
                when (val item = placed.item) {
                    is ReportItem.Heading -> {
                        canvas.drawText(item.textKo, left, y, heading)
                        canvas.drawLine(
                            left, y + 6f,
                            (metrics.pageWidthPt - metrics.marginPt).toFloat(), y + 6f,
                            rule,
                        )
                    }

                    is ReportItem.Warning ->
                        canvas.drawText(item.textKo, left + WARN_INDENT, y, warning)

                    is ReportItem.SectionTitle ->
                        canvas.drawText(item.textKo, left, y, sectionTitle)

                    is ReportItem.Row -> {
                        if (item.labelKo.isNotEmpty()) {
                            canvas.drawText(item.labelKo, left, y, label)
                        }
                        canvas.drawText(
                            item.valueKo,
                            left + metrics.labelWidthPt,
                            y,
                            body,
                        )
                    }
                }
            }

            canvas.drawText(
                "${page.number} / ${page.total}",
                left,
                (metrics.pageHeightPt - metrics.marginPt / 2).toFloat(),
                footer,
            )
            doc.finishPage(pdfPage)
        }
        doc.writeTo(out)
        doc.close()
        return pages.size
    }

    /**
     * 경고 줄들의 왼쪽에 굵은 막대를 세운다.
     *
     * **흑백으로 뽑아도 남는 표시다.** 색만으로 갈라 두면 인쇄된 종이에서
     * 경고가 본문과 똑같아진다.
     */
    private fun drawWarningBar(
        canvas: android.graphics.Canvas,
        page: ReportPage,
        metrics: ReportMetrics,
        bar: Paint,
    ) {
        val warnings = page.items.filter { it.item is ReportItem.Warning }
        if (warnings.isEmpty()) return
        val top = warnings.first().yPt - metrics.bodySizePt
        val bottom = warnings.last().yPt + 3
        val x = metrics.marginPt.toFloat()
        canvas.drawRect(x, top.toFloat(), x + WARN_BAR_WIDTH, bottom.toFloat(), bar)
    }

    /**
     * 글꼴이 아는 폭으로 줄을 나눈다.
     *
     * **한 글자도 못 넣는 폭이 오면 그래도 한 글자는 넣는다** — 안 그러면
     * 영영 줄지 않아 제자리를 돈다.
     */
    private fun wrap(paint: Paint, text: String, widthPt: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val width = widthPt.toFloat()
        val out = ArrayList<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            val fits = paint.breakText(rest, true, width, null).coerceAtLeast(1)
            out += rest.substring(0, fits)
            rest = rest.substring(fits)
        }
        return out
    }

    private fun paint(sizePt: Int, bold: Boolean, color: Int) = Paint().apply {
        isAntiAlias = true
        textSize = sizePt.toFloat()
        this.color = color
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private val INK = Color.rgb(24, 24, 27)
    private val ACCENT = Color.rgb(29, 78, 137)
    private val WARN_INK = Color.rgb(124, 45, 18)
    private val WARN_BAR = Color.rgb(194, 65, 12)
    private val FAINT = Color.rgb(130, 130, 135)
    private const val WARN_BAR_WIDTH = 3f
    private const val WARN_INDENT = 12f
}
