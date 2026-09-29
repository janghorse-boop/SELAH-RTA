package kr.joa.selahrta.audio

import android.media.AudioManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **바깥 사정으로 소리를 끊어야 하는가**(지시서 §5 `Interrupted`).
 *
 * 판단만 본다 — 실제로 포커스를 얻고 놓는 일은 안드로이드가 하고,
 * 그쪽은 `AudioInterruptionsTest`(계측)와 실기기가 본다. 이 시험은
 * **무엇을 안 보는지** 먼저 적는다.
 */
class InterruptionReasonTest {

    /**
     * **전화가 오면 멈춘다.** 영영 잃든 잠깐 잃든 같다 — 지시서는
     * 백그라운드·잠금·프로세스 종료 뒤 **저절로 다시 틀지 않는다**고
     * 못박았고, 포커스도 같은 자리다.
     */
    @Test
    fun `포커스를 잃으면 멈춘다`() {
        assertNotNull(focusLossReasonKo(AudioManager.AUDIOFOCUS_LOSS))
        assertNotNull(focusLossReasonKo(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
    }

    /**
     * **눌러서 작게 내보내는 것(ducking)도 멈춘다.**
     *
     * 소리를 줄여 계속 내보내면 **재는 사람이 모르는 채로 크기가
     * 달라진다.** 알림 하나에 3dB 가 깎인 핑크 잡음으로 방을 재고
     * 그것을 방의 응답이라고 읽게 된다 — 끊기는 것보다 나쁘다.
     */
    @Test
    fun `눌러 내보내라고 해도 멈춘다`() {
        val why = focusLossReasonKo(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertNotNull("눌러 내보내면 잰 값이 조용히 틀어진다", why)
        assertTrue("왜 멈췄는지 크기 얘기를 해야 한다", why!!.contains("크기"))
    }

    /** **되찾아도 저절로 다시 틀지 않는다**(지시서 §5). */
    @Test
    fun `포커스를 되찾는 것은 멈출 일이 아니다`() {
        assertNull(focusLossReasonKo(AudioManager.AUDIOFOCUS_GAIN))
        assertNull(focusLossReasonKo(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT))
    }

    /** 모르는 값이 오면 **건드리지 않는다.** 멋대로 끊는 편이 더 나쁘다. */
    @Test
    fun `모르는 값은 그냥 둔다`() {
        assertNull(focusLossReasonKo(12345))
    }

    /**
     * **까닭을 사람 말로 적는다.** 「신호가 갑자기 멎었다」만 남으면
     * 고장인지 바깥 사정인지 가릴 수 없다.
     */
    @Test
    fun `까닭이 사람 말로 적힌다`() {
        val why = focusLossReasonKo(AudioManager.AUDIOFOCUS_LOSS)!!
        assertTrue("무엇이 멈췄는지 안 적혔다: $why", why.contains("신호"))
        assertTrue("너무 짧다: $why", why.length >= 10)
    }

    /**
     * **출력이 바뀌면 멈춘다.**
     *
     * 이어폰이나 USB 를 뽑으면 안드로이드가 소리를 **폰 스피커로
     * 돌린다.** PA 에 물려 핑크 잡음을 틀어 둔 채로 케이블이 빠지면
     * 예배당에 그 소리가 그대로 터져 나온다.
     */
    @Test
    fun `출력이 바뀐 까닭도 사람 말로 적힌다`() {
        assertTrue(
            "스피커로 나갈 뻔했다는 말이 없다: $BECOMING_NOISY_REASON_KO",
            BECOMING_NOISY_REASON_KO.contains("스피커"),
        )
    }
}
