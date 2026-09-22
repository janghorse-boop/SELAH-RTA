package kr.joa.selahrta.audio

import kr.joa.selahrta.domain.MicKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **케이스가 막은 것을 마이크의 응답으로 적지 않게 한다.**
 *
 * 폰 케이스는 후면 마이크 구멍을 막거나 좁힌다. 하단은 대개 트여 있다.
 * 그 차이를 모르고 재면 케이스가 만든 감쇠가 보정에 들어가고, 케이스를
 * 바꾸는 순간 그 보정이 틀린 값이 된다(S23 지시서 6장의 「케이스 등
 * 음향 조건 변경」).
 */
class BuiltInMicNoticeTest {

    private fun mic(addr: String, kind: MicKind = MicKind.BuiltIn) = InputDeviceInfo(
        id = 1,
        productName = "SM-S918N",
        kind = kind,
        typeKo = "내장 마이크",
        address = addr,
    )

    @Test
    fun `뒤쪽 주소를 알아본다`() {
        assertTrue(isRearAddress("back"))
        assertTrue(isRearAddress("rear"))
        assertTrue("대소문자·공백을 가리지 않는다", isRearAddress(" BACK "))
        assertFalse(isRearAddress("bottom"))
        assertFalse(isRearAddress("top"))
        assertFalse(isRearAddress(""))
    }

    @Test
    fun `후면을 고르면 케이스를 벗기라고 한다`() {
        val s = builtInMicNoticeKo(mic("back"))
        assertNotNull(s)
        assertTrue("케이스를 말해야 한다", s!!.contains("케이스"))
        assertTrue("왜 그런지 적어야 한다", s.contains("감쇠"))
    }

    /** **따로 재라**는 것도 함께 말한다 — 두 마이크를 같은 자리에 둘 수 없다. */
    @Test
    fun `따로 재라고 말한다`() {
        val s = builtInMicNoticeKo(mic("back"))!!
        assertTrue(s.contains("따로"))
    }

    @Test
    fun `하단에는 아무 말도 하지 않는다`() {
        assertNull(builtInMicNoticeKo(mic("bottom")))
        assertNull(builtInMicNoticeKo(mic("top")))
    }

    /** **모르는 주소면 짐작하지 않는다.** 케이스를 벗기라고 할 근거가 없다. */
    @Test
    fun `모르는 주소면 말하지 않는다`() {
        assertNull(builtInMicNoticeKo(mic("")))
        assertNull(builtInMicNoticeKo(mic("mic3")))
    }

    @Test
    fun `외부 기기에는 해당 없다`() {
        assertNull(builtInMicNoticeKo(mic("back", MicKind.Usb)))
        assertNull(builtInMicNoticeKo(null))
    }

    /**
     * **탐색 결과가 「갈리지 않는다」면 그것도 함께 말한다.**
     *
     * 2026-09-23 실측: S23 은 후면을 골라도 하단이 함께 활성이다.
     * 케이스를 벗겨도 그 사실은 그대로다.
     */
    @Test
    fun `갈리지 않으면 그 사실도 말한다`() {
        val s = builtInMicNoticeKo(mic("back"), MicSeparation.LogicalOnly)!!
        assertTrue("함께 켜진다는 것을 말해야 한다", s.contains("하단이 함께"))

        val sep = builtInMicNoticeKo(mic("back"), MicSeparation.Separable)!!
        assertFalse("갈리면 그 말은 하지 않는다", sep.contains("하단이 함께"))
    }

    /** 아직 안 재 봤으면 그 이야기는 꺼내지 않는다. */
    @Test
    fun `탐색 전에는 그 이야기를 꺼내지 않는다`() {
        val s = builtInMicNoticeKo(mic("back"), null)!!
        assertFalse(s.contains("하단이 함께"))
        assertTrue("케이스 이야기는 그대로 한다", s.contains("케이스"))
    }

    /** **화면에 마크다운이 새지 않는다.** 서식 없는 Text 로 그려진다. */
    @Test
    fun `별표가 새지 않는다`() {
        val s = builtInMicNoticeKo(mic("back"), MicSeparation.LogicalOnly)!!
        assertFalse("마크다운 강조가 글자로 보이면 안 된다", s.contains("**"))
    }
}
