package kr.joa.selahrta.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * **뽑았다 꽂으면 같은 기기인데 id 가 바뀐다**(2026-10-01 실측).
 *
 * 검증지시서의 사례 4·5(USB-C 를 뽑았다 다시 꽂기)를 실기기에서 돌리다
 * 찾았다. 출력이 제대로 D3V 로 돌아왔는데도 화면이 이렇게 적고 있었다:
 *
 * ```
 * 출력 USB-Audio - ADAM Audio D3V — 고른 곳(USB-Audio - ADAM Audio D3V)과 다릅니다.
 * ```
 *
 * 같은 이름을 두고 「다릅니다」라고 말한다. 까닭은 **id 로 견줬기**
 * 때문이다 — 안드로이드는 꽂을 때마다 새 id 를 준다(실기기에서 546 → 849).
 *
 * **멀쩡한데 틀렸다고 말하는 경고는 경고를 죽인다.** 다음에 진짜로
 * 어긋났을 때 사람이 안 믿는다.
 *
 * 그래서 사람이 「같은 기기」라고 부르는 것 — **종류·이름·주소** — 로
 * 견준다.
 */
class SameOutputKeyTest {

    private val d3v = AudioDeviceInfo.TYPE_USB_HEADSET to "USB-Audio - ADAM Audio D3V"

    /** **이것이 이번에 고친 자리다.** 꽂을 때마다 바뀌는 id 는 안 본다. */
    @Test
    fun `뽑았다 꽂아 id 가 바뀌어도 같은 기기다`() {
        assertEquals(
            sameOutputKey(d3v.first, d3v.second, "card=1;device=0"),
            sameOutputKey(d3v.first, d3v.second, "card=1;device=0"),
        )
    }

    /** **다른 기기는 갈라야 한다.** 흐리게 보느라 진짜 어긋남을 놓치면 안 된다. */
    @Test
    fun `폰 스피커와 USB 는 다르다`() {
        assertNotEquals(
            sameOutputKey(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "SM-S918N", ""),
            sameOutputKey(d3v.first, d3v.second, "card=1;device=0"),
        )
    }

    /** 같은 종류라도 **이름이 다르면 다른 기기**다 — 인터페이스가 둘일 수 있다. */
    @Test
    fun `같은 종류라도 이름이 다르면 다르다`() {
        assertNotEquals(
            sameOutputKey(d3v.first, "USB-Audio - UMC404HD", "card=1;device=0"),
            sameOutputKey(d3v.first, d3v.second, "card=1;device=0"),
        )
    }

    /** **주소도 본다.** 카드가 둘이면 이름이 같아도 다른 자리다. */
    @Test
    fun `주소가 다르면 다르다`() {
        assertNotEquals(
            sameOutputKey(d3v.first, d3v.second, "card=1;device=0"),
            sameOutputKey(d3v.first, d3v.second, "card=2;device=0"),
        )
    }
}
