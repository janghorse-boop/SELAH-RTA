package kr.joa.selahrta.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.doublePreferencesKey
import kr.joa.selahrta.audio.DisconnectPolicy
import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.domain.DefaultSegmentRanges
import kr.joa.selahrta.domain.SegmentRange
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
    /**
     * 기기별로 고른 **측정 채널**(0부터). 없으면 0번이다.
     *
     * **기기마다 따로 기억한다.** 하나로 두면 4채널 인터페이스에서
     * 3번을 쓰다가 내장 마이크로 바꿈 때 엉뚱한 값이 따라다닌다
     * (USB 오디오 지시서 9.2: 「UMC404HD / Input 1 / EMM-6」을 각각 관리).
     */
    val inputChannels: Map<String, Int> = emptyMap(),
    /** 외부 기기가 꽂히면 자동으로 그쪽을 쓸 것인가(명세 2장). */
    val autoPreferExternal: Boolean = true,
    /** 쓰던 기기가 빠졌을 때(명세 2장). */
    val disconnectPolicy: DisconnectPolicy = DisconnectPolicy.FallBack,
    /** 지금 재고 있는 예배 구간. */
    val segment: ChurchSegment = ChurchSegment.Sermon,
    /**
     * 구간별 참고 범위. 고친 것이 없으면 초기값을 쓴다.
     *
     * 명세 10장이 「사용자 수정 가능한 참고값」이라고 못박았다 —
     * 예배당마다 알맞은 값이 다르다.
     */
    val ranges: Map<ChurchSegment, SegmentRange> = emptyMap(),
) {
    /** 이 구간의 참고 범위. 고친 값이 있으면 그것을, 없으면 초기값을. */
    fun rangeFor(s: ChurchSegment): SegmentRange? =
        ranges[s] ?: DefaultSegmentRanges.of(s)

    /** 이 구간의 범위를 사용자가 고쳤는가. 화면이 「기본값으로」를 띄울 근거다. */
    fun isCustom(s: ChurchSegment): Boolean = ranges.containsKey(s)
}

class MeterSettingsStore(private val context: Context) {

    private val weightingKey = stringPreferencesKey("weighting")
    private val timeWeightKey = stringPreferencesKey("timeWeight")
    private val leqWindowKey = longPreferencesKey("leqWindowMs")
    private val preferredInputKey = stringPreferencesKey("preferredInput")

    /** 기기 열쇠가 길고 임의라 접두어로 모아 둔다. */
    private fun channelKey(deviceKey: String) = intPreferencesKey("$CHANNEL_PREFIX$deviceKey")
    private val autoExternalKey = stringPreferencesKey("autoPreferExternal")
    private val disconnectKey = stringPreferencesKey("disconnectPolicy")
    private val segmentKey = stringPreferencesKey("segment")

    // 범위는 구간마다 네 값이라 열쇠를 만들어 쓴다.
    private fun rangeKey(s: ChurchSegment, part: String) =
        doublePreferencesKey("range|${s.name}|$part")

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
                inputChannels = p.asMap().mapNotNull { (k, v) ->
                    if (!k.name.startsWith(CHANNEL_PREFIX)) return@mapNotNull null
                    val idx = v as? Int ?: return@mapNotNull null
                    // 음수는 저장된 것이 상한다 — 무시한다.
                    if (idx < 0) return@mapNotNull null
                    k.name.removePrefix(CHANNEL_PREFIX) to idx
                }.toMap(),
                autoPreferExternal = p[autoExternalKey] != "false",
                disconnectPolicy = p[disconnectKey]?.let { n ->
                    DisconnectPolicy.entries.firstOrNull { it.name == n }
                } ?: DisconnectPolicy.FallBack,
                segment = p[segmentKey]?.let { n ->
                    ChurchSegment.entries.firstOrNull { it.name == n }
                } ?: ChurchSegment.Sermon,
                ranges = ChurchSegment.entries.mapNotNull { seg ->
                    // 네 값이 모두 있을 때만 고친 것으로 본다. 하나라도 빠지면
                    // 반쯤 저장된 상태라 초기값으로 돌아가는 편이 안전하다.
                    val al = p[rangeKey(seg, "avgLow")] ?: return@mapNotNull null
                    val ah = p[rangeKey(seg, "avgHigh")] ?: return@mapNotNull null
                    val pl = p[rangeKey(seg, "peakLow")] ?: return@mapNotNull null
                    val ph = p[rangeKey(seg, "peakHigh")] ?: return@mapNotNull null
                    val r = SegmentRange(al, ah, pl, ph)
                    // 저장된 값이 말이 안 되면 무시한다 — 앱 판이 바뀌거나
                    // 손으로 건드린 경우다.
                    if (r.isSane) seg to r else null
                }.toMap(),
            )
        }

    suspend fun setWeighting(w: Weighting) = write { it[weightingKey] = w.name }
    suspend fun setTimeWeight(t: TimeWeight) = write { it[timeWeightKey] = t.name }
    suspend fun setLeqWindow(w: LeqWindow) = write { it[leqWindowKey] = w.millis }
    suspend fun setPreferredInput(key: String?) = write { it[preferredInputKey] = key ?: "" }

    /** 그 기기로 쟰 때 쓸 채널을 기억한다. */
    suspend fun setInputChannel(deviceKey: String, index: Int) =
        write { it[channelKey(deviceKey)] = index.coerceAtLeast(0) }
    suspend fun setAutoPreferExternal(on: Boolean) = write { it[autoExternalKey] = on.toString() }
    suspend fun setDisconnectPolicy(p: DisconnectPolicy) = write { it[disconnectKey] = p.name }
    suspend fun setSegment(s: ChurchSegment) = write { it[segmentKey] = s.name }

    /** 구간 범위를 고친다. 말이 안 되는 값은 저장하지 않는다. */
    suspend fun setRange(s: ChurchSegment, r: SegmentRange): Boolean {
        if (!r.isSane) return false
        write {
            it[rangeKey(s, "avgLow")] = r.avgLowDb
            it[rangeKey(s, "avgHigh")] = r.avgHighDb
            it[rangeKey(s, "peakLow")] = r.peakLowDb
            it[rangeKey(s, "peakHigh")] = r.peakHighDb
        }
        return true
    }

    /** 초기값으로 되돌린다. */
    suspend fun resetRange(s: ChurchSegment) = write {
        listOf("avgLow", "avgHigh", "peakLow", "peakHigh").forEach { part ->
            it.remove(rangeKey(s, part))
        }
    }

    private suspend fun write(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        // 저장이 막혀도 앱이 멈추면 안 된다. 이번 세션에는 적용되고
        // 다음에 열면 기본값으로 돌아간다 — 측정 자체는 계속된다.
        runCatching { context.meterDataStore.edit(block) }
    }
}

/** 채널 설정을 모아 두는 접두어. 기기 열쇠가 뒤에 붙는다. */
private const val CHANNEL_PREFIX = "inputChannel|"
