package kr.joa.selahrta.calibration

import kr.joa.selahrta.domain.CalibrationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **전대역 보정에도 잰 자리가 붙는가**(독립 재검토 CAR-03).
 *
 * 주파수 프로파일에는 자리 보호가 있었는데 **전대역 오프셋**은 그 길을
 * 지나지 않았다. 검토자가 잰 것:
 *
 * ```
 * MEASURED_ROUTE old=bottom new=back auto=false
 * GLOBAL_ROUTE  sameStorageKey=true offset=110.0 state=GlobalCalibrated referenceOnly=false
 * ```
 *
 * 같은 열쇠에서 읽은 +110dB 에는 경로를 검사할 자료가 없었다. 주소가
 * 다르다고 물리적으로 다른 마이크라 단정하는 것은 아니다 — 문제는
 * **같은 교정을 써도 된다는 근거 없이 「보정 완료」로 승인**하는 것이다.
 */
class CalibrationRouteTest {

    private fun saved(route: String?) = GlobalCalibration(
        offsetDb = 110.0,
        savedAtEpochMs = 0L,
        referenceDb = 94.0,
        measuredDbfs = -16.0,
        routeAddress = route,
    )

    // ------------------------------------------------------------------
    // 판정
    // ------------------------------------------------------------------

    @Test
    fun `같은 자리면 건다`() {
        assertEquals(
            RouteVerdict.Same,
            judgeCalibrationRoute("bottom", "bottom", routeConfirmed = true),
        )
    }

    @Test
    fun `다른 자리면 걸지 않는다`() {
        val v = judgeCalibrationRoute("bottom", "back", routeConfirmed = true)
        assertEquals(RouteVerdict.Different, v)
        assertFalse(v.mayAutoApply)
    }

    /** **옛 기록에는 자리가 없다.** 그것을 「같다」로 읽으면 검사가 무력해진다. */
    @Test
    fun `옛 기록은 모름이다`() {
        assertEquals(
            RouteVerdict.Unknown,
            judgeCalibrationRoute(null, "bottom", routeConfirmed = true),
        )
        assertEquals(
            RouteVerdict.Unknown,
            judgeCalibrationRoute("", "bottom", routeConfirmed = true),
        )
    }

    /** 지금 자리를 모르는 것도 모르는 것이다. */
    @Test
    fun `지금 자리를 모르면 모름이다`() {
        assertEquals(
            RouteVerdict.Unknown,
            judgeCalibrationRoute("bottom", "", routeConfirmed = true),
        )
        assertEquals(
            RouteVerdict.Unknown,
            judgeCalibrationRoute("bottom", "bottom", routeConfirmed = false),
        )
    }

    // ------------------------------------------------------------------
    // 거는 자리
    // ------------------------------------------------------------------

    @Test
    fun `같은 자리면 보정이 걸린다`() {
        val a = ActiveCalibration.from(saved("bottom"), nowRoute = "bottom", routeConfirmed = true)
        assertEquals(CalibrationState.GlobalCalibrated, a.state)
        assertEquals(110.0, a.offset.db, 0.0)
        assertNull(a.holdNoticeKo)
    }

    /**
     * **가장 중요한 시험.** 하단에서 잰 감도를 후면에서 쓰면서 「보정
     * 완료」라 적던 자리다.
     */
    @Test
    fun `다른 자리면 걸지 않고 값은 들고 있는다`() {
        val a = ActiveCalibration.from(saved("bottom"), nowRoute = "back", routeConfirmed = true)
        assertEquals("자리가 다른데 걸렸다", CalibrationState.Uncalibrated, a.state)
        assertTrue("미보정이라 말해야 한다", a.isReferenceOnly)
        assertEquals("짐작 눈금으로 돌아간다", ASSUMED_FULL_SCALE_SPL, a.offset.db, 0.0)
        assertNotNull("왜 안 걸었는지 말해야 한다", a.holdNoticeKo)
        assertNotNull("**값은 지우지 않는다** — 다시 재게 하면 안 된다", a.saved)
        assertEquals(110.0, a.saved!!.offsetDb, 0.0)
        assertTrue(a.holdNoticeKo!!, a.holdNoticeKo!!.contains("bottom"))
        assertTrue(a.holdNoticeKo!!, a.holdNoticeKo!!.contains("back"))
    }

    /** 옛 기록도 지우지 않는다 — 사람이 확인하면 곧바로 걸린다. */
    @Test
    fun `자리를 모르면 걸지 않고 묻는다`() {
        val a = ActiveCalibration.from(saved(null), nowRoute = "bottom", routeConfirmed = true)
        assertTrue(a.isReferenceOnly)
        assertTrue(a.heldForRoute)
        assertNotNull(a.saved)
        assertTrue(a.holdNoticeKo!!, a.holdNoticeKo!!.contains("계속 쓰기"))
    }

    @Test
    fun `저장된 것이 없으면 예전 그대로다`() {
        val a = ActiveCalibration.from(null)
        assertEquals(ActiveCalibration.assumed, a)
        assertNull(a.holdNoticeKo)
    }

    /**
     * 화면에 그대로 나가는 문장이다 — **마크다운 강조를 쓰지 않는다.**
     * 이 자리는 서식 없는 Text 라 별표가 글자 그대로 보인다.
     */
    @Test
    fun `안내 문구에 별표가 없다`() {
        for (v in listOf(RouteVerdict.Unknown, RouteVerdict.Different)) {
            val s = routeNoticeKo(v, "bottom", "back")
            assertNotNull(s)
            assertFalse("별표가 있다: $s", s!!.contains("**"))
        }
    }
}
