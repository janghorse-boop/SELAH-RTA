package kr.joa.selahrta.ui

import android.content.pm.ActivityInfo
import kr.joa.selahrta.settings.MeterSettings
import kr.joa.selahrta.settings.portraitModeOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 화면 방향 규칙(2026-10-03 담당자 요구: 「폰 하단을 스피커로 향하면 세로 화면 자체가 거꾸로 되어야 보기
 * 편하다」). 규칙은 [orientationFor] 한 자리에 있다.
 *
 * 여기서 보는 것은 **무엇을 요청하는가**까지다. 기기가 그 요청대로 실제로 뒤집히는지(센서·회전 잠금·제조사
 * 정책)는 실기기 확인 몫이다.
 */
class OrientationPolicyTest {

    @Test
    fun `분석 구역은 세로 선택과 상관없이 가로 양방향이다`() {
        PortraitMode.entries.forEach { m ->
            assertEquals(m.name, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, orientationFor(inAnalysis = true, portrait = m))
        }
    }

    @Test
    fun `분석 밖의 세로는 선택을 따른다`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, orientationFor(false, PortraitMode.Auto))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientationFor(false, PortraitMode.Upright))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, orientationFor(false, PortraitMode.UpsideDown))
    }

    /** 예전에는 정상 세로(`PORTRAIT`)만 요청해 폰을 뒤집어도 화면이 따라오지 않았다. 기본은 이제 자동이다. */
    @Test
    fun `기본은 자동 — 정상 세로에만 묶지 않는다`() {
        assertEquals(PortraitMode.Auto, PortraitMode.DEFAULT)
        assertEquals(PortraitMode.DEFAULT, MeterSettings().portraitMode)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, orientationFor(false, MeterSettings().portraitMode))
    }

    /** 분석을 나오면 **그때의** 선택으로 — 분석에 들어가기 전 값을 붙잡아 두지 않는다. */
    @Test
    fun `분석을 나오면 지금의 세로 선택으로 돌아간다`() {
        val before = PortraitMode.Auto
        val inAnalysis = orientationFor(true, before)
        val changedMeanwhile = PortraitMode.UpsideDown // 분석 중에 설정이 바뀌어 도착했다
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, inAnalysis)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, orientationFor(false, changedMeanwhile))
    }

    @Test
    fun `저장된 선택은 이름으로 되살리고 모르면 자동이다`() {
        PortraitMode.entries.forEach { assertEquals(it, portraitModeOf(it.name)) }
        assertEquals(PortraitMode.Auto, portraitModeOf(null))
        assertEquals("손상된 값으로 화면을 거꾸로 세우지 않는다", PortraitMode.Auto, portraitModeOf("Sideways"))
    }
}
