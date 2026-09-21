package kr.joa.selahrta.audio

import kr.joa.selahrta.dsp.BlockStats

/**
 * PCM 한 덩어리.
 *
 * **[samples] 는 재사용되는 버퍼다.** 콜백이 돌아간 뒤에는 내용이 바뀐다 —
 * 붙들어 두려면 반드시 복사해야 한다. 덩어리마다 배열을 새로 만들면
 * 초당 수십 번 쓰레기가 생겨 장시간 예배에서 GC 가 캡처를 멈춘다(명세 17장).
 *
 * [frames] 밖의 값은 지난 덩어리의 찌꺼기다. 절대 읽지 말 것.
 */
class AudioBlock(
    val samples: FloatArray,
    val frames: Int,
    val sampleRate: Int,
    /** 단조 시계 기준 시각(ns). 벽시계를 쓰지 않는다 — 시간 동기화의 기준이다. */
    val monotonicNs: Long,
)

/**
 * 입력 소스.
 *
 * 명세 2장: **내장 마이크는 USB 의 fallback 이 아니라 정식 입력이다.**
 * 그래서 두 구현이 이 인터페이스 아래에서 동등하고, 같은 DSP 를 지난다.
 * 어느 쪽인지는 [OpenedFormat] 에만 남고 계산 경로는 갈라지지 않는다.
 */
interface AudioSource {

    /** 사람에게 보여줄 이름. */
    val labelKo: String

    /**
     * 마이크를 연다. 성공하면 실제로 열린 형식을 돌려준다.
     *
     * 실패는 예외가 아니라 [OpenResult.Failed] 로 돌려준다 — 권한 거부나
     * 기기 없음은 프로그램의 오류가 아니라 화면에 설명해야 할 상황이다.
     */
    fun open(requested: RequestedFormat): OpenResult

    /**
     * 캡처를 시작한다. [onBlock] 은 **전용 스레드**에서 불린다 —
     * UI 를 만지지 말고, 파일을 쓰지 말고, 무거운 계산을 하지 말 것.
     * 여기서 늦어지면 곧바로 버려지는 프레임이 된다(명세 17장).
     */
    fun start(onBlock: (AudioBlock, BlockStats) -> Unit)

    /** 멈추고 자원을 놓는다. 여러 번 불러도 안전해야 한다. */
    fun close()
}

sealed interface OpenResult {
    data class Opened(val format: OpenedFormat) : OpenResult
    data class Failed(val reason: OpenFailure, val detail: String?) : OpenResult
}

/**
 * 캡처가 **스스로** 끝난 까닭. 사람이 멈춘 경우는 여기 오지 않는다.
 *
 * 조용히 끝나면 화면은 「측정 중」인 채로 마지막 숫자를 붙들고 있게 된다.
 * 그 숫자는 더 이상 지금 소리가 아니다(독립 검증 L01).
 */
enum class CaptureEnd(val messageKo: String, val reason: kr.joa.selahrta.domain.FailureReason) {
    /** 다른 앱이 마이크를 가져갔다(통화 등). */
    Preempted(
        "다른 앱이 마이크를 가져가 측정이 끊겼습니다. 통화나 녹음 앱을 끄고 다시 시작하십시오.",
        kr.joa.selahrta.domain.FailureReason.Preempted,
    ),

    /** 기기가 빠졌거나 오디오 서버가 죽었다. */
    DeviceLost(
        "마이크와의 연결이 끊겨 측정이 멈췄습니다. 다시 꽂고 시작하십시오.",
        kr.joa.selahrta.domain.FailureReason.DeviceLost,
    ),

    /** 그 밖의 읽기 오류. */
    ReadError(
        "소리를 읽지 못해 측정이 멈췄습니다.",
        kr.joa.selahrta.domain.FailureReason.Unknown,
    ),
    ;

    companion object {
        /** `AudioRecord.read()` 가 돌려준 음수 코드를 옮긴다. */
        fun of(code: Int): CaptureEnd = when (code) {
            android.media.AudioRecord.ERROR_INVALID_OPERATION -> Preempted
            android.media.AudioRecord.ERROR_DEAD_OBJECT -> DeviceLost
            else -> ReadError
        }
    }
}

/** 열지 못한 까닭. 담당자가 할 수 있는 일이 저마다 다르다. */
enum class OpenFailure(val messageKo: String) {
    PermissionDenied("마이크 권한이 없습니다. 설정에서 허용해 주십시오."),
    NoDevice("쓸 수 있는 입력 기기가 없습니다."),
    Busy("다른 앱이 마이크를 쓰고 있습니다. 통화 중이거나 녹음 앱이 켜져 있는지 보십시오."),
    Unsupported("이 기기에서 열 수 있는 형식을 찾지 못했습니다."),
    Unknown("마이크를 열지 못했습니다."),
}

/**
 * 캡처가 도는 동안 쌓이는 사실들(명세 16장 Diagnostics).
 *
 * 측정값이 아니라 **캡처가 건강한지**를 말한다. 이것이 흔들리면
 * 그 위에 올린 숫자는 전부 못 믿는다.
 */
data class CaptureDiagnostics(
    val blocks: Long = 0,
    val frames: Long = 0,
    /** AudioRecord 가 음수를 돌려준 횟수. 0 이 아니면 무언가 잘못됐다. */
    val readErrors: Long = 0,
    /** 잘린 덩어리 수. */
    val clippedBlocks: Long = 0,
    /** 최근 덩어리의 절대값 최대(0..1). 소리가 실제로 들어오는지 눈으로 볼 수 있다. */
    val lastPeakAbs: Double = 0.0,
    /**
     * 한 덩어리를 **처리**하는 데 걸린 시간(ms). 읽기를 기다린 시간은 빼고 잰다 —
     * READ_BLOCKING 의 대기 시간까지 넣으면 잘 돌아가는 기기도 늘 느려 보인다.
     */
    val lastProcessMs: Double = 0.0,
    /** 덩어리 하나가 담는 시간(ms). 위 값과 견주는 기준이다. */
    val blockDurationMs: Double = 0.0,
    /**
     * 받은 오디오 길이가 실제로 흐른 시간보다 얼마나 뒤처졌는가(ms).
     *
     * **이것이 프레임을 잃고 있는지를 말하는 진짜 지표다.** 처리 시간이 빨라도
     * 읽기가 밀려 버퍼가 넘치면 오디오가 통째로 사라지는데, 그건 처리 시간에
     * 안 나타난다. 시간이 갈수록 이 값이 커지면 잃고 있는 것이다.
     */
    val audioLagMs: Double = 0.0,
) {
    /** 처리 시간이 덩어리 길이의 절반을 넘으면 아슬아슬하다. */
    val processingHeadroom: Boolean
        get() = blockDurationMs <= 0.0 || lastProcessMs < blockDurationMs * 0.5

    /**
     * 오디오를 잃지 않고 있는가.
     *
     * 한 덩어리 길이(약 21ms)의 몇 배까지는 버퍼 사정으로 흔들릴 수 있다.
     * 500ms 를 넘으면 설명이 필요한 양이다.
     */
    val keepingUp: Boolean
        get() = audioLagMs < 500.0
}
