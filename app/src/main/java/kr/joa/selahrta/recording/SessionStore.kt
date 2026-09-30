package kr.joa.selahrta.recording

import java.io.File
import java.io.IOException

/**
 * 기록을 파일로 두고 찾아 준다(Phase 10).
 *
 * ## 한 세션 = 한 폴더
 *
 * ```
 * sessions/<id>/meta.txt      겉장 (SessionMeta)
 * sessions/<id>/timeline.bin  행 (TimelineFormat)
 * ```
 *
 * 폴더로 묶는 까닭은 **지우기가 한 번**이기 때문이다. 파일이 흩어져
 * 있으면 하나만 남아 목록에 유령이 뜬다 — 열면 아무것도 없는 기록이다.
 *
 * ## Room 을 쓰지 않는다
 *
 * 명세 표는 Room 을 적었지만, 녹음 설계 §5.2 와 그 독립 검토가 **고정
 * 길이 파일**을 택했다. 의존성이 늘지 않고, 건너뛰기가 나눗셈 한 번이며,
 * 무엇보다 **순수 JVM 에서 결정적으로 시험할 수 있다** — 이 저장소에서
 * 잡은 결함은 거의 전부 그렇게 잡았다.
 *
 * 세션을 가로지르는 질의(「하울링이 N회 넘은 예배 찾기」)가 필요해지면
 * 그때 타임라인을 읽어 넣으면 된다. 원본이 파일이라 되돌릴 수 있다.
 *
 * ## 겉장만 읽어 목록을 만든다
 *
 * [list] 는 `timeline.bin` 을 열지 않는다. 2시간짜리가 1.4MB 라, 목록을
 * 그리려고 전부 읽으면 화면이 멈춘다.
 */
class SessionStore(private val root: File) {

    /** 세션 하나가 들어앉을 폴더. */
    fun dirOf(id: String): File = File(root, id)

    fun metaFile(id: String): File {
        recover(id)
        return File(dirOf(id), META_NAME)
    }

    fun timelineFile(id: String): File {
        recover(id)
        return File(dirOf(id), TIMELINE_NAME)
    }

    /** 원본이 남아 있는 자리. 한 번도 다시 분석하지 않았으면 없다. */
    fun originalDir(id: String): File = File(dirOf(id), ORIGINAL_DIR)

    /** 원본 겉장과 타임라인. 없으면 null — **지금 것이 곧 원본이다.** */
    fun readOriginalMeta(id: String): SessionMeta? {
        val f = File(originalDir(id), META_NAME)
        if (!f.isFile) return null
        return decodeSessionMeta(f.readText()).getOrNull()
    }

    fun originalTimelineFile(id: String): File = File(originalDir(id), TIMELINE_NAME)

    /**
     * **게시하다 죽었으면 마저 게시한다**(독립 검토 R3-02).
     *
     * ## 왜 필요한가
     *
     * 다시 분석은 **타임라인과 겉장 둘을 함께** 갈아 끼운다. 파일 둘을
     * 차례로 옮기는 것은 **트랜잭션이 아니다** — 사이에서 죽으면 새 행과
     * 옛 겉장이 함께 남고, 그러면 **그래프·CSV·PDF 가 서로 맞지 않는
     * 설정으로 값을 해석한다.**
     *
     * 그래서 **한 번의 이름 바꾸기**로 게시한다. [READY_NAME] 이 생기는
     * 그 순간이 경계다. 그 뒤에 죽어도 다음에 열 때 여기서 마저 옮긴다.
     *
     * 읽는 자리마다 먼저 부른다. 값이 싸다 — 파일 하나가 있는지 볼 뿐이다.
     */
    fun recover(id: String) {
        val dir = dirOf(id)
        val ready = File(dir, READY_NAME)
        if (!ready.isFile) return
        val staged = File(dir, STAGING_DIR)
        val meta = File(staged, META_NAME)
        val timeline = File(staged, TIMELINE_NAME)
        // **둘 다 있어야 옮긴다.** 하나만 있으면 게시 준비가 덜 된 것이고,
        // 그 상태로 옮기면 반쪽짜리가 활성이 된다.
        //
        // **없으면 버린다.** 예전에는 그냥 건너뛰고 표시 파일을 그대로
        // 두었는데, 그러면 **열 때마다 같은 반쪽을 다시 보려 든다** —
        // 고쳐지지 않을 일을 영원히 되풀이한다. 게시되지 못한 판은
        // 쓸모가 없으므로 치운다.
        if (!meta.isFile || !timeline.isFile) {
            ready.delete()
            staged.deleteRecursively()
            return
        }
        runCatching {
            File(dir, TIMELINE_NAME).writeBytes(timeline.readBytes())
            File(dir, META_NAME).writeBytes(meta.readBytes())
        }.onFailure {
            // **숨기지 않는다**(독립 검토 R4-01).
            //
            // 예전에는 그냥 돌아갔다. 그러면 **타임라인은 새것인데 겉장은
            // 옛것**인 채로 `readMeta` 가 멀쩡히 값을 돌려주고, 다시 분석도
            // 「성공」으로 끝난다 — **새 행을 옛 보정으로 읽으면서**
            // 「다시 분석했습니다」라고 말하는 셈이다.
            //
            // 표시 파일은 **남겨 둔다.** 옮기다 실패한 것은 온전한 판이라
            // 저장공간이 비워지면 다음에 마저 옮길 여지가 있다. 다만
            // **그때까지 읽지 못하게** 막는다.
            throw IOException("기록을 복구하지 못했습니다: $id", it)
        }
        ready.delete()
        staged.deleteRecursively()
    }

    /**
     * 새 세션의 자리를 만든다.
     *
     * **아직 겉장을 쓰지 않는다.** 측정이 끝나야 길이·요약이 정해지기
     * 때문이다. 겉장 없는 폴더는 [list] 가 건너뛴다 — 재는 도중에 앱이
     * 죽어도 목록에 반쪽짜리가 뜨지 않는다.
     */
    fun create(id: String): Result<File> = runCatching {
        val dir = dirOf(id)
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException("기록 폴더를 만들지 못했습니다: $id")
        }
        dir
    }

    /**
     * 겉장을 쓴다. **마지막에 부른다** — 이것이 「이 기록은 온전하다」는
     * 표시다.
     *
     * 임시 이름으로 쓴 뒤 옮긴다. 쓰다가 죽으면 겉장이 아예 없는
     * 상태로 남아, 반쯤 쓰인 겉장을 읽는 일이 없다.
     */
    fun writeMeta(meta: SessionMeta): Result<Unit> = runCatching {
        val dir = dirOf(meta.id)
        if (!dir.isDirectory) throw IOException("기록 폴더가 없습니다: ${meta.id}")
        val tmp = File(dir, "$META_NAME.tmp")
        tmp.writeText(encodeSessionMeta(meta))
        val target = File(dir, META_NAME)
        if (!tmp.renameTo(target)) {
            // 옮기기가 안 되면 **덮어쓰기라도** 한다. 옮기기만 믿으면
            // 일부 저장소에서 기록이 통째로 사라진다.
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    /**
     * 메모만 고쳐 쓴다(명세 12장).
     *
     * ## 통째로 다시 쓴다
     *
     * 겉장은 `키=값` 줄의 묶음이라 한 줄만 바꿔치기할 수도 있지만,
     * 그렇게 하면 **읽는 쪽과 쓰는 쪽이 갈라진다** — 인코더가 바뀌면
     * 조용히 어긋난다. 읽어서 고치고 통째로 다시 쓰는 편이 안전하다.
     * 쓰는 자리는 [writeMeta] 하나뿐이므로 임시 파일 안전장치도 그대로
     * 따라온다.
     *
     * ## 빈 메모는 지우기다
     *
     * 공백만 적으면 화면에 빈 줄이 생긴다. 앞뒤를 떼고, 남는 것이
     * 없으면 「없음」으로 돌아간다.
     *
     * @return 없는 기록이면 실패. **조용히 새로 만들지 않는다.**
     */
    fun setMemo(id: String, memo: String): Result<Unit> = runCatching {
        val meta = readMeta(id).getOrThrow()
        writeMeta(meta.copy(memo = memo.trim().take(MEMO_MAX))).getOrThrow()
    }

    /** 겉장 하나를 읽는다. */
    fun readMeta(id: String): Result<SessionMeta> = runCatching {
        val f = metaFile(id)
        if (!f.isFile) throw IOException("기록이 없습니다: $id")
        decodeSessionMeta(f.readText()).getOrThrow()
    }

    /**
     * **겉장과 행을 한 판으로 읽는다**(독립 검토 R4-02).
     *
     * ## 왜 따로 읽으면 안 되나
     *
     * 예전에는 목록이 준 `meta` 를 그대로 들고 다니다가, 행은 나중에
     * `timelineFile()` 로 읽었다. 그 사이에 복구가 일어나면 **겉장과
     * 행이 서로 다른 판**이 된다 — 새 행에 옛 보정을 걸어 그리고,
     * 보고서는 옛 요약을 적는다.
     *
     * 여기서는 **복구를 먼저 끝내고**, 그 뒤에 둘을 **이어서** 읽는다.
     * 읽는 쪽은 이 한 덩어리만 쓴다.
     *
     * **이것으로 모든 경합이 닫히는 것은 아니다.** 읽는 동안 다른 곳에서
     * 게시가 일어나면 여전히 갈릴 수 있다 — 잠금까지는 아직 안 걸었다.
     * 지금 막은 것은 **재시작 뒤의 어긋남**이다.
     */
    fun readSnapshot(id: String): Result<SessionSnapshot> = runCatching {
        recover(id)
        val meta = readMetaAfterRecover(id).getOrThrow()
        val table = EpochTable(max = maxOf(1, meta.epochs.size))
            .also { t -> meta.epochs.forEach { t.add(it) } }
        val rows = File(dirOf(id), TIMELINE_NAME).let { f ->
            if (!f.isFile) emptyList() else f.inputStream().buffered().use {
                TimelineReader(it, table).all()
            }
        }
        SessionSnapshot(meta, rows)
    }

    /** 복구를 이미 끝낸 뒤에 겉장만. [readSnapshot] 안에서만 쓴다. */
    private fun readMetaAfterRecover(id: String): Result<SessionMeta> = runCatching {
        val f = File(dirOf(id), META_NAME)
        if (!f.isFile) throw IOException("기록이 없습니다: $id")
        decodeSessionMeta(f.readText()).getOrThrow()
    }

    /**
     * 기록 목록. **새것부터**.
     *
     * 읽을 수 없는 기록은 **건너뛰되 세어 둔다**([SessionList.broken]).
     * 조용히 빼면 「분명히 쟀는데 없다」가 되고, 막아 세우면 멀쩡한
     * 기록까지 못 보게 된다.
     */
    fun list(): SessionList {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val ok = ArrayList<SessionMeta>(dirs.size)
        var broken = 0
        for (d in dirs) {
            // **목록도 먼저 복구한다**(독립 검토 R4-02).
            //
            // 예전에는 목록만 `meta.txt` 를 곧바로 읽었다. 그래서 게시
            // 직후에 죽었다면 **목록은 옛 겉장, 상세는 새 타임라인**이
            // 되었다 — 상세 화면이 뒤늦게 복구하기 때문이다.
            // **동시에 여럿이 돌지 않아도** 일어난다.
            //
            // 복구가 실패하면 **그 기록만 「못 읽음」으로 센다.** 한 기록
            // 때문에 목록 전체가 막히면 멀쩡한 기록까지 못 보게 된다.
            if (runCatching { recover(d.name) }.isFailure) {
                broken++
                continue
            }
            val f = File(d, META_NAME)
            // 겉장이 없으면 **아직 안 끝난 기록**이다. 깨진 것과 다르다.
            if (!f.isFile) continue
            val r = runCatching { decodeSessionMeta(f.readText()).getOrThrow() }
            if (r.isSuccess) ok += r.getOrThrow() else broken++
        }
        ok.sortByDescending { it.startedAtEpochMs }
        return SessionList(ok, broken)
    }

    /**
     * 기록 하나를 지운다. **폴더째** 지운다.
     *
     * 파일 하나만 지우면 목록에 안 뜨는 채로 자리만 차지한다.
     */
    fun delete(id: String): Result<Unit> = runCatching {
        val dir = dirOf(id)
        if (!dir.exists()) return@runCatching
        if (!dir.deleteRecursively()) throw IOException("기록을 지우지 못했습니다: $id")
    }

    /**
     * 끝나지 않은 폴더를 치운다.
     *
     * 재는 도중에 앱이 죽으면 겉장 없는 폴더가 남는다. 목록에는 안 뜨지만
     * 자리를 차지하므로, 앱이 시작할 때 한 번 치운다.
     *
     * **돌아가는 세션은 건드리지 않는다** — 부르는 쪽이 [keepId] 로 지킨다.
     */
    fun sweepUnfinished(keepId: String? = null): Int {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return 0
        var removed = 0
        for (d in dirs) {
            if (d.name == keepId) continue
            if (File(d, META_NAME).isFile) continue
            if (d.deleteRecursively()) removed++
        }
        return removed
    }

    /** 기록이 쓰는 자리(바이트). 설정 화면이 적는다. */
    fun bytesUsed(): Long =
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val META_NAME = "meta.txt"
        const val TIMELINE_NAME = "timeline.bin"

        /** 처음 잰 것이 통째로 들어앉는 자리(겉장 + 타임라인). */
        const val ORIGINAL_DIR = "original"

        /** 게시를 기다리는 새 판. */
        const val STAGING_DIR = "staging"

        /**
         * **이 파일이 생기는 순간이 경계다.**
         *
         * 만드는 데 이름 바꾸기 한 번이면 되므로 **쪼개지지 않는다.**
         * 있으면 [STAGING_DIR] 을 활성으로 옮긴다.
         */
        const val READY_NAME = "staged.ready"

        /**
         * 세션 이름. **시각을 앞에 둔다** — 폴더를 이름순으로 늘어놓으면
         * 곧 시간순이 되고, 사람이 파일 탐색기에서 봐도 알아본다.
         *
         * 뒤에 짧은 무작위를 붙인다. 같은 초에 두 번 시작하는 일이
         * 없어야 하지만, 없다고 **가정**해 덮어쓰는 것보다 낫다.
         */
        fun newId(startedAtEpochMs: Long, salt: String): String {
            val t = java.time.Instant.ofEpochMilli(startedAtEpochMs)
                .toString()
                .replace(':', '-')
                .replace('.', '-')
                .removeSuffix("Z")
            return "$t-$salt"
        }
    }
}

/**
 * 목록과 **못 읽은 수**.
 *
 * 깨진 기록을 조용히 빼면 「분명히 쟀는데 없다」가 된다. 몇 개가
 * 읽히지 않았는지 화면이 말할 수 있게 함께 돌려준다.
 */
data class SessionList(
    val sessions: List<SessionMeta>,
    val broken: Int,
)

/**
 * **한 판**의 겉장과 행(독립 검토 R4-02).
 *
 * 둘을 따로 들고 다니면 **서로 다른 판**이 섞인다. 읽는 쪽은 이
 * 덩어리만 쓴다.
 */
data class SessionSnapshot(
    val meta: SessionMeta,
    /** 타임라인이 아직 없으면 빈 목록. **없는 것과 못 읽는 것은 다르다.** */
    val rows: List<TimelineRow>,
)
