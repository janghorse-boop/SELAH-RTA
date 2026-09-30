package kr.joa.selahrta.recording

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.IOException
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
 * ## 글자는 그림으로 박히되, **글 정보도 함께 들어간다**
 *
 * 안드로이드의 `PdfDocument` 는 글자마다 **그리는 절차**를 파일에 박는다
 * (`Type3`·`CharProcs`). 덕분에 받는 쪽에 한글 글꼴이 없어도 똑같이 보이고
 * 인쇄도 그대로 된다. 한 장에 200KB 안팎이 되는 까닭도 이것이다.
 *
 * **여기서 한 번 틀렸다**(독립 검토 PND-06). `Type3` 이라는 이유만으로
 * 「복사·검색이 안 된다」고 적어 두었는데, **사실이 아니다.** 만들어진
 * 파일에는 글꼴마다 `ToUnicode` 지도가 함께 들어간다 — 실제로 84개가
 * 들어 있고 한글 121자가 매핑돼 있다(`ReportPdfTest` 가 본다).
 *
 * **그림만 보고 단정했다.** 글리프를 그림으로 그린다는 사실과 「글 정보가
 * 없다」는 전혀 다른 말인데 한 묶음으로 적었다.
 *
 * 다만 **읽개마다 고르고 찾는 품질은 다르다** — 줄바꿈과 읽는 차례가
 * 끊겨 나오므로 「어느 읽개에서나 매끄럽다」고 반대로 단정하지도 않는다.
 * 숫자를 **다시 셈에 쓰려는 사람**에게는 여전히 CSV 가 낫다.
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
     * **쓰기가 실패하면 던진다.** 당연해 보이지만 그냥 두면 그렇지 않다 —
     * 아래 [Guarded] 의 까닭을 보라.
     *
     * @return 몇 장이 되었나.
     * @throws IOException 한 바이트도 못 썼거나 쓰는 중에 끊겼을 때.
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
        // **쓰다 실패해도 닫는다**(독립 검토 2회차 잔여 권고).
        //
        // [PdfDocument] 는 네이티브 자원을 쥔다. `writeTo` 는 나눔 대상이
        // 먼저 끊기거나 저장 공간이 차면 던진다 — 그때 `close` 를 못
        // 지나가면 **자원이 그대로 남는다.** 여러 장을 잇달아 뽑는
        // 자리라 한 번의 실패가 쌓인다.
        try {
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
                            canvas.drawText(
                                item.textKo,
                                left + metrics.warningIndentPt,
                                y,
                                warning,
                            )

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
            val guarded = Guarded(out)
            doc.writeTo(guarded)
            guarded.failOnProblem()
        } finally {
            doc.close()
        }
        return pages.size
    }

    /**
     * 경고 줄들의 왼쪽에 굵은 막대를 세운다.
     *
     * **흑백으로 뽑아도 남는 표시다.** 색만으로 갈라 두면 인쇄된 종이에서
     * 경고가 본문과 똑같아진다.
     */
    /**
     * **`PdfDocument.writeTo` 는 쓰기 실패를 삼킨다.**
     *
     * 기기에서 재 보고 알았다. 모든 바이트에서 던지는 스트림을 주었는데
     * `write` 가 **1장을 썼다고 돌려주었다.** 한 바이트도 안 나갔는데
     * 「됐다」가 된다 — 나눔 대상이 먼저 끊기거나 저장 공간이 차면 실제로
     * 그렇게 된다. **저장 성공인데 자료가 없는** 자리이고, 이번 검토에서
     * 같은 성질의 결함(R2-01)을 하나 받은 참이다.
     *
     * 그래서 사이에 끼워 **첫 실패를 붙들었다가 뒤에 다시 던진다.**
     * 한 바이트도 안 나간 경우도 실패로 본다 — 빈 PDF 는 PDF 가 아니다.
     */
    private class Guarded(private val inner: OutputStream) : OutputStream() {
        private var failure: IOException? = null
        private var written = 0L

        private inline fun guard(bytes: Int, block: () -> Unit) {
            // 한 번 끊기면 그다음은 시도하지 않는다 — 첫 까닭을 지키려는 것이다.
            if (failure != null) return
            try {
                block()
                written += bytes
            } catch (e: IOException) {
                failure = e
            }
        }

        override fun write(b: Int) = guard(1) { inner.write(b) }

        override fun write(b: ByteArray, off: Int, len: Int) = guard(len) {
            inner.write(b, off, len)
        }

        override fun flush() = guard(0) { inner.flush() }

        fun failOnProblem() {
            failure?.let { throw it }
            if (written <= 0L) throw IOException("PDF 를 한 바이트도 쓰지 못했습니다.")
        }
    }

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
}
