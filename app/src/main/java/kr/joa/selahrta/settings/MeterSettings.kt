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
import kr.joa.selahrta.domain.DEFAULT_SEGMENTS
import kr.joa.selahrta.domain.MAX_SEGMENTS
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.domain.DefaultSegmentRanges
import kr.joa.selahrta.domain.SegmentRange
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import kr.joa.selahrta.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.meterDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "meter")

/**
 * 측정 시작부터 지금까지를 뜻하는 표시값.
 *
 * **창 길이가 아니다.** 다른 창의 millis 와 겹치지 않게 음수를 쓴다 —
 * 저장 코드가 millis 를 열쇠로 쓰기 때문이다.
 */
const val SESSION_MILLIS = -1L

/** FFT 길이의 기본값(명세 7장의 출발점). */
const val DEFAULT_FFT_SIZE = 4096

/** 고를 수 있는 FFT 길이. 이 밖의 값이 저장돼 있으면 기본값으로 돌아간다. */
val FFT_SIZES = listOf(2048, 4096, 8192)

/** 화면에 보여줄 긴 Leq 의 길이(명세 14장). */
enum class LeqWindow(val labelKo: String, val millis: Long) {
    TenSeconds("10초", 10_000),
    OneMinute("1분", 60_000),
    FiveMinutes("5분", 300_000),

    /**
     * 측정 시작부터 지금까지의 누적 Leq(지시서 §4).
     *
     * 값은 이미 `SplFrame.leqSessionDbfs` 로 나오고 있었다 — 받는 곳이
     * 없었을 뿐이다.
     */
    Session("전체", SESSION_MILLIS),
    ;

    /**
     * **엔진에 넘길 창 길이.** [millis] 와 다를 수 있다.
     *
     * Session 의 [millis] 는 -1 이라 그대로 넘기면 엔진이 상한다. 세션
     * Leq 는 창이 아니라 누적 합계이므로 창 길이가 필요 없고, 엔진에는
     * 기본 창을 준 뒤 화면에 적을 값만 세션 쪽에서 가져온다.
     */
    val engineMillis: Long get() = if (this == Session) OneMinute.millis else millis
}

/**
 * 측정 설정(명세 14장).
 *
 * 바꾸면 **측정 엔진을 다시 만들어야 한다** — 가중 필터의 계수가 달라지고
 * 시간가중의 상수가 달라진다. 돌아가는 중에 슬쩍 바꿔 끼우면 그 순간의
 * 값이 두 설정의 중간쯤인 이상한 숫자가 된다.
 */
data class MeterSettings(
    /**
     * 음압(SPL 큰 숫자·Leq·MIN·MAX)의 가중. 권장 범위 판정이 이 값을 본다.
     */
    val splWeighting: Weighting = Weighting.A,
    /**
     * PEAK 의 가중. **음압과 따로 둔다**(지시서 §16: 「Peak 를 SPL
     * Weighting 에 무조건 종속시키지 않는다」).
     *
     * 킥·스네어의 저역이 A-weighting 에 깎여 순간 음압을 놓치는 일을
     * 막자는 것이다.
     */
    val peakWeighting: Weighting = Weighting.Z,
    /**
     * RTA·Spectrum·Spectrogram 의 가중.
     *
     * **셋을 묶는 까닭**: 같은 FFT 장을 나눠 쓴다. 따로 두면 RTA 의 63Hz
     * 막대와 Spectrum 의 63Hz 봉우리가 다른 값이 된다.
     *
     * FR 은 여기 없다. 예배당의 응답 자체를 재는 화면이라 가중을 걸면
     * 뜻이 없어진다(지시서 §7).
     */
    val analysisWeighting: Weighting = Weighting.Z,
    /**
     * 시간가중. **기본은 Fast 다.**
     *
     * 2026-09-27 에 Slow 로 바꿨다가 **하루 만에 되돌렸다**(담당자 지시
     * 2026-09-28). 까닭은 화면이 자리를 잡는 시간이다 —
     * `settleSamples = 샘플레이트 × τ × 3` 이라:
     *
     * | | τ | 자리 잡는 시간 |
     * |---|---|---|
     * | Fast | 125ms | **0.375초** |
     * | Slow | 1초 | **3초** |
     *
     * 그 사이의 값은 0 에서 올라오는 중이라 실제보다 낮다. Fast 에서는
     * 눈 깜짝할 새라 아무도 몰랐는데, Slow 로 바꾸니 **예배를 시작할
     * 때마다 3초**가 되었다.
     *
     * 「이만한 크기로 얼마나 이어지나」를 보려는 뜻은 **Leq 가 이미
     * 하고 있다.** 시간가중까지 느리게 할 까닭이 없었다.
     *
     * 사람이 고른 값은 그대로 남는다 — 이 기본값은 저장된 것이 없을
     * 때만 쓰인다.
     */
    val timeWeight: TimeWeight = TimeWeight.Fast,
    val leqWindow: LeqWindow = LeqWindow.OneMinute,
    /**
     * 화면 밝기 한 벌(담당자 지시 2026-09-28).
     *
     * **기본은 다크다.** 어두운 예배당·방송실에서 쓰는 도구라 흰 화면
     * 하나가 앞자리까지 밝힌다. **기기 설정을 따라가지 않는다** — 폰이
     * 라이트로 넘어갔다는 이유로 예배 중에 화면이 하얘지는 것은
     * 사용자가 고른 적 없는 일이다.
     */
    val themeMode: ThemeMode = ThemeMode.Dark,
    /**
     * FFT 길이. 길수록 저역이 또렷하고 반응이 느려진다.
     *
     * **다음 측정부터 적용된다.** 측정 중에 바꾸면 분석 엔진을 통째로
     * 새로 만들어야 하고, 그러면 하울링 탐지와 FR 수집이 붙어 있던
     * 자리가 끊긴다 — FR 을 재는 도중이면 그 회차를 잃는다.
     */
    val fftSize: Int = DEFAULT_FFT_SIZE,
    /**
     * 소리를 담기로 했을 때 어느 꼴로 담을지.
     *
     * **담을지 말지는 여기 없다** — 그것은 기록을 시작할 때마다 묻는다.
     */
    val audioFormat: kr.joa.selahrta.recording.AudioFileFormat =
        kr.joa.selahrta.recording.AudioFileFormat.M4a,
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
     * **쓰는 구간들**(담당자 지시 2026-09-28: 최대 5개).
     *
     * 설교·찬양은 늘 들어 있다 — 뺄 수 없다. 나머지는 사람이 더하고
     * 뺀다. 뺀 칸의 범위와 이름은 **저장소에 그대로 남으므로**, 다시
     * 더하면 고쳐 둔 값이 돌아온다.
     */
    val segments: Set<ChurchSegment> = DEFAULT_SEGMENTS,
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
    /**
     * 화면에 그릴 차례대로의 구간 목록.
     *
     * enum 차례를 따른다 — 사람이 더한 차례로 두면 기기를 바꿀 때마다
     * 줄이 뒤바뀐다.
     */
    val orderedSegments: List<ChurchSegment>
        get() = ChurchSegment.entries.filter { it in segments }

    /**
     * 지금 견주고 있는 구간. **하나도 더하지 않았으면 null.**
     *
     * [segment] 는 저장된 값이라 목록에서 뺀 뒤에도 남아 있을 수 있다.
     * 화면은 반드시 이쪽을 본다 — 뺀 구간의 범위와 견주면 「지우지도
     * 않은 기준」으로 색이 뜬다.
     */
    val activeSegment: ChurchSegment?
        get() = segment.takeIf { it in segments } ?: orderedSegments.firstOrNull()

    /** 더 더할 자리가 있는가. */
    val canAddSegment: Boolean get() = segments.size < MAX_SEGMENTS

    /** 다음에 더할 칸. 더 없으면 null. */
    val nextFreeSegment: ChurchSegment?
        get() = ChurchSegment.entries.firstOrNull { it !in segments }

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

    // **음압 가중은 지금 쓰던 열쇠를 그대로 쓴다.** 쓰던 사람이 C 로
    // 맞춰 뒀다면 그 설정이 남아야 한다. 나머지 둘은 새 열쇠다.
    private val splWeightingKey = stringPreferencesKey(SPL_WEIGHTING_KEY_NAME)
    private val peakWeightingKey = stringPreferencesKey("peakWeighting")
    private val analysisWeightingKey = stringPreferencesKey("analysisWeighting")
    private val timeWeightKey = stringPreferencesKey("timeWeight")
    private val themeModeKey = stringPreferencesKey("themeMode")
    private val fftSizeKey = intPreferencesKey("fftSize")
    private val leqWindowKey = longPreferencesKey("leqWindowMs")
    private val audioFormatKey = stringPreferencesKey("audioFormat")
    private val preferredInputKey = stringPreferencesKey("preferredInput")

    /** 기기 열쇠가 길고 임의라 접두어로 모아 둔다. */
    private fun channelKey(deviceKey: String) = intPreferencesKey("$CHANNEL_PREFIX$deviceKey")

    /** 한 번이라도 연결됐던 기기. 값은 `종류|이름`. */
    private fun knownKey(deviceKey: String) = stringPreferencesKey("$KNOWN_PREFIX$deviceKey")
    private val segmentKey = stringPreferencesKey("segment")

    /**
     * 쓰는 구간들. `Sermon,Worship` 처럼 쉼표로 잇는다.
     *
     * 저장된 것이 없으면 **기본 둘**이다 — 지금까지 쓰던 사람에게
     * 화면이 달라지지 않는다.
     */
    private val segmentsKey = stringPreferencesKey("segments")

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
                splWeighting = p.weightingOr(splWeightingKey, Weighting.A),
                peakWeighting = p.weightingOr(peakWeightingKey, Weighting.Z),
                analysisWeighting = p.weightingOr(analysisWeightingKey, Weighting.Z),
                timeWeight = p[timeWeightKey]?.let { n ->
                    TimeWeight.entries.firstOrNull { it.name == n }
                } ?: TimeWeight.Fast,
                // **모르는 이름이면 다크다.** 저장된 것이 상했을 때
                // 흰 화면으로 떨어지지 않게 한다.
                themeMode = p[themeModeKey]?.let { n ->
                    ThemeMode.entries.firstOrNull { it.name == n }
                } ?: ThemeMode.Dark,
                // **목록에 없는 값이면 기본값이다.** 손으로 건드렸거나
                // 앱 판이 바뀐 경우다 — 임의의 길이로 돌리지 않는다.
                fftSize = p[fftSizeKey]?.takeIf { it in FFT_SIZES } ?: DEFAULT_FFT_SIZE,
                // **모르는 이름이면 기본값이다.** 임의로 고르지 않는다.
                audioFormat = p[audioFormatKey]
                    ?.let { n ->
                        kr.joa.selahrta.recording.AudioFileFormat.entries
                            .firstOrNull { it.name == n }
                    }
                    ?: kr.joa.selahrta.recording.AudioFileFormat.M4a,
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
                // **저장된 것이 없으면 하나도 없다.** 앱이 먼저 깔아
                // 두지 않는다 — 권장 범위는 예배당마다 다른 참고값이라,
                // 쓰지도 않는 범위와 견주어 색이 뜨면 안 된다.
                //
                // 빈 문자열은 「다 뺐다」는 뜻이다. `split` 이 빈 칸
                // 하나를 돌려주지만 아는 이름이 아니라 걸러진다.
                segments = p[segmentsKey]
                    ?.split(',')
                    ?.mapNotNull { n -> ChurchSegment.entries.firstOrNull { it.name == n } }
                    ?.toSet()
                    ?: DEFAULT_SEGMENTS,
                ranges = ChurchSegment.entries.mapNotNull { seg ->
                    // 두 값이 다 있을 때만 고친 것으로 본다. 하나라도 빠지면
                    // 반쯤 저장된 상태라 초기값으로 돌아가는 편이 안전하다.
                    //
                    // **옛 `peakLow`/`peakHigh` 는 읽지 않는다**(2026-09-28
                    // 에 걷어냈다). 저장소에 남아 있어도 그냥 지나간다 —
                    // 지우려고 돌아다니면 지울 것이 더 생긴다.
                    val al = p[rangeKey(seg, "avgLow")] ?: return@mapNotNull null
                    val ah = p[rangeKey(seg, "avgHigh")] ?: return@mapNotNull null
                    val r = SegmentRange(al, ah)
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

    /** 저장된 이름이 알 수 없는 것이면 기본값으로 돌아간다. */
    private fun Preferences.weightingOr(
        key: Preferences.Key<String>,
        fallback: Weighting,
    ): Weighting = this[key]?.let { n ->
        Weighting.entries.firstOrNull { it.name == n }
    } ?: fallback

    suspend fun setSplWeighting(w: Weighting) = write { it[splWeightingKey] = w.name }
    suspend fun setPeakWeighting(w: Weighting) = write { it[peakWeightingKey] = w.name }
    suspend fun setAnalysisWeighting(w: Weighting) =
        write { it[analysisWeightingKey] = w.name }

    /** 그 줄만 기본값으로 되돌린다. **옆 줄은 건드리지 않는다.** */
    suspend fun resetSplWeighting() = write { it.remove(splWeightingKey) }
    suspend fun resetPeakWeighting() = write { it.remove(peakWeightingKey) }
    suspend fun resetAnalysisWeighting() = write { it.remove(analysisWeightingKey) }

    suspend fun setTimeWeight(t: TimeWeight) = write { it[timeWeightKey] = t.name }
    suspend fun setThemeMode(m: ThemeMode) = write { it[themeModeKey] = m.name }

    /** 목록에 없는 길이는 저장하지 않는다. */
    suspend fun setFftSize(n: Int) {
        if (n !in FFT_SIZES) return
        write { it[fftSizeKey] = n }
    }
    suspend fun setLeqWindow(w: LeqWindow) = write { it[leqWindowKey] = w.millis }

    /**
     * 소리를 담기로 했을 때 어느 꼴로 담을지.
     *
     * **담을지 말지는 여기 없다.** 그것은 기록을 시작할 때마다 묻는다 —
     * 실수로 켜진 채 다음 예배까지 담기는 일이 없어야 한다.
     */
    suspend fun setAudioFormat(f: kr.joa.selahrta.recording.AudioFileFormat) =
        write { it[audioFormatKey] = f.name }
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

    /**
     * 구간을 하나 더한다. 다섯을 넘으면 아무 일도 없다.
     *
     * **범위와 이름은 건드리지 않는다.** 전에 쓰다 뺀 칸이면 그때 고쳐 둔
     * 값이 그대로 돌아온다.
     */
    suspend fun addSegment(s: ChurchSegment) = write { p ->
        val now = readSegments(p)
        if (s in now || now.size >= MAX_SEGMENTS) return@write
        p[segmentsKey] = (now + s).joinToString(",") { it.name }
    }

    /**
     * 구간을 뺀다. **설교·찬양은 빼지 못한다.**
     *
     * 범위와 이름은 저장소에 남긴다 — 다시 더하면 돌아온다. 지금 고른
     * 구간을 뺐으면 설교로 돌린다.
     */
    suspend fun removeSegment(s: ChurchSegment) = write { p ->
        val now = readSegments(p)
        if (s !in now) return@write
        val left = now - s
        p[segmentsKey] = left.joinToString(",") { it.name }
        // 지금 고른 것을 뺐으면 남은 첫 칸으로 옮긴다. 남은 것이 없으면
        // 고른 것도 없다 — 화면이 권장 범위 상자를 그리지 않는다.
        if (p[segmentKey] == s.name) {
            val next = ChurchSegment.entries.firstOrNull { it in left }
            if (next == null) p.remove(segmentKey) else p[segmentKey] = next.name
        }
    }

    private fun readSegments(p: Preferences): Set<ChurchSegment> =
        p[segmentsKey]
            ?.split(',')
            ?.mapNotNull { n -> ChurchSegment.entries.firstOrNull { it.name == n } }
            ?.toSet()
            ?: DEFAULT_SEGMENTS

    /** 구간 범위를 고친다. 말이 안 되는 값은 저장하지 않는다. */
    suspend fun setRange(s: ChurchSegment, r: SegmentRange): Boolean {
        if (!r.isSane) return false
        write {
            it[rangeKey(s, "avgLow")] = r.avgLowDb
            it[rangeKey(s, "avgHigh")] = r.avgHighDb
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
        // 옛 피크 열쇠도 함께 지운다 — 되돌리기는 자취를 남기지 않는다.
        listOf("avgLow", "avgHigh", "peakLow", "peakHigh").forEach { part ->
            it.remove(rangeKey(s, part))
        }
    }

    private suspend fun write(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        // 저장이 막혀도 앱이 멈추면 안 된다. 이번 세션에는 적용되고
        // 다음에 열면 기본값으로 돌아간다 — 측정 자체는 계속된다.
        runCatching { context.meterDataStore.edit(block) }
    }

    companion object {
        /**
         * 음압 가중의 저장 열쇠.
         *
         * **바꾸면 쓰던 사람의 설정이 날아간다.** 가중이 셋으로 갈라지기
         * 전부터 쓰던 이름이라 그대로 이어받는다. 시험이 이 이름을 직접
         * 본다(`MeterSettingsWeightingTest`).
         */
        const val SPL_WEIGHTING_KEY_NAME = "weighting"
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
