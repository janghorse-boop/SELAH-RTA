package kr.joa.selahrta.data.rta

import kr.joa.selahrta.calibration.Reader
import kr.joa.selahrta.calibration.parseLines
import kr.joa.selahrta.calibration.put
import kr.joa.selahrta.dsp.ThirdOctave
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * **저장한 RTA 측정을 담아 두는 곳**(담당자 지시 2026-09-29, 지시서 §7).
 *
 * ## 왜 파일인가
 *
 * DataStore 는 보정·곡선 **설정**을 담는 자리라 31칸 실수 배열을 여러 벌
 * 담기에 맞지 않고, Room 을 새로 들이는 것은 이 크기에 과하다.
 *
 * 기록 탭의 `SessionStore` 가 이미 쓰는 방식을 그대로 따른다 — **기록마다
 * 폴더 하나, 겉장은 `.tmp` 로 쓴 뒤 이름을 바꾼다.** 쓰다 죽으면 겉장이
 * 아예 없는 상태로 남아, **반쯤 쓰인 겉장을 읽는 일이 없다.**
 *
 * 겉장 모양도 집안 형식(`key=value`)을 쓴다. `SessionMeta`·교정 프로필과
 * 같은 `parseLines`·`put` 이다 — 한 저장소에 형식이 둘이면 escape 규칙이
 * 갈려 어느 날 한쪽만 깨진다.
 *
 * ## 깨진 것 하나가 나머지를 막지 않는다
 *
 * 읽다 실패한 폴더는 **그 하나만 건너뛴다.** 기록 하나가 상했다고 나머지
 * 스무 개를 못 보게 하면, 사람은 앱을 지우고 다시 깐다.
 */
class RtaMeasurementStore(
    private val root: File,
    /**
     * 이름 바꾸기. **시험이 실패하게 만들어 볼 수 있게** 밖에서도 받는다.
     *
     * 교체가 실패했을 때 **옛 기록이 살아남는가**는 일부러 실패시켜
     * 보지 않고는 확인할 길이 없다(독립 검토 12회차 4장).
     */
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) {

    private fun dirOf(id: String) = File(root, id)

    /** 측정 하나를 남긴다. **있던 것을 덮지 않는다** — 새 [id] 로 온다. */
    fun save(m: RtaMeasurement): Result<Unit> = runCatching {
        require(m.bandsSpl.size == ThirdOctave.BAND_COUNT) {
            "밴드가 ${m.bandsSpl.size}칸이다. ${ThirdOctave.BAND_COUNT}칸이어야 한다."
        }
        val dir = dirOf(m.id)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("폴더를 만들지 못했습니다: ${m.id}")
        writeAtomically(File(dir, META_NAME), encode(m))
    }

    /** 남아 있는 측정 전부. **읽히는 것만** 돌려준다. */
    fun list(): List<RtaMeasurement> = listing().items

    /**
     * 목록과 **못 읽은 것의 수**를 함께 돌려준다(독립 검토 12회차 4장).
     *
     * ## 왜 수를 세어 돌려주나
     *
     * [list] 는 못 읽은 겉장을 **조용히 건너뛴다.** 그러면 새 판으로 저장한
     * 기록이 있는 기기에서 **아무 말 없이 목록이 짧아진다** — 사람은 저장이
     * 안 된 줄 알고 다시 잰다. 「읽을 수 없다」와 「없다」는 다른 말이다.
     */
    fun listing(): RtaListing {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return RtaListing(emptyList(), 0)
        var unreadable = 0
        val items = dirs.mapNotNull { d ->
            val f = File(d, META_NAME)
            if (!f.isFile) return@mapNotNull null
            decodeOrNull(f).also { if (it == null) unreadable++ }
        }.sortedBy { it.measuredAtEpochMs }
        return RtaListing(items, unreadable)
    }

    private fun decodeOrNull(f: File): RtaMeasurement? =
        runCatching { decode(f.readText()) }.getOrNull()

    fun delete(id: String) {
        dirOf(id).deleteRecursively()
    }

    /** 세트째 지운다. 그 안의 측정이 다 사라진다. */
    fun deleteSet(setId: String) {
        list().filter { it.setId == setId }.forEach { delete(it.id) }
        File(setsFile().parentFile, SETS_NAME).takeIf { it.isFile } ?: return
        synchronized(setsLock) {
            val kept = readSets().filterNot { it.id == setId }
            writeSets(kept)
        }
    }

    /** 세트 파일은 여러 벌이 한 장에 있다 — 읽고-고쳐-쓰기를 한 줄로 세운다. */
    private val setsLock = Any()

    // ── 비교 세트 ────────────────────────────────────────

    /**
     * 세트 목록. **이름을 안 지은 세트도 나온다.**
     *
     * 이름은 따로 적어 두는 것이라, 저장만 하고 이름을 안 지으면 이름표가
     * 없다. 그렇다고 목록에서 빼면 **저장은 됐는데 안 보이는** 기록이
     * 생긴다 — 그쪽이 훨씬 나쁘다.
     */
    fun sets(): List<RtaComparisonSet> {
        val named = readSets().associateBy { it.id }
        val used = list().groupBy { it.setId }
        return used.map { (id, items) ->
            named[id] ?: RtaComparisonSet(
                id = id,
                nameKo = "",
                createdAtEpochMs = items.minOf { it.measuredAtEpochMs },
            )
        }.sortedBy { it.createdAtEpochMs }
    }

    /**
     * 세트 이름을 짓거나 바꾼다. 측정은 건드리지 않는다.
     *
     * **읽고-고쳐-쓰기를 한 줄로 세운다**(독립 검토 12회차 4장). 세트는
     * 여러 벌이 **한 파일**에 있어서, 두 곳에서 동시에 이름을 지으면 나중
     * 것이 앞의 것을 **통째로 덮는다** — 앞서 지은 이름이 소리 없이 사라진다.
     */
    fun putSet(set: RtaComparisonSet): Result<Unit> = runCatching {
        synchronized(setsLock) {
            if (!root.isDirectory && !root.mkdirs()) throw IOException("폴더를 만들지 못했습니다")
            val kept = readSets().filterNot { it.id == set.id } + set
            writeSets(kept)
        }
    }

    // ── 겉장 ─────────────────────────────────────────────

    private fun setsFile() = File(root, SETS_NAME)

    private fun readSets(): List<RtaComparisonSet> {
        val f = setsFile()
        if (!f.isFile) return emptyList()
        return runCatching {
            f.readText().lineSequence()
                .mapNotNull { line ->
                    val r = Reader(parseLines(line.replace(RECORD_SEP, "\n")))
                    val id = r.strOrNull("id") ?: return@mapNotNull null
                    RtaComparisonSet(
                        id = id,
                        nameKo = r.strOrNull("nameKo").orEmpty(),
                        createdAtEpochMs = r.longOrNull("createdAtEpochMs") ?: 0L,
                    )
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun writeSets(sets: List<RtaComparisonSet>) {
        val text = sets.joinToString("\n") { s ->
            buildString {
                put("id", s.id)
                put("nameKo", s.nameKo)
                put("createdAtEpochMs", s.createdAtEpochMs)
            }.trim().replace("\n", RECORD_SEP)
        }
        writeAtomically(setsFile(), text)
    }

    /**
     * **임시 이름으로 쓴 뒤 옮긴다. 못 옮기면 그대로 실패한다.**
     *
     * ## 덮어쓰기 갈래를 없앤 까닭 (독립 검토 12회차 4장)
     *
     * 예전에는 옮기기가 실패하면 **원본에 직접 덮어썼다.** 그것은 「안전한
     * 대비책」이 아니라 **원자 교체를 포기하는 일**이다 — 덮어쓰는 도중에
     * 끊기면 **멀쩡하던 옛 기록까지 함께 잃는다.** 새 기록을 못 쓰는 것과
     * 옛 기록을 잃는 것은 **피해의 크기가 다르다.**
     *
     * 끝 표시(`end=1`)가 이 자리를 대신하지 못한다. 그것은 **잘린 새 파일을
     * 걸러 낼 뿐**, 덮어쓰다 잃은 옛 파일을 되살리지 않는다. 둘이 같은
     * 보장인 줄 알고 `docs/unverified.md` 에 「구별되지 않는 자리」로
     * 적어 두었는데, **틀린 묶음이었다.**
     *
     * 그래서 **실패는 실패로 알린다.** 부르는 쪽이 사람에게 말한다.
     */
    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!rename(tmp, target)) {
            tmp.delete()
            error("임시 파일을 옮기지 못했습니다: ${target.name}")
        }
    }

    companion object {
        const val META_NAME = "meta.txt"
        const val SETS_NAME = "sets.txt"

        /**
         * 세트 한 줄 안에서 줄바꿈 대신 쓰는 표.
         *
         * 세트는 여러 벌을 한 파일에 담으므로 **한 줄에 한 벌**이어야 한다.
         * `esc` 가 이미 `\n` 을 `\\n` 으로 바꾸므로 이 글자가 값에 섞여
         * 들어올 일이 없다.
         */
        private const val RECORD_SEP = "\u0001"

        const val SCHEMA_VERSION = 1

        fun newId(): String = UUID.randomUUID().toString()

        internal fun encode(m: RtaMeasurement): String = buildString {
            put("schemaVersion", SCHEMA_VERSION)
            put("id", m.id)
            put("setId", m.setId)
            put("nameKo", m.nameKo)
            put("method", m.method)
            put("bandsSpl", m.bandsSpl.joinToString(",") { it.toString() })
            put("signal", m.signal)
            put("channel", m.channel)
            put("outputDbfs", m.outputDbfs)
            put("averagedFrames", m.averagedFrames)
            put("inputKey", m.conditions.inputKey)
            put("calibrationState", m.conditions.calibrationState)
            put("calibrationSource", m.conditions.calibrationSource)
            put("curveName", m.conditions.curveName)
            put("fftSize", m.conditions.fftSize)
            put("sampleRate", m.conditions.sampleRate)
            put("analysisWeighting", m.conditions.analysisWeighting)
            put("offsetDb", m.conditions.offsetDb)
            put("curveHash", m.conditions.curveHash)
            put("inputSource", m.conditions.inputSource)
            put("inputChannel", m.conditions.inputChannel)
            put("signalSpec", m.conditions.signalSpec)
            put("measuredAtEpochMs", m.measuredAtEpochMs)
            put("memoKo", m.memoKo)
            // **끝 표시를 맨 뒤에 둔다.** 쓰다가 죽으면 이 줄이 없으므로
            // 잘린 겉장을 온전한 것으로 읽는 일이 없다.
            //
            // 바꿔치기(`.tmp` → 이름 바꾸기)와 **둘이 다른 자리를 막는다**:
            // 바꿔치기는 잘린 겉장이 애초에 생기지 않게 하고, 이 표시는
            // 그래도 생겼을 때(다른 저장소·복사·동기화) 읽히지 않게 한다.
            put("end", 1)
        }

        internal fun decode(text: String): RtaMeasurement {
            val r = Reader(parseLines(text))
            val schema = r.intOrNull("schemaVersion")
                ?: throw IllegalArgumentException("측정 기록이 아닙니다(판 번호가 없습니다).")
            if (schema > SCHEMA_VERSION) {
                throw IllegalArgumentException("더 새 판(v$schema)의 기록입니다. 앱을 올린 뒤 여십시오.")
            }
            // **`doubles` 를 쓴다.** 손으로 쪼개면 NaN·무한대가 그대로 들어와,
            // 저장 곡선을 그릴 때 차트가 조용히 깨진다.
            val bands = r.doubles("bandsSpl")
                ?: throw IllegalArgumentException("측정 곡선을 읽지 못했습니다.")
            require(bands.size == ThirdOctave.BAND_COUNT) {
                "밴드가 ${bands.size}칸입니다. ${ThirdOctave.BAND_COUNT}칸이어야 합니다."
            }
            val id = r.strOrNull("id")?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("번호가 없습니다.")
            // 끝 표시가 없으면 **쓰다 만 것**이다. 반쯤 읽어 쓰지 않는다.
            if (r.intOrNull("end") != 1) {
                throw IllegalArgumentException("기록이 끝까지 쓰이지 않았습니다.")
            }
            return RtaMeasurement(
                id = id,
                setId = r.str("setId"),
                nameKo = r.str("nameKo"),
                method = r.strOrNull("method").orEmpty().ifBlank { "rta" },
                bandsSpl = bands,
                signal = r.str("signal"),
                channel = r.str("channel"),
                outputDbfs = r.dblOrNull("outputDbfs") ?: 0.0,
                averagedFrames = r.intOrNull("averagedFrames") ?: 0,
                // **없는 것은 없는 채로 둔다**(담당자 지시 기준 5). 지금
                // 설정이나 기본값으로 채우면 「이 조건으로 쟀다」는 거짓이
                // 만들어져, 다음에 견줄 때 다른 조건인데 같다고 읽힌다.
                conditions = RtaConditions(
                    inputKey = r.strOrNull("inputKey"),
                    calibrationState = r.strOrNull("calibrationState"),
                    calibrationSource = r.strOrNull("calibrationSource"),
                    curveName = r.strOrNull("curveName"),
                    fftSize = r.intOrNull("fftSize"),
                    sampleRate = r.intOrNull("sampleRate"),
                    analysisWeighting = r.strOrNull("analysisWeighting"),
                    offsetDb = r.dblOrNull("offsetDb"),
                    curveHash = r.strOrNull("curveHash"),
                    inputSource = r.strOrNull("inputSource"),
                    inputChannel = r.intOrNull("inputChannel"),
                    signalSpec = r.strOrNull("signalSpec"),
                ),
                measuredAtEpochMs = r.longOrNull("measuredAtEpochMs") ?: 0L,
                memoKo = r.strOrNull("memoKo").orEmpty(),
            )
        }
    }
}
