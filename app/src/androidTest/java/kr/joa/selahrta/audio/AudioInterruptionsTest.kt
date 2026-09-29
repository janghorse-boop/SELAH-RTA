package kr.joa.selahrta.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * **진짜 안드로이드에게 포커스를 빼앗겨 본다**(지시서 §5 `Interrupted`).
 *
 * ## 왜 계측 시험인가
 *
 * `focusLossReasonKo` 가 옳은 값을 돌려준다는 것은 JVM 시험이 본다
 * ([InterruptionReasonTest]). 그런데 **그 함수가 불리기는 하는가**는
 * 다른 물음이다 — 요청을 잘못 만들면 안드로이드가 우리 쪽 귀를 아예
 * 부르지 않는다. 그 자리는 진짜 `AudioManager` 로만 갈린다.
 *
 * 「시험이 있으니 지켜진다」가 이 저장소에서 다섯 번 틀렸고, 그 다섯이
 * 모두 **부품은 맞는데 아무도 안 부르는** 모양이었다.
 *
 * ## 어떻게 빼앗나
 *
 * 흉내 내지 않는다. 시험이 **또 하나의 포커스 요청**을 `GAIN` 으로
 * 넣으면 안드로이드가 먼저 있던 쪽(우리 것)에게 잃었다고 알린다.
 * 포커스는 앱이 아니라 **귀(listener)** 마다 세므로 같은 앱 안에서도
 * 갈린다.
 *
 * ## 이 시험이 못 보는 것
 *
 * - **이어폰을 뽑는 것**(`ACTION_AUDIO_BECOMING_NOISY`)은 시스템만
 *   보낼 수 있는 보호된 방송이라 시험이 일으킬 수 없다. 수신기가 재생
 *   중에 등록되고 멈추면 떨어지는 것은 기기에서
 *   `dumpsys activity broadcasts` 로 확인했다.
 * - **소리가 실제로 멎는지**도 여기서는 안 본다. 이 부품은 알릴 뿐이고,
 *   멈추는 일은 `CaptureViewModel` 이 한다.
 */
class AudioInterruptionsTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var thief: AudioFocusRequest? = null
    private var under: AudioInterruptions? = null

    @After
    fun 치운다() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        thief?.let { am.abandonAudioFocusRequest(it) }
        under?.release()
    }

    /** 포커스를 가져가는 쪽. 실제 요청이다. */
    private fun stealFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        thief = req
        am.requestAudioFocus(req)
    }

    @Test
    fun 포커스를_얻는다() {
        val it = AudioInterruptions(context) { }
        under = it
        assertTrue("포커스를 못 얻었다 — 다른 앱이 붙들고 있는가", it.acquire())
    }

    /**
     * **핵심이다.** 남이 소리를 가져가면 우리 쪽 귀가 불려야 한다.
     */
    @Test
    fun 남이_가져가면_까닭과_함께_알린다() {
        val heard = CountDownLatch(1)
        val why = AtomicReference<String>()
        val it = AudioInterruptions(context) { reason ->
            why.set(reason)
            heard.countDown()
        }
        under = it
        assertTrue(it.acquire())

        stealFocus()

        assertTrue("포커스를 빼앗겼는데 아무 말이 없다", heard.await(5, TimeUnit.SECONDS))
        assertNotNull(why.get())
        assertTrue("무엇이 멈췄는지 안 적혔다: ${why.get()}", why.get().contains("신호"))
    }

    /**
     * **놓은 뒤에는 부르지 않는다.** 안 놓으면 다음에 남이 소리를 낼
     * 때마다 안 틀었는데 「멈췄습니다」가 뜬다.
     */
    @Test
    fun 놓은_뒤에는_알리지_않는다() {
        val heard = CountDownLatch(1)
        val it = AudioInterruptions(context) { heard.countDown() }
        under = it
        assertTrue(it.acquire())
        it.release()

        stealFocus()

        assertFalse("놓았는데도 알림이 왔다", heard.await(2, TimeUnit.SECONDS))
    }

    /** **두 번 놓아도 터지지 않는다.** 멈추는 길이 셋이라 겹쳐 불린다. */
    @Test
    fun 두_번_놓아도_괜찮다() {
        val it = AudioInterruptions(context) { }
        under = it
        it.acquire()
        it.release()
        it.release()
    }
}
