package kr.joa.selahrta.calibration

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.dsp.CalibrationFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private val Context.curveDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "curves")

/**
 * 지금 적용 중인 주파수 보정.
 *
 * **이 보정은 RTA 막대에만 걸린다.** 아래 [FREQUENCY_SCOPE_NOTE] 참고.
 */
data class ActiveCurve(
    val curve: CalibrationCurve,
    /** 가져온 파일 이름. 어느 파일인지 화면에 적는다. */
    val fileName: String,
    val pointCount: Int,
    val importedAtEpochMs: Long,
)

/**
 * 주파수 보정이 어디까지 걸리는지.
 *
 * **RTA 막대에만 건다. 큰 음압 숫자(dBA 등)에는 걸지 않는다.**
 *
 * 왜 그런가 — 주파수별 보정을 광대역 음압에 제대로 걸려면 시간 영역에서
 * 역응답 필터(FIR)를 통과시켜야 한다. 그 대신 「밴드마다 고쳐서 다시
 * 합치는」 손쉬운 방법을 쓰면 Fast/Slow 시간가중이 깨진다 — 규격이 정의한
 * 것은 시간 영역 신호의 지수 가중이지 밴드 합이 아니기 때문이다.
 *
 * 그래서 지금은 걸지 않고, 그 사실을 화면에 적는다. 다행히 이 선택의 대가는
 * 작다: 주파수 보정이 정말 필요한 경우(측정용 마이크)는 응답이 원래 평탄해서
 * (±2dB 안팎) 광대역 값에 미치는 영향이 0.5dB 아래이고, 그 정도는 기준
 * 소음계로 맞춘 전대역 보정이 이미 흡수한다.
 *
 * 나중에 FIR 을 넣으면 그때 광대역에도 걸 수 있다.
 */
const val FREQUENCY_SCOPE_NOTE: String =
    "주파수 보정은 RTA 막대에만 적용됩니다. 큰 음압 숫자는 전대역 보정값을 씁니다."

/**
 * 기기별 주파수 보정 곡선을 저장한다.
 *
 * 파일 내용은 앱 전용 폴더에 그대로 둔다 — 점이 수천 개인 파일도 있어서
 * 설정 저장소에 문자열로 넣기에는 크고, 원본을 남겨 두면 나중에 다시
 * 해석하거나 내보낼 수 있다.
 */
class CurveStore(private val context: Context) {

    private fun nameKey(k: CalibrationKey) = stringPreferencesKey("${k.storageKey()}|curveFile")
    private fun countKey(k: CalibrationKey) = intPreferencesKey("${k.storageKey()}|curvePoints")
    private fun atKey(k: CalibrationKey) = longPreferencesKey("${k.storageKey()}|curveAt")

    private fun curveDir(): File = File(context.filesDir, "curves").apply { mkdirs() }

    /** 파일 이름에 쓸 수 없는 글자를 뺀다. 기기 이름에 슬래시가 들어갈 수 있다. */
    private fun fileFor(k: CalibrationKey): File =
        File(curveDir(), k.storageKey().replace(Regex("[^A-Za-z0-9._-]"), "_") + ".cal")

    fun watch(key: CalibrationKey): Flow<ActiveCurve?> =
        context.curveDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { prefs ->
                val name = prefs[nameKey(key)] ?: return@map null
                val f = fileFor(key)
                if (!f.exists()) return@map null
                val loaded = runCatching { CalibrationFile.load(f.readText()) }
                    .getOrNull()?.getOrNull() ?: return@map null
                ActiveCurve(
                    curve = loaded.curve,
                    fileName = name,
                    pointCount = prefs[countKey(key)] ?: loaded.pointCount,
                    importedAtEpochMs = prefs[atKey(key)] ?: 0L,
                )
            }

    /**
     * 파일 내용을 저장한다. **먼저 해석해 보고, 안 되면 저장하지 않는다.**
     *
     * 읽히지 않는 파일을 저장해 두면 다음에 앱을 열 때 조용히 무시되고,
     * 담당자는 보정이 걸린 줄 안다.
     */
    suspend fun save(key: CalibrationKey, fileName: String, text: String): Result<ActiveCurve> =
        withContext(Dispatchers.IO) {
            val loaded = CalibrationFile.load(text).getOrElse { return@withContext Result.failure(it) }
            runCatching {
                fileFor(key).writeText(text)
                context.curveDataStore.edit { p ->
                    p[nameKey(key)] = fileName
                    p[countKey(key)] = loaded.pointCount
                    p[atKey(key)] = System.currentTimeMillis()
                }
                ActiveCurve(loaded.curve, fileName, loaded.pointCount, System.currentTimeMillis())
            }.recoverCatching {
                throw IOException("보정 파일을 저장하지 못했습니다: ${it.message}")
            }
        }

    suspend fun clear(key: CalibrationKey) = withContext(Dispatchers.IO) {
        runCatching {
            fileFor(key).delete()
            context.curveDataStore.edit { p ->
                p.remove(nameKey(key))
                p.remove(countKey(key))
                p.remove(atKey(key))
            }
        }
        Unit
    }
}
