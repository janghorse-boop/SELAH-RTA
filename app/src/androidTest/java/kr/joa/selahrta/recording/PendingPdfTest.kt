package kr.joa.selahrta.recording

import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import kr.joa.selahrta.domain.CalibrationState
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PendingPdfTest {
    @Test fun resultPagesMustRetainTheirWarning() {
        // Reuse the shipped PDF fixture, then exercise real report/layout/writer code.
        val fixture = ReportPdfTest()
        val factory = fixture.javaClass.getDeclaredMethod("meta", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java, CalibrationState::class.java,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val meta = factory.invoke(fixture, 12, 3, "가나다라마바사아자차카타파하 ".repeat(200),
            CalibrationState.Uncalibrated, true) as SessionMeta
        val paint = Paint().apply { textSize = 10f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL) }
        val wrap = ReportPdf.javaClass.getDeclaredMethod("wrap", Paint::class.java,
            String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val pages = ReportPageLayout.paginate(ReportPdf.TITLE_KO, reportWarnings(meta), buildReport(meta)) { text, width ->
            @Suppress("UNCHECKED_CAST")
            (wrap.invoke(ReportPdf, paint, text, width) as List<String>)
        }
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pending-unreviewed-long.pdf")
        file.outputStream().use { ReportPdf.write(meta, it) }
        val resultPages = pages.filter { page -> page.items.any { (it.item as? ReportItem.Row)?.labelKo == "Leq" } }
        println("PEND_PDF total=${pages.size} resultPages=${resultPages.map { it.number }} warningPages=${pages.filter { p -> p.items.any { it.item is ReportItem.Warning } }.map { it.number }}")
        assertTrue("A printed result page must retain the uncalibrated warning", resultPages.all { p ->
            p.items.any { (it.item as? ReportItem.Warning)?.textKo?.contains("미보정") == true }
        })
    }
}
