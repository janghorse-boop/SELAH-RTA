package kr.joa.selahrta.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * 디버그 빌드에서 쓰는 합성 신호(명세 16장).
 *
 * **마이크 대신 값을 아는 소리를 넣는다.** 측정 코드가 맞게 도는지
 * 확인하려면 답을 아는 입력이 있어야 한다. 실제 예배당에 가지 않고도
 * 하울링 탐지·RTA·음압 계산을 눈으로 확인할 수 있다.
 */
/**
 * 현장에서 내보내는 시험 신호(명세 16장).
 *
 * **실무에서 실제로 쓰는 것만 둔다.** 백색 잡음은 뺐다 — 1/3 옥타브 RTA 에
 * 옥타브당 +3dB 기울어져 보여서 방의 응답을 읽기 어렵다. 음향에서 방을
 * 재는 표준은 핑크 잡음이다.
 */
enum class TestSignal(val labelKo: String, val noteKo: String) {
    /** 방 응답을 보는 표준 신호. 옥타브마다 에너지가 고르다. */
    Pink("핑크 잡음", "방의 주파수 응답을 봅니다. RTA 를 고를 때 쓰는 기본 신호입니다"),

    /** 20Hz→20kHz 로그 스윕. 공진과 하울링 나는 자리를 찾는다. */
    Sweep("스윕 20Hz~20kHz", "30초에 한 번 훑습니다. 방이 울리는 자리와 하울링 나기 쉬운 자리를 찾습니다"),

    Sine125("125Hz", "저역. 웅웅거림을 보는 자리입니다"),
    Sine250("250Hz", "저중역. 여기가 많으면 말소리가 탁해집니다"),
    Sine500("500Hz", "중역"),
    Sine1k("1kHz", "보정 기준 주파수입니다. 가중이 0dB 인 자리입니다"),
    Sine2k("2kHz", "말소리 명료도 대역"),
    Sine4k("4kHz", "하울링이 잘 생기는 자리입니다"),
    Sine8k("8kHz", "고역. 치찰음 대역입니다"),

    /**
     * 사람이 직접 고르는 주파수(2026-09-24 담당자 지시).
     *
     * **정해 둔 여덟 자리로는 모자란다.** RTA 가 하울링 후보를 「762Hz」처럼
     * 정확히 알려 주는데, 확인하려면 그 자리를 그대로 낼 수 있어야 한다.
     * 800Hz 로 내면 1/3 옥타브 안에서 5% 어긋난 다른 소리다.
     *
     * 주파수는 [SignalRequest.toneHz] 가 들고 온다 — enum 에 담으면 상수가
     * 되어 버려 「지정」이라는 말이 뜻을 잃는다.
     */
    Custom("주파수 지정", "20Hz~20kHz 에서 직접 고릅니다. RTA 가 알려 준 하울링 자리를 그대로 넣어 봅니다"),
    ;

    /** 정해진 순음이면 그 주파수. 잡음·스윕·[Custom] 이면 null. */
    val toneHz: Double?
        get() = when (this) {
            Sine125 -> 125.0
            Sine250 -> 250.0
            Sine500 -> 500.0
            Sine1k -> 1000.0
            Sine2k -> 2000.0
            Sine4k -> 4000.0
            Sine8k -> 8000.0
            else -> null
        }

    /** 순음인가. 화면이 묶어서 보이는 데 쓴다. [Custom] 도 순음이다. */
    val isTone: Boolean get() = toneHz != null || this == Custom
}

/**
 * 어느 쪽 스피커로 내보내는가(2026-09-24 담당자 지시: 스테레오 L/R 시험).
 *
 * ## 폰 스피커로는 뜻이 없다
 *
 * 폰의 스피커는 하나거나, 둘이어도 몇 센티미터 떨어져 있다. 거기서
 * 「왼쪽만」을 틀어도 방에서는 왼쪽과 오른쪽이 갈리지 않는다.
 *
 * **이 고르개는 폰을 PA 에 물렸을 때를 위한 것이다** — 케이블·채널이
 * 바뀌어 꽂혔는지, 한쪽 앰프가 죽었는지를 그때 가른다. 화면이 그 사실을
 * 적어야 하고, 적지 않으면 폰 스피커로 시험하고 「좌우가 같다」는 잘못된
 * 결론을 얻는다.
 */
enum class SignalChannels(val labelKo: String) {
    Both("양쪽"),
    Left("왼쪽만"),
    Right("오른쪽만"),
}

/**
 * 한 번 내보낼 소리의 전부 — 무엇을, 얼마나 크게, 어느 쪽으로.
 *
 * **묶어서 넘긴다.** 셋이 따로 다니면 「주파수만 바꿔 다시 틀기」 같은
 * 자리에서 한 가지를 빠뜨리기 쉽다.
 */
data class SignalRequest(
    val signal: TestSignal,
    /**
     * 진폭(0~1). [MIN_AMPLITUDE]~[MAX_AMPLITUDE] 로 잘린다.
     *
     * **단계가 아니라 이어진 값이다**(2026-09-24 담당자 지시). 예전에는
     * 작게·보통·크게 셋뿐이라, PA 에 물렸을 때 「보통은 크고 작게는 안
     * 들리는」 자리에서 맞출 것이 없었다.
     */
    val amplitude: Double,
    /** [TestSignal.Custom] 일 때 낼 주파수(Hz). 그 외에는 쓰지 않는다. */
    val toneHz: Double = 1_000.0,
    val channels: SignalChannels = SignalChannels.Both,
) {
    /** 실제로 낼 주파수. 순음이 아니면 null. */
    val effectiveHz: Double?
        get() = if (signal == TestSignal.Custom) toneHz else signal.toneHz

    /** 잘라 낸 진폭. 내보내는 쪽은 이것만 본다. */
    val safeAmplitude: Double
        get() = amplitude.coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE)
}

/**
 * 낼 수 있는 가장 작은 진폭. 이보다 작으면 들리지 않아 고르개가 뜻을 잃는다.
 *
 * −40 dBFS 다.
 */
const val MIN_AMPLITUDE = 0.01

/**
 * 낼 수 있는 가장 큰 진폭. **−8 dBFS 에서 막는다.**
 *
 * 순음은 같은 크기의 음악보다 훨씬 날카롭게 들리고, 예배당 PA 에 물린
 * 채로 크게 틀면 트위터가 상할 수 있다. 예전 「크게」 단계가 이 값이었고,
 * 이어진 고르개로 바꾸면서도 천장은 그대로 둔다 — 슬라이더가 되었다고
 * 해서 스피커가 튼튼해지지는 않는다.
 */
const val MAX_AMPLITUDE = 0.4

/** 고르개의 기본값. 예전 「작게」와 같은 −26 dBFS 다. */
const val DEFAULT_AMPLITUDE = 0.05

/** 직접 고를 수 있는 주파수의 범위(Hz). 사람이 듣는 범위 그대로다. */
const val MIN_TONE_HZ = 20.0
const val MAX_TONE_HZ = 20_000.0

/**
 * 로그 스윕의 위상(rad).
 *
 * 주파수가 시간에 따라 **지수로** 오른다. 선형으로 올리면 저역을 훑는
 * 시간이 너무 짧아 방의 저역 공진을 놓친다 — 사람이 듣는 방식도, 방이
 * 울리는 방식도 로그 눈금이다.
 *
 *   f(t) = f₀·(f₁/f₀)^(t/T)
 *   φ(t) = 2π·f₀·T/ln(f₁/f₀) · ((f₁/f₀)^(t/T) − 1)
 */
internal fun sweepPhase(
    seconds: Double,
    startHz: Double = 20.0,
    endHz: Double = 20_000.0,
    periodSeconds: Double = 30.0,
): Double {
    val t = seconds % periodSeconds
    val ratio = endHz / startHz
    val k = kotlin.math.ln(ratio)
    return 2 * PI * startHz * periodSeconds / k * (Math.pow(ratio, t / periodSeconds) - 1.0)
}

/** 핑크 잡음(−3dB/옥타브). Voss-McCartney 를 줄여 쓴 것이다. */
internal class PinkNoise(private val rng: Random) {
    private val rows = DoubleArray(16)
    private var counter = 0
    private var running = 0.0

    fun next(): Double {
        counter++
        var k = 0
        var c = counter
        while (c and 1 == 0 && k < rows.size - 1) {
            c = c shr 1
            k++
        }
        running -= rows[k]
        rows[k] = rng.nextDouble() * 2 - 1
        running += rows[k]
        return running / rows.size * 4
    }
}
