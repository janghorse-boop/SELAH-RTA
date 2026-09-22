package kr.joa.selahrta.audio

import kr.joa.selahrta.domain.MicKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **찾을 수 없는 것을 찾게 만들지 않는다.**
 *
 * USB 오디오 지시서 6·7·14장의 문구는 오디오 인터페이스를 쓰는 사람에게
 * 한 말이다 — 「GAIN 을 낮춰주세요」, 「+48 V 팬텀전원을 확인해주세요」.
 * **내장 마이크에는 둘 다 없다.**
 */
class InputSignalNoticeTest {

    @Test
    fun `자르는 쪽이 먼저다`() {
        assertEquals(
            InputSignalState.Clipping,
            inputSignalState(clipping = true, noSignal = true),
        )
        assertEquals(
            InputSignalState.NoSignal,
            inputSignalState(clipping = false, noSignal = true),
        )
        assertEquals(
            InputSignalState.Ok,
            inputSignalState(clipping = false, noSignal = false),
        )
    }

    @Test
    fun `평범하면 아무 말도 하지 않는다`() {
        assertNull(inputSignalNoticeKo(InputSignalState.Ok, MicKind.Usb, "UMC404HD"))
        assertNull(inputSignalNoticeKo(InputSignalState.Ok, MicKind.BuiltIn, "SM-S918N"))
    }

    // ------------------------------------------------------------------

    @Test
    fun `외부 기기에는 GAIN 과 팬텀전원을 말한다`() {
        val clip = inputSignalNoticeKo(InputSignalState.Clipping, MicKind.Usb, "UMC404HD")!!
        assertTrue("GAIN 을 말해야 한다", clip.contains("GAIN"))
        assertTrue("어느 기기인지 적어야 한다", clip.contains("UMC404HD"))

        val none = inputSignalNoticeKo(InputSignalState.NoSignal, MicKind.Usb, "UMC404HD")!!
        assertTrue("팬텀전원을 말해야 한다", none.contains("48 V") || none.contains("팬텀"))
        assertTrue(none.contains("UMC404HD"))
    }

    /**
     * **기기 이름을 하드코딩하지 않는다**(지시서 19장 3).
     *
     * 다른 인터페이스를 꽂아도 그 이름이 나와야 한다.
     */
    @Test
    fun `기기 이름을 그대로 쓴다`() {
        val s = inputSignalNoticeKo(InputSignalState.Clipping, MicKind.Usb, "Focusrite Scarlett")!!
        assertTrue(s.contains("Focusrite Scarlett"))
        assertFalse("다른 제품 이름이 섞이면 안 된다", s.contains("UMC404HD"))
    }

    // ------------------------------------------------------------------

    @Test
    fun `내장 마이크에는 없는 것을 찾게 하지 않는다`() {
        for (state in listOf(InputSignalState.Clipping, InputSignalState.NoSignal)) {
            val s = inputSignalNoticeKo(state, MicKind.BuiltIn, "SM-S918N")
            assertNotNull(s)
            assertFalse("$state: 내장 마이크에 GAIN 노브는 없다", s!!.contains("GAIN"))
            assertFalse("$state: 내장 마이크에 팬텀전원은 없다", s.contains("팬텀"))
            assertFalse("$state: 48V 도 없다", s.contains("48 V"))
        }
    }

    /** 잘렸다는 **뜻**은 두 쪽 모두에 적어야 한다 — 값이 틀어진다는 사실이다. */
    @Test
    fun `잘리면 값이 틀어진다는 것을 말한다`() {
        for (kind in listOf(MicKind.BuiltIn, MicKind.Usb)) {
            val s = inputSignalNoticeKo(InputSignalState.Clipping, kind, "X")!!
            assertTrue("$kind: 값이 어떻게 되는지 적어야 한다", s.contains("낮게"))
        }
    }
}
