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
}
