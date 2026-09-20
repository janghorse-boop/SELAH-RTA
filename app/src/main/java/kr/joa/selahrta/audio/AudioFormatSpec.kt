package kr.joa.selahrta.audio

import android.media.AudioFormat
import android.media.MediaRecorder
import kr.joa.selahrta.domain.MicKind

/**
 * 우리가 **바라는** 캡처 형식(명세 5장).
 *
 * 48kHz mono 를 기본 목표로 한다. 기기가 48kHz 로 돌고 있으면 리샘플링이
 * 끼지 않아 주파수 축이 흔들리지 않는다.
 */
data class RequestedFormat(
    val sampleRate: Int = 48_000,
    val channelMask: Int = AudioFormat.CHANNEL_IN_MONO,
)

/**
 * 실제로 **열린** 형식.
 *
 * `RequestedFormat` 과 따로 둔 이유가 이 프로젝트의 전부에 가깝다 —
 * **「48kHz 로 요청했다」와 「48kHz 로 열렸다」는 다른 사실이다.**
 * 기기가 44.1kHz 로 열어 주면 FFT 의 주파수 축이 통째로 8% 어긋나는데,
 * 요청값만 들고 다니면 그 사실이 어디에도 안 남는다.
 *
 * 세션에 그대로 기록된다(명세 2장).
 */
data class OpenedFormat(
    /** 내장인가 USB 인가. 보정값이 이것으로 갈린다 — 감도가 수십 dB 다르다. */
    val micKind: MicKind,
    val sampleRate: Int,
    val encoding: PcmEncoding,
    val audioSource: CaptureSource,
    /** AudioRecord 가 잡은 버퍼 크기(바이트). 진단에 쓴다. */
    val bufferSizeBytes: Int,
    /** 실제로 열린 입력 기기 이름(위치 포함). 화면에 적는 값이다. */
    val deviceLabel: String,
    /**
     * 실제로 열린 기기의 열쇠. **보정값이 이것으로 갈린다.**
     *
     * 이름만 쓰면 안 된다 — 갤럭시의 하단·후면 내장 마이크는 이름이 같아서
     * 서로 다른 마이크에 같은 보정값이 적용된다(실측으로 확인).
     */
    val deviceKey: String,
    /** 기기가 UNPROCESSED 를 공식 지원한다고 알리는가. */
    val unprocessedSupported: Boolean,
    /** 신호 가공(AGC/NS/AEC)을 끈 결과. 하나라도 남으면 절대값을 믿기 어렵다. */
    val effects: EffectsReport,
    /** 사용자가 고른 기기 이름. 고르지 않았으면 null. */
    val requestedDeviceLabel: String? = null,
    /**
     * 고른 기기로 실제로 열렸는가.
     *
     * false 면 **다른 마이크의 소리를 그 마이크의 보정값으로 재고 있다.**
     * 숫자는 멀쩡해 보이므로 화면이 반드시 알려야 한다.
     */
    val routedAsRequested: Boolean = true,
) {
    /**
     * 지금 들어오는 소리를 얼마나 믿을 수 있는가. 화면에 그대로 띄운다.
     *
     * **아는 것과 모르는 것을 갈라서 적는다.** 효과 API 로 확인할 수 있는
     * 것은 「우리 세션에 AGC/NS/AEC 모듈이 붙어 있는가」뿐이다. 하드웨어와
     * HAL 안쪽에서 손대는 것까지는 알 수 없다 — 그건 기준 소음계와 맞춰
     * 봐야만 갈린다(명세 Phase 12). 확인한 만큼만 말한다.
     */
    val trustNoteKo: String
        get() = when {
            effects.stillOn.isNotEmpty() ->
                "${effects.stillOn.joinToString(" · ")} 이(가) 켜진 채입니다. " +
                    "소리를 건드리고 있어 절대값을 믿기 어렵습니다."

            audioSource.trustworthy ->
                "가공 없는 입력(UNPROCESSED)으로 재고 있습니다."

            else ->
                "이 기기는 가공 없는 입력을 지원하지 않아 ${audioSource.labelKo}로 잽니다. " +
                    "자동 게인·잡음 억제·반향 제거는 걸려 있지 않은 것을 확인했습니다. " +
                    "다만 하드웨어 안쪽의 가공까지는 알 수 없어, 절대 음압은 기준 소음계와 맞춰 봐야 합니다."
        }

    /** 위 문구를 경고로 띄울 것인가, 사실로 띄울 것인가. */
    val trustIsWarning: Boolean
        get() = effects.stillOn.isNotEmpty() || !audioSource.trustworthy
}

/**
 * 샘플 표현 방식.
 *
 * 명세 5장은 「안정적인 24-bit 지원 시 사용, 아니면 16-bit fallback」이라고
 * 한다. 안드로이드에서 그 자리를 실제로 채우는 것은 **float** 다 —
 * 24비트 고정소수점보다 정밀도가 높고, `ENCODING_PCM_24BIT_PACKED` 처럼
 * 세 바이트를 손으로 풀어 붙이지 않아도 된다(그 자리는 부호 확장 실수가
 * 나기 쉽고, 틀려도 소리는 그럴듯하게 난다).
 */
enum class PcmEncoding(val labelKo: String, val androidValue: Int, val bitsLabel: String) {
    Float("32-bit float", AudioFormat.ENCODING_PCM_FLOAT, "32 bit float"),
    Int16("16-bit 정수", AudioFormat.ENCODING_PCM_16BIT, "16 bit"),
}

/**
 * 어떤 입력 경로로 땄는가.
 *
 * 명세 5장: **AGC/NS/AEC 경로를 피한다.** 자동 게인이 걸리면 큰 소리가
 * 들어올 때 앱 몰래 볼륨이 내려가서, 음압을 재는 도구가 「음압이 안 올라가는」
 * 도구가 된다. UNPROCESSED 가 있으면 그것을 쓰고, 없으면 무엇으로 땄는지
 * **화면에 적는다** — 숨기면 담당자가 그 숫자를 그대로 믿는다.
 */
enum class CaptureSource(
    val labelKo: String,
    val androidValue: Int,
    /** 이 경로의 값을 측정에 써도 되는가. */
    val trustworthy: Boolean,
) {
    Unprocessed("가공 없음 (UNPROCESSED)", MediaRecorder.AudioSource.UNPROCESSED, true),
    VoiceRecognition("음성인식 경로", MediaRecorder.AudioSource.VOICE_RECOGNITION, false),
    Mic("기본 마이크", MediaRecorder.AudioSource.MIC, false),
}
