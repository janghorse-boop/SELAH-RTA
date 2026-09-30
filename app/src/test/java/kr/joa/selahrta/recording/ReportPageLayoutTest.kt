package kr.joa.selahrta.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **리포트를 종이에 나누어 앉히는 규칙**(Phase 11 — PDF).
 *
 * 그리는 일은 안드로이드가 하지만 **어디에 무엇이 오는가**는 안드로이드를
 * 모른다. 그래서 떼어 두고 여기서 기기 없이 고정한다 — `dsp` 를 떼어 둔
 * 것과 같은 까닭이다.
 *
 * 글자 폭을 재는 일만 기기 몫이라 **줄바꿈 함수를 밖에서 받는다.** 시험은
 * 「몇 글자마다 끊는다」는 가짜 자를 넣는다.
 */
class ReportPageLayoutTest {

    /** 한 줄에 [perLine] 글자씩 끊는 가짜 자. */
    private fun ruler(perLine: Int): (String, Int) -> List<String> = { text, _ ->
        if (text.isEmpty()) listOf("") else text.chunked(perLine)
    }

    private val sections = listOf(
        ReportSection("언제 쟀나", listOf(ReportLine("시작", "2026-09-29"))),
        ReportSection("무엇으로 쟀나", listOf(ReportLine("마이크", "내장"))),
    )

    private fun layout(
        warnings: List<String> = emptyList(),
        sections: List<ReportSection> = this.sections,
        metrics: ReportMetrics = ReportMetrics(),
        perLine: Int = 200,
    ) = ReportPageLayout.paginate("예배 측정 기록", warnings, sections, metrics, ruler(perLine))

    // ── 경고가 먼저다 ───────────────────────────────────

    /**
     * **경고는 숫자보다 앞에 온다.**
     *
     * 화면에서는 경고 띠가 눈에 들어오지만 **종이에서는 아니다** — 넘겨
     * 보다가 숫자만 읽고 접는다. 그래서 자리로 강제한다.
     */
    @Test
    fun `경고가 첫 쪽 맨 위에 온다`() {
        val pages = layout(warnings = listOf("미보정으로 쟀습니다."))
        val kinds = pages.first().items.map { it.item }
        assertTrue("제목이 먼저", kinds.first() is ReportItem.Heading)
        assertTrue("그다음이 경고", kinds[1] is ReportItem.Warning)
        assertTrue(
            "경고가 섹션보다 앞",
            kinds.indexOfFirst { it is ReportItem.Warning } <
                kinds.indexOfFirst { it is ReportItem.SectionTitle },
        )
    }

    /** 경고가 없으면 자리를 차지하지 않는다. */
    @Test
    fun `경고가 없으면 빈자리를 안 만든다`() {
        val pages = layout()
        assertTrue(pages.first().items.none { it.item is ReportItem.Warning })
    }

    /** 긴 경고는 종이 폭에 맞춰 여러 줄이 된다. */
    @Test
    fun `긴 경고가 줄바꿈된다`() {
        val pages = layout(warnings = listOf("가".repeat(25)), perLine = 10)
        val lines = pages.first().items.map { it.item }.filterIsInstance<ReportItem.Warning>()
        assertEquals(3, lines.size)
    }

    // ── 값도 줄바꿈된다 ─────────────────────────────────

    /**
     * **이어지는 줄에는 이름을 다시 적지 않는다.** 같은 이름이 두 번
     * 보이면 값이 둘인 줄 안다.
     */
    @Test
    fun `긴 값은 여러 줄이 되고 이름은 한 번만 적힌다`() {
        val long = listOf(ReportSection("결과", listOf(ReportLine("메모", "나".repeat(25)))))
        val rows = layout(sections = long, perLine = 10)
            .flatMap { it.items }.map { it.item }.filterIsInstance<ReportItem.Row>()
        assertEquals(3, rows.size)
        assertEquals("메모", rows[0].labelKo)
        assertEquals("", rows[1].labelKo)
        assertEquals("", rows[2].labelKo)
    }

    // ── 쪽 나누기 ───────────────────────────────────────

    /** 짧으면 한 쪽이다. */
    @Test
    fun `짧은 리포트는 한 쪽이다`() {
        assertEquals(1, layout().size)
    }

    /** 길면 쪽이 늘고, 쪽 번호와 총 쪽수가 붙는다. */
    @Test
    fun `길면 쪽이 늘고 번호가 붙는다`() {
        val many = (1..40).map { n ->
            ReportSection("묶음 $n", (1..5).map { ReportLine("이름 $it", "값 $it") })
        }
        val pages = layout(sections = many)
        assertTrue("여러 쪽이어야 한다(났다: ${pages.size})", pages.size > 1)
        assertEquals(listOf(1, 2), pages.map { it.number }.take(2))
        assertTrue(pages.all { it.total == pages.size })
    }

    /**
     * **제목만 남기고 넘기지 않는다.**
     *
     * 쪽 끝에 묶음 이름만 덩그러니 남으면, 넘기기 전까지는 그 묶음이
     * **비어 있는 줄 안다.** 제목과 첫 줄은 같은 쪽에 함께 둔다.
     *
     * ## 치수를 손으로 짚는 까닭
     *
     * 처음에는 「묶음을 40개 넣으면 어디선가 걸리겠지」로 썼다. **막는
     * 줄을 지워도 그대로 통과했다** — A4 치수에서는 제목이 쪽 끝에
     * 홀로 남는 자리가 **한 번도 오지 않았다.** 넣어 보고 알았다.
     *
     * 그래서 홀로 남는 자리를 **셈해서 만든다.** 아래 치수에서 셋째
     * 묶음의 제목은 y=156 에서 시작하는데, 제목(20)만 보면 176 으로
     * 들어가고 첫 줄(16)까지 보면 192 로 넘친다 — 바닥은 190 이다.
     * 막는 줄이 없으면 **첫 쪽이 제목으로 끝난다.**
     */
    @Test
    fun `묶음 제목이 쪽 끝에 홀로 남지 않는다`() {
        val tight = ReportMetrics(
            pageHeightPt = 200,
            marginPt = 10,
            headingHeightPt = 10,
            headingGapPt = 0,
            sectionTitleHeightPt = 20,
            sectionGapPt = 0,
            rowHeightPt = 16,
        )
        val three = (1..3).map { n ->
            ReportSection("묶음 $n", (1..3).map { ReportLine("이름 $it", "값 $it") })
        }
        val pages = layout(sections = three, metrics = tight)
        assertTrue("여러 쪽이어야 이 시험이 뜻이 있다", pages.size > 1)
        pages.forEach { page ->
            assertFalse(
                "쪽 ${page.number} 끝에 제목만 남았다",
                page.items.last().item is ReportItem.SectionTitle,
            )
        }
    }

    /** 쪽 안의 것들은 위에서 아래로 겹치지 않고 앉는다. */
    @Test
    fun `같은 쪽 안에서 아래로 내려간다`() {
        val many = (1..10).map { n ->
            ReportSection("묶음 $n", (1..3).map { ReportLine("이름 $it", "값 $it") })
        }
        layout(sections = many).forEach { page ->
            page.items.zipWithNext { a, b ->
                assertTrue("y 가 커져야 한다", b.yPt > a.yPt)
            }
            val m = ReportMetrics()
            assertTrue(
                "아래 여백을 넘지 않는다",
                page.items.last().yPt <= m.pageHeightPt - m.marginPt,
            )
        }
    }

    /** 제목은 첫 쪽에만 적는다. 쪽마다 반복하면 종이만 먹는다. */
    @Test
    fun `제목은 첫 쪽에만 있다`() {
        val many = (1..40).map { n ->
            ReportSection("묶음 $n", (1..5).map { ReportLine("이름 $it", "값 $it") })
        }
        val pages = layout(sections = many)
        assertEquals(1, pages.count { p -> p.items.any { it.item is ReportItem.Heading } })
        assertTrue(pages.first().items.any { it.item is ReportItem.Heading })
    }

    /** 빈 리포트도 터지지 않는다 — 제목 한 장이 나온다. */
    @Test
    fun `내용이 없어도 한 쪽은 나온다`() {
        val pages = layout(sections = emptyList())
        assertEquals(1, pages.size)
        assertTrue(pages.first().items.any { it.item is ReportItem.Heading })
    }

    // ── 경고가 쪽마다 남는가 (독립 검토 PND-05) ──

    /**
     * **숫자가 있는 쪽에는 경고도 있어야 한다.**
     *
     * 처음에는 맨 앞에 한 번만 두었다. 그러다 메모가 길어 숫자가
     * 뒷쪽으로 밀리면, **그 쪽만 인쇄하거나 전달했을 때 해석할 단서가
     * 통째로 떨어져 나간다** — 「미보정이라 참고값」이라는 말이 없는
     * 숫자 한 장이 된다. **종이는 한 장씩 돌아다닌다.**
     */
    @Test
    fun `경고가 쪽마다 되풀이된다`() {
        val many = (1..40).map { n ->
            ReportSection("묶음 $n", (1..5).map { ReportLine("이름 $it", "값 $it") })
        }
        val pages = layout(warnings = listOf("미보정으로 재습니다."), sections = many)
        assertTrue("여러 쪽이어야 뜻이 있다", pages.size > 1)
        pages.forEach { page ->
            assertTrue(
                "쪽 ${page.number} 에 경고가 없다",
                page.items.any { it.item is ReportItem.Warning },
            )
        }
    }

    /** 되풀이는 경고는 **본문보다 위**에 온다. 아래에 있으면 먼저 숫자를 읽는다. */
    @Test
    fun `되풀이는 경고도 본문보다 위다`() {
        val many = (1..40).map { n ->
            ReportSection("묶음 $n", (1..5).map { ReportLine("이름 $it", "값 $it") })
        }
        layout(warnings = listOf("미보정으로 재습니다."), sections = many).drop(1).forEach { page ->
            val firstWarn = page.items.indexOfFirst { it.item is ReportItem.Warning }
            val firstBody = page.items.indexOfFirst {
                it.item is ReportItem.Row || it.item is ReportItem.SectionTitle
            }
            assertTrue("쪽 ${page.number}: 경고가 본문 아래에 있다", firstWarn < firstBody)
        }
    }

    /**
     * **경고가 종이를 다 먹으면 잘라 내고 잘렸다고 적는다.**
     *
     * 다 실으려다 본문이 들어갈 자리가 없어지면 손만 놓게 된다.
     * 잘렸다는 사실을 안 적으면 「이게 전부」로 읽힌다.
     */
    @Test
    fun `경고가 너무 길면 잘리고 그 사실을 적는다`() {
        val huge = (1..80).map { "경고 $it" }
        val pages = layout(warnings = huge)
        val warns = pages.first().items.mapNotNull { (it.item as? ReportItem.Warning)?.textKo }
        assertTrue("잘리지 않았다(${warns.size}줄)", warns.size < huge.size)
        assertEquals("(경고가 더 있습니다)", warns.last())
    }

    // ── 들여쓴 만큼 좁게 끊는다 ─────────────────────────

    /**
     * **경고를 끊는 폭은 들여쓴 만큼 좁아야 한다**(독립 검토 2회차 잔여 권고).
     *
     * 그리는 쪽은 경고를 왼쪽에서 [ReportMetrics.warningIndentPt] 만큼
     * 들여 쓴다. 끊는 폭이 전체 글 폭이면 **꽉 찬 줄이 그만큼 오른쪽으로
     * 밀려 여백을 넘고, 넘은 글자는 종이 밖이라 그냥 사라진다.**
     *
     * 여기서는 자에게 들어온 폭을 그대로 받아 적어 본다.
     */
    @Test
    fun `경고는 들여쓴 만큼 좁은 폭으로 끊는다`() {
        val widths = mutableListOf<Int>()
        val metrics = ReportMetrics()
        ReportPageLayout.paginate(
            "예배 측정 기록",
            listOf("미보정으로 쟀습니다."),
            sections,
            metrics,
        ) { text, widthPt ->
            widths += widthPt
            listOf(text)
        }

        // 첫 번째가 경고다(제목 다음, 본문 앞).
        assertEquals(metrics.contentWidthPt - metrics.warningIndentPt, widths.first())
        assertTrue("들여쓴 만큼 좁지 않다: $widths", widths.first() < metrics.contentWidthPt)
    }
}
