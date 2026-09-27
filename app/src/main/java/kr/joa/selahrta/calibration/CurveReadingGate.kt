package kr.joa.selahrta.calibration

import kr.joa.selahrta.dsp.CURVE_READING_RULES_VERSION
import kr.joa.selahrta.dsp.CurveReading
import kr.joa.selahrta.dsp.ReadingDecision
import kr.joa.selahrta.dsp.ReadingStakes
import kr.joa.selahrta.dsp.columnDeclarationOf
import kr.joa.selahrta.dsp.decideReading
import kr.joa.selahrta.dsp.signEvidenceOf

/**
 * **가져온 곡선을 걸어도 되는가**(독립 재검토 CFRF-01).
 *
 * `CurveStore` 에서 떼어 둔 까닭은 하나다 — 여기 있는 판단이 틀리면
 * **보정이 거꾸로 두 배 걸리는데 화면에서는 드러나지 않는다.** 저장소
 * 안에 섞여 있으면 안드로이드 `Context` 없이는 한 줄도 돌려 볼 수 없고,
 * 그러면 「막았다」를 짐작으로만 말하게 된다.
 *
 * ## 무엇이 잘못됐었나
 *
 * 부호가 모호한 파일은 가져올 때 꺼진 채로 들어오게 해 두었다. 그런데
 * **꺼 두는 것으로는 못 막았다.** 검토자가 실제 DataStore 로 둘을 재현했다:
 *
 * ```
 * TOGGLE_WITHOUT_READING enabled=true correction=FromFile   ← 일반 스위치로 켜면 그만
 * REOPEN                 enabled=true correction=FromFile   ← 다시 열면 켜진 채로 복원
 * ```
 *
 * 켜짐 표시 하나만 보고 걸었기 때문이다. 그래서 **읽을 때마다 다시
 * 판정하고**, 켜는 자리에서도 같은 판정을 거치게 했다.
 *
 * ## 확인은 내용에 매단다
 *
 * 「이 파일은 보정값이다」라는 대답은 **그 내용**에 매단다. 파일 이름이나
 * 참·거짓 하나에 매달면, 이름이 같고 내용이 다른 파일을 넣었을 때 사람이
 * 하지 않은 확인이 새 파일에 따라붙는다.
 */

/** 곡선 파일 첫 줄에 남기는 주인 표시. 해석기가 건너뛰는 주석이다. */
const val KEY_COMMENT_PREFIX = "# selah-key: "

/**
 * 저장된 파일에서 **원본 CAL 내용**만 남긴다.
 *
 * ## 왜 떼어야 하는가
 *
 * 저장할 때 첫 줄에 `# selah-key: …` 를 붙인다. 그런데 읽을 때 그대로
 * 넘기면 그 줄이 **머리글 여덟 칸 중 하나를 차지한다**
 * (`CalibrationFile.MAX_HEADER_LINES`). 여덟째 줄에 있던 제조사 단서는
 * 창 밖으로 밀려난다 — **저장할 때는 「보정값」으로 읽혀 막혔던 파일이,
 * 다시 열면 단서가 사라져 그냥 통과한다.**
 *
 * 해시도 이 내용으로 뜬다. 앱이 붙인 줄까지 넣으면 저장 열쇠가 바뀔 때
 * 같은 파일의 해시가 달라져, 하지도 않은 파일 교체로 읽힌다.
 */
fun curveSourceText(raw: String): String =
    if (raw.startsWith(KEY_COMMENT_PREFIX)) raw.substringAfter('\n', "") else raw

/** 사람이 남긴 확인 기록. 셋이 모두 맞아야 인정한다. */
data class ReadingConfirmationRecord(
    /** 확인해 준 그 내용의 SHA-256. */
    val sha: String,
    /** [CurveReading] 의 이름. 모르는 이름이면 기록이 없는 것으로 친다. */
    val readingName: String,
    val rulesVersion: Int,
)

/** 이 파일을 지금 어떻게 읽을 것인가. */
data class CurveReadingResolution(
    /**
     * 걸 때 쓸 규약. **null 이면 사람이 정해야 한다** — 그동안은 걸지
     * 않는다.
     */
    val reading: CurveReading?,
    /** 자동 판정이 아니라 사람이 확인해 준 값인가. */
    val confirmed: Boolean,
    /** 지금 규칙으로 다시 내린 판정. 문구와 제안이 여기서 나온다. */
    val decision: ReadingDecision,
) {
    /** 사람이 정해 줘야 하는가. */
    val needsPerson: Boolean get() = reading == null

    /** 켜 두었을 때 **실제로** 걸리는가. */
    fun enabledWith(onFlag: Boolean): Boolean = onFlag && !needsPerson
}

/**
 * 이 파일을 어떻게 읽을지 **지금 규칙으로** 다시 정한다.
 *
 * 저장할 때의 판정을 믿지 않는다. 옛 판으로 저장된 파일은 그때 규칙으로
 * 통과한 것이고, 그 사이에 규칙이 두 번 바뀌었다(CF2-02 · CFR-02).
 *
 * @param sourceSha [curveSourceText] 로 떼어 낸 내용의 해시.
 * @param record 사람이 남긴 확인. 없으면 null.
 */
fun resolveCurveReading(
    headerLines: List<String>,
    sourceSha: String,
    record: ReadingConfirmationRecord?,
): CurveReadingResolution {
    val decision = decideReading(
        signEvidenceOf(headerLines),
        ReadingStakes.DisplayCurve,
        // **열 선언까지 넘긴다.** 빼면 `Frequency,Correction(dB)` 처럼
        // 둘째 열이 보정값이라고 **적혀 있는** 파일이 설명문 쪽으로 새어
        // 「단서 없음 → 관례(응답)」로 확정된다.
        columnDeclarationOf(headerLines),
    )
    val confirmed = confirmedReadingOf(record, sourceSha)
    return CurveReadingResolution(
        reading = confirmed ?: (decision as? ReadingDecision.Settled)?.reading,
        confirmed = confirmed != null,
        decision = decision,
    )
}

/**
 * 기록이 **이 내용에 대한** 확인이 맞는가. 아니면 null.
 *
 * 셋이 모두 맞아야 한다 — 기록이 있고, 내용 해시가 같고, 규칙 판이 같아야
 * 한다. 하나라도 어긋나면 확인이 없는 것으로 친다.
 *
 * - **해시가 다르면** 파일이 바뀐 것이다. 같은 이름으로 다른 내용을 넣은
 *   경우가 여기 걸린다.
 * - **규칙 판이 다르면** 앱이 묻던 말이 달라진 것이다. 옛 대답을 그대로
 *   쓰면 사람이 하지 않은 말을 한 것으로 친다.
 */
private fun confirmedReadingOf(
    record: ReadingConfirmationRecord?,
    sourceSha: String,
): CurveReading? {
    if (record == null) return null
    if (record.sha != sourceSha) return null
    if (record.rulesVersion != CURVE_READING_RULES_VERSION) return null
    return runCatching { CurveReading.valueOf(record.readingName) }.getOrNull()
}

/** 사람이 확인을 못 해서 막혔을 때 할 말. 막히지 않았으면 null. */
fun curveEnableRefusalKo(resolution: CurveReadingResolution): String? =
    if (resolution.needsPerson) {
        "이 파일은 읽는 법을 먼저 정해야 걸 수 있습니다. " +
            "「마이크 응답 으로 사용」이나 「보정값 으로 사용」을 골라 주십시오."
    } else {
        null
    }
