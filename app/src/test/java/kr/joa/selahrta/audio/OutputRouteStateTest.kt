package kr.joa.selahrta.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「고른 USB 기기에 실제로 붙었는가」(TF 설계 3.2, 30회차 R30-02). 요청이 아니라 실제 경로로 본다.
 */
class OutputRouteStateTest {

    private val umc = sameOutputKey(AudioDeviceInfo.TYPE_USB_DEVICE, "UMC404HD 192k", "card=1;device=0")

    private fun st(
        rejected: Boolean = false,
        type: Int? = AudioDeviceInfo.TYPE_USB_DEVICE,
        actual: String? = umc,
        requested: String? = umc,
        origin: RouteOrigin = RouteOrigin.Initial,
    ) = OutputRouteState(origin, requested, rejected, type, actual)

    @Test
    fun `고른 USB 기기에 붙었으면 확인`() {
        assertTrue(isConfirmedUsbOutput(st()))
        assertTrue(isConfirmedUsbOutput(st(origin = RouteOrigin.Event)))
    }

    @Test
    fun `요청이 거절됐으면 확인 아님 — 다른 곳으로 재생이 계속될 수 있다`() {
        assertFalse(isConfirmedUsbOutput(st(rejected = true)))
    }

    @Test
    fun `실제로 폰 스피커로 나가면 확인 아님`() {
        val speaker = sameOutputKey(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "SM-S918N", "")
        assertFalse(isConfirmedUsbOutput(st(type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, actual = speaker)))
    }

    @Test
    fun `유선이어도 3_5 잭이면 확인 아님`() {
        val jack = sameOutputKey(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "헤드폰", "")
        assertFalse(isConfirmedUsbOutput(st(type = AudioDeviceInfo.TYPE_WIRED_HEADPHONES, actual = jack, requested = jack)))
    }

    @Test
    fun `다른 USB 기기면 확인 아님`() {
        val other = sameOutputKey(AudioDeviceInfo.TYPE_USB_DEVICE, "다른 인터페이스", "card=2;device=0")
        assertFalse(isConfirmedUsbOutput(st(actual = other)))
    }

    @Test
    fun `아직 모르거나 고르지 않았으면 확인 아님`() {
        assertFalse(isConfirmedUsbOutput(st(type = null, actual = null)))
        assertFalse(isConfirmedUsbOutput(st(requested = null)))
    }
}
