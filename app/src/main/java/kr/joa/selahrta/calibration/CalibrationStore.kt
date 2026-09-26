package kr.joa.selahrta.calibration

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.calibrationDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "calibration")

/**
 * 기기·입력경로별 보정값을 폰에 남긴다(명세 8장).
 *
 * 값 하나가 아니라 [CalibrationKey] 마다 따로 둔다 — USB 마이크를 꽂았다
 * 뺐다 해도 각자의 보정이 살아 있어야 한다.
 */
class CalibrationStore(private val context: Context) {

    private fun offsetKey(k: CalibrationKey) = doublePreferencesKey("${k.storageKey()}|offset")
    private fun refKey(k: CalibrationKey) = doublePreferencesKey("${k.storageKey()}|ref")
    private fun measuredKey(k: CalibrationKey) = doublePreferencesKey("${k.storageKey()}|measured")
    private fun savedAtKey(k: CalibrationKey) = longPreferencesKey("${k.storageKey()}|savedAt")

    /** 교정할 때의 입력 잡음 바닥(dBFS). 옛 기록에는 없다(독립 검토 R06). */
    private fun noiseKey(k: CalibrationKey) = doublePreferencesKey("${k.storageKey()}|noiseFloor")

    /** 무엇에 맞춘 보정인가(교정기·소음계). 옛 기록에는 없다. */
    private fun sourceKey(k: CalibrationKey) = stringPreferencesKey("${k.storageKey()}|calSource")

    /**
     * **어느 자리에서 잰 것인가.** 옛 기록에는 없다(독립 재검토 CAR-03).
     *
     * 저장 열쇠에 자리가 없어서, 이 값이 없으면 `bottom` 에서 잰 감도를
     * `back` 에 그대로 걸면서 「보정 완료」로 적는다.
     */
    private fun routeKey(k: CalibrationKey) = stringPreferencesKey("${k.storageKey()}|route")

    /**
     * 이 조합의 보정값을 지켜본다. 없으면 null 이 흐른다.
     *
     * 읽기가 실패해도 앱이 멈추면 안 된다 — 보정이 없는 것과 같게 다루고
     * 화면은 「미보정」으로 간다. 측정 자체는 보정 없이도 되어야 한다.
     */
    fun watch(key: CalibrationKey): Flow<GlobalCalibration?> =
        context.calibrationDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { prefs ->
                val offset = prefs[offsetKey(key)] ?: return@map null
                GlobalCalibration(
                    offsetDb = offset,
                    savedAtEpochMs = prefs[savedAtKey(key)] ?: 0L,
                    referenceDb = prefs[refKey(key)] ?: Double.NaN,
                    measuredDbfs = prefs[measuredKey(key)] ?: Double.NaN,
                    // 옛 기록에는 없다. **없는 것을 0 으로 채우지 않는다** —
                    // 0dBFS 잡음은 말이 안 되고, 그 값으로 견주면 늘
                    // 「이득이 바뀌었다」가 된다.
                    noiseFloorDbfs = prefs[noiseKey(key)],
                    // 모르는 이름이면 「기록 없음」이다. 임의로 고르지 않는다.
                    source = prefs[sourceKey(key)]
                        ?.let { runCatching { CalibrationSource.valueOf(it) }.getOrNull() }
                        ?: CalibrationSource.Unknown,
                    // **빈 값은 없는 것과 같다.** 옛 기록에는 자리가 없고,
                    // 빈 문자열을 자리로 읽으면 「모른다」가 「같다」가 된다.
                    routeAddress = prefs[routeKey(key)]?.takeIf { it.isNotEmpty() },
                )
            }

    /**
     * 보정값을 저장한다.
     *
     * 그럴듯하지 않은 값은 **저장하지 않고 거부한다.** 기준값 자리에 dBFS 를
     * 적었거나 측정이 안정되기 전에 눌렀을 때인데, 조용히 저장하면 그 뒤의
     * 모든 숫자가 틀린 채로 그럴듯해 보인다.
     */
    suspend fun save(key: CalibrationKey, cal: GlobalCalibration): SaveResult {
        if (cal.referenceDb !in PLAUSIBLE_REFERENCE_RANGE) {
            return SaveResult.Rejected(
                "기준 소음계 값이 ${PLAUSIBLE_REFERENCE_RANGE.start.toInt()}~" +
                    "${PLAUSIBLE_REFERENCE_RANGE.endInclusive.toInt()} dB 범위를 벗어납니다.",
            )
        }
        if (cal.offsetDb !in PLAUSIBLE_OFFSET_RANGE) {
            return SaveResult.Rejected(
                "계산된 보정값 ${"%.1f".format(cal.offsetDb)} dB 이 " +
                    "보통 범위(${PLAUSIBLE_OFFSET_RANGE.start.toInt()}~" +
                    "${PLAUSIBLE_OFFSET_RANGE.endInclusive.toInt()} dB)를 크게 벗어납니다. " +
                    "소리가 안정된 뒤에 다시 해 보십시오.",
            )
        }
        return try {
            context.calibrationDataStore.edit { p ->
                p[offsetKey(key)] = cal.offsetDb
                p[refKey(key)] = cal.referenceDb
                p[measuredKey(key)] = cal.measuredDbfs
                p[savedAtKey(key)] = cal.savedAtEpochMs
                // **모르면 적지 않는다.** 0 으로 채우면 나중에 견줄 때
                // 늘 「이득이 바뀌었다」가 된다.
                val noise = cal.noiseFloorDbfs
                if (noise != null && noise.isFinite()) p[noiseKey(key)] = noise else p.remove(noiseKey(key))
                p[sourceKey(key)] = cal.source.name
                // **모르면 적지 않는다.** 빈 문자열을 적으면 다음에 읽을 때
                // 「그 자리에서 쟀다」가 되어 검사가 통째로 무력해진다.
                val route = cal.routeAddress
                if (!route.isNullOrEmpty()) p[routeKey(key)] = route else p.remove(routeKey(key))
            }
            SaveResult.Saved
        } catch (e: IOException) {
            // 저장에 실패했는데 성공했다고 말하면 안 된다. 다음에 앱을 열면
            // 보정이 사라져 있는데 담당자는 이유를 알 수 없다.
            SaveResult.Rejected("보정값을 저장하지 못했습니다: ${e.message}")
        }
    }

    /**
     * **사람이** 「이 자리에서 잰 것이 맞다」고 확인해 준다(CAR-03).
     *
     * 값은 건드리지 않고 **빠져 있던 자리만** 채운다. 앱이 저절로 채우지
     * 않는 까닭은 그러면 확인하지 않은 것을 확인했다고 적는 꼴이기
     * 때문이다 — 「현재 주소를 과거 측정 주소로 채우는 마이그레이션은
     * 피한다」(검토자 3번 답).
     *
     * **이미 다른 자리가 적혀 있으면 덮어쓰지 않는다.** 그때는 진짜로
     * 옮겨 온 것이므로 다시 재야 한다.
     */
    suspend fun confirmRoute(key: CalibrationKey, address: String): SaveResult {
        if (address.isEmpty()) {
            return SaveResult.Rejected("지금 마이크가 어느 자리에 붙었는지 확인하지 못했습니다.")
        }
        return try {
            var rejected: String? = null
            context.calibrationDataStore.edit { p ->
                if (p[offsetKey(key)] == null) {
                    rejected = "이 경로에는 저장된 보정이 없습니다."
                    return@edit
                }
                val known = p[routeKey(key)]
                if (!known.isNullOrEmpty() && known != address) {
                    rejected = "저장된 보정은 $known 자리에서 잰 것입니다. " +
                        "이 자리에서 쓰려면 간편 보정을 다시 하십시오."
                    return@edit
                }
                p[routeKey(key)] = address
            }
            rejected?.let { SaveResult.Rejected(it) } ?: SaveResult.Saved
        } catch (e: IOException) {
            SaveResult.Rejected("확인을 저장하지 못했습니다: ${e.message}")
        }
    }

    /** 이 조합의 보정을 지운다. */
    suspend fun clear(key: CalibrationKey) {
        runCatching {
            context.calibrationDataStore.edit { p ->
                p.remove(offsetKey(key))
                p.remove(refKey(key))
                p.remove(measuredKey(key))
                p.remove(savedAtKey(key))
                // 자리도 함께 지운다 — 남겨 두면 다음 보정이 옛 자리를
                // 물려받아, 다른 자리에서 재고도 「같다」가 된다.
                p.remove(routeKey(key))
            }
        }
    }
}

sealed interface SaveResult {
    data object Saved : SaveResult
    data class Rejected(val reasonKo: String) : SaveResult
}
