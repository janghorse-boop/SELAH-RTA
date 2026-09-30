package kr.joa.selahrta.recording

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kr.joa.selahrta.calibration.CalibrationSource
import kr.joa.selahrta.domain.CalibrationState
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **정말로 열리는 PDF 인가, 그리고 한글이 그 안에 있는가.**
 *
 * 나누는 규칙은 `ReportPageLayoutTest` 가 기기 없이 본다. 여기서는
 * 기기가 있어야만 알 수 있는 것만 본다.
 *
 * ## 한글 글꼴을 파일 안에서 찾는 까닭
 *
 * PDF 는 글꼴을 **파일 안에 박을 수도, 이름만 적어 둘 수도** 있다.
 * 이름만 적힌 채 나가면 **보내는 쪽 화면에서는 멀쩡하고** 받는 쪽
 * 컴퓨터에 그 글꼴이 없을 때 한글이 네모로 뜬다. 우리 손을 떠난 뒤에
 * 깨지는 것이라 **이쪽에서 열어 보는 것으로는 절대 못 잡는다.**
 *
 * ## 처음에 엉뚱한 것을 찾았다
 *
 * `FontFile2`(TrueType 을 통째로 박는 방식)를 찾았고 **없다고 나왔다.**
 * 파일을 열어 보니 안드로이드는 다른 길을 쓴다 — **`Type3`**, 곧 글자
 * 하나하나를 **그리는 절차(`CharProcs`)로 박는다.** 글꼴이 없어도 받는
 * 쪽에서 똑같이 보인다는 점은 같다.
 *
 * **짐작으로 「안 박혔다」고 고칠 뻔했다.** 파일을 직접 연 것이 갈랐다.
 */
class ReportPdfTest {

    private val dir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    private fun meta(
        clipped: Int = 0,
        dropped: Int = 0,
        memo: String = "주일 2부",
        calibration: CalibrationState = CalibrationState.GlobalCalibrated,
        referenceOnly: Boolean = false,
    ) = SessionMeta(
        id = "pdf-1",
        startedAtEpochMs = 1_700_000_000_000L,
        endedAtEpochMs = 1_700_000_060_000L,
        durationMs = 60_000L,
        deviceKey = "BuiltIn|SM-S918N",
        deviceLabel = "SM-S918N",
        micKind = MicKind.BuiltIn,
        sampleRate = 48_000,
        encoding = "Float",
        channelCount = 1,
        channelIndex = 0,
        calibrationOffsetDb = 118.0,
        referenceOnly = referenceOnly,
        curveApplied = false,
        curveLabel = "",
        weighting = Weighting.A,
        timeWeight = TimeWeight.Fast,
        leqWindowMs = 10_000L,
        leqDb = 72.3,
        minDb = 55.0,
        maxDb = 88.0,
        peakDb = 96.0,
        memo = memo,
        events = emptyList(),
        droppedPackets = dropped,
        clippedRows = clipped,
        conditions = MeasurementConditions(
            calibrationState = calibration,
            calibrationSource = CalibrationSource.Calibrator,
        ),
    )

    private fun write(m: SessionMeta, name: String = "report.pdf"): Pair<File, Int> {
        val f = File(dir, name)
        val pages = f.outputStream().use { ReportPdf.write(m, it) }
        return f to pages
    }

    private fun pagesInFile(f: File): Int =
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }

    // ── 정말 PDF 인가 ───────────────────────────────────

    /**
     * **안드로이드의 PDF 읽개로 열어 본다.** 「파일이 생겼다」는 것만으로는
     * 열리는 파일인지 알 수 없다.
     */
    @Test
    fun 열리는_PDF_가_나온다() {
        val (f, pages) = write(meta())
        assertTrue("파일이 비었다", f.length() > 0)
        assertEquals("%PDF", f.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("셈한 쪽수와 파일의 쪽수가 같아야 한다", pages, pagesInFile(f))
    }

    /** 짧은 기록은 한 장이다. 종이를 괜히 늘리지 않는다. */
    @Test
    fun 짧은_기록은_한_장이다() {
        val (_, pages) = write(meta())
        assertEquals(1, pages)
    }

    // ── 한글이 파일 안에 있는가 ─────────────────────────

    /**
     * **글자가 파일 안에 박혀야 한다.**
     *
     * 안 박히면 받는 쪽에서 한글이 네모로 뜬다. 이쪽 화면에서는 멀쩡해
     * 보이므로 열어 보는 것으로는 못 잡는다.
     *
     * 안드로이드는 글자마다 **그리는 절차**를 박는다(`Type3`·`CharProcs`).
     * 그래서 받는 쪽에 한글 글꼴이 없어도 똑같이 보인다.
     *
     * **「그러니 복사·검색은 안 된다」고 적었던 것은 틀렸다**(독립 검토
     * PND-06). 같은 파일에 `ToUnicode` 지도가 함께 들어간다 — 아래
     * [한글이_글로도_읽히게_박힌다] 가 그것을 본다. 그림으로 그린다는
     * 사실과 「글 정보가 없다」는 **전혀 다른 말**인데 한 묶음으로 적었다.
     */
    @Test
    fun 한글이_그림으로_파일_안에_박힌다() {
        val (f, _) = write(meta())
        val bytes = f.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(
            "글자를 그리는 절차가 파일에 없다 — 받는 쪽에서 한글이 네모로 뜬다",
            bytes.contains("/Type3") && bytes.contains("CharProcs"),
        )
    }

    /**
     * **글자를 되읽을 수 있는 지도가 함께 들어간다**(독립 검토 PND-06).
     *
     * `ToUnicode` 가 없으면 받는 쪽에서 **보이기는 해도 고르거나 찾을 수
     * 없다.** 있으면 된다 — 그 사실을 짐작으로 적지 않고 **파일에서 직접
     * 꺼내 본다.**
     *
     * 지도는 눌려 있으므로(Flate) 풀어서 읽고, 매핑된 글자에 **한글이
     * 있는지**까지 본다. 지도만 있고 한글이 안 들어 있으면 소용이 없다.
     *
     * **읽개마다 고르고 찾는 품질이 같다는 뜻은 아니다** — 줄바꿈과 읽는
     * 차례는 끊겨 나온다. 여기서 보는 것은 「글 정보가 들어 있는가」뿐이다.
     */
    @Test
    fun 한글이_글로도_읽히게_박힌다() {
        val (f, _) = write(meta(), "unicode.pdf")
        val bytes = f.readBytes()
        val maps = inflatedStreams(bytes).filter {
            it.contains("beginbfchar") || it.contains("beginbfrange")
        }
        assertTrue("ToUnicode 지도가 하나도 없다", maps.isNotEmpty())

        val hangul = Regex("<[0-9A-Fa-f]{2,4}>\\s*<([0-9A-Fa-f]{4,})>")
            .findAll(maps.joinToString("\n"))
            .flatMap { m ->
                m.groupValues[1].chunked(4).mapNotNull { it.toIntOrNull(16)?.toChar() }
            }
            .filter { it in '가'..'힣' }
            .toSet()
        assertTrue("지도에 한글이 없다(${maps.size}개 지도)", hangul.size >= 20)
    }

    /** 눌린 스트림을 풀어서 글로 돌려준다. 못 푸는 것은 건너뛴다. */
    private fun inflatedStreams(bytes: ByteArray): List<String> {
        val text = bytes.toString(Charsets.ISO_8859_1)
        val out = ArrayList<String>()
        var i = text.indexOf("stream")
        while (i >= 0) {
            var s = i + "stream".length
            if (s < text.length && text[s] == '\r') s++
            if (s < text.length && text[s] == '\n') s++
            val e = text.indexOf("endstream", s)
            if (e < 0) break
            runCatching {
                val raw = bytes.copyOfRange(s, e)
                val inf = java.util.zip.Inflater()
                inf.setInput(raw)
                val buf = ByteArray(1 shl 16)
                val sb = StringBuilder()
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0) break
                    sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
                }
                inf.end()
                out += sb.toString()
            }
            // **"endstream" 안의 "stream" 을 다시 잡으면 어긋난 데를 읽어
            // 그 뒤가 통째로 어긋난다. 끝표 뒤로 넘긴다.
            i = text.indexOf("stream", e + "endstream".length)
        }
        return out
    }

    // ── 경고가 종이에 남는가 ────────────────────────────

    /**
     * **경고가 있으면 종이가 그만큼 길어진다.**
     *
     * PDF 안의 글자는 글꼴의 **글리프 번호**로 적히므로 「미보정」이라는
     * 글자를 파일에서 찾을 수는 없다. 대신 **경고가 들어간 쪽이 더 길다**는
     * 것으로 본다. 어떤 말이 어디에 오는지는 `ReportPageLayoutTest` 가
     * 직접 본다.
     */
    @Test
    fun 경고가_붙으면_내용이_늘어난다() {
        val 깨끗 = write(meta(), "clean.pdf").first.length()
        val 경고 = write(
            meta(clipped = 12, dropped = 3, referenceOnly = true, calibration = CalibrationState.Uncalibrated),
            "warned.pdf",
        ).first.length()
        assertTrue("경고가 붙었는데 길이가 그대로다($깨끗 → $경고)", 경고 > 깨끗)
    }

    // ── 긴 내용 ─────────────────────────────────────────

    /**
     * **긴 메모가 종이 밖으로 나가지 않는다.**
     *
     * 폭을 넘긴 글자는 잘리는 것이 아니라 **그냥 안 보인다** — 파일은
     * 멀쩡하고 내용만 사라진다. 줄바꿈이 도는지 쪽수로 본다.
     */
    @Test
    fun 긴_메모는_여러_줄이_되어_쪽을_늘린다() {
        val short = write(meta(memo = "짧은 메모"), "short.pdf").second
        val long = write(meta(memo = "가나다라마바사아자차카타파하 ".repeat(200)), "long.pdf")
        assertTrue("긴 메모가 쪽을 늘려야 한다($short → ${long.second})", long.second > short)
        assertEquals(long.second, pagesInFile(long.first))
    }

    /**
     * **빈 종이가 나오지 않는다.**
     *
     * 그리는 자리를 잘못 잡으면 — 이를테면 y 를 종이 밖으로 보내면 —
     * 파일은 멀쩡하고 **쪽수도 맞는데 아무것도 안 적힌 종이**가 나온다.
     * 열어 보기 전에는 모른다. 그래서 실제로 그려 **먹이 묻었는지** 본다.
     *
     * 그린 그림은 `report-page1.png` 로 남긴다 — 사람이 눈으로 볼 때 쓴다.
     */
    @Test
    fun 그려_보면_빈_종이가_아니다() {
        val (f, _) = write(meta(), "look.pdf")
        val png = File(dir, "report-page1.png")
        val inked = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bmp = android.graphics.Bitmap.createBitmap(
                        page.width * 2,
                        page.height * 2,
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    png.outputStream().use {
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    val pixels = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    pixels.count { android.graphics.Color.red(it) < 128 }
                }
            }
        }
        assertTrue("종이에 아무것도 안 적혔다", inked > 500)
    }

    /** 파일 이름은 CSV·소리와 **같은 줄기**를 쓴다. */
    @Test
    fun 파일_이름이_CSV_와_같은_줄기다() {
        val m = meta()
        assertEquals(
            SessionExport.fileName(m).removeSuffix(".csv"),
            ReportPdf.fileName(m).removeSuffix(".pdf"),
        )
    }

    /**
     * **쓰다 실패해도 다음 장을 뽑을 수 있다**(독립 검토 2회차 잔여 권고).
     *
     * [android.graphics.pdf.PdfDocument] 는 네이티브 자원을 쥔다. `writeTo`
     * 가 던질 때 `close` 를 못 지나가면 그 자원이 남는다.
     *
     * ## 여기서 진짜 결함이 나왔다
     *
     * 처음 돌렸을 때 **던지지 않았다.** `PdfDocument.writeTo` 가 스트림의
     * `IOException` 을 **삼키고**, `write` 는 **1장을 썼다고 돌려주었다** —
     * 한 바이트도 안 나갔는데 「됐다」가 된다. 나눔 대상이 먼저 끊기거나
     * 저장 공간이 차면 실제로 그렇게 된다.
     *
     * ## 이 시험이 보는 것과 못 보는 것
     *
     * **본다**: 쓰기 실패가 **밖으로 나온다**는 것.
     *
     * **못 본다**: 네이티브 자원이 실제로 놓였는지. 자바 쪽에서 셀 길이
     * 없다. 잇달아 뽑아도 죽지 않는다는 것만 본다 — **닫는 코드가 있다는
     * 증거이지 자원이 놓였다는 증거가 아니다.**
     */
    @Test
    fun 쓰다_실패해도_다음_장을_뽑을_수_있다() {
        val m = meta()
        repeat(5) {
            val boom = object : java.io.OutputStream() {
                override fun write(b: Int) = throw java.io.IOException("일부러 끊는다")
            }
            try {
                ReportPdf.write(m, boom)
                org.junit.Assert.fail("던졌어야 한다 (회차 $it)")
            } catch (e: java.io.IOException) {
                assertEquals("일부러 끊는다", e.message)
            }
        }
        // 다섯 번 실패한 뒤에도 정상으로 한 장 뽑힌다.
        val out = java.io.ByteArrayOutputStream()
        val pages = ReportPdf.write(m, out)
        assertTrue("실패 뒤에 못 뽑는다", pages >= 1)
        assertTrue(out.size() > 0)
    }
}
