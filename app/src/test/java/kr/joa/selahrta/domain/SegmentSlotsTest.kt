package kr.joa.selahrta.domain

import kr.joa.selahrta.settings.MeterSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **구간은 다섯까지**(담당자 지시 2026-09-28).
 *
 * 설교·찬양 둘뿐이던 것을 사람이 더해 쓸 수 있게 늘렸다. 여기서 지키는
 * 두 줄:
 *
 * 1. **enum 이름은 바꾸지 않는다.** 설정과 기록이 그 이름을 열쇠로 쓴다
 *    (`segName|Extra1`). 바꾸면 저장해 둔 범위와 이름을 통째로 잃는다.
 * 2. **설교·찬양은 빼지 못한다.** 이미 남긴 기록들이 이 둘을 가리키고
 *    있고, 고를 것이 하나는 남아야 한다.
 */
class SegmentSlotsTest {

    @Test
    fun `구간 칸이 다섯이다`() {
        assertEquals(MAX_SEGMENTS, ChurchSegment.entries.size)
    }

    /**
     * **이름을 바꾸면 저장해 둔 값을 잃는다.** 열쇠가 곧 이 글자다.
     * 누가 「Prayer」처럼 고치려 할 때 이 시험이 막는다.
     */
    @Test
    fun `enum 이름이 저장 열쇠라 바뀌면 안 된다`() {
        assertEquals(
            listOf("Sermon", "Worship", "Extra1", "Extra2", "Extra3"),
            ChurchSegment.entries.map { it.name },
        )
    }

    /**
     * **하나도 없이 시작한다**(담당자 지시 2026-09-28).
     *
     * 전에는 설교·찬양이 처음부터 깔려 있었다. 권장 범위는 예배당마다
     * 다른 참고값이라, 앱이 먼저 깔아 두면 **쓰지도 않는 기준과 견주어
     * 색이 뜬다.**
     */
    @Test
    fun `처음에는 구간이 하나도 없다`() {
        assertTrue(MeterSettings().segments.isEmpty())
        assertTrue(MeterSettings().orderedSegments.isEmpty())
    }

    /** 구간이 없으면 견줄 것도 없다 — 화면이 권장 범위 상자를 그리지 않는다. */
    @Test
    fun `구간이 없으면 고른 구간도 없다`() {
        assertEquals(null, MeterSettings().activeSegment)
    }

    /**
     * **뺀 구간의 범위와 견주지 않는다.**
     *
     * `segment` 는 저장된 값이라 목록에서 뺀 뒤에도 남아 있을 수 있다.
     * 그대로 쓰면 「지우지도 않은 기준」으로 색이 뜬다.
     */
    @Test
    fun `목록에 없는 구간을 고른 채로 두지 않는다`() {
        val s = MeterSettings(
            segment = ChurchSegment.Worship,
            segments = setOf(ChurchSegment.Sermon),
        )
        assertEquals(ChurchSegment.Sermon, s.activeSegment)
    }

    /**
     * **구간을 지우는 것만으로 화면이 보는 구간이 바뀐다**(독립 검토 UIS-04).
     *
     * 검토자가 저장소를 직접 돌려 재현한 전이가 이것이다:
     * `Worship → Sermon → null`. 여기에 사건 기록이 따라붙지 않으면
     * 표는 계속 지워진 구간으로 분류한다.
     *
     * 이 시험은 **그 전이가 실제로 일어난다**는 것을 못박는다 —
     * `CaptureViewModel` 이 설정 경계에서 앞뒤를 견주는 것이 여기에
     * 기대고 있다.
     */
    @Test
    fun `쓰던 구간을 지우면 고른 구간이 따라 바뀐다`() {
        val two = MeterSettings(
            segment = ChurchSegment.Worship,
            segments = setOf(ChurchSegment.Sermon, ChurchSegment.Worship),
        )
        assertEquals(ChurchSegment.Worship, two.activeSegment)

        // 쓰던 찬양을 뺀다 → 남은 설교로 넘어간다
        val one = two.copy(segments = setOf(ChurchSegment.Sermon))
        assertEquals(ChurchSegment.Sermon, one.activeSegment)

        // 마지막 하나까지 빼면 고른 구간이 없다
        val none = one.copy(segments = emptySet())
        assertEquals(null, none.activeSegment)
    }

    /** 반대 방향도 사건이다 — 없다가 처음 생기는 자리. */
    @Test
    fun `첫 구간을 더하면 없던 고른 구간이 생긴다`() {
        val none = MeterSettings(segments = emptySet())
        assertEquals(null, none.activeSegment)
        assertEquals(
            ChurchSegment.Sermon,
            none.copy(segments = setOf(ChurchSegment.Sermon)).activeSegment,
        )
    }

    @Test
    fun `고른 구간이 목록에 있으면 그대로다`() {
        val s = MeterSettings(
            segment = ChurchSegment.Extra1,
            segments = setOf(ChurchSegment.Sermon, ChurchSegment.Extra1),
        )
        assertEquals(ChurchSegment.Extra1, s.activeSegment)
    }

    /** 처음 더하면 설교부터 생긴다 — 가장 흔히 쓰는 것이 먼저다. */
    @Test
    fun `아무것도 없을 때 먼저 더해지는 것은 설교다`() {
        assertEquals(ChurchSegment.Sermon, MeterSettings().nextFreeSegment)
    }

    /**
     * **enum 차례를 따른다.** 더한 차례로 두면 기기를 바꿀 때마다 줄이
     * 뒤바뀐다.
     */
    @Test
    fun `목록은 더한 차례가 아니라 enum 차례다`() {
        val s = MeterSettings(
            segments = setOf(
                ChurchSegment.Extra2,
                ChurchSegment.Sermon,
                ChurchSegment.Extra1,
                ChurchSegment.Worship,
            ),
        )
        assertEquals(
            listOf(
                ChurchSegment.Sermon,
                ChurchSegment.Worship,
                ChurchSegment.Extra1,
                ChurchSegment.Extra2,
            ),
            s.orderedSegments,
        )
    }

    @Test
    fun `다섯이 차면 더 더하지 못한다`() {
        val full = MeterSettings(segments = ChurchSegment.entries.toSet())
        assertFalse(full.canAddSegment)
        assertEquals(null, full.nextFreeSegment)
    }

    @Test
    fun `다음에 더할 칸은 비어 있는 첫 칸이다`() {
        assertEquals(
            ChurchSegment.Worship,
            MeterSettings(segments = setOf(ChurchSegment.Sermon)).nextFreeSegment,
        )
        assertEquals(
            ChurchSegment.Extra1,
            MeterSettings(
                segments = setOf(ChurchSegment.Sermon, ChurchSegment.Worship),
            ).nextFreeSegment,
        )
    }

    // ── 범위에서 피크를 걷어냈다 ─────────────────────────

    /**
     * **피크 아래·위를 뺐다**(담당자 지시 2026-09-28: 「의미가 없어
     * 보여서」). 실제로 그 값으로 판정하는 자리가 어디에도 없었다 —
     * 저장만 되고 아무 일도 하지 않았다.
     */
    @Test
    fun `범위는 평균 두 값뿐이다`() {
        val r = SegmentRange(68.0, 75.0)
        assertEquals(68.0, r.avgLowDb, 1e-9)
        assertEquals(75.0, r.avgHighDb, 1e-9)
        assertEquals(68.0..75.0, r.avg)
    }

    @Test
    fun `아래가 위보다 크면 말이 안 된다`() {
        assertFalse(SegmentRange(80.0, 70.0).isSane)
        assertFalse(SegmentRange(70.0, 70.0).isSane)
        assertTrue(SegmentRange(70.0, 71.0).isSane)
    }

    /** 사람이 자릿수를 잘못 눌렀을 때 걸러야 한다. */
    @Test
    fun `터무니없는 값은 막는다`() {
        assertFalse("20dB 을 받아들였다", SegmentRange(20.0, 70.0).isSane)
        assertFalse("150dB 을 받아들였다", SegmentRange(70.0, 150.0).isSane)
    }

    /** 더해 쓰는 칸의 출발점도 말이 되어야 한다. */
    @Test
    fun `모든 구간의 초기값이 말이 된다`() {
        ChurchSegment.entries.forEach {
            assertTrue("${it.name} 의 초기값이 이상하다", DefaultSegmentRanges.of(it).isSane)
        }
    }
}
