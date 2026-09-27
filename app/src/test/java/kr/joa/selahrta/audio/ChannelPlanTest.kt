package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * **고른 채널이 들어가는 가장 작은 수로 연다.**
 *
 * USB 오디오 지시서 5장: *「UMC404HD 라는 이유로 4채널을 하드코딩하지
 * 않는다. 실제 접근 가능한 채널 수를 기준으로 한다.」*
 */
class ChannelPlanTest {

    @Test
    fun `모르면 모노로 연다`() {
        assertEquals(ChannelPlan(1, 0), planChannels(emptyList(), 0))
        assertEquals("모르는데 3번을 골랐어도 모노다", ChannelPlan(1, 0), planChannels(emptyList(), 3))
    }

    @Test
    fun `내장 마이크는 지금까지와 같다`() {
        assertEquals(ChannelPlan(1, 0), planChannels(listOf(1), 0))
    }

    /** 0번을 고르면 **모노로 열린다** — 넉넉히 열 까닭이 없다. */
    @Test
    fun `0번이면 가장 작은 것으로 연다`() {
        assertEquals(ChannelPlan(1, 0), planChannels(listOf(1, 2, 4), 0))
    }

    @Test
    fun `고른 번호가 들어가는 가장 작은 수를 고른다`() {
        assertEquals(ChannelPlan(2, 1), planChannels(listOf(1, 2, 4), 1))
        assertEquals(ChannelPlan(4, 2), planChannels(listOf(1, 2, 4), 2))
        assertEquals(ChannelPlan(4, 3), planChannels(listOf(1, 2, 4), 3))
    }

    @Test
    fun `목록이 정렬돼 있지 않아도 된다`() {
        assertEquals(ChannelPlan(2, 1), planChannels(listOf(4, 1, 2), 1))
    }

    /**
     * **기기를 바꿔 번호가 사라지면 가장 큰 것으로 당긴다.**
     *
     * 4채널을 쓰던 사람이 2채널 기기를 꽂았을 때, 조용히 1번으로
     * 되돌리는 것보다 2번이 그 사람이 쓰던 것에 가깝다.
     */
    @Test
    fun `고른 번호가 사라지면 가장 큰 것으로 당긴다`() {
        assertEquals(ChannelPlan(2, 1), planChannels(listOf(1, 2), 3))
        assertEquals(ChannelPlan(1, 0), planChannels(listOf(1), 3))
    }

    @Test
    fun `이상한 값은 걸러 낸다`() {
        assertEquals(ChannelPlan(2, 1), planChannels(listOf(0, -1, 2), 1))
        assertEquals("음수 번호는 0번으로 본다", ChannelPlan(1, 0), planChannels(listOf(1, 2), -5))
        assertEquals("중복은 한 번만 센다", ChannelPlan(2, 1), planChannels(listOf(2, 2, 2), 1))
    }

    @Test
    fun `말이 안 되는 계획은 만들지 않는다`() {
        assertNotNull(runCatching { ChannelPlan(0, 0) }.exceptionOrNull())
        assertNotNull(runCatching { ChannelPlan(2, 2) }.exceptionOrNull())
        assertNotNull(runCatching { ChannelPlan(2, -1) }.exceptionOrNull())
    }

    /**
     * **1번을 고르면 모노로 열린다** — 그래서 채널별 레벨 막대가 뜨지
     * 않는다.
     *
     * 버스 대역과 버퍼를 아끼려는 설계이고, 실기기(UMC404HD, 2026-09-23)에서
     * 그대로 동작하는 것을 확인했다. 다만 **「마이크가 몇 번에 꽂혔나」를
     * 찾을 때는 걸림돌**이다 — 가장 흔한 기본값(1번)에서 견줄 채널이
     * 하나도 없다.
     *
     * 화면이 그 사실과 길을 적어 둔다(설정의 안내 문구). 이 시험은
     * 그 안내가 **왜 있는지**를 코드로 남겨, 누가 planChannels 를 고쳤을
     * 때 안내도 같이 손보게 한다.
     */
    @Test
    fun `1번을 고르면 한 채널만 연다`() {
        val plan = planChannels(listOf(1, 2, 3, 4), 0)
        assertEquals(1, plan.count)
        assertEquals(0, plan.index)
    }

    /** 마지막 번호를 고르면 **모든 입력이 함께 열린다.** 찾을 때 쓰는 길이다. */
    @Test
    fun `마지막 번호를 고르면 모든 채널이 열린다`() {
        val plan = planChannels(listOf(1, 2, 3, 4), 3)
        assertEquals(4, plan.count)
        assertEquals(3, plan.index)
    }
}
