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

    fun metaFile(id: String): File = File(dirOf(id), META_NAME)

    fun timelineFile(id: String): File = File(dirOf(id), TIMELINE_NAME)

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

    /** 겉장 하나를 읽는다. */
    fun readMeta(id: String): Result<SessionMeta> = runCatching {
        val f = metaFile(id)
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
