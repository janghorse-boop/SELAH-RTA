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

    private fun opened(
        deviceKey: String = "BuiltIn|SM-S918N|back",
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

    @Test
    fun `주소를 목록에서 가져온다`() {
        val env = currentProfileEnvironment(opened(), listOf(input("back")), build)
        assertEquals("back", env.deviceAddress)
    }

    /**
     * **주소가 내장 마이크 둘을 가르는 유일한 단서다.**
     *
     * 갤럭시 S23 은 하단·후면을 둘 다 모델명으로 알린다. 주소가 비면
     * 두 프로파일이 같은 경로로 보여 서로 걸린다.
     */
    @Test
    fun `하단과 후면이 다른 환경으로 나온다`() {
        val back = currentProfileEnvironment(
            opened("BuiltIn|SM-S918N|back"),
            listOf(input("back"), input("bottom")),
            build,
        )
        val bottom = currentProfileEnvironment(
            opened("BuiltIn|SM-S918N|bottom"),
            listOf(input("back"), input("bottom")),
            build,
        )
        assertNotEquals(back.deviceAddress, bottom.deviceAddress)
        assertNotEquals(back.key(), bottom.key())
    }

    /** 목록이 비어 있어도(막 뽑혔거나 훑기 전) 주소를 잃지 않는다. */
    @Test
    fun `목록에 없으면 열쇠에서 주소를 뽑는다`() {
        val env = currentProfileEnvironment(opened(), emptyList(), build)
        assertEquals("back", env.deviceAddress)
    }

    /** 이름에 막대가 든 기기는 **목록 쪽이 이긴다.** */
    @Test
    fun `이름에 막대가 들어도 목록을 쓰면 옳다`() {
        val weird = "BuiltIn|이상한|이름|bottom"
        val env = currentProfileEnvironment(
            opened(deviceKey = weird),
            listOf(input("bottom", name = "이상한|이름")),
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
            opened(deviceKey = "Usb|UMC404HD|", micKind = MicKind.Usb),
            emptyList(),
            build,
        )
        assertEquals("", env.deviceAddress)
    }
}
