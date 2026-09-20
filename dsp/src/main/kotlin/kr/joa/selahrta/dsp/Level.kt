package kr.joa.selahrta.dsp

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 디지털 풀스케일 기준 레벨(dBFS).
 *
 * **이것은 음압이 아니다.** 마이크와 프리앰프의 감도를 모르면 실제 dB SPL 로
 * 옮길 수 없다. 명세 6장이 「dBFS 와 실제 SPL 을 코드에서 분리한다」고 못박은
 * 까닭이고, 독립 검증 체크리스트(24.2)의 첫 항목이기도 하다.
 *
 * 값 클래스로 둬서 [SplDb] 와 **컴파일 단계에서** 섞이지 않게 한다. 둘 다
 * Double 이면 언젠가 반드시 한 번은 섞이고, 그때 화면에는 그럴듯한 숫자가
 * 뜨기 때문에 아무도 알아채지 못한다.
 */
@JvmInline
value class Dbfs(val value: Double) {
    /** 보정값을 더해 음압으로 옮긴다. 이 한 줄이 유일한 통로다. */
    fun toSpl(calibration: CalibrationOffset): SplDb = SplDb(value + calibration.db)
}

/**
 * 음압 레벨(dB SPL). 20 µPa 기준.
 *
 * 보정을 거치지 않고는 만들 수 없다 — [Dbfs.toSpl] 이 유일한 생성 경로가
 * 되도록 쓴다. 미보정 상태에서 이 값을 화면에 「측정값」으로 내보내면 안 된다.
 */
@JvmInline
value class SplDb(val value: Double)

/**
 * dBFS 를 dB SPL 로 옮기는 값. 기기·입력경로별로 다르다.
 *
 * 단순한 덧셈인 것은 **전대역 보정(global calibration)** 일 때뿐이다.
 * 주파수별 보정은 이것과 별개로 대역마다 적용한다(명세 8장).
 */
@JvmInline
value class CalibrationOffset(val db: Double) {
    companion object {
        /** 보정 전. 이 상태의 SPL 값은 「참고용」으로만 표시한다. */
        val UNCALIBRATED = CalibrationOffset(0.0)
    }
}

/** 레벨이 0 일 때 dB 가 -무한으로 발산하는 것을 막는 바닥값. */
const val SILENCE_DBFS: Double = -200.0

/**
 * 선형 RMS 를 구한다. 샘플은 -1.0 ~ +1.0 으로 정규화돼 있다고 본다.
 *
 * 제곱 평균의 제곱근이다. dB 를 평균 내면 안 되는 것과 같은 이유로,
 * 에너지 영역에서 평균한 뒤에 한 번만 dB 로 옮긴다.
 */
fun rms(samples: DoubleArray, from: Int = 0, until: Int = samples.size): Double {
    require(from in 0..samples.size) { "from=$from 이 범위를 벗어난다 (크기 ${samples.size})" }
    require(until in from..samples.size) { "until=$until 이 범위를 벗어난다 (from=$from, 크기 ${samples.size})" }
    val n = until - from
    if (n == 0) return 0.0
    var sum = 0.0
    for (i in from until until) {
        val s = samples[i]
        sum += s * s
    }
    return sqrt(sum / n)
}

/**
 * 선형 진폭비를 dBFS 로 옮긴다. `20 × log10(rms)` (명세 6장).
 *
 * 무음(0)은 수학적으로 -무한이다. 그대로 흘리면 화면·평균·그래프가 전부
 * 무너지므로 [SILENCE_DBFS] 로 잡는다. 이건 근사가 아니라 **표시 가능한
 * 바닥**이라는 약속이다.
 */
fun amplitudeToDbfs(linear: Double): Dbfs {
    require(linear >= 0.0) { "진폭비는 음수일 수 없다: $linear" }
    if (linear <= 0.0) return Dbfs(SILENCE_DBFS)
    val db = 20.0 * log10(linear)
    return Dbfs(if (db < SILENCE_DBFS) SILENCE_DBFS else db)
}

/** 정규화된 샘플 블록의 dBFS. */
fun blockDbfs(samples: DoubleArray): Dbfs = amplitudeToDbfs(rms(samples))
