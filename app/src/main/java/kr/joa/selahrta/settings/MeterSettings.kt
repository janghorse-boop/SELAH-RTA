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
import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.domain.MicKind
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
    /**
     * 한 번이라도 연결됐던 기기들. 지금 꽂혀 있지 않아도 남는다.
     *
     * **왜 기억하는가**: 예배당에서는 인터페이스를 늘 꽂아 두지 않는다.
     * 뺄 때마다 목록에서 사라지면, 다시 꽂을 때까지 「그 기기로 고를
     * 수 있다」는 사실 자체가 화면에서 없어진다. 보정값은 기기마다
     * 따로 두므로, 목록에 남아 있어야 무엇이 보정돼 있는지도 보인다.
     */
    val knownDevices: List<KnownDevice> = emptyList(),
    /** 지금 재고 있는 예배 구간. */
    val segment: ChurchSegment = ChurchSegment.Sermon,
    /**
     * 구간별 참고 범위. 고친 것이 없으면 초기값을 쓴다.
     *
     * 명세 10장이 「사용자 수정 가능한 참고값」이라고 못박았다 —
     * 예배당마다 알맞은 값이 다르다.
     */
    val ranges: Map<ChurchSegment, SegmentRange> = emptyMap(),
    /**
     * 구간의 **이름**. 고친 것이 없으면 기본 이름을 쓴다.
     *
     * **예배당마다 부르는 말이 다르다**(2026-09-25 담당자 지시: 「지금은
     * 설교, 찬양이라고 했지만 나중에 사용자가 안전 또는 다른 용어로 변경할
     * 수 있게」). 어떤 곳은 「말씀·경배」로, 산업 현장에서 쓰면 「작업·안전」
     * 으로 부를 것이다.
     *
     * **속뜻은 그대로다.** 바뀌는 것은 화면에 적히는 글자뿐이고, 어느
     * 구간의 범위인지는 [ChurchSegment] 가 그대로 쥔다 — 이름을 열쇠로
     * 삼았다면 이름을 바꿀 때마다 저장된 범위를 잃었을 것이다.
     */
    val names: Map<ChurchSegment, String> = emptyMap(),
) {
    /** 이 구간의 참고 범위. 고친 값이 있으면 그것을, 없으면 초기값을. */
    fun rangeFor(s: ChurchSegment): SegmentRange? =
        ranges[s] ?: DefaultSegmentRanges.of(s)

    /** 이 구간의 범위를 사용자가 고쳤는가. 화면이 「기본값으로」를 띄울 근거다. */
    fun isCustom(s: ChurchSegment): Boolean = ranges.containsKey(s)

    /** 화면에 적을 이름(짧은 쪽). 고친 이름이 있으면 그것을. */
    fun nameFor(s: ChurchSegment): String = names[s] ?: s.shortKo

    /** 긴 이름. 고친 이름이 있으면 짧은 것과 같다 — 사람이 하나만 적는다. */
    fun longNameFor(s: ChurchSegment): String = names[s] ?: s.labelKo

    /** 이 구간의 이름을 사용자가 고쳤는가. */
    fun isCustomName(s: ChurchSegment): Boolean = names.containsKey(s)
}

/**
 * 구간 이름으로 받아 줄 길이.
 *
 * 화면의 알약에 들어가야 하므로 짧아야 한다. 길면 알약이 범위 상자를
 * 밀어내고, 좁은 화면에서 글자가 쪼개진다.
 */
const val SEGMENT_NAME_MAX = 8

class MeterSettingsStore(private val context: Context) {

    private val weightingKey = stringPreferencesKey("weighting")
    private val timeWeightKey = stringPreferencesKey("timeWeight")
    private val leqWindowKey = longPreferencesKey("leqWindowMs")
    private val preferredInputKey = stringPreferencesKey("preferredInput")

    /** 기기 열쇠가 길고 임의라 접두어로 모아 둔다. */
    private fun channelKey(deviceKey: String) = intPreferencesKey("$CHANNEL_PREFIX$deviceKey")

    /** 한 번이라도 연결됐던 기기. 값은 `종류|이름`. */
    private fun knownKey(deviceKey: String) = stringPreferencesKey("$KNOWN_PREFIX$deviceKey")
    private val segmentKey = stringPreferencesKey("segment")

    // 범위는 구간마다 네 값이라 열쇠를 만들어 쓴다.
    private fun rangeKey(s: ChurchSegment, part: String) =
        doublePreferencesKey("range|${s.name}|$part")

    /**
     * 구간 이름.
     *
     * **열쇠는 enum 이름이다.** 사람이 지은 이름을 열쇠로 삼았다면 이름을
     * 고칠 때마다 그 구간에 저장해 둔 범위를 잃었을 것이다.
     */
    private fun nameKey(s: ChurchSegment) = stringPreferencesKey("segName|${s.name}")

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
                knownDevices = p.asMap().mapNotNull { (k, v) ->
                    if (!k.name.startsWith(KNOWN_PREFIX)) return@mapNotNull null
                    val s = v as? String ?: return@mapNotNull null
                    val kind = MicKind.entries.firstOrNull { it.name == s.substringBefore("|") }
                        ?: return@mapNotNull null
                    KnownDevice(
                        key = k.name.removePrefix(KNOWN_PREFIX),
                        name = s.substringAfter("|"),
                        kind = kind,
                    )
                }.sortedBy { it.name },
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
                names = ChurchSegment.entries.mapNotNull { seg ->
                    val n = p[nameKey(seg)]?.trim()
                    if (n.isNullOrBlank()) null else seg to n.take(SEGMENT_NAME_MAX)
                }.toMap(),
            )
        }

    suspend fun setWeighting(w: Weighting) = write { it[weightingKey] = w.name }
    suspend fun setTimeWeight(t: TimeWeight) = write { it[timeWeightKey] = t.name }
    suspend fun setLeqWindow(w: LeqWindow) = write { it[leqWindowKey] = w.millis }
    suspend fun setPreferredInput(key: String?) = write { it[preferredInputKey] = key ?: "" }

    /**
     * 이 기기를 **봤다고 기억한다.** 목록에 남기려는 것이다.
     *
     * 덮어쓰기라 이름이 바뀌면 새 이름이 남는다 — 같은 열쇠면 같은
     * 기기이므로 최신 이름이 맞다.
     */
    suspend fun rememberDevice(key: String, name: String, kind: MicKind) =
        write { it[knownKey(key)] = "${kind.name}|$name" }

    /** 기억에서 지운다. 사람이 목록에서 치울 때 쓴다. */
    suspend fun forgetDevice(key: String) = write { it.remove(knownKey(key)) }

    /** 그 기기로 잴 때 쓸 채널을 기억한다. */
    suspend fun setInputChannel(deviceKey: String, index: Int) =
        write { it[channelKey(deviceKey)] = index.coerceAtLeast(0) }
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

    /**
     * 구간 이름을 고친다. 빈 이름은 **되돌리기**로 본다.
     *
     * 길이를 잘라 저장한다 — 화면의 알약에 들어가야 하므로 긴 이름은
     * 상자를 밀어낸다.
     */
    suspend fun setSegmentName(s: ChurchSegment, name: String) = write {
        val trimmed = name.trim().take(SEGMENT_NAME_MAX)
        if (trimmed.isEmpty()) it.remove(nameKey(s)) else it[nameKey(s)] = trimmed
    }

    /** 이름을 기본값으로 되돌린다. */
    suspend fun resetSegmentName(s: ChurchSegment) = write { it.remove(nameKey(s)) }

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
private const val KNOWN_PREFIX = "known|"

/**
 * 한 번이라도 연결됐던 입력 기기.
 *
 * 지금 꽂혀 있는 기기는 [kr.joa.selahrta.audio.InputDeviceInfo] 로 오고,
 * 이것은 **꽂혀 있지 않아도 남는 기억**이다. 둘을 합쳐 화면이 「지금
 * 연결됨」과 「전에 썼음」을 갈라 보여 준다.
 */
data class KnownDevice(
    val key: String,
    val name: String,
    val kind: MicKind,
)
