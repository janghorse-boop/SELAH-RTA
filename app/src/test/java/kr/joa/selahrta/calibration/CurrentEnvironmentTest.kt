package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.audio.EffectState
import kr.joa.selahrta.audio.EffectsReport
import kr.joa.selahrta.audio.InputDeviceInfo
import kr.joa.selahrta.audio.OpenedFormat
import kr.joa.selahrta.audio.PcmEncoding
import kr.joa.selahrta.domain.MicKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * **「지금 열린 경로」를 프로파일이 아는 꼴로 옮긴다.**
 *
 * 여기가 틀리면 [judgeProfileApply] 가 엉뚱한 것끼리 견주게 되고,
 * 그 결과는 「왜 안 걸리지」 또는 **더 나쁘게** 「엉뚱한 마이크에
 * 걸렸는데 아무도 모른다」가 된다.
 */
class CurrentEnvironmentTest {

    /**
     * **열쇠는 실제 `stableKey` 처럼 만든다**(독립 재검토 CA-R03).
     *
     * 예전 fixture 는 `BuiltIn|SM-S918N|back` 처럼 손으로 적었는데,
     * 내장 마이크의 실제 열쇠에는 **주소가 없다**. 실제와 다른 fixture 로
     * 시험이 통과해, 「열쇠에서 주소를 뽑는다」는 틀린 동작을 못박고
     * 있었다.
     */
    private fun opened(
        deviceKey: String = "BuiltIn|SM-S918N",
        routedAddress: String = "back",
        sampleRate: Int = 48_000,
        channelCount: Int = 1,
        channelIndex: Int = 0,
        micKind: MicKind = MicKind.BuiltIn,
        source: CaptureSource = CaptureSource.Unprocessed,
    ) = OpenedFormat(
        micKind = micKind,
        sampleRate = sampleRate,
        channelCount = channelCount,
        channelIndex = channelIndex,
        encoding = PcmEncoding.Float,
        audioSource = source,
        bufferSizeBytes = 4096,
        deviceLabel = "SM-S918N",
        deviceKey = deviceKey,
        routedAddress = routedAddress,
        unprocessedSupported = true,
        effects = EffectsReport(
            EffectState("AGC", available = false, wasEnabled = false, disabled = true),
            EffectState("NS", available = false, wasEnabled = false, disabled = true),
            EffectState("AEC", available = false, wasEnabled = false, disabled = true),
        ),
    )

    private fun input(address: String, name: String = "SM-S918N") = InputDeviceInfo(
        id = 1,
        productName = name,
        kind = MicKind.BuiltIn,
        typeKo = "내장",
        address = address,
    )

    private val build = DeviceBuildInfo("samsung", "SM-S918N", "UP1A.231005.007")

    /**
     * **열린 경로가 들고 있는 주소가 먼저다**(독립 재검토 CA-R03).
     *
     * 목록은 내장 마이크를 한 줄로 접으며 주소를 지운다. 거기서 찾으면
     * 언제나 비어 있어, 「자리가 바뀌었는가」 검사가 통째로 돌지 않았다.
     */
    @Test
    fun `열린 경로의 주소를 쓴다`() {
        // 목록이 접혀 주소가 비어 있어도 열린 경로의 것을 쓴다.
        val env = currentProfileEnvironment(opened(), listOf(input("")), build)
        assertEquals("back", env.deviceAddress)
    }

    @Test
    fun `열린 경로에 없으면 목록에서 가져온다`() {
        val env = currentProfileEnvironment(
            opened(routedAddress = ""),
            listOf(input("back")),
            build,
        )
        assertEquals("back", env.deviceAddress)
    }

    /**
     * **열쇠를 갈라 주소로 읽지 않는다.**
     *
     * 내장 열쇠의 마지막 토막은 주소가 아니라 **제품명**이다. 그것을
     * 주소로 읽으면 「SM-S918N 자리에서 쟀다」가 된다.
     */
    @Test
    fun `모르면 빈 채로 둔다`() {
        val env = currentProfileEnvironment(opened(routedAddress = ""), emptyList(), build)
        assertEquals("", env.deviceAddress)
    }

    /**
     * **주소가 내장 마이크 둘을 가르는 유일한 단서다.**
     *
     * 갤럭시 S23 은 하단·후면을 둘 다 모델명으로 알린다. 주소가 비면
     * 두 프로파일이 같은 경로로 보여 서로 걸린다.
     */
    @Test
    fun `하단과 후면이 다른 환경으로 나온다`() {
        // **열쇠는 같다**(내장은 주소를 빼고 묶는다). 가르는 것은 주소다.
        val back = currentProfileEnvironment(opened(routedAddress = "back"), emptyList(), build)
        val bottom = currentProfileEnvironment(opened(routedAddress = "bottom"), emptyList(), build)
        assertNotEquals(back.deviceAddress, bottom.deviceAddress)
        assertEquals("열쇠까지 갈리지는 않는다", back.key(), bottom.key())
    }

    /** 이름에 막대가 들어도 열린 경로의 주소가 그대로 온다. */
    @Test
    fun `이름에 막대가 들어도 주소가 옳다`() {
        val weird = "BuiltIn|이상한|이름"
        val env = currentProfileEnvironment(
            opened(deviceKey = weird, routedAddress = "bottom"),
            emptyList(),
            build,
        )
        assertEquals("bottom", env.deviceAddress)
    }

    @Test
    fun `열린 값을 그대로 쓴다`() {
        val env = currentProfileEnvironment(
            opened(sampleRate = 44_100, channelCount = 2, channelIndex = 1),
            listOf(input("back")),
            build,
        )
        assertEquals(44_100, env.sampleRate)
        assertEquals(2, env.channelCount)
        assertEquals(1, env.channelIndex)
    }

    @Test
    fun `기기 신원이 그대로 실린다`() {
        val env = currentProfileEnvironment(opened(), listOf(input("back")), build)
        assertEquals("samsung", env.manufacturer)
        assertEquals("SM-S918N", env.model)
        assertEquals("UP1A.231005.007", env.osBuild)
    }

    /**
     * **주소가 없는 기기**(USB 인터페이스 등)도 빈 문자열로 온다 —
     * `null` 이나 열쇠 전체가 아니다.
     */
    @Test
    fun `주소가 없으면 빈 값이다`() {
        val env = currentProfileEnvironment(
            opened(deviceKey = "Usb|UMC404HD|", routedAddress = "", micKind = MicKind.Usb),
            emptyList(),
            build,
        )
        assertEquals("", env.deviceAddress)
    }
}
