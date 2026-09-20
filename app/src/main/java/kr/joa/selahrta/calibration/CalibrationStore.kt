package kr.joa.selahrta.calibration

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
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
            }
            SaveResult.Saved
        } catch (e: IOException) {
            // 저장에 실패했는데 성공했다고 말하면 안 된다. 다음에 앱을 열면
            // 보정이 사라져 있는데 담당자는 이유를 알 수 없다.
            SaveResult.Rejected("보정값을 저장하지 못했습니다: ${e.message}")
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
            }
        }
    }
}

sealed interface SaveResult {
    data object Saved : SaveResult
    data class Rejected(val reasonKo: String) : SaveResult
}
