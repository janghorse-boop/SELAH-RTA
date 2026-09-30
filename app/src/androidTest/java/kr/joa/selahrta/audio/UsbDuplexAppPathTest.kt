package kr.joa.selahrta.audio

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * **USB 로 재는 동안 소리를 틀어도 입력이 살아 있는가**(2026-09-30).
 *
 * 안드로이드는 인터페이스를 꽂으면 출력도 그쪽으로 보내는데, **같은 USB
 * 카드로 동시에 넣고 빼면 입력이 완전한 디지털 무음**이 되는 기기가 있다.
 * 그러면 교정 마법사 2·4단계가 **아무것도 못 잰 채로** 넘어간다.
 *
 * ```
 * 출력 안 정함 → 실제 출력=UMC404HD, 입력 RMS=-240.0dBFS  (죽음)
 * 폰 스피커로  → 실제 출력=SM-S918N,  입력 RMS= -67.8dBFS  (삶)
 * ```
 *
 * ## USB 가 없으면 스스로 건너뛴다
 *
 * **건너뛴 것을 통과로 세지 마십시오.**
 */
class UsbDuplexAppPathTest {

    @Test
    fun usb_로_재는_동안_소리를_틀어도_입력이_산다() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val usb = InputDeviceScanner(ctx).listAll()
            .firstOrNull { it.kind == kr.joa.selahrta.domain.MicKind.Usb }
        Log.i("DUPLEXAPP", "USB=${usb?.productName ?: "없음"}")
        assumeTrue("USB 오디오 기기가 꽂혀 있어야 한다", usb != null)

        // **앱이 쓰는 그 고름을 그대로 쓴다.** 여기서 딴 자리를 고르면
        // 앱이 겪는 일을 재는 것이 아니다.
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
            as android.media.AudioManager
        val speaker = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val player = SignalPlayer(
            openSink = {
                AudioTrackSink(
                    preferredOutput = {
                        val want = SignalOutputChoice.preferBuiltInSpeaker(usb!!.kind)
                        Log.i("DUPLEXAPP", "폰 스피커로 돌릴까=$want")
                        if (want) speaker else null
                    },
                )
            },
        )

        val src = MicSource(ctx, usb)
        val opened = src.open(RequestedFormat(sampleRate = 48_000))
        assumeTrue("USB 를 열어야 한다", opened is OpenResult.Opened)

        player.start(SignalRequest(signal = TestSignal.Pink, amplitude = 0.2))
        Thread.sleep(300)

        var peak = 0.0
        var sum = 0.0
        var n = 0L
        val done = CountDownLatch(1)
        src.start { block, _ ->
            for (i in 0 until block.frames) {
                val v = block.samples[i].toDouble()
                if (abs(v) > peak) peak = abs(v)
                sum += v * v
            }
            n += block.frames
            if (n > 48_000L * 2) done.countDown()
        }
        done.await(10, TimeUnit.SECONDS)
        src.close()
        player.stop()

        val rms = if (n > 0) sqrt(sum / n) else 0.0
        val db = 20 * log10(maxOf(rms, 1e-12))
        Log.i("DUPLEXAPP", "소리를 틀면서 잡은 입력 RMS=${"%.1f".format(db)}dBFS 표본=$n")
        org.junit.Assert.assertTrue(
            "소리를 트는 동안 USB 입력이 죽었다(RMS ${"%.1f".format(db)}dBFS). " +
                "출력이 같은 인터페이스로 나가고 있는지 보라.",
            db > -150.0,
        )
    }
}
