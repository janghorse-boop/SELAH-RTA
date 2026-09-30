package kr.joa.selahrta.recording

import kr.joa.selahrta.dsp.Weighting
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

        // **옛 겉장을 읽는 순간부터 게시가 끝날 때까지 한 잠금**
        // (독립 검토 R5-01).
        //
        // 이 사이가 몇 분이다. 예전에는 열려 있어서, 그 사이에 저장된 메모가
        // 게시에 덮여 **저장 성공이라고 답해 놓고 사라졌다**
        // (`R5_MEMO saveAcknowledged=true finalMemo=`).
        //
        // **분석까지 잠금 안에 둔다.** 분석을 밖에 두면 게시 때 판 번호를
        // 견주고 다시 셈하는 규칙이 따로 필요하다 — 그쪽이 더 복잡하고,
        // 기다리는 쪽은 같은 기록을 만지려던 사람뿐이다.
        store.withSession(id) {
            val old = store.readMeta(id).getOrThrow()
            val session = Reanalysis.run(audioFile, id, settings, onProgress).getOrThrow()
            if (session.rows.isEmpty()) {
                throw IOException("소리에서 잴 것이 없습니다. 기록을 그대로 두었습니다.")
            }

            val dir = store.dirOf(id)

            // **원본은 겉장까지 함께 남긴다**(독립 검토 R3-01).
            //
            // 예전에는 `timeline.bin` 만 복사했다. 그런데 행에 든 것은 **보정
            // 전 raw 값과 epoch 번호**다 — 그 번호를 풀 표(겉장의 `epochs`)가
            // 새 값으로 덮이면 **바이트는 남아도 원래 SPL 을 복원할 근거가
            // 사라진다.** 「처음 잰 값도 그대로 남아 있습니다」가 거짓이 된다.
            //
            // **한 번만 만든다.** 두 번째 재분석이 첫 번째 결과를 「원본」으로
            // 만들면 처음 잰 것이 사라진다.
            val original = store.originalDir(id)
            if (!original.isDirectory) {
                val tmp = File(dir, "$ORIGINAL_DIR.tmp")
                tmp.deleteRecursively()
                if (!tmp.mkdirs()) throw IOException("원본 자리를 만들지 못했습니다.")
                File(dir, SessionStore.TIMELINE_NAME).copyTo(File(tmp, SessionStore.TIMELINE_NAME))
                File(tmp, SessionStore.META_NAME).writeText(encodeSessionMeta(old))
                if (!tmp.renameTo(original)) {
                    tmp.deleteRecursively()
                    throw IOException("원본을 남기지 못했습니다. 기록을 그대로 두었습니다.")
                }
            }

            val next = old.merged(session, settings, nowMs)

            // **둘을 함께 게시한다**(독립 검토 R3-02).
            //
            // 타임라인을 먼저 바꾸고 겉장을 나중에 쓰면, 그 사이에 실패했을 때
            // **새 행과 옛 겉장이 함께 남는다** — 그래프·CSV·PDF 가 서로 맞지
            // 않는 설정으로 값을 해석한다. 실패를 돌려주면서 기록은 이미
            // 바뀌어 있는 셈이다.
            //
            // 그래서 **옆에 다 만들어 놓고 검사한 뒤**, 표시 파일 하나를
            // 만드는 것으로 게시한다. 이름 바꾸기 한 번이라 쪼개지지 않는다.
            publish(dir, next) { out ->
                val w = TimelineWriter(
                    out,
                    TimelineHeader(
                        nominalSampleRate = old.sampleRate,
                        rowMillis = TimelineFormat.ROW_MILLIS,
                    ),
                )
                session.rows.forEach { w.write(it) }
            }

            // 여기서 죽어도 다음에 열 때 [SessionStore.recover] 가 마저 옮긴다.
            store.recover(id)
            store.readMeta(id).getOrThrow()
        }
    }

    /**
     * **처음 잰 것으로 되돌린다.**
     *
     * 원본을 남겨 두고도 **꺼낼 길이 없으면** 「그대로 남습니다」는 확인할
     * 수 없는 말이다. 되돌릴 수 있어야 남겨 둔 뜻이 산다.
     *
     * **원본은 그대로 둔다** — 되돌린 뒤에 다시 분석할 수도 있다.
     * 게시하는 길은 [run] 과 같아서, **되돌리다 죽어도 반쪽이 안 된다.**
     */
    fun restoreOriginal(id: String): Result<SessionMeta> = runCatching {

        // 되돌리기도 **읽고-게시-복구가 한 판**이다(독립 검토 R5-01).
        store.withSession(id) {
            val dir = store.dirOf(id)
            // **둘이 다 있어야 원본이다.** 겉장만으로는 행을 못 읽고,
            // 타임라인만으로는 그 행이 무슨 보정의 것인지 모른다 — 그것이
            // 이번 회차(R3-01)에 배운 것이다.
            //
            // 검사를 둘로 나눠 두었더니 **뒤엣것이 앞엣것을 가려** 변이가
            // 안 잡혔다. 하나로 묻는다.
            val originalMeta = store.readOriginalMeta(id)
            val originalTimeline = store.originalTimelineFile(id)
            if (originalMeta == null || !originalTimeline.isFile) {
                throw IOException("이 기록에는 되돌릴 원본이 온전히 남아 있지 않습니다.")
            }

            // **메모는 사람의 것이지 분석의 것이 아니다.**
            //
            // 「처음 잰 **값**으로 되돌리기」인데 원본 겉장을 통째로 게시하면
            // **다시 분석한 뒤에 적은 메모가 조용히 사라진다.** 다시 분석
            // 쪽은 `merged()` 가 메모를 그대로 두고 있었으니, 되돌리기만
            // 어긋나 있던 셈이다.
            //
            // 검토가 짚은 자리는 아니지만 **같은 결의 결함**이라 여기서 함께
            // 고친다(잃는 자리는 잠금으로 막을 수 없다 — 제 손으로 지운다).
            val keepMemo = store.readMeta(id).getOrNull()?.memo ?: originalMeta.memo
            publish(dir, originalMeta.copy(memo = keepMemo)) { out ->
                originalTimeline.inputStream().buffered().use { it.copyTo(out) }
            }
            store.recover(id)
            store.readMeta(id).getOrThrow()
        }
    }

    /**
     * **타임라인과 겉장을 한 번에 게시한다**(독립 검토 R3-02).
     *
     * 옆에 다 만들어 **검사한 뒤**, 표시 파일 하나를 만드는 것으로
     * 게시한다 — 이름 바꾸기 한 번이라 쪼개지지 않는다. 게시 전에
     * 실패하면 **활성은 그대로**다.
     *
     * 다시 분석과 되돌리기가 **같은 길**을 쓴다. 게시하는 자리가 둘이면
     * 하나만 고쳐져 어긋난다.
     */
    private fun publish(
        dir: File,
        meta: SessionMeta,
        writeTimeline: (java.io.OutputStream) -> Unit,
    ) {
        val staged = File(dir, SessionStore.STAGING_DIR)
        staged.deleteRecursively()
        if (!staged.mkdirs()) throw IOException("새 판을 놓을 자리를 만들지 못했습니다.")
        try {
            File(staged, SessionStore.TIMELINE_NAME).outputStream().buffered()
                .use(writeTimeline)
            val text = encodeSessionMeta(meta)
            // **다시 읽어 본 뒤에 게시한다.** 쓸 수는 있는데 못 읽는 겉장을
            // 게시하면 그 기록이 목록에서 사라진다(R2-01 과 같은 성질).
            decodeSessionMeta(text).getOrThrow()
            File(staged, SessionStore.META_NAME).writeText(text)

            val ready = File(dir, SessionStore.READY_NAME)
            val readyTmp = File(dir, "${SessionStore.READY_NAME}.tmp")
            readyTmp.writeText(meta.id)
            if (!readyTmp.renameTo(ready)) {
                readyTmp.delete()
                throw IOException("새 판을 게시하지 못했습니다. 기록을 그대로 두었습니다.")
            }
        } catch (e: Throwable) {
            staged.deleteRecursively()
            throw e
        }
    }

    companion object {
        /**
         * 처음 잰 것이 통째로 들어앉는 자리(**겉장 + 타임라인**).
         *
         * 예전에는 `timeline-v1.bin` 하나였다. 행은 **보정 전 raw 값과
         * epoch 번호**라, 그 번호를 풀 표가 없으면 **바이트가 남아도
         * 원래 SPL 을 복원할 수 없다**(독립 검토 R3-01).
         */
        const val ORIGINAL_DIR = SessionStore.ORIGINAL_DIR
    }
}

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
    // **곡선의 이름도 따라간다**(독립 검토 R4-04). 값만 바꾸고 이름을
    // 두면 **새 곡선으로 셈해 놓고 옛 마이크 이름을 적는다.**
    curveLabel = if (settings.curve != null) settings.curveLabel else "",
    // **셈에 실제로 건 가중을 적는다**(독립 검토 R3-04).
    //
    // 예전에는 옛 값이 그대로 남아, **새 C 분석인데 겉장은 A** 라고
    // 말했다. 라벨과 숫자가 다르면 나중에 견줄 수가 없다.
    analysisWeighting = settings.analysisWeighting,
    // **PEAK 에는 가중이 안 걸린다.** 기록에 남는 peak 는
    // `SplFrame.blockPeakDbfs` 이고, 그 값은 A·C·Z 가 **모두 같다**
    // (재서 확인했다 — `SplEngine` 이 「Peak 는 가중 전에 잰다」).
    // 그러니 여기에 A 나 C 를 적으면 **걸지 않은 가중을 걸었다고 말하는 것**이다.
    peakWeighting = Weighting.Z,
    // **보정 상태도 셈을 따라간다.** 미보정으로 다시 셈했는데 옛
    // 「전대역 보정」이 남아 **미보정 경고가 통째로 사라졌다**(R3-04).
    conditions = conditions.copy(
        calibrationState = when {
            settings.referenceOnly -> kr.joa.selahrta.domain.CalibrationState.Uncalibrated
            settings.curve != null -> kr.joa.selahrta.domain.CalibrationState.FrequencyCalibrated
            else -> kr.joa.selahrta.domain.CalibrationState.GlobalCalibrated
        },
        // **곡선의 출처와 확인 근거도 새것으로**(독립 검토 R4-04).
        // 남겨 두면 「사람이 확인했다」가 **다른 곡선의 확인**이 된다.
        curveReading = if (settings.curve != null) settings.curveReading else null,
        curveReadingConfirmed = settings.curve != null && settings.curveReadingConfirmed,
        calibrationSource = settings.calibrationSource,
    ),
    reanalyzedAtEpochMs = nowMs,
    originalTimelineName = SessionReanalyzer.ORIGINAL_DIR,
)
