package kr.joa.selahrta.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * **소리를 끊어야 할 바깥 사정**(지시서 §5 `Interrupted`).
 *
 * ## 무엇을 막으려는 것인가
 *
 * 둘이다.
 *
 * **하나, 출력이 바뀌는 것.** 이어폰이나 USB 를 뽑으면 안드로이드는
 * 소리를 **폰 스피커로 돌린다.** PA 에 물려 핑크 잡음을 틀어 둔 채로
 * 케이블이 빠지면 예배당에 그 소리가 그대로 터져 나온다. 케이블은
 * 예배 중에 발에 걸려 빠지는 물건이다.
 *
 * **둘, 다른 앱이 소리를 가져가는 것.** 전화가 오면 순음이 통화에
 * 섞인다. 되찾아도 **저절로 다시 틀지 않는다** — 지시서가 백그라운드·
 * 잠금·프로세스 종료에 대해 못박은 것과 같은 자리다. 소리를 내는 일은
 * 사람이 누를 때만 시작한다.
 *
 * ## 왜 「눌러 내보내기」도 멈추나
 *
 * 안드로이드는 알림 소리가 날 때 배경음을 **끄지 않고 줄이라고**
 * (`CAN_DUCK`) 말할 수 있다. 음악이면 그게 맞다. 그런데 여기서 나가는
 * 것은 **재는 데 쓰는 신호**다 — 줄여서 계속 내보내면 사람이 모르는 채로
 * 크기가 달라지고, 그 신호로 잰 방의 응답을 참이라고 읽게 된다.
 *
 * **조용히 틀리는 것이 끊기는 것보다 나쁘다.**
 *
 * ## 이 클래스가 하지 않는 것
 *
 * 소리를 직접 멈추지 않는다. [onInterrupted] 를 부를 뿐이다 — 무엇을
 * 멈출지는 부른 쪽이 안다. 여기서 [SignalPlayer] 를 직접 잡으면 교정
 * 마법사처럼 **소리를 내는 다른 주인**이 생겼을 때 또 갈라진다.
 */
class AudioInterruptions(
    context: Context,
    /** 오디오 스레드가 아니라 **부른 쪽의 스레드**로 온다고 보장하지 않는다. */
    private val onInterrupted: (reasonKo: String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        focusLossReasonKo(change)?.let(onInterrupted)
    }

    /**
     * 출력이 바뀌려 한다는 방송.
     *
     * **뽑히기 직전에 온다.** 그래서 받자마자 멈추면 스피커로 새어
     * 나가는 소리를 줄일 수 있다 — 램프(30ms)가 그 사이에 돈다.
     */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                onInterrupted(BECOMING_NOISY_REASON_KO)
            }
        }
    }

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            // **내보내는 쪽(`AudioTrackSink`)과 같은 성격으로 요청한다.**
            // 다르게 요청하면 시스템이 다른 갈래로 다루어, 막상 눌리거나
            // 끊기는 규칙이 우리가 본 것과 달라진다.
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        // **눌러 내보내기를 시스템에 맡기지 않는다.**
        //
        // 앞서 「이렇게 두면 `CAN_DUCK` 대신 `LOSS_TRANSIENT` 가 온다」고
        // 적었는데 **틀린 말이다**(독립 검토 9회차 3장). 이 플래그는
        // 시스템이 **저절로 소리를 줄이지 않게** 하고 그 판단을 우리 귀
        // (listener)로 넘길 뿐이다 — 어느 값이 오는지는 보장하지 않는다.
        //
        // 그래서 [focusLossReasonKo] 가 **두 값을 모두** 다룬다. 이 플래그는
        // 「우리가 알아서 하겠다」는 뜻이고, 무엇을 할지는 그쪽이 정한다.
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(listener)
        .build()

    private var held = false

    /**
     * 소리를 내기 직전에 부른다. **얻었으면 true.**
     *
     * ## 못 얻었을 때 무엇을 할지는 부른 쪽이 정한다
     *
     * 여기서는 막지 않는다 — 방송 수신기는 이미 걸었고, 돌려주는 값은
     * 「얻었는가」일 뿐이다.
     *
     * **그러나 부른 쪽은 false 를 흘려보내면 안 된다**(독립 검토 SRLR-06).
     * 앞서 이 KDoc 에 「얻지 못해도 막지 않는다 — 다른 앱 하나 때문에
     * 예배 준비가 멈추면 안 된다」고 적어 두었는데, **그 정책은
     * 폐기됐다.** 포커스 없이 나가는 소리는 끊김을 알려 줄 길도 없는
     * 소리인데 화면은 정상 재생으로 보이고, 사람은 그 조건으로 잰 값을
     * 믿는다.
     *
     * 지금 `CaptureViewModel` 은 false 면 **시작하지 않고, 건 것을 놓고,
     * 까닭을 적는다.**
     */
    fun acquire(): Boolean {
        if (!held) {
            // **공개 여부를 못박는다**(targetSdk 34 부터 필수. 이 앱은 36).
            // 안 적으면 `SecurityException` 으로 **앱이 죽는다** — 소리를
            // 트는 첫 순간에.
            //
            // `NOT_EXPORTED` 여도 이 방송은 온다. 시스템만 보낼 수 있는
            // 보호된 방송이고, 막는 것은 **다른 앱이 보내는 가짜**다.
            ContextCompat.registerReceiver(
                appContext,
                noisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            held = true
        }
        return audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * 소리를 멈춘 뒤에 부른다.
     *
     * **두 번 불러도 괜찮아야 한다.** 멈추는 길이 여럿이다 — 사람이 누른
     * 것, 스스로 끝난 것, 바깥 사정. 셋이 겹쳐 불린다.
     */
    fun release() {
        if (held) {
            // 등록한 적 없는 것을 떼면 터진다. 그래서 [held] 를 본다.
            runCatching { appContext.unregisterReceiver(noisy) }
            held = false
        }
        audio.abandonAudioFocusRequest(request)
    }
}

/**
 * 포커스가 [change] 로 바뀌었다. 소리를 멈춰야 하면 **그 까닭**을,
 * 아니면 null 을 돌려준다.
 *
 * **모르는 값은 그냥 둔다.** 멋대로 끊는 편이 더 나쁘다 — 사람은 왜
 * 멎었는지 알 길이 없고, 다시 틀면 또 멎는다.
 */
fun focusLossReasonKo(change: Int): String? = when (change) {
    AudioManager.AUDIOFOCUS_LOSS,
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
    ->
        "다른 앱이 소리를 가져가 테스트 신호를 멈췄습니다."

    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
        "다른 앱 소리에 눌려 크기가 달라질 수 있어 테스트 신호를 멈췄습니다."

    else -> null
}

/**
 * 출력이 바뀌어 멈췄을 때의 까닭.
 *
 * **스피커로 나갈 뻔했다는 말을 적는다.** 「멈췄습니다」만으로는 왜
 * 고마운 일인지 모른다.
 */
const val BECOMING_NOISY_REASON_KO: String =
    "출력이 바뀌어 테스트 신호를 멈췄습니다 — 폰 스피커로 나갈 뻔했습니다."
