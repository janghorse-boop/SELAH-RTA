package kr.joa.selahrta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **주파수를 적을 때 없는 정밀도를 만들지 않는다**(2026-10-01 담당자 지시).
 *
 * > 20.00 kHz는 20 kHz로 수정해주세요. / 1.00 kHz도 1 kHz로 수정해주세요.
 *
 * 소수 둘로 못박아 두어 **1kHz 가 「1.00 kHz」**로 적혔다. 자릿수는
 * 3150Hz 를 「3.15 kHz」로 적으려고 둔 것인데, 떨어지는 값까지 끌고 다니면
 * **잰 것보다 정밀해 보인다.**
 *
 * 이 앱에서 그것은 가볍지 않다 — 숫자의 자릿수가 「이만큼 믿어도 된다」는
 * 말로 읽히는 화면이기 때문이다.
 */
class FormatHzTest {

    /** 떨어지는 값은 **정수로**. 이것이 이번에 고친 자리다. */
    @Test
    fun `떨어지는 kHz 는 소수를 안 붙인다`() {
        assertEquals("1", formatHz(1_000.0))
        assertEquals("2", formatHz(2_000.0))
        assertEquals("20", formatHz(20_000.0))
    }

    /** **한 자리만 필요하면 한 자리만.** 1.50 이 아니라 1.5 다. */
    @Test
    fun `남는 자리만 떼어 낸다`() {
        assertEquals("1.5", formatHz(1_500.0))
        assertEquals("1.6", formatHz(1_600.0))
        assertEquals("6.3", formatHz(6_300.0))
    }

    /** **쓸모 있는 자리는 지킨다.** 1/3 옥타브 중심이 여기 든다. */
    @Test
    fun `두 자리가 필요하면 두 자리로 적는다`() {
        assertEquals("1.25", formatHz(1_250.0))
        assertEquals("3.15", formatHz(3_150.0))
        assertEquals("12.5", formatHz(12_500.0))
    }

    /** 1kHz 아래는 **Hz 정수** 그대로다. 여기는 안 건드렸다. */
    @Test
    fun `1kHz 아래는 정수 Hz 다`() {
        assertEquals("20", formatHz(20.0))
        assertEquals("762", formatHz(762.4))
        assertEquals("999", formatHz(999.0))
    }

    /** 단위도 같은 경계에서 갈린다 — 숫자와 단위가 따로 놀면 안 된다. */
    @Test
    fun `단위가 숫자와 같은 경계를 쓴다`() {
        assertEquals("Hz", hzUnit(999.0))
        assertEquals("kHz", hzUnit(1_000.0))
    }
}
