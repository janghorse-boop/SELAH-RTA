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
    ;

    /** 순음이면 그 주파수. 잡음·스윕이면 null. */
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

    /** 순음인가. 화면이 묶어서 보이는 데 쓴다. */
    val isTone: Boolean get() = toneHz != null
}

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
