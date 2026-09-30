package kr.joa.selahrta.data.rta

import kr.joa.selahrta.dsp.ThirdOctave
import kotlin.math.abs

/**
 * **저장한 두 곡선의 차이**(지시서 §7 후속).
 *
 * 좌우를 견주는 것이 이 기능의 목적인데, 31칸을 눈으로 훑어 「어디가
 * 얼마나 벌어졌나」를 찾으라고 하면 절반만 만든 셈이다.
 *
 * ## dB 는 그냥 뺀다
 *
 * 평균을 낼 때는 전력으로 바꿔야 했다([BandPowerAverage]) — 여러 장을
 * **합치는** 일이었기 때문이다. 그러나 두 값의 **비**를 구하는 일은
 * dB 에서 뺄셈이다. 여기서 또 전력으로 바꾸면 틀린다.
 */
class RtaDifference private constructor(
    /** 앞엣것 − 뒤엣것(dB). 양수면 앞엣것이 크다. */
    val perBandDb: DoubleArray,
    /** 가장 크게 벌어진 밴드 번호. */
    val widestBand: Int,
    /** 그 밴드에서의 차이(dB). 부호가 있다. */
    val widestDb: Double,
    /** 밴드마다의 **절대** 차이를 평균한 값. */
    val meanAbsDb: Double,
    private val firstNameKo: String,
    private val secondNameKo: String,
) {
    /**
     * 사람 말로 한 줄.
     *
     * **어느 쪽이 큰지 적는다.** 「6.3dB 차」만으로는 어느 쪽을 내려야
     * 하는지 모른다.
     */
    fun summaryKo(): String {
        val hz = ThirdOctave.label(widestBand)
        val bigger = if (widestDb >= 0) firstNameKo else secondNameKo
        val smaller = if (widestDb >= 0) secondNameKo else firstNameKo
        return "%s 에서 %s이(가) %s보다 %.1f dB 큽니다 (평균 차 %.1f dB)."
            .format(hz, bigger, smaller, abs(widestDb), meanAbsDb)
    }

    companion object {
        /**
         * 두 기록의 차이를 낸다. **셈할 수 없으면 null 이다.**
         *
         * ## 언제 안 하는가 (담당자 지시 2026-09-29 기준 5)
         *
         * > 비교에 필요한 조건이 없는 자료는 자동 차이 계산에서 제외해
         * > 주세요.
         *
         * **숫자를 자동으로 내놓으면 사람은 그것을 믿는다.** 어떤 조건으로
         * 잰 것인지 모르는 자료로 「좌우가 6dB 다르다」고 적으면, 그 6dB 가
         * 방의 것인지 마이크의 것인지 모르는 채로 EQ 를 만지게 된다.
         *
         * 그래서 **모르는 조건이 하나라도 있으면** 하지 않고, **견주는 데
         * 쓰이는 조건이 서로 다르면** 하지 않는다.
         *
         * **겹쳐 보는 것까지 막지는 않는다** — 눈으로 견주는 일은 사람의
         * 몫이고, 화면은 조건이 다르다고 알리기만 한다. 숫자를 자동으로
         * 내는 것은 그와 다른 일이다.
         *
         * 채널과 세기가 다른 것은 **막을 일이 아니다** — 그것이 다른 것이
         * 바로 비교하려는 까닭이다.
         */
        fun of(first: RtaMeasurement, second: RtaMeasurement): RtaDifference? {
            if (first.id == second.id) return null
            if (first.bandsSpl.size != ThirdOctave.BAND_COUNT) return null
            if (second.bandsSpl.size != ThirdOctave.BAND_COUNT) return null
            if (first.conditions.hasUnknown || second.conditions.hasUnknown) return null
            if (!comparable(first.conditions, second.conditions)) return null
            // **무슨 소리를 넣었는지도 같아야 한다**(독립 검토 PND-02).
            //
            // 조건 쪽만 보다 보니 **핑크로 잰 것과 순음으로 잰 것**이 같은
            // 조건으로 셈해졌다. 자극이 다르면 그 차이는 방의 차이가 아니다.
            // 자세한 것(주파수·대역 모양)은 `conditions.signalSpec` 이 든다.
            if (first.signal != second.signal) return null

            val n = ThirdOctave.BAND_COUNT
            val diff = DoubleArray(n) { first.bandsSpl[it] - second.bandsSpl[it] }
            var widest = 0
            for (i in 1 until n) if (abs(diff[i]) > abs(diff[widest])) widest = i

            return RtaDifference(
                perBandDb = diff,
                widestBand = widest,
                widestDb = diff[widest],
                meanAbsDb = diff.sumOf { abs(it) } / n,
                firstNameKo = labelOf(first, second),
                secondNameKo = labelOf(second, first),
            )
        }

        /**
         * 이 기록을 뭐라고 부를 것인가.
         *
         * **사람이 붙인 이름을 쓴다** — 목록에서 보는 것이 그것이다.
         * 그런데 둘 다 이름을 안 지었으면(「이름 없음」) 그 이름으로는
         * **어느 쪽이 어느 쪽인지 갈리지 않는다.** 그때만 채널을 적는다.
         */
        private fun labelOf(m: RtaMeasurement, other: RtaMeasurement): String {
            val name = m.nameKo.trim()
            if (name.isNotBlank() && name != other.nameKo.trim()) return name
            return channelKo(m.channel)
        }

        /** 채널을 사람 말로. 이름이 겹칠 때만 쓴다. */
        fun channelKo(channel: String): String = when (channel) {
            "Left" -> "왼쪽"
            "Right" -> "오른쪽"
            "Both" -> "양쪽"
            else -> RtaConditions.UNKNOWN_KO
        }

        /**
         * 잰 값을 **같은 잣대로** 놓을 수 있는가.
         *
         * 마이크·보정·분석 설정이 다르면 그 차이는 방의 차이가 아니다.
         * **출력 채널과 레벨은 보지 않는다** — [RtaConditions] 에 아예 없다.
         */
        private fun comparable(a: RtaConditions, b: RtaConditions): Boolean =
            a.inputKey == b.inputKey &&
                a.inputSource == b.inputSource &&
                a.inputChannel == b.inputChannel &&
                a.calibrationState == b.calibrationState &&
                a.calibrationSource == b.calibrationSource &&
                // **수치까지 본다**(독립 검토 RMS-03). 「보정됨·교정기」까지만
                // 보면 100dB 로 맞춘 것과 106dB 로 맞춘 것이 같은 조건이 되고,
                // 그 6dB 이 좌우 차이로 읽힌다.
                a.offsetDb == b.offsetDb &&
                a.curveName == b.curveName &&
                // **이름이 같아도 내용이 다를 수 있다.**
                a.curveHash == b.curveHash &&
                // **가중이 다르면 같은 소리도 다르게 찍힌다** — 100Hz 에서 19dB.
                a.analysisWeighting == b.analysisWeighting &&
                // **주파수·대역 모양까지 본다**(독립 검토 PND-02). 1kHz 로
                // 재 것과 2kHz 로 재 것은 같은 「주파수 지정」이지만 전혀 다른
                // 소리다 — 그 차이를 방의 차이로 읽게 두면 안 된다.
                a.signalSpec == b.signalSpec &&
                a.fftSize == b.fftSize &&
                a.sampleRate == b.sampleRate
    }
}
