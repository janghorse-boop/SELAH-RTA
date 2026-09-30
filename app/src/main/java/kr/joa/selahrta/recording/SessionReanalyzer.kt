package kr.joa.selahrta.recording

import java.io.File
import java.io.IOException

/**
 * **기록 하나를 다시 분석해 갈아 끼운다**(명세 Recording-E).
 *
 * 명세의 완료 기준은 「**원본 보존** + 새 분석 결과 생성」이다. 그래서
 * 여기서 지키는 것은 둘이다.
 *
 * ## 1. 원본 타임라인은 남긴다
 *
 * 처음 다시 분석할 때 `timeline.bin` 을 **`timeline-v1.bin` 으로
 * 복사해 둔다.** 그 뒤로는 덮지 않는다 — **두 번째 재분석이 첫 번째
 * 결과를 「원본」으로 만들어 버리면** 진짜 원본이 사라진다.
 *
 * ## 2. 지금 것은 `timeline.bin` 자리에 둔다
 *
 * 새 결과를 다른 이름으로 두면 **읽는 쪽을 전부 고쳐야 한다**(화면·CSV·
 * PDF). 고치는 곳이 많을수록 하나를 빠뜨려 **어떤 화면은 옛 숫자를
 * 보여 준다.** 자리는 그대로 두고 내용만 갈아 끼우는 편이 안전하다.
 *
 * ## 겉장에서 **바꾸는 것과 그대로 두는 것**
 *
 * 다시 셈해서 달라지는 것만 고친다:
 *
 * - **바꾼다** — 보정·가중치·시간가중, 그리고 그것으로 새로 난 요약
 *   (Leq·MIN·MAX·PEAK)·epoch·잘린 행 수.
 * - **그대로 둔다** — 언제 어디서 무엇으로 **쟀는가**. 그것은 소리를
 *   담을 때의 사실이라 다시 셈한다고 바뀌지 않는다.
 * - **특히 [SessionMeta.droppedPackets] 는 건드리지 않는다.** 녹음이
 *   놓친 조각은 **파일에 아예 없다** — 다시 읽어서는 몇 개를 놓쳤는지
 *   알 길이 없고, 0 으로 적으면 「안 놓쳤다」는 거짓이 된다.
 */
class SessionReanalyzer(private val store: SessionStore) {

    /**
     * [id] 기록을 [settings] 로 다시 분석한다.
     *
     * @param audioFile 그 기록의 소리 파일.
     * @param nowMs 다시 분석한 시각(벽시계).
     * @return 새 겉장. 실패하면 **아무것도 안 바뀐다.**
     */
    fun run(
        id: String,
        audioFile: File,
        settings: ReanalysisSettings,
        nowMs: Long,
        onProgress: ((Float) -> Unit)? = null,
    ): Result<SessionMeta> = runCatching {
        val old = store.readMeta(id).getOrThrow()
        val session = Reanalysis.run(audioFile, id, settings, onProgress).getOrThrow()
        if (session.rows.isEmpty()) {
            throw IOException("소리에서 잴 것이 없습니다. 기록을 그대로 두었습니다.")
        }

        val dir = store.dirOf(id)
        val current = store.timelineFile(id)
        val original = File(dir, ORIGINAL_TIMELINE_NAME)

        // **먼저 원본을 지켜 둔다.** 새 것을 쓰다 죽어도 원본은 남는다.
        // 이미 있으면 덮지 않는다 — 두 번째 재분석이 첫 번째 결과를
        // 「원본」으로 만들면 진짜 원본이 사라진다.
        if (!original.isFile && current.isFile) {
            current.copyTo(original, overwrite = false)
        }

        // **임시로 쓴 뒤 옮긴다.** 쓰다 죽으면 옛 타임라인이 그대로 남는다.
        val tmp = File(dir, "$TIMELINE_TMP_NAME")
        tmp.outputStream().buffered().use { out ->
            val w = TimelineWriter(
                out,
                TimelineHeader(
                    nominalSampleRate = settings.sampleRateOf(old),
                    rowMillis = TimelineFormat.ROW_MILLIS,
                ),
            )
            session.rows.forEach { w.write(it) }
        }
        if (!tmp.renameTo(current)) {
            // 옮기기가 안 되는 저장소가 있다. **덮어쓰기라도** 한다.
            current.writeBytes(tmp.readBytes())
            tmp.delete()
        }

        val next = old.merged(session, settings, nowMs)
        store.writeMeta(next).getOrThrow()
        next
    }

    companion object {
        /** 처음 다시 분석할 때 원본을 여기로 옮겨 둔다. */
        const val ORIGINAL_TIMELINE_NAME = "timeline-v1.bin"
        private const val TIMELINE_TMP_NAME = "timeline.bin.tmp"
    }
}

/**
 * 소리에 적힌 샘플레이트를 못 읽었을 때를 위한 되돌아갈 값.
 *
 * [Reanalysis] 는 **파일 머리의 값**을 쓴다. 겉장의 값과 다르면 소리 쪽이
 * 맞다 — 여기는 타임라인 머리에 적을 값을 고르는 자리일 뿐이다.
 */
private fun ReanalysisSettings.sampleRateOf(old: SessionMeta): Int = old.sampleRate

/**
 * 다시 셈한 결과를 겉장에 녹인다.
 *
 * **달라지는 것만 고친다.** 무엇을 그대로 두는지는 [SessionReanalyzer]
 * 머리말에 적어 두었다.
 */
private fun SessionMeta.merged(
    session: RecordedSession,
    settings: ReanalysisSettings,
    nowMs: Long,
): SessionMeta = copy(
    schemaVersion = SESSION_SCHEMA_VERSION,
    durationMs = session.durationMs,
    calibrationOffsetDb = settings.offsetDb,
    referenceOnly = settings.referenceOnly,
    weighting = settings.weighting,
    timeWeight = settings.timeWeight,
    leqWindowMs = settings.leqWindowMs,
    // **없는 값을 0 으로 적지 않는다.** 못 낸 값은 옛 값을 그대로 둔다.
    leqDb = session.summary.leqDb ?: leqDb,
    minDb = session.summary.minDb ?: minDb,
    maxDb = session.summary.maxDb ?: maxDb,
    peakDb = session.summary.peakDb ?: peakDb,
    epochs = session.epochs,
    events = session.events,
    clippedRows = session.rows.count { it.clipped },
    curveApplied = settings.curve != null,
    reanalyzedAtEpochMs = nowMs,
    originalTimelineName = SessionReanalyzer.ORIGINAL_TIMELINE_NAME,
)
