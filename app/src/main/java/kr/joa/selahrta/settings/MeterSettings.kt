package kr.joa.selahrta.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kr.joa.selahrta.audio.DisconnectPolicy
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.meterDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "meter")

/** 화면에 보여줄 긴 Leq 의 길이(명세 14장). */
enum class LeqWindow(val labelKo: String, val millis: Long) {
    TenSeconds("10초", 10_000),
    OneMinute("1분", 60_000),
    FiveMinutes("5분", 300_000),
}

/**
 * 측정 설정(명세 14장).
 *
 * 바꾸면 **측정 엔진을 다시 만들어야 한다** — 가중 필터의 계수가 달라지고
 * 시간가중의 상수가 달라진다. 돌아가는 중에 슬쩍 바꿔 끼우면 그 순간의
 * 값이 두 설정의 중간쯤인 이상한 숫자가 된다.
 */
data class MeterSettings(
    val weighting: Weighting = Weighting.A,
    val timeWeight: TimeWeight = TimeWeight.Fast,
    val leqWindow: LeqWindow = LeqWindow.OneMinute,
    /** 사용자가 고른 입력 기기의 열쇠. null 이면 자동. */
    val preferredInputKey: String? = null,
    /** 외부 기기가 꽂히면 자동으로 그쪽을 쓸 것인가(명세 2장). */
    val autoPreferExternal: Boolean = true,
    /** 쓰던 기기가 빠졌을 때(명세 2장). */
    val disconnectPolicy: DisconnectPolicy = DisconnectPolicy.FallBack,
)

class MeterSettingsStore(private val context: Context) {

    private val weightingKey = stringPreferencesKey("weighting")
    private val timeWeightKey = stringPreferencesKey("timeWeight")
    private val leqWindowKey = longPreferencesKey("leqWindowMs")
    private val preferredInputKey = stringPreferencesKey("preferredInput")
    private val autoExternalKey = stringPreferencesKey("autoPreferExternal")
    private val disconnectKey = stringPreferencesKey("disconnectPolicy")

    val settings: Flow<MeterSettings> = context.meterDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            MeterSettings(
                // 저장된 값이 알 수 없는 것이면 기본값으로 돌아간다. 앱을
                // 새로 깔거나 설정 이름이 바뀌어도 측정은 되어야 한다.
                weighting = p[weightingKey]?.let { n ->
                    Weighting.entries.firstOrNull { it.name == n }
                } ?: Weighting.A,
                timeWeight = p[timeWeightKey]?.let { n ->
                    TimeWeight.entries.firstOrNull { it.name == n }
                } ?: TimeWeight.Fast,
                leqWindow = p[leqWindowKey]?.let { ms ->
                    LeqWindow.entries.firstOrNull { it.millis == ms }
                } ?: LeqWindow.OneMinute,
                // 빈 문자열은 「자동」을 뜻한다. DataStore 에 null 을 넣을 수 없어서다.
                preferredInputKey = p[preferredInputKey]?.takeIf { it.isNotEmpty() },
                autoPreferExternal = p[autoExternalKey] != "false",
                disconnectPolicy = p[disconnectKey]?.let { n ->
                    DisconnectPolicy.entries.firstOrNull { it.name == n }
                } ?: DisconnectPolicy.FallBack,
            )
        }

    suspend fun setWeighting(w: Weighting) = write { it[weightingKey] = w.name }
    suspend fun setTimeWeight(t: TimeWeight) = write { it[timeWeightKey] = t.name }
    suspend fun setLeqWindow(w: LeqWindow) = write { it[leqWindowKey] = w.millis }
    suspend fun setPreferredInput(key: String?) = write { it[preferredInputKey] = key ?: "" }
    suspend fun setAutoPreferExternal(on: Boolean) = write { it[autoExternalKey] = on.toString() }
    suspend fun setDisconnectPolicy(p: DisconnectPolicy) = write { it[disconnectKey] = p.name }

    private suspend fun write(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        // 저장이 막혀도 앱이 멈추면 안 된다. 이번 세션에는 적용되고
        // 다음에 열면 기본값으로 돌아간다 — 측정 자체는 계속된다.
        runCatching { context.meterDataStore.edit(block) }
    }
}
