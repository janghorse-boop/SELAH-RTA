package kr.joa.selahrta.calibration

import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.dsp.CurvePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **끄는 것과 지우는 것은 다르다**(USB 오디오 지시서 11장).
 *
 * > 파일을 삭제하지 않고 적용만 즉시 켜고 끌 수 있어야 한다.
 * > OFF = 원본 측정값 / ON = 동일 입력에 캘리브레이션 적용
 *
 * `CurveStore` 는 DataStore·파일에 매여 있어 기기 없이 못 돌린다. 그래서
 * **엔진에 무엇을 넘기는지**를 고르는 규칙만 따로 본다 — 실제로 조용히
 * 틀릴 수 있는 자리가 거기다.
 */
class CurveEnabledTest {

    private val curve = CalibrationCurve.of(
        listOf(CurvePoint(100.0, -2.0), CurvePoint(1000.0, 0.0), CurvePoint(10000.0, 3.0)),
    ).getOrThrow()

    private fun active(enabled: Boolean) = ActiveCurve(
        curve = curve,
        fileName = "EMM-6_123456.txt",
        pointCount = 3,
        importedAtEpochMs = 0L,
        enabled = enabled,
    )

    /** `CaptureViewModel` 이 엔진에 넘기는 식과 같다. */
    private fun toEngine(c: ActiveCurve?): CalibrationCurve? = c?.curve?.takeIf { c.enabled }

    @Test
    fun `켜져 있으면 엔진에 넘긴다`() {
        assertNotNull(toEngine(active(enabled = true)))
    }

    @Test
    fun `꺼 두면 엔진에 넘기지 않는다`() {
        assertNull("꺼 뒀는데 걸리면 원본 값을 볼 수 없다", toEngine(active(enabled = false)))
    }

    @Test
    fun `없으면 넘길 것도 없다`() {
        assertNull(toEngine(null))
    }

    /**
     * **꺼 둔 것과 없는 것은 화면에서 달라야 한다.**
     *
     * 둘 다 「없음」으로 보이면, 꺼 둔 사람이 파일을 다시 가져온다.
     */
    @Test
    fun `꺼 둔 것과 없는 것을 가른다`() {
        val off = active(enabled = false)
        assertEquals("파일 이름은 그대로 남아 있어야 한다", "EMM-6_123456.txt", off.fileName)
        assertEquals("점 수도 그대로다", 3, off.pointCount)
    }

    /** 기본값은 **켜짐**이다. 지금까지 저장된 곡선이 조용히 풀리면 안 된다. */
    @Test
    fun `기본값은 켜짐이다`() {
        val c = ActiveCurve(curve, "x.cal", 3, 0L)
        assertNotNull(toEngine(c))
    }
}
