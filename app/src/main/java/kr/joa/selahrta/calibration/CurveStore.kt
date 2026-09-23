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
import kr.joa.selahrta.dsp.ReadingStakes
import kr.joa.selahrta.dsp.decideReading
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
    /**
     * 이 보정이 **어느 마이크의 것인지.** 사람이 적는다. 비어 있을 수 있다.
     *
     * **짓어내지 않는다.** 파일 머리글에 적혀 있기도 하지만, 거기서
     * 이름을 뽑아내 「이 마이크다」라고 말하면 틀렸을 때 더 나쁘다.
     * 오디오 인터페이스는 채널마다 다른 마이크가 꽂힐 수 있어, 어느 것이
     * 뭐인지는 꽂은 사람만 안다(USB 오디오 지시서 9.2).
     */
    val micName: String = "",
    val pointCount: Int,
    /**
     * 파일 앞의 머리글. **보정 부호를 가를 단서이고, 마이크가
     * 무엇인지도 보통 여기 적혀 있다.**
     *
     * 화면에 그대로 보인다 — 우리가 해석해 「이 마이크다」라고 말하지
     * 않는다. 짓어낸 이름이 틀리면 그것이 더 나쁘다.
     */
    val headerLines: List<String> = emptyList(),
    val importedAtEpochMs: Long,
    /**
     * 지금 **걸려 있는가.** 꺼도 파일은 그대로 둔다.
     *
     * 보정 전·후를 견주려면 꺼봤다 켜봐야 하는데, 그때마다 파일을
     * 다시 가져오게 하면 아무도 견주지 않는다(USB 오디오 지시서 11장).
     */
    val enabled: Boolean = true,
)

/**
 * 주파수 보정이 어디까지 걸리는지.
 *
 * **RTA 막대에만 건다. 큰 음압 숫자(dBA·Leq·MAX·PEAK)에는 걸지 않는다.**
 *
 * 왜 그런가 — 주파수별 보정을 광대역 음압에 제대로 걸려면 시간 영역에서
 * 역응답 필터(FIR/IIR)를 통과시켜야 한다. 「밴드마다 고쳐서 다시 합치는」
 * 손쉬운 방법은 쓸 수 없다: 규격이 정의한 Fast/Slow 는 시간 영역 신호의
 * 지수 가중이지 밴드 합이 아니고, 파형 PEAK 은 아예 밴드로 되살릴 수 없다.
 *
 * **남는 오차를 작다고 말하지 않는다.** 예전에 여기에 「±2dB 마이크면
 * 광대역 영향이 0.5dB 아래이고 전대역 보정이 흡수한다」고 적었는데, 그것은
 * 일반적으로 틀리다(독립 검증 R04). 남는 오차는 **보정을 맞춘 주파수의
 * 응답과 실제 소리가 놓인 주파수의 응답 차이**다:
 *
 * - 1kHz 에서 맞추고 그 마이크의 125Hz 응답이 +2dB 이면, 저역 위주 소리에서
 *   광대역 값이 +2dB 만큼 높게 나온다.
 * - 보정 기준 대역이 −2dB, 측정 대역이 +2dB 이면 차이는 4dB 까지 벌어진다.
 *
 * C−A 도 마찬가지다. 전대역 보정값은 양쪽에서 상쇄되지만 마이크 응답의
 * 기울기는 상쇄되지 않는다.
 *
 * 그래서 지금은 걸지 않고, **그 사실과 남는 오차의 크기를 화면에 적는다.**
 * 나중에 역응답 필터를 넣으면 그때 광대역에도 건다.
 */
const val FREQUENCY_SCOPE_NOTE: String =
    "주파수 보정은 RTA 막대에만 적용됩니다. 큰 음압 숫자(dBA·Leq·MAX·PEAK)는 " +
        "전대역 보정값만 씁니다 — 마이크 응답이 고르지 않으면, 보정을 맞춘 " +
        "주파수와 실제 소리가 놓인 주파수의 응답 차이만큼 오차가 남습니다."

/**
 * 기기별 주파수 보정 곡선을 저장한다.
 *
 * 파일 내용은 앱 전용 폴더에 그대로 둔다 — 점이 수천 개인 파일도 있어서
 * 설정 저장소에 문자열로 넣기에는 크고, 원본을 남겨 두면 나중에 다시
 * 해석하거나 내보낼 수 있다.
 */
/** 곡선 파일 첫 줄에 남기는 주인 표시. 해석기가 건너뛰는 주석이다. */
const val KEY_COMMENT_PREFIX = "# selah-key: "

class CurveStore(private val context: Context) {

    private fun nameKey(k: CalibrationKey) = stringPreferencesKey("${k.storageKey()}|curveFile")
    private fun countKey(k: CalibrationKey) = intPreferencesKey("${k.storageKey()}|curvePoints")
    private fun atKey(k: CalibrationKey) = longPreferencesKey("${k.storageKey()}|curveAt")

    /**
     * 걸어 둔 것을 사람이 꺼 두었는가.
     *
     * **없으면 켜진 것으로 본다.** 지금까지 저장된 곱선은 이 값이 없는데,
     * 그걸 「꺼짐」으로 읽으면 앱을 올리는 순간 보정이 조용히 풀린다.
     */
    private fun onKey(k: CalibrationKey) = stringPreferencesKey("${k.storageKey()}|curveOn")

    /** 사람이 적은 마이크 이름. */
    private fun micNameKey(k: CalibrationKey) = stringPreferencesKey("${k.storageKey()}|micName")

    private fun curveDir(): File = File(context.filesDir, "curves").apply { mkdirs() }

    /**
     * 열쇠를 파일 이름으로 옮긴다. **해시를 쓴다.**
     *
     * 예전에는 쓸 수 없는 글자를 전부 `_` 로 바꿨는데, 그러면 서로 다른
     * 기기가 같은 파일을 쓴다 — `Mic A` 와 `Mic_A` 가 둘 다
     * `cal_Usb_Mic_A_...cal` 이 된다(독립 검증 R09). 한쪽 곡선을 저장하면
     * 다른 쪽이 덮이고, 한쪽을 지우면 다른 쪽 파일이 사라진다. 설정 저장소의
     * 열쇠는 서로 달라서 화면은 곡선이 있다고 말하는데 파일 내용은 남의
     * 것이다.
     *
     * SHA-256 은 서로 다른 열쇠가 같은 이름이 될 걱정을 없애 준다. 대신
     * 폴더만 봐서는 어느 기기 것인지 알 수 없으므로, 원래 열쇠를 파일
     * 첫 줄에 주석으로 남긴다([KEY_COMMENT_PREFIX]).
     */
    private fun fileFor(k: CalibrationKey): File =
        File(curveDir(), sha256Hex(k.storageKey()) + ".cal")

    private fun sha256Hex(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 이전 판이 쓰던 파일 이름. **읽기만 한다.**
     *
     * 이름 규칙이 바뀌었으므로(R09) 예전에 저장한 곡선은 새 이름으로는
     * 찾을 수 없다. 조용히 사라지면 담당자는 보정이 걸린 줄 알고 재게
     * 된다.
     *
     * 그렇다고 옛 파일을 새 이름으로 옮기지도 않는다 — 옛 이름은 서로 다른
     * 기기가 같은 파일을 가리킬 수 있었으므로, 옮기는 순간 **어느 기기의
     * 것인지 정하는 셈**이 된다. 그 판단은 우리가 할 수 없다(독립 재검증
     * 추가 지적). 있다는 사실만 알리고 다시 가져오게 한다.
     */
    private fun legacyFileFor(k: CalibrationKey): File =
        File(curveDir(), k.storageKey().replace(Regex("[^A-Za-z0-9._-]"), "_") + ".cal")

    /** 이전 판의 곡선 파일이 남아 있는가. 화면이 「다시 가져오십시오」를 띄운다. */
    fun hasLegacyFile(key: CalibrationKey): Boolean =
        !fileFor(key).exists() && legacyFileFor(key).exists()

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
                    headerLines = loaded.headerLines,
                    importedAtEpochMs = prefs[atKey(key)] ?: 0L,
                    enabled = prefs[onKey(key)] != "false",
                    micName = prefs[micNameKey(key)].orEmpty(),
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
            val decision = decideReading(loaded.signEvidence, ReadingStakes.DisplayCurve)
            runCatching {
                // 원래 열쇠를 첫 줄 주석으로 남긴다. 해석기가 건너뛰는 줄이라
                // 곡선에는 영향이 없고, 폴더만 봐도 어느 기기 것인지 알 수 있다.
                val body = KEY_COMMENT_PREFIX + key.storageKey() + "\n" + text
                writeAtomically(fileFor(key), body)
                context.curveDataStore.edit { p ->
                    p[nameKey(key)] = fileName
                    p[countKey(key)] = loaded.pointCount
                    p[atKey(key)] = System.currentTimeMillis()
                    // 새로 가져오면 켜진다. 꺼 두고 가져왔는데 그대로 꺼져
                    // 있으면 「가져왔는데 아무 일도 안 일어난다」가 된다.
                    //
                    // **다만 읽는 법이 안 정해졌으면 켜지 않는다**(독립 검토
                    // R04). 머리글이 우리 가정과 반대를 가리키는 파일을 그대로
                    // 걸면 보정이 반대로 두 배 걸리고, 그 차이는 ±30dB 경고로
                    // 잡히지 않는다 — 측정 마이크 파일은 대개 ±5dB 안쪽이다.
                    if (decision.settled) p.remove(onKey(key)) else p[onKey(key)] = "false"
                }
                ActiveCurve(
                    curve = loaded.curve,
                    fileName = fileName,
                    // 읽는 법이 안 정해졌으면 꺼진 채로 들어온다(R04).
                    enabled = decision.settled,
                    pointCount = loaded.pointCount,
                    headerLines = loaded.headerLines,
                    importedAtEpochMs = System.currentTimeMillis(),
                )
            }.recoverCatching {
                throw IOException("보정 파일을 저장하지 못했습니다: ${it.message}")
            }
        }

    /**
     * 걸기를 켜거나 끔다. **파일은 그대로 둔다.**
     *
     * 지우는 것과 다르다 — 지우면 다시 가져와야 하고, 그러면 보정
     * 전·후를 견주지 못한다.
     */
    suspend fun setEnabled(key: CalibrationKey, on: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            context.curveDataStore.edit { p -> p[onKey(key)] = on.toString() }
        }
        Unit
    }

    /**
     * 어느 마이크의 보정인지 적어 둔다. 빈 문자열이면 지운다.
     *
     * 측정에는 아무 영향이 없다 — 사람이 뒤에 보고 알아보려고 두는 것이다.
     */
    suspend fun setMicName(key: CalibrationKey, name: String) = withContext(Dispatchers.IO) {
        runCatching {
            context.curveDataStore.edit { p -> p[micNameKey(key)] = name.trim() }
        }
        Unit
    }

    suspend fun clear(key: CalibrationKey) = withContext(Dispatchers.IO) {
        runCatching {
            fileFor(key).delete()
            context.curveDataStore.edit { p ->
                p.remove(nameKey(key))
                p.remove(countKey(key))
                p.remove(atKey(key))
                p.remove(onKey(key))
                p.remove(micNameKey(key))
            }
        }
        Unit
    }
}
