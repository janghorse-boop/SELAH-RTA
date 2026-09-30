package kr.joa.selahrta.audio

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **USB 인터페이스를 꽂았을 때 무엇으로 열리는가**(2026-09-30).
 *
 * 폰 속성이 「UNPROCESSED 안 된다」여도 USB 에서는 열린다는 것을 재서
 * 알았다. 이 시험은 **그 앎이 앱의 실제 경로에 반영돼 있는지**를 본다.
 *
 * ## USB 가 없으면 스스로 건너뛴다
 *
 * **건너뛴 것을 통과로 세지 마십시오.** 로그에 어느 쪽인지 남긴다.
 */
class UsbOpenedSourceTest {
    @Test
    fun usb_는_가공_없는_경로로_열린다() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val usb = InputDeviceScanner(ctx).listAll()
            .firstOrNull { it.kind == kr.joa.selahrta.domain.MicKind.Usb }
        Log.i("USBSRC", "USB 기기=${usb?.productName ?: "없음"}")
        assumeTrue("USB 오디오 기기가 꽂혀 있어야 한다", usb != null)

        val src = MicSource(ctx, usb)
        when (val r = src.open(RequestedFormat(sampleRate = 48_000))) {
            is OpenResult.Failed -> {
                Log.w("USBSRC", "열기 실패: ${r.reason} ${r.detail}")
                throw AssertionError("USB 를 열지 못했다: ${r.reason}")
            }
            is OpenResult.Opened -> {
                Log.i("USBSRC", "열린 경로=${r.format.audioSource} rate=${r.format.sampleRate}")
                src.close()
                org.junit.Assert.assertEquals(
                    "USB 인데 가공 없는 경로로 안 열렸다",
                    CaptureSource.Unprocessed,
                    r.format.audioSource,
                )
            }
        }
    }

    /**
     * **기록에 남는 「지원」은 기기가 스스로 알린 값이어야 한다**
     * (독립 검토 R5-03).
     *
     * USB 면 폰 속성이 false 여도 시도한다. 그런데 그 **시도 여부**가
     * 그대로 [OpenedFormat.unprocessedSupported] 로 흘러들어, 속성이
     * false 인데 보고서가 **「가공 없는 입력 지원: 예」**라고 적었다.
     *
     * ## 이 시험만 이것을 볼 수 있다
     *
     * 배선이라 JVM 에서는 안 보인다 — 잘못 이어도 순수 함수들은 다
     * 통과한다. **USB 가 꽂혀 있을 때만** 두 값이 갈리므로(정책 true /
     * 속성 false), 여기가 유일한 자리다.
     */
    @Test
    fun 기록에_남는_지원은_기기가_알린_값이다() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val usb = InputDeviceScanner(ctx).listAll()
            .firstOrNull { it.kind == kr.joa.selahrta.domain.MicKind.Usb }
        assumeTrue("USB 오디오 기기가 꽂혀 있어야 한다", usb != null)

        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
            as android.media.AudioManager
        val reported = am.getProperty(
            android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED,
        ) == "true"
        Log.i("USBSRC", "기기 속성 지원=$reported")

        val src = MicSource(ctx, usb)
        val r = src.open(RequestedFormat(sampleRate = 48_000))
        assumeTrue("USB 를 열지 못했다", r is OpenResult.Opened)
        val fmt = (r as OpenResult.Opened).format
        src.close()
        Log.i(
            "USBSRC",
            "열린 경로=${fmt.audioSource} 기록된 지원=${fmt.unprocessedSupported}",
        )

        org.junit.Assert.assertEquals(
            "시도 여부가 「기기가 지원한다」로 기록됐다",
            reported,
            fmt.unprocessedSupported,
        )
        // 짝을 둔다 — 「늘 false 로 적는 코드」로도 위가 통과하지 않게.
        org.junit.Assert.assertEquals(
            "시도조차 안 했다면 이 시험은 아무것도 안 본다",
            CaptureSource.Unprocessed,
            fmt.audioSource,
        )
    }
}
