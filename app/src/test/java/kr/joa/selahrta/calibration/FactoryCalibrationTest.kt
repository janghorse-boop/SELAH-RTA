package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.domain.CalibrationState
import kr.joa.selahrta.domain.MicKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **기종 기본값**(개발자가 재서 앱에 실어 둔 값).
 *
 * 여기서 지키는 것은 둘이다. 하나는 「짐작보다 나은 값을 기본으로
 * 준다」이고, 다른 하나는 **「그 값을 이 기기의 보정이라고 말하지
 * 않는다」**이다. 뒤엣것이 무너지면 앞엣것은 해가 된다.
 */
class FactoryCalibrationTest {

    private val s23 = DeviceBuildInfo("samsung", "SM-S918N", "UP1A.231005.007")

    private fun entry(
        maker: String = "samsung",
        model: String = "SM-S918N",
        source: CaptureSource = CaptureSource.Unprocessed,
        route: String? = "bottom",
        offset: Double = 122.6,
    ) = FactoryCalibration(
        manufacturer = maker,
        model = model,
        source = source,
        routeAddress = route,
        offsetDb = offset,
        measuredOn = "2026-09-27",
        referenceKo = "EMM-6 + UMC404HD",
        byKo = "장훈",
    )

    private fun find(
        table: List<FactoryCalibration>,
        build: DeviceBuildInfo = s23,
        kind: MicKind = MicKind.BuiltIn,
        source: CaptureSource = CaptureSource.Unprocessed,
        route: String = "bottom",
        confirmed: Boolean = true,
    ) = findFactoryCalibration(build, kind, source, route, confirmed, table)

    // ── 찾기 ────────────────────────────────────────────────

    @Test
    fun `같은 기종 같은 소스 같은 자리면 찾는다`() {
        assertEquals(122.6, find(listOf(entry()))?.offsetDb)
    }

    @Test
    fun `제조사와 모델은 대소문자를 가리지 않는다`() {
        val found = find(
            listOf(entry(maker = "Samsung", model = "sm-s918n")),
        )
        assertNotNull("같은 기종인데 대소문자로 갈렸다", found)
    }

    @Test
    fun `모델이 다르면 걸지 않는다`() {
        assertNull(find(listOf(entry(model = "SM-S911N"))))
    }

    /**
     * 개발지시서 6장: 소스가 달라지면 제조사 DSP·AGC 가 달리 걸린다.
     * 같은 마이크라도 같은 보정으로 보지 않는다.
     */
    @Test
    fun `소스가 다르면 걸지 않는다`() {
        assertNull(find(listOf(entry(source = CaptureSource.Unprocessed)), source = CaptureSource.Mic))
    }

    /** USB 마이크는 폰의 일부가 아니다 — 기종으로 값을 정할 수 없다. */
    @Test
    fun `USB 마이크에는 기종 기본값을 걸지 않는다`() {
        assertNull(find(listOf(entry(route = null)), kind = MicKind.Usb))
    }

    // ── 자리 ────────────────────────────────────────────────

    @Test
    fun `자리가 적힌 항목은 다른 자리에 걸리지 않는다`() {
        assertNull(find(listOf(entry(route = "bottom")), route = "back"))
    }

    /**
     * **빈 자리는 「모른다」이지 「같다」가 아니다**(CAR-03 이 데인 자리).
     * 자리를 모르는 채로 `bottom` 의 값을 걸면 그 보호가 기본값이라는
     * 이름으로 되살아난다.
     */
    @Test
    fun `자리를 확인하지 못하면 자리를 적은 항목은 걸지 않는다`() {
        assertNull(find(listOf(entry(route = "bottom")), route = "", confirmed = false))
        assertNull(find(listOf(entry(route = "bottom")), route = "bottom", confirmed = false))
    }

    /** null 은 「자리를 가리지 않는다」는 **사람의 선언**이다. */
    @Test
    fun `자리를 가리지 않는 항목은 자리를 몰라도 걸린다`() {
        assertNotNull(find(listOf(entry(route = null)), route = "", confirmed = false))
    }

    @Test
    fun `자리를 적은 항목이 가리지 않는 항목보다 먼저다`() {
        val table = listOf(entry(route = null, offset = 120.0), entry(route = "bottom", offset = 122.6))
        assertEquals("더 좁게 말한 쪽을 써야 한다", 122.6, find(table)?.offsetDb)
    }

    // ── 표의 오타 ───────────────────────────────────────────

    /**
     * 표는 사람이 손으로 적는다. 오타 하나가 **그 기종 전체**에 걸리므로
     * 그물을 두 겹으로 둔다 — 시험이 먼저 막고, 찾기가 다시 막는다.
     */
    @Test
    fun `그럴듯하지 않은 값은 없는 것으로 친다`() {
        assertNull(find(listOf(entry(offset = 1226.0))))
        assertNull(find(listOf(entry(offset = 2.6))))
    }

    @Test
    fun `제조사나 모델을 모르면 찾지 않는다`() {
        assertNull(find(listOf(entry()), build = DeviceBuildInfo("", "SM-S918N", "")))
        assertNull(find(listOf(entry()), build = DeviceBuildInfo("samsung", "", "")))
    }

    // ── 앱에 실린 표 자체 ───────────────────────────────────

    /**
     * **잰 적 없는 값을 싣지 않는다.** 항목마다 언제·무엇으로·누가
     * 쟀는지가 있어야 한다 — 빈칸이면 나중에 그 값을 믿을지 정할 수 없다.
     */
    @Test
    fun `실린 표의 항목은 모두 내력과 그럴듯한 값을 가진다`() {
        FACTORY_CALIBRATIONS.forEach { e ->
            assertTrue("${e.model}: 제조사가 비었다", e.manufacturer.isNotBlank())
            assertTrue("${e.model}: 모델이 비었다", e.model.isNotBlank())
            assertTrue("${e.model}: 잰 날이 비었다", e.measuredOn.isNotBlank())
            assertTrue("${e.model}: 기준이 비었다", e.referenceKo.isNotBlank())
            assertTrue("${e.model}: 잰 사람이 비었다", e.byKo.isNotBlank())
            assertTrue(
                "${e.model}: 보정값 ${e.offsetDb} 이 그럴듯한 범위 밖이다",
                e.offsetDb in PLAUSIBLE_OFFSET_RANGE,
            )
            assertFalse(
                "${e.model}: 자리를 빈 문자열로 적었다 — 가리지 않을 것이면 null 이다",
                e.routeAddress?.isEmpty() == true,
            )
        }
    }

    @Test
    fun `실린 표에 같은 조합이 두 번 있지 않다`() {
        val keys = FACTORY_CALIBRATIONS.map {
            listOf(
                it.manufacturer.lowercase(),
                it.model.lowercase(),
                it.source.name,
                it.routeAddress ?: "*",
            )
        }
        assertEquals("같은 조합이 두 번 실렸다", keys.size, keys.toSet().size)
    }

    // ── 거는 순서 ───────────────────────────────────────────

    @Test
    fun `잰 값이 없으면 기종 기본값이 걸린다`() {
        val a = ActiveCalibration.from(saved = null, factory = entry())
        assertEquals(CalibrationState.FactoryDefault, a.state)
        assertEquals(122.6, a.offset.db, 1e-9)
        assertTrue(a.usingFactory)
    }

    @Test
    fun `기본값도 없으면 짐작으로 내려간다`() {
        val a = ActiveCalibration.from(saved = null, factory = null)
        assertEquals(CalibrationState.Uncalibrated, a.state)
        assertEquals(ASSUMED_FULL_SCALE_SPL, a.offset.db, 1e-9)
    }

    /** 사람이 이 기기에서 잰 값은 **언제나** 기본값을 이긴다. */
    @Test
    fun `잰 값이 있으면 기본값을 이긴다`() {
        val saved = GlobalCalibration(
            offsetDb = 118.0,
            savedAtEpochMs = 1L,
            referenceDb = 85.0,
            measuredDbfs = -33.0,
            routeAddress = "bottom",
        )
        val a = ActiveCalibration.from(saved, "bottom", routeConfirmed = true, factory = entry())
        assertEquals(CalibrationState.GlobalCalibrated, a.state)
        assertEquals(118.0, a.offset.db, 1e-9)
        assertFalse(a.usingFactory)
        // 걸지 않아도 **들고 있다** — 초기화하면 어디로 돌아가는지 말해야 한다.
        assertNotNull("초기화 뒤 돌아갈 자리를 화면이 모른다", a.factory)
    }

    /**
     * 자리가 어긋나 잰 값을 보류했을 때도 짐작이 아니라 기본값으로 간다.
     * **보류 까닭은 그대로 남는다** — 숫자만 조용히 바뀌면 안 된다.
     */
    @Test
    fun `자리가 어긋나 보류하면 기본값으로 내려가되 까닭을 적는다`() {
        val saved = GlobalCalibration(
            offsetDb = 118.0,
            savedAtEpochMs = 1L,
            referenceDb = 85.0,
            measuredDbfs = -33.0,
            routeAddress = "back",
        )
        val a = ActiveCalibration.from(saved, "bottom", routeConfirmed = true, factory = entry())
        assertEquals(CalibrationState.FactoryDefault, a.state)
        assertEquals(122.6, a.offset.db, 1e-9)
        assertNotNull("보류 까닭이 사라졌다", a.holdNoticeKo)
        assertTrue(
            "무엇으로 대신하고 있는지 말하지 않는다",
            a.holdNoticeKo!!.contains("기종 기본값"),
        )
    }

    // ── 「보정됨」이라 말하지 않는다 ────────────────────────

    /**
     * **기본값은 이 기기를 잰 값이 아니다.**
     *
     * 녹음 파일에도 참고용 구간으로 남고, 화면도 「보정됨」이라 적지
     * 않는다. 이 한 줄이 무너지면 앱이 거짓말을 한다.
     */
    @Test
    fun `기종 기본값은 측정값이라 부르지 않는다`() {
        val a = ActiveCalibration.from(saved = null, factory = entry())
        assertTrue(a.isReferenceOnly)
        assertEquals("기본값", a.state.shortKo)
    }

    /**
     * 마법사가 이 값을 **다른 마이크의 절대 보정으로 옮기지 못하게**
     * 한다(CARF-02 와 같은 자리). 옮기는 순간 개체 차이가 굳는다.
     *
     * **저장된 값이 남아 있는 채로 기본값이 걸린 자리**에서 본다. 잰 값이
     * 아예 없으면 옮길 것도 없어 이 시험이 그냥 통과해 버린다 — 실제로
     * 새어 나갈 수 있는 자리는 자리가 어긋나 **보류된** 쪽이다.
     */
    @Test
    fun `기종 기본값이 걸린 동안에는 저장된 값도 옮겨지지 않는다`() {
        val saved = GlobalCalibration(
            offsetDb = 118.0,
            savedAtEpochMs = 1L,
            referenceDb = 85.0,
            measuredDbfs = -33.0,
            routeAddress = "back",
        )
        val a = ActiveCalibration.from(saved, "bottom", routeConfirmed = true, factory = entry())
        assertEquals(CalibrationState.FactoryDefault, a.state)
        assertNull("보류한 값이 기준 마이크의 절대값으로 새어 나간다", a.appliedOffsetDb)
    }

    @Test
    fun `잰 값이 없을 때도 옮길 것이 없다`() {
        assertNull(ActiveCalibration.from(saved = null, factory = entry()).appliedOffsetDb)
    }

    // ── 붙여 넣을 코드 ─────────────────────────────────────

    @Test
    fun `자리를 모르면 코드가 null 로 적고 사람에게 되묻는다`() {
        val snippet = factoryEntrySnippet(
            build = s23,
            source = CaptureSource.Unprocessed,
            routeAddress = "",
            cal = GlobalCalibration(122.6, 0L, 85.0, -37.6),
            measuredOn = "2026-09-27",
        )
        assertTrue(snippet.contains("routeAddress = null"))
        assertTrue("사람이 판단해야 한다는 말이 없다", snippet.contains("확인하지 못했습니다"))
    }

    @Test
    fun `적힌 자리는 코드에 그대로 들어간다`() {
        val snippet = factoryEntrySnippet(
            build = s23,
            source = CaptureSource.Unprocessed,
            routeAddress = "bottom",
            cal = GlobalCalibration(122.6, 0L, 85.0, -37.6),
            measuredOn = "2026-09-27",
        )
        assertTrue(snippet.contains("routeAddress = \"bottom\""))
        assertTrue(snippet.contains("model = \"SM-S918N\""))
        assertTrue(snippet.contains("source = CaptureSource.Unprocessed"))
        assertTrue(snippet.contains("offsetDb = 122.6"))
    }
}
