package kr.joa.selahrta.audio

import kr.joa.selahrta.domain.MicKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **USB 에서는 폰 속성이 「안 된다」고 해도 시도한다**(2026-09-30).
 *
 * `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` 는 **이 폰의 내장 경로**에
 * 대한 답이다. 소리가 **USB 인터페이스에서 오는** 경우는 다른 이야기인데,
 * 그 값 하나로 **시도조차 안 하고** 음성인식 경로로 내려가고 있었다.
 *
 * ## 재서 알았다
 *
 * SM-S918N + Behringer UMC404HD 에서 폰 속성은 `false` 인데 USB 입력의
 * `UNPROCESSED` 가 **열리고 데이터도 들어왔다**:
 *
 * ```
 * 기기 속성 SUPPORT_AUDIO_SOURCE_UNPROCESSED=false
 * source=9(UNPROCESSED) 상태=3 읽음=81920 실제기기=USB-Audio - UMC404HD 192k
 * ```
 *
 * ## 이 시험이 **주장하지 않는 것**
 *
 * **「가공이 없다」가 아니다.** 상수를 받아 주었다는 것뿐이고, 실제로
 * 가공이 도는지는 신호로 재야 안다 — 교정 마법사 2단계가 그 일을 한다.
 * 여기서 지키는 것은 **쓸 수 있는 경로를 안 써 보고 포기하지 않는 것**이다.
 */
class UnprocessedChoiceTest {

    private fun decide(phoneProperty: Boolean, kind: MicKind?) =
        shouldTryUnprocessed(phoneProperty, kind)

    @Test
    fun `폰이 된다고 하면 무엇이든 시도한다`() {
        assertTrue(decide(true, MicKind.BuiltIn))
        assertTrue(decide(true, MicKind.Usb))
        assertTrue(decide(true, null))
    }

    @Test
    fun `폰이 안 된다고 해도 USB 는 시도한다`() {
        assertTrue("USB 를 안 써 보고 포기한다", decide(false, MicKind.Usb))
    }

    /** **내장 마이크는 그대로 둔다.** 폰 속성이 답하는 바로 그 경로다. */
    @Test
    fun `폰이 안 된다고 하면 내장은 시도하지 않는다`() {
        assertFalse(decide(false, MicKind.BuiltIn))
    }

    /** 어느 기기인지 모르면 **폰 속성을 따른다.** 짐작해서 올리지 않는다. */
    @Test
    fun `기기를 모르면 폰 속성을 따른다`() {
        assertFalse(decide(false, null))
    }
}
