package kr.joa.selahrta.audio

import kr.joa.selahrta.domain.MicKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 기기 고르는 규칙은 순수 함수라 **USB 마이크 없이도 검증된다.**
 * 실제 하드웨어가 있어야 확인되는 것은 「열렸는가」뿐이다.
 */
class InputDevicesTest {

    private fun builtIn(id: Int = 1, addr: String = "bottom") =
        InputDeviceInfo(id, "SM-S918N", MicKind.BuiltIn, "내장 마이크", listOf(48_000), addr)

    private fun usb(id: Int = 2, name: String = "iMM-6C", rates: List<Int> = listOf(48_000)) =
        InputDeviceInfo(id, name, MicKind.Usb, "USB 오디오 기기", rates)

    @Test
    fun `기기가 없으면 없다고 말한다`() {
        val c = chooseInput(emptyList(), null)
        assertNull(c.device)
        assertEquals(ChoiceReason.NoDevice, c.reason)
        assertNotNull(c.reason.noticeKo(null))
    }

    @Test
    fun `내장만 있으면 내장으로 잰다 — fallback 이 아니라 정식 입력이다`() {
        val c = chooseInput(listOf(builtIn()), null)
        assertEquals(MicKind.BuiltIn, c.device!!.kind)
        assertEquals(ChoiceReason.Auto, c.reason)
        // 「기기가 없다」는 경고가 뜨면 안 된다. 내장은 정상 상태다.
        assertNull(c.reason.noticeKo(c.device))
    }

    /**
     * **고른 것이 없으면 내장이다**(2026-09-24 담당자 지시).
     *
     * 예전에는 「외부 기기 자동 사용」 설정이 있어서, 켜 두면 USB 가
     * 꽂히는 순간 그쪽으로 갈아탔다. 그 설정을 없앴다 — 고르는 것은
     * 사람이 하고, 고른 것이 없으면 내장으로 연다.
     */
    @Test
    fun `고른 것이 없으면 USB 가 꽂혀 있어도 내장으로 잰다`() {
        val c = chooseInput(listOf(builtIn(), usb()), null)
        assertEquals(MicKind.BuiltIn, c.device!!.kind)
        assertEquals(ChoiceReason.Auto, c.reason)
    }

    @Test
    fun `사용자가 고른 기기가 가장 앞선다`() {
        val u = usb()
        val c = chooseInput(listOf(builtIn(), u), u.stableKey)
        assertEquals(u.id, c.device!!.id)
        assertEquals(ChoiceReason.UserPicked, c.reason)
    }

    @Test
    fun `내장을 골라 두면 USB 가 꽂혀도 내장을 쓴다`() {
        val b = builtIn()
        val c = chooseInput(listOf(b, usb()), b.stableKey)
        assertEquals(MicKind.BuiltIn, c.device!!.kind)
        assertEquals(ChoiceReason.UserPicked, c.reason)
    }

    @Test
    fun `골라 둔 기기가 사라지면 조용히 바꾸지 않고 알린다`() {
        val c = chooseInput(listOf(builtIn()), usb().stableKey)
        assertEquals(MicKind.BuiltIn, c.device!!.kind)
        assertEquals(ChoiceReason.PreferredMissing, c.reason)
        val notice = c.reason.noticeKo(c.device)
        assertNotNull("사실을 알려야 한다", notice)
        assertTrue("보정값이 바뀐다는 것도 알려야 한다", notice!!.contains("보정"))
    }

    @Test
    fun `id 가 바뀌어도 같은 기기로 알아본다`() {
        // USB 를 뺐다 꽂으면 id 가 바뀐다. id 로 기억하면 「사라졌다」고 오판한다.
        val before = usb(id = 7)
        val after = usb(id = 19)
        assertEquals(before.stableKey, after.stableKey)
        val c = chooseInput(listOf(builtIn(), after), before.stableKey)
        assertEquals(ChoiceReason.UserPicked, c.reason)
        assertEquals(19, c.device!!.id)
    }

    @Test
    fun `이름이 다른 USB 는 다른 기기다`() {
        val a = usb(name = "iMM-6C")
        val b = usb(name = "Umik-1")
        assertTrue(a.stableKey != b.stableKey)
        // 다른 마이크의 보정값이 적용되면 완전히 틀린 음압이 나온다.
        val c = chooseInput(listOf(builtIn(), b), a.stableKey)
        assertEquals(ChoiceReason.PreferredMissing, c.reason)
    }

    @Test
    fun `샘플레이트를 모르면 될 수도 있다고 본다`() {
        // 기기가 목록을 안 알리는 경우가 흔하다. 미리 배제하면 멀쩡한
        // 마이크를 못 쓰게 된다 — 열어 보면 알 수 있다.
        //
        // 자동 고르기를 없앤 뒤로 이 값은 **고르는 데 쓰이지 않는다.**
        // 화면이 「이 기기가 48kHz 를 알리는가」를 적는 데만 쓴다.
        assertTrue(usb(rates = emptyList()).supports48k)
        assertTrue(usb(rates = listOf(44_100, 48_000)).supports48k)
        assertTrue(!usb(rates = listOf(44_100)).supports48k)
    }

    /**
     * 내장 마이크는 **한 기기로 묶인다**(2026-09-23 결정).
     *
     * 예전에는 주소까지 열쇠에 넣어 하단·상단을 다른 기기로 다뤘다.
     * 실측이 그 전제를 무너뜨렸다 — 「상단」을 골라도 하단이 함께 켜지고
     * (활성=[22,24]), 주소가 가리키는 위치조차 실제와 달랐다.
     *
     * **이 시험이 지키는 것은 보정값이다.** 열쇠에 주소가 남으면 라우팅이
     * 한 번 넘어가는 것만으로 「모르는 기기」가 되어 저장된 보정이 조용히
     * 떨어져 나간다.
     */
    @Test
    fun `내장 마이크는 주소가 달라도 같은 기기로 묶인다`() {
        val bottom = builtIn(id = 22, addr = "bottom")
        val back = builtIn(id = 24, addr = "back")
        assertEquals("같은 폰의 내장이면 같은 열쇠다", bottom.stableKey, back.stableKey)
        assertEquals("이름은 기기명 그대로다", "SM-S918N", bottom.displayName)
        assertEquals("위치를 붙이지 않는다", "SM-S918N", back.displayName)
    }

    /**
     * 묶은 뒤에도 **탐색은 둘을 갈라 볼 수 있어야 한다.**
     *
     * 갈라지는지 묻는 일까지 접어 버리면, 정말 갈라 주는 폰이 나와도
     * 알아채지 못한다. 보정에 쓰는 열쇠와 탐색에 쓰는 열쇠를 따로 둔다.
     */
    @Test
    fun `탐색 열쇠는 내장도 주소로 가른다`() {
        val bottom = builtIn(id = 22, addr = "bottom")
        val back = builtIn(id = 24, addr = "back")
        assertTrue("후보를 가릴 수 있어야 한다", bottom.probeKey != back.probeKey)
        assertTrue("어느 후보인지 보여야 한다", bottom.probeLabel.contains("bottom"))
        assertTrue(back.probeLabel.contains("back"))
    }

    /**
     * 외부 기기는 **여전히 주소로 가른다.** 같은 이름의 인터페이스를 둘
     * 꽂으면 주소만이 둘을 구별한다 — 묶으면 다른 마이크에 같은 보정이 간다.
     */
    @Test
    fun `외부 기기는 주소로 구별한다`() {
        val a = usb(id = 30).copy(address = "usb:1")
        val b = usb(id = 31).copy(address = "usb:2")
        assertTrue("서로 다른 기기여야 한다", a.stableKey != b.stableKey)

        val c = chooseInput(listOf(a, b), b.stableKey)
        assertEquals(31, c.device!!.id)
        assertEquals(ChoiceReason.UserPicked, c.reason)
    }

    @Test
    fun `주소를 사람 말로 옮긴다`() {
        assertEquals("하단", micPositionKo("bottom"))
        assertEquals("후면", micPositionKo("back"))
        assertNull("주소가 없으면 붙일 말도 없다", micPositionKo(""))
        // 모르는 주소는 지어내지 않고 그대로 보여 준다.
        assertEquals("sidecar", micPositionKo("sidecar"))
    }

    /**
     * **기기가 빠지면 멈춘다**(2026-09-24 담당자 지시).
     *
     * 예전에는 「내장 마이크로 전환」 정책이 있어서, 빠진 뒤에도 규칙대로
     * 다시 열었다. 그 길을 통째로 없앴다 — 그 시점부터 다른 마이크·다른
     * 보정값인 값이 같은 Leq·MAX 에 합쳐지기 때문이다. 그래서 여기서
     * 시험할 전환 규칙 자체가 없어졌다(CaptureControllerTest 가 「멈추고
     * 알린다」를 지킨다).
     */
    @Test
    fun `고른 기기가 없으면 내장으로 연다`() {
        val c = chooseInput(listOf(usb(2, "iMM-6C"), builtIn(1)), preferredKey = null)
        assertEquals(MicKind.BuiltIn, c.device?.kind)
    }

    /** 내장이 없는 기기에서도 터지지 않는다. */
    @Test
    fun `내장이 없으면 남은 것으로 연다`() {
        val c = chooseInput(listOf(usb(2, "iMM-6C")), preferredKey = null)
        assertEquals(MicKind.Usb, c.device?.kind)
    }
}
