package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **녹음 중 실제로 소리를 받는 마이크 조합**(개발지시서 4·18장).
 *
 * 여기서 지키는 한 줄은 「**빈 목록은 「없다」가 아니라 「모른다」다**」.
 * 이 저장소가 같은 모양으로 한 번 데였다 — 빈 주소를 「같다」로 읽어
 * `bottom` 의 감도를 `back` 에 걸었다(CAR-03).
 */
class ActiveMicComboTest {

    private fun mic(id: Int, group: Int = 0, index: Int = 0) = ActiveMicInfo(
        id = id,
        address = "",
        description = "",
        location = 0,
        directionality = 0,
        group = group,
        indexInTheGroup = index,
    )

    // ── 이름 굳히기 ─────────────────────────────────────────

    @Test
    fun `조합을 정렬해서 한 이름으로 굳힌다`() {
        assertEquals("5+7", activeMicComboKey(listOf(mic(7), mic(5))))
    }

    /**
     * 안드로이드가 주는 차례는 보장되지 않는다. 그대로 이으면 **같은
     * 조합이 회차마다 다른 이름**이 되어, 바뀌지도 않았는데 바뀌었다고
     * 말하게 된다.
     */
    @Test
    fun `차례가 달라도 같은 이름이다`() {
        assertEquals(
            activeMicComboKey(listOf(mic(5), mic(7))),
            activeMicComboKey(listOf(mic(7), mic(5))),
        )
    }

    @Test
    fun `같은 마이크가 두 번 와도 한 번으로 센다`() {
        assertEquals("5", activeMicComboKey(listOf(mic(5), mic(5, index = 2))))
    }

    @Test
    fun `모르면 빈 이름이다`() {
        assertEquals("", activeMicComboKey(emptyList()))
    }

    // ── 사람에게 보일 말 ───────────────────────────────────

    @Test
    fun `모르는 것을 없다고 적지 않는다`() {
        assertEquals("확인 불가", activeMicComboKo(emptyList()))
    }

    @Test
    fun `그룹과 차례까지 적는다`() {
        val s = activeMicComboKo(listOf(mic(7, group = 0, index = 2), mic(5)))
        assertTrue(s, s.startsWith("Mic 5"))
        assertTrue(s, s.contains("Mic 7(그룹 0·2)"))
    }

    // ── 바뀌었는가 ─────────────────────────────────────────

    @Test
    fun `조합이 바뀌면 그 말을 한다`() {
        val ko = activeMicChangeKo(listOf(mic(5), mic(7)), listOf(mic(5)))
        assertNotNull(ko)
        assertTrue(ko!!, ko.contains("5+7"))
        assertTrue(ko, ko.contains("보정값"))
    }

    @Test
    fun `그대로면 아무 말도 하지 않는다`() {
        assertNull(activeMicChangeKo(listOf(mic(5), mic(7)), listOf(mic(7), mic(5))))
    }

    /**
     * **한쪽이라도 모르면 견주지 않는다.** 안드로이드 9 아래이거나
     * 제조사가 말해 주지 않으면 빈 목록이 온다 — 그것을 「마이크가
     * 사라졌다」로 읽으면, 물어볼 수 없는 기기에서 경고가 늘 뜬다.
     */
    @Test
    fun `모르는 쪽이 끼면 바뀌었다고 하지 않는다`() {
        assertNull("몰랐다가 알게 된 것은 변화가 아니다", activeMicChangeKo(emptyList(), listOf(mic(5))))
        assertNull("알다가 모르게 된 것도 변화가 아니다", activeMicChangeKo(listOf(mic(5)), emptyList()))
        assertNull(activeMicChangeKo(emptyList(), emptyList()))
    }

    @Test
    fun `화면 문구에 마크다운이 없다`() {
        val ko = activeMicChangeKo(listOf(mic(5), mic(7)), listOf(mic(5)))!!
        assertTrue(ko, !ko.contains("**"))
        assertEquals("확인 불가", activeMicComboKo(emptyList()))
    }
}
