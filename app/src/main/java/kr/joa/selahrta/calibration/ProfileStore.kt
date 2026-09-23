package kr.joa.selahrta.calibration

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.joa.selahrta.dsp.CalibrationOutcome
import java.io.File

/**
 * 측정한 프로파일을 **폰에 남긴다**(지시서 6장).
 *
 * ## 무엇을 파일로 두는가
 *
 * 프로파일 하나가 파일 둘이다 — `<id>.profile`(적은 값)과
 * `<id>.curves`(곡선 넷). 곡선은 프로파일보다 훨씬 크고, 목록을 그릴
 * 때는 필요 없다. 목록을 열 때마다 곡선 수백 점을 다 읽으면 느려지고,
 * 곡선 하나가 깨졌다고 목록 전체가 안 보이게 된다.
 *
 * ## 못 읽는 파일을 지우지 않는다
 *
 * 지시서가 「손상·구버전 파일에 대한 안전한 마이그레이션/비활성화
 * 경로」를 요구한다. 못 읽는 파일을 조용히 지우면 **사람이 다시 잴
 * 기회를 잃는다** — 앱을 올리면 읽히는 파일일 수도 있다. 그래서
 * [StoredProfile.Damaged] 로 목록에 **남겨서 보여 주고**, 지우는 것은
 * 사람이 정한다.
 *
 * ## 왜 [Context] 가 아니라 [File] 을 받는가
 *
 * 폴더만 있으면 되는 일이라, 폴더만 받으면 **JVM 시험에서 그대로
 * 돌릴 수 있다.** 안드로이드를 띄워야만 확인되는 코드는 결국 확인되지
 * 않는다. 앱에서는 [forApp] 으로 만든다.
 */
class ProfileStore(private val dir: File) {

    companion object {
        fun forApp(context: Context): ProfileStore =
            ProfileStore(File(context.filesDir, "profiles"))

        /** 곡선 파일 이름은 **프로파일 id 에서 나온다.** 아래 참고. */
        fun curvesFileNameFor(id: String): String = "$id.curves"

        fun profileFileNameFor(id: String): String = "$id.profile"

        private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")

        /**
         * 파일 이름으로 써도 되는 글자인가.
         *
         * **파일에서 읽은 이름도 검사한다.** `curvesFileName` 은 파일
         * 안에 적힌 값이라, 손상되거나 손댄 파일이 `../../` 같은 것을
         * 담고 있을 수 있다. 그대로 [File] 에 넣으면 앱 폴더 밖을
         * 가리킨다.
         */
        internal fun isSafeName(name: String): Boolean =
            SAFE_NAME.matches(name) && !name.contains("..")
    }

    private fun folder(): File = dir.apply { mkdirs() }

    private fun profileFile(id: String) = File(folder(), profileFileNameFor(id))

    // ------------------------------------------------------------------
    // 저장
    // ------------------------------------------------------------------

    /**
     * 프로파일과 곡선을 함께 저장한다.
     *
     * ## 곡선을 먼저 쓴다
     *
     * 프로파일이 먼저 놓이면, 그 사이에 죽었을 때 **곡선이 없는
     * 프로파일**이 남는다. 목록에는 멀쩡히 보이면서 열면 비어 있다.
     * 거꾸로 곡선만 남는 것은 아무도 가리키지 않는 파일이라 해가 없고,
     * 여기서 지워 준다.
     *
     * ## 이름은 저장소가 정한다
     *
     * [MeasuredProfile.curvesFileName] 이 id 에서 나온 이름과 다르면
     * **거절한다.** 부르는 쪽이 이름을 자유로이 정하면 두 프로파일이
     * 같은 곡선 파일을 가리킬 수 있고, 그러면 하나를 지울 때 다른
     * 하나의 곡선이 사라진다.
     */
    suspend fun save(
        profile: MeasuredProfile,
        outcome: CalibrationOutcome,
    ): Result<MeasuredProfile> = withContext(Dispatchers.IO) {
        if (!isSafeName(profile.id)) {
            return@withContext Result.failure(
                IllegalArgumentException("프로파일 이름으로 쓸 수 없는 id 입니다: ${profile.id}"),
            )
        }
        val expected = curvesFileNameFor(profile.id)
        if (profile.curvesFileName != expected) {
            return@withContext Result.failure(
                IllegalArgumentException(
                    "곡선 파일 이름이 프로파일과 맞지 않습니다(${profile.curvesFileName} ≠ $expected).",
                ),
            )
        }

        val curves = File(folder(), expected)
        val curvesExisted = curves.exists()
        runCatching {
            writeAtomically(curves, encodeCurves(outcome))
            writeAtomically(profileFile(profile.id), encodeProfile(profile))
        }.fold(
            onSuccess = { Result.success(profile) },
            onFailure = { e ->
                // 이번에 만든 곡선만 걷어낸다. **먼저 있던 것은 두어야**
                // 한다 — 다시 저장하다 실패했다고 예전 곡선을 없애면
                // 멀쩡하던 프로파일이 빈 껍데기가 된다.
                if (!curvesExisted) curves.delete()
                Result.failure(e)
            },
        )
    }

    // ------------------------------------------------------------------
    // 읽기
    // ------------------------------------------------------------------

    /** 저장된 것 모두. 최근에 만든 것이 앞. 못 읽은 것도 함께 온다. */
    suspend fun list(): List<StoredProfile> = withContext(Dispatchers.IO) {
        val files = folder().listFiles { f: File -> f.isFile && f.name.endsWith(".profile") }
            ?: return@withContext emptyList()
        files.map { f ->
            val text = runCatching { f.readText() }.getOrElse { e ->
                return@map StoredProfile.Damaged(f.name, e.message ?: "파일을 읽지 못했습니다.")
            }
            decodeProfile(text).fold(
                onSuccess = { p ->
                    if (p.curvesFileName != curvesFileNameFor(p.id) || !isSafeName(p.curvesFileName)) {
                        // 파일 안의 이름을 그대로 믿지 않는다.
                        StoredProfile.Damaged(f.name, "곡선 파일 이름이 이상합니다: ${p.curvesFileName}")
                    } else {
                        StoredProfile.Ok(f.name, p)
                    }
                },
                onFailure = { e -> StoredProfile.Damaged(f.name, e.message ?: "읽지 못했습니다.") },
            )
        }.sortedByDescending { it.sortKey }
    }

    /**
     * 이 프로파일의 곡선 넷.
     *
     * 목록에서 하나를 열 때만 읽는다. 없으면 **없다고 말한다** — 빈
     * 곡선을 돌려주면 「보정이 0 이다」와 구별되지 않는다.
     */
    suspend fun loadCurves(profile: MeasuredProfile): Result<CalibrationOutcome> =
        withContext(Dispatchers.IO) {
            if (!isSafeName(profile.curvesFileName)) {
                return@withContext Result.failure(
                    IllegalArgumentException("곡선 파일 이름이 이상합니다: ${profile.curvesFileName}"),
                )
            }
            val f = File(folder(), profile.curvesFileName)
            if (!f.isFile) {
                return@withContext Result.failure(
                    java.io.FileNotFoundException("곡선 파일이 없습니다: ${profile.curvesFileName}"),
                )
            }
            runCatching { f.readText() }.fold(
                onSuccess = { decodeCurves(it) },
                onFailure = { Result.failure(it) },
            )
        }

    // ------------------------------------------------------------------
    // 고치기 · 지우기
    // ------------------------------------------------------------------

    /**
     * 자동 적용을 켜거나 끈다. **곡선은 건드리지 않는다.**
     *
     * 지우는 것과 다르다 — 꺼 둔 프로파일도 보정 전·후를 견주는 데 쓴다.
     */
    suspend fun setEnabled(
        profile: MeasuredProfile,
        enabled: Boolean,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Result<MeasuredProfile> = withContext(Dispatchers.IO) {
        val updated = profile.copy(enabled = enabled, updatedAtEpochMs = nowEpochMs)
        runCatching {
            writeAtomically(profileFile(profile.id), encodeProfile(updated))
            updated
        }
    }

    /**
     * 지운다. 프로파일 파일과 그 곡선 파일 **둘 다.**
     *
     * 못 읽는 파일도 지울 수 있어야 한다 — 지우지 못하면 목록에 영영
     * 남는다. 그래서 id 가 아니라 **파일 이름**을 받는다. 곡선 파일
     * 이름은 프로파일 파일 이름에서 나오므로, 내용을 읽지 못해도 짝을
     * 찾을 수 있다.
     */
    suspend fun delete(profileFileName: String): Boolean = withContext(Dispatchers.IO) {
        if (!isSafeName(profileFileName) || !profileFileName.endsWith(".profile")) {
            return@withContext false
        }
        val f = File(folder(), profileFileName)
        val id = profileFileName.removeSuffix(".profile")
        val curves = File(folder(), curvesFileNameFor(id))
        val gone = f.delete()
        curves.delete()
        gone
    }
}

/**
 * 저장된 프로파일 하나.
 *
 * 못 읽은 것을 [Damaged] 로 **목록에 남기는 것**이 요점이다. 걸러
 * 버리면 사람은 「없어졌다」고만 알게 되고, 왜 없어졌는지도, 다시 잴
 * 것이 무엇인지도 모른다.
 */
sealed interface StoredProfile {
    val fileName: String

    /** 목록을 세우는 값. 못 읽은 것은 뒤로 간다. */
    val sortKey: Long

    data class Ok(
        override val fileName: String,
        val profile: MeasuredProfile,
    ) : StoredProfile {
        override val sortKey: Long get() = profile.createdAtEpochMs
    }

    data class Damaged(
        override val fileName: String,
        val reasonKo: String,
    ) : StoredProfile {
        override val sortKey: Long get() = Long.MIN_VALUE
    }
}
