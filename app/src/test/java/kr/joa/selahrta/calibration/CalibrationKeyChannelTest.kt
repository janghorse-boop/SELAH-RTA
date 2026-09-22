package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.audio.EffectState
import kr.joa.selahrta.audio.EffectsReport
import kr.joa.selahrta.audio.OpenedFormat
import kr.joa.selahrta.audio.PcmEncoding
import kr.joa.selahrta.domain.MicKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * **채널마다 보정값이 따로다 — 다만 모노는 지금까지와 같다.**
 *
 * USB 오디오 지시서 9.2: *「UMC404HD = EMM-6 calibration 으로 묶지 말고
 * UMC404HD / Input 1 / Dayton EMM-6 로 각각 관리한다.」* Input 1 과
 * Input 3 에 서로 다른 마이크가 꽂혀 있으면 보정값도 다르다.
 *
 * **모노 기기의 열쇠는 건드리지 않는다.** `|ch0` 을 붙이면 지금까지
 * 저장된 내장 마이크 보정값이 **전부 사라진다** — 사람이 기준 소음계와
 * 맞춰 가며 넣은 값이다.
 */
class CalibrationKeyChannelTest {

    private fun opened(channelCount: Int, channelIndex: Int) = OpenedFormat(
        micKind = MicKind.Usb,
        sampleRate = 48_000,
        channelCount = channelCount,
        channelIndex = channelIndex,
        encoding = PcmEncoding.Float,
        audioSource = CaptureSource.Unprocessed,
        bufferSizeBytes = 4096,
        deviceLabel = "UMC404HD",
        deviceKey = "Usb|UMC404HD|",
        unprocessedSupported = true,
        effects = EffectsReport(
            EffectState("AGC", available = false, wasEnabled = false, disabled = true),
            EffectState("NS", available = false, wasEnabled = false, disabled = true),
            EffectState("AEC", available = false, wasEnabled = false, disabled = true),
        ),
    )

    @Test
    fun `모노면 열쇠가 지금까지와 같다`() {
        val k = CalibrationKey.of(opened(channelCount = 1, channelIndex = 0))
        assertEquals("cal|Usb|UMC404HD||Unprocessed", k.storageKey())
    }

    @Test
    fun `여러 채널이면 채널이 열쇠에 들어간다`() {
        val a = CalibrationKey.of(opened(channelCount = 4, channelIndex = 0))
        val b = CalibrationKey.of(opened(channelCount = 4, channelIndex = 2))

        assertEquals("cal|Usb|UMC404HD||Unprocessed|ch0", a.storageKey())
        assertEquals("cal|Usb|UMC404HD||Unprocessed|ch2", b.storageKey())
        assertNotEquals("채널이 다르면 보정값도 달라야 한다", a.storageKey(), b.storageKey())
    }

    /**
     * **모노와 「4채널 중 0번」은 다른 열쇠다.**
     *
     * 같은 기기라도 모노로 열었을 때와 4채널로 열어 0번을 쓸 때는 게인
     * 경로가 다를 수 있다. 섞지 않는다.
     */
    @Test
    fun `모노와 여러 채널의 0번은 다른 열쇠다`() {
        assertNotEquals(
            CalibrationKey.of(opened(1, 0)).storageKey(),
            CalibrationKey.of(opened(4, 0)).storageKey(),
        )
    }
}
