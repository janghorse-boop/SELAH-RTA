package kr.joa.selahrta.transfer

import kr.joa.selahrta.audio.OpenedFormat
import kr.joa.selahrta.audio.activeMicComboKey
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.DriftAnalysis
import kr.joa.selahrta.dsp.DriftLogAnalyzer
import kr.joa.selahrta.dsp.DriftObservation

/**
 * 음향 드리프트 기록(`AcousticDriftRecordingTest`)의 **세션 판정**.
 *
 * 계측 시험 안에 두었던 상태 관리에서 9회차 독립 검토의 반례가 모두 나왔다.
 * 그 자리는 JVM 시험으로 돌릴 수 없어 구현자가 직접 확인하지 못했다. 그래서
 * 판정을 이 파일로 꺼내 `DriftSessionGuardTest` 로 지킨다. 계측 시험은 안드로이드
 * 값을 읽어 여기에 넘기기만 한다.
 *
 * 설계와 재기 전에 정한 규칙: `docs/superpowers/specs/2026-10-02-acoustic-drift-recording-design.md`.
 */

/**
 * 입력 확인 한 번의 요지.
 *
 * @param comboKey 활성 마이크 조합([activeMicComboKey]). **빈 문자열은 「모름」**이지
 *   「없다」가 아니다.
 */
data class InputSnapshot(val deviceKey: String, val builtIn: Boolean, val comboKey: String) {
    companion object {
        fun of(f: OpenedFormat) = InputSnapshot(f.deviceKey, f.micKind == MicKind.BuiltIn, activeMicComboKey(f.activeMics))
    }
}

sealed interface SessionVerdict {
    /** @param notes 유효하되 결론 범위를 좁히는 사실(예: 시작 때 조합을 몰랐다). */
    data class Valid(val notes: List<String>) : SessionVerdict
    data class Invalid(val reasons: List<String>) : SessionVerdict
}

/**
 * 기록 한 번 동안 **입력·출력 경로가 처음과 같았는가**를 지킨다.
 *
 * ## 기준을 잡는 일과 감시를 시작하는 일을 한 번에 (9회차 R9-01 ②)
 *
 * 예전에는 시작 확인을 읽고, 단언하고, 그다음에 변경 기록을 비웠다. 그 사이에
 * 온 변경이 지워졌다. 이제 [arm] 이 **지금까지의 마지막 확인을 기준으로 잡는
 * 것과 감시 시작을 한 잠금 안에서** 한다. 그 뒤에 온 것은 무엇이든 남는다.
 * [arm] 전의 확인·통지는 여는 과정이라 변경으로 세지 않는다.
 *
 * ## 「모름」은 「같음」이 아니다 (9회차 R9-01 ①)
 *
 * 시작 때 활성 조합이 비어 있으면 예전에는 그 빈 값을 영원한 기준으로 삼아,
 * 나중에 알게 된 조합끼리의 변경(22 → 24)을 놓쳤다. 이제는 **마지막으로
 * 알려진 조합**을 따로 들고, 알려진 조합끼리 다르면 무효, **알던 조합을 잃어도
 * 무효**다. 시작 때 몰랐던 것은 유효하되 [SessionVerdict.Valid.notes] 에 적는다
 * — 그 사이의 연속성은 보이지 못한다.
 *
 * ## 끝을 확정한다 (9회차 R9-01 ③)
 *
 * 마지막 관측 뒤의 출력 변경은 다음 관측이 없어 세지지 않았다. [finish] 가
 * 마지막 출력 경로를 받아 반영한 뒤 문을 닫는다. 닫은 뒤의 통지는 판정을 바꾸지
 * 않고 [lateEvents] 로만 센다 — 정리 중에 오는 정상 통지다.
 */
class DriftSessionGuard {
    private val lock = Any()
    private var latestInput: InputSnapshot? = null
    private var base: InputSnapshot? = null
    private var baseOutput: String? = null
    private var lastKnownCombo = ""
    private var comboUnknownAtStart = false
    private var comboLearned: String? = null
    private val reasons = mutableListOf<String>()
    private var changedSincePoll = false
    private var armed = false
    private var closed = false

    /** 끝낸 뒤에 온 통지 수. */
    var lateEvents = 0
        private set

    private fun invalidate(why: String) {
        if (why !in reasons) reasons += why
        changedSincePoll = true
    }

    /** `MicSource.onRouteConfirmed` 마다. */
    fun inputConfirmed(s: InputSnapshot) = synchronized(lock) {
        if (closed) { lateEvents++; return@synchronized }
        latestInput = s
        if (!armed) return@synchronized
        val b = base!!
        if (!s.builtIn) invalidate("도중에 입력이 내장 마이크가 아니게 됐다(${s.deviceKey})")
        if (s.deviceKey != b.deviceKey) invalidate("도중에 입력 기기가 바뀌었다(${b.deviceKey} → ${s.deviceKey})")
        when {
            s.comboKey.isEmpty() && lastKnownCombo.isNotEmpty() ->
                invalidate("도중에 활성 마이크 조합을 잃었다(${lastKnownCombo} → 모름)")
            s.comboKey.isNotEmpty() && lastKnownCombo.isNotEmpty() && s.comboKey != lastKnownCombo ->
                invalidate("도중에 활성 마이크 조합이 바뀌었다(${lastKnownCombo} → ${s.comboKey})")
            s.comboKey.isNotEmpty() && lastKnownCombo.isEmpty() -> {
                lastKnownCombo = s.comboKey
                comboLearned = s.comboKey
            }
        }
    }

    /** `MicSource.onRoutingChanged` 마다. */
    fun inputRouted(desc: String) = synchronized(lock) {
        if (closed) { lateEvents++; return@synchronized }
        if (armed) invalidate("도중에 입력 경로가 바뀌었다 → $desc")
    }

    /** 출력의 경로 변경 통지마다. */
    fun outputRouted() = synchronized(lock) {
        if (closed) { lateEvents++; return@synchronized }
        if (armed) invalidate("도중에 출력 경로 변경 통지가 왔다")
    }

    /** 관측마다 실제 출력 경로의 열쇠(종류·이름·주소). 모르면 null. */
    fun outputPolled(key: String?) = synchronized(lock) {
        if (closed) { lateEvents++; return@synchronized }
        if (armed && key != baseOutput) invalidate("도중에 출력 경로가 바뀌었다(${baseOutput} → ${key ?: "모름"})")
    }

    /**
     * **지금까지의 마지막 입력 확인**을 기준으로 잡고 감시를 시작한다. 돌려준
     * 값으로 시작 단언을 한다 — 따로 읽지 않는다. 확인된 입력이 없으면 null.
     */
    fun arm(outputKey: String): InputSnapshot? = synchronized(lock) {
        val s = latestInput ?: return@synchronized null
        base = s
        baseOutput = outputKey
        lastKnownCombo = s.comboKey
        comboUnknownAtStart = s.comboKey.isEmpty()
        armed = true
        s
    }

    /** 지난 부름 뒤로 변경이 있었나 — 관측 줄의 `routeChanged` 에 쓴다. */
    fun takeChanged(): Boolean = synchronized(lock) {
        val c = changedSincePoll
        changedSincePoll = false
        c
    }

    /** 마지막 출력 경로를 반영하고 문을 닫는다. */
    fun finish(finalOutputKey: String?): SessionVerdict = synchronized(lock) {
        if (!closed) {
            if (!armed) invalidate("기준을 잡지 못했다")
            else if (finalOutputKey != baseOutput) {
                invalidate("끝에서 출력 경로가 달랐다(${baseOutput} → ${finalOutputKey ?: "모름"})")
            }
            closed = true
        }
        if (reasons.isNotEmpty()) return@synchronized SessionVerdict.Invalid(reasons.toList())
        val notes = buildList {
            if (comboUnknownAtStart) {
                add(
                    "시작 때 활성 마이크 조합을 확인할 수 없었다" +
                        (comboLearned?.let { " — 도중에 $it 로 확인됐다" } ?: "") +
                        ". 그 사이의 연속성은 보이지 못한다",
                )
            }
        }
        SessionVerdict.Valid(notes)
    }
}

enum class DriftMode { Trial, Record }

sealed interface DriftArgs {
    data class Accepted(val mode: DriftMode, val minutes: Double) : DriftArgs
    data class Rejected(val why: String) : DriftArgs
}

/**
 * `-e mode`·`-e minutes` 를 읽는다. **기본값으로 조용히 돌지 않는다**(8회차 R8-04).
 *
 * 기록은 **11분 이상**이다. 첫 관측이 10초 뒤에 나오므로 정확히 10분이면 관측
 * 폭이 9분 50초쯤이 되어 「10분 이상 구간」을 못 채우고 반드시 실패한다(9회차).
 */
fun parseDriftArgs(mode: String?, minutes: String?): DriftArgs {
    val m = when (mode) {
        "trial" -> DriftMode.Trial
        "record" -> DriftMode.Record
        else -> return DriftArgs.Rejected("-e mode trial|record 를 정하십시오 — 받은 값: $mode")
    }
    val min = minutes?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        ?: return DriftArgs.Rejected("-e minutes 에 0 보다 큰 수를 주십시오 — 받은 값: $minutes")
    return when (m) {
        DriftMode.Trial -> if (min <= 5.0) DriftArgs.Accepted(m, min) else DriftArgs.Rejected("시운전은 5분 이하: $min")
        DriftMode.Record -> if (min >= 11.0) DriftArgs.Accepted(m, min) else DriftArgs.Rejected("기록은 11분 이상: $min")
    }
}

/** [driftSessionReport] 의 결과. [lines] 는 그 차례대로 ADRIFT 에 적는다. */
data class SessionReport(val lines: List<String>, val passed: Boolean, val failure: String?)

/** 시운전이 「돈다」고 볼 **서로 다른 창**의 최소 수. 통계 기준이 아니라 동작 확인이다. */
const val TRIAL_MIN_DISTINCT = 3

/**
 * **세션 판정을 먼저 확정하고, 그다음에 구간과 결론을 적는다**(9회차 R9-03).
 *
 * 예전에는 구간마다 「씀=true」와 결론을 먼저 찍고 마지막에 경로 단언으로
 * 실패했다. ADRIFT 로그만 가져다 쓰면 **무효 세션의 구간**을 쓰게 된다. 이제는
 * 첫 줄이 `SESSION VALID`/`SESSION INVALID` 이고, 무효이거나 시운전이면 어느
 * 구간도 「채택」이라 적지 않는다. 길이 조건을 채운 것과 실제로 채택한 것을
 * 가른다.
 */
fun driftSessionReport(
    mode: DriftMode,
    verdict: SessionVerdict,
    analysis: DriftAnalysis,
    observations: List<DriftObservation>,
): SessionReport {
    val lines = mutableListOf<String>()
    val valid = verdict is SessionVerdict.Valid
    when (verdict) {
        is SessionVerdict.Valid -> {
            lines += "SESSION VALID"
            verdict.notes.forEach { lines += "SESSION 한계 — $it" }
        }
        is SessionVerdict.Invalid -> lines += "SESSION INVALID — ${verdict.reasons.joinToString("; ")}"
    }
    lines += "관측 ${observations.size} · 서로 다른 창 ${analysis.distinctFound} · 중복 ${analysis.duplicates} · " +
        "버림 ${analysis.skipped} · 못 찾음 ${analysis.notFound} · 마지막 관측 진행=${analysis.lastProgressed}"

    analysis.segments.forEachIndexed { i, s ->
        lines += "SEG #$i epoch=${s.epoch} 시작까닭=${s.startReason ?: "처음"} 관측=${s.points} " +
            "%.1f분 기울기=%.4f ppm 1표본환산=%.4f ppm 최대잔차=%.2f 계단의심=${s.stepSuspected} 길이조건=${s.points >= 20 && s.minutes >= 10.0}"
                .format(s.minutes, s.ppm, s.resolutionPpm, s.maxResidual)
        val adopt = mode == DriftMode.Record && valid && s.usable
        lines += if (adopt) {
            "SEG #$i 채택 — ${DriftLogAnalyzer.conclusion(s)}"
        } else {
            val why = when {
                !valid -> "무효 세션"
                mode == DriftMode.Trial -> "시운전 — 결론을 내지 않는다"
                else -> DriftLogAnalyzer.conclusion(s).removePrefix("이 구간은 결론에 쓰지 않는다 — ")
            }
            "SEG #$i 채택 안 함 — $why"
        }
    }

    val last = observations.lastOrNull()
    val errors = (last?.outErrors ?: 0) + (last?.inErrors ?: 0)
    val failure: String? = when {
        verdict is SessionVerdict.Invalid -> "세션이 무효다 — ${verdict.reasons.joinToString("; ")}"
        mode == DriftMode.Trial && analysis.distinctFound < TRIAL_MIN_DISTINCT ->
            "시운전에서 지연을 찾은 서로 다른 창이 ${analysis.distinctFound} 개 — ${TRIAL_MIN_DISTINCT}개는 있어야 한다"
        mode == DriftMode.Trial && !analysis.lastProgressed ->
            "시운전의 마지막 관측이 새 창으로 나아가지 않았다 — 스트림이 멈췄을 수 있다"
        mode == DriftMode.Trial && errors > 0 -> "시운전 중 입출력 오류가 $errors 번 났다"
        mode == DriftMode.Record && analysis.segments.none { it.usable } ->
            "쓸 수 있는 구간이 하나도 없다 — 「돌렸다」를 「쟀다」로 적지 않는다"
        else -> null
    }
    lines += if (failure == null) "RESULT PASS" else "RESULT FAIL — $failure"
    return SessionReport(lines, failure == null, failure)
}
