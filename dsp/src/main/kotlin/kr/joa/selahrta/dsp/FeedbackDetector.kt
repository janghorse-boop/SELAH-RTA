package kr.joa.selahrta.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 하울링 후보의 상태(명세 9장).
 *
 * **셋 다 「후보」다.** [Persistent] 라고 해서 하울링이라고 단정하지
 * 않는다 — 오래 이어지는 파이프오르간 저음도 여기까지 온다. 화면은
 * 「후보」라고 적고, 판단은 담당자가 한다.
 */
enum class FeedbackState(val labelKo: String) {
    /** 아무것도 없다. */
    None("없음"),

    /** 좁고 솟은 소리가 막 나타났다. 아직 짧다. */
    Suspect("의심"),

    /** 같은 주파수에 오래 머물고 있다. 가장 하울링답다. */
    Persistent("지속"),
}

/**
 * 하울링 후보 하나(명세 9장: frequency, level, prominence, duration, timestamp).
 */
data class FeedbackCandidate(
    /** 주파수(Hz). 칸 사이 위치까지 되찾은 값이다. */
    val hz: Double,
    /** 그 봉우리의 레벨(dBFS). 보정을 거치지 않은 값이다. */
    val levelDbfs: Double,
    /** 둘레보다 솟은 정도(dB). */
    val prominenceDb: Double,
    /** 이어진 시간(ms). */
    val durationMs: Long,
    /** 처음 나타난 시각(단조 시계 기준 ms). */
    val firstSeenMs: Long,
    val state: FeedbackState,
    /**
     * 이어지는 동안 주파수가 얼마나 흔들렸는가(cent, 100cent = 반음).
     *
     * **하울링은 거의 흔들리지 않는다.** 방의 공진이 정하는 주파수라
     * 사람이 부르는 음과 달리 비브라토가 없다. 노래하는 목소리는 보통
     * ±50~100cent 흔들린다.
     */
    val driftCents: Double,
    /**
     * 나타난 뒤 프레임 가운데 몇 번이나 보였는가(0~1).
     *
     * 하울링은 매 프레임 거기 있다. 오가는 소리는 제 자리를 비운다.
     */
    val continuity: Double,
    /**
     * 배음이 함께 보이는가.
     *
     * 악기와 목소리는 기음 위에 2배·3배 주파수가 함께 선다. 하울링은
     * 대개 홀로 선다 — 다만 크게 일그러지면 하울링도 배음을 만들므로
     * **이것만으로 걸러내지 않고** 판정을 더 오래 미루는 데 쓴다.
     */
    val hasHarmonics: Boolean,
)

/**
 * 기록이 **왜** 끝났는가.
 *
 * 「측정이 끝나서 관측이 잘린 것」은 **소리가 그쳤다는 증거가 아니다.**
 * Phase 10 에서 이 기록을 저장하기 전에 그 차이를 남겨 둬야, 나중에
 * 리포트가 「30초 울렸다」를 잘못 말하지 않는다(독립 검증 C 답변 5번).
 */
enum class FeedbackCloseReason(val labelKo: String) {
    /** 소리가 그쳐서 끊겼다. 울린 시간이 곧 기록의 길이다. */
    SilenceGap("소리가 그침"),

    /** 측정이 끝나서 관측이 잘렸다. **더 울렸을 수 있다.** */
    MeasurementEnded("측정 종료"),
}

/**
 * 「지속」까지 간 후보 하나의 **기록**(명세 9장).
 *
 * 화면의 후보 목록은 소리가 그치면 사라진다. 그러면 예배가 끝난 뒤
 * 「아까 그게 몇 Hz 였지」를 답할 수 없다 — 담당자가 EQ 를 만질 때
 * 필요한 것이 바로 그 답이다.
 *
 * 명세가 적으라고 한 다섯 가지를 그대로 담는다: frequency, level,
 * prominence, duration, timestamp.
 */
data class FeedbackEvent(
    /** 주파수(Hz). 이어지는 동안의 평균이다. */
    val hz: Double,
    /** 이어지는 동안의 **가장 큰** 레벨(dBFS). */
    val peakLevelDbfs: Double,
    /** 이어지는 동안의 **가장 큰** 솟음(dB). */
    val maxProminenceDb: Double,
    /** 시작 시각(측정을 시작한 뒤 ms). */
    val startMs: Long,
    /** 끝난 시각. 아직 이어지는 중이면 null. */
    val endMs: Long?,
    /**
     * 이어진 시간(ms). 아직 이어지는 중이면 마지막으로 본 시각까지다.
     *
     * **소리가 실제로 난 시간의 합이 아니다.** 처음 본 때부터 마지막으로
     * 본 때까지이며, 그 사이에 허용 공백([FeedbackDetector] 의 `gapMs`,
     * 250ms) 안에서 잠깐 사라졌다 돌아온 구간이 있으면 그 공백도 들어
     * 있다 — 「허용 공백을 포함한 **사건의 관측 구간 길이**」다(독립 검증
     * 게이트 재검증 답변 2번). 순수하게 소리가 있던 시간이 필요하면 따로
     * 누적해야 한다.
     */
    val durationMs: Long,
    /** 배음이 함께 보였는가. 악기음일 수 있다는 뜻이다. */
    val hasHarmonics: Boolean,
    /** 왜 끝났는가. 아직 울리는 중이면 null. */
    val closeReason: FeedbackCloseReason? = null,
) {
    /** 아직 울리고 있는가. */
    val ongoing: Boolean get() = endMs == null

    /**
     * 이 길이를 「울린 시간」이라고 말해도 되는가.
     *
     * 측정이 끝나서 잘린 것이면 **더 울렸을 수 있다.** 리포트에 그대로
     * 싣기 전에 이 값을 본다.
     */
    val durationIsComplete: Boolean get() = closeReason == FeedbackCloseReason.SilenceGap
}

/**
 * 하울링 후보 탐지기(명세 9장).
 *
 * **가장 큰 FFT 칸 하나를 하울링으로 단정하지 않는다.** 명세가 그렇게
 * 못박았고, 그렇게 하면 큰 소리가 날 때마다 오탐이 난다. 다섯 가지를
 * 함께 본다:
 *
 * | 무엇 | 왜 |
 * |---|---|
 * | 솟은 정도(prominence) | 둘레보다 확실히 튀어야 한다 |
 * | 좁기(narrowness) | 하울링은 순음이다. 말소리 포먼트는 넓다 |
 * | 레벨 | 고요 속의 작은 봉우리는 의미가 없다 |
 * | 이어짐(persistence) | 스쳐 가는 소리가 아니라 머무는 소리다 |
 * | 되풀이(주파수 안정) | 같은 자리에 머문다. 노래는 흔들린다 |
 *
 * **오탐을 어떻게 줄이는가** — 두 가지가 실제로 갈라 준다:
 *
 * - **흔들림**: 하울링은 방이 정하는 주파수라 거의 안 흔들린다. 사람의
 *   목소리와 현악기는 비브라토로 흔들린다([driftCents]).
 * - **배음**: 악기음은 2배·3배 주파수가 함께 선다. 그런 후보는 판정을
 *   더 오래 미룬다 — 없애지는 않는다. 크게 일그러진 하울링도 배음을
 *   만들기 때문이다.
 *
 * 그래도 **오래 끄는 오르간 저음은 여기까지 올 수 있다.** 그래서 화면에
 * 「후보」라고 적는다. 우리가 할 수 있는 말은 「여기 좁고 센 소리가
 * 오래 머문다」까지다.
 */
class FeedbackDetector(
    fftSize: Int,
    sampleRate: Int,
    /** 둘레보다 이만큼은 솟아야 후보로 본다. */
    private val minProminenceDb: Double = 10.0,
    /**
     * 이보다 넓으면 순음이 아니다(칸 수).
     *
     * 4096점 Hann 창에서 순음의 −3dB 폭은 2~3칸이다. 넉넉히 잡아도
     * 말소리 포먼트(수십 칸)와는 확실히 갈린다.
     */
    private val maxWidthBins: Int = DEFAULT_MAX_WIDTH_BINS,
    /** 이보다 조용하면 보지 않는다. 고요 속의 봉우리는 뜻이 없다. */
    private val minLevelDbfs: Double = -70.0,
    /** 이만큼 이어지면 「의심」. */
    private val suspectMs: Long = 300,
    /** 이만큼 이어지면 「지속」. */
    private val persistentMs: Long = 1_500,
    /** 배음이 함께 보이면 「지속」까지 이만큼 더 기다린다. */
    private val harmonicPatience: Double = 3.0,
    /** 「지속」이 되려면 흔들림이 이 안이어야 한다(cent). */
    private val maxDriftCents: Double = 35.0,
    /**
     * 「지속」이 되려면 프레임 가운데 이 비율 이상에서 보여야 한다.
     *
     * **흔들림만으로는 부족하다.** 비브라토는 한 주파수 자리에 머물지
     * 않고 오가므로, 좁은 자리마다 따로 따라가게 되면 **각 자리의 흔들림은
     * 작아 보인다.** 실제로 ±60cent 비브라토가 429Hz·450Hz 두 자리로
     * 갈라졌고, 450Hz 쪽은 흔들림이 32.8cent 라 문턱을 넘어 「지속」이
     * 됐다 — 오탐이다.
     *
     * 갈라진 자리는 소리가 그 범위를 지날 때만 보인다. 그래서 **얼마나
     * 자주 보였는가**가 갈라 준다. 하울링은 매 프레임 거기 있다.
     */
    private val minContinuity: Double = 0.85,
    /** 이만큼 안 보이면 끊긴 것으로 본다. */
    private val gapMs: Long = 250,
) {
    private val finder = SpectralPeakFinder(fftSize, sampleRate)

    /** 같은 소리로 볼 주파수 차이. 반음의 1/4 쯤이다. */
    private val matchCents = 25.0

    companion object {
        /**
         * 순음으로 볼 최대 폭(칸).
         *
         * **이 값을 Hz 로 옮길 때 `5 × 칸폭` 으로 읽으면 틀린다.**
         * `widthBins` 는 half-power 이상인 **연속 칸의 개수**라, 칸 한가운데
         * 놓인 대칭 봉우리에서는 5개(±2칸) 다음이 7개(±3칸)다 — 6은 나오지
         * 않는다. 그래서 실제로 떨어지는 자리는 **±3칸이 들어오는 폭**,
         * 곧 `6 × 칸폭` 직전이다(48kHz 70.3Hz · 44.1kHz 64.6Hz).
         * 자세한 것은 `SpectralWidthBoundaryTest`.
         */
        const val DEFAULT_MAX_WIDTH_BINS = 5

        /** 기록을 몇 개까지 들고 있을 것인가. */
        private const val MAX_EVENTS = 50
    }

    /**
     * 처리와 종료가 함께 쓰는 자물쇠.
     *
     * **입구 검사만으로는 부족하다.** ViewModel 의 캡처 콜백은 맨 앞에서
     * 「내가 살아 있는 세션인가」를 보는데, 그 검사를 **이미 지나 들어와
     * 있는** 콜백은 막지 못한다. 그 상태에서 주 스레드가 [finish] 를
     * 부르면 둘이 같은 `tracks` 를 동시에 만져
     * `ConcurrentModificationException` 이 난다(독립 검증 C01).
     *
     * **얼마나 기다리는지는 보장이 아니라 관측값이다**(독립 검증 게이트
     * 재검증 답변 1번). 자물쇠를 쥔 오디오 스레드가 GC·스케줄링으로 밀리면
     * 주 스레드의 대기도 함께 늘어난다. Galaxy S23 에서 한 프레임 처리에
     * 잰 값은 **3.42ms**(21.3ms 예산)였고, 부딪히는 것은 멈추는 순간
     * 한 번이다 — 같은 경로의 `close()` 가 최대 500ms 기다리는 것보다는
     * 짧다. 장시간·부하에서 다시 재야 하고, 화면이 밀리면 완료를 비동기로
     * 받는 쪽으로 옮긴다.
     */
    private val lock = Any()

    private val tracks = ArrayList<Track>()

    /**
     * 이미 끝났는가. 끝난 뒤에 늦게 온 덩어리가 기록을 되살리면 안 된다.
     */
    private var finished = false

    /** 넣은 프레임 수. 「얼마나 자주 보였는가」를 세는 기준이다. */
    private var frame = 0L

    /**
     * 이번 측정에서 「지속」까지 간 것들의 기록(명세 9장).
     *
     * **개수를 막아 둔다.** 예배 한 번이 두 시간이고 하울링이 잦으면
     * 끝없이 쌓인다. 오래된 것부터 버린다 — 방금 일이 더 중요하다.
     */
    private val log = ArrayDeque<FeedbackEvent>()

    /** 이번 측정의 기록. 새것부터. */
    val events: List<FeedbackEvent>
        get() = synchronized(lock) { log.toList().asReversed() }

    /**
     * 지금 내놓을 만한 후보들. 센 것부터.
     *
     * **아직 [FeedbackState.None] 인 것은 넣지 않는다.** 잡음 속의 우연한
     * 봉우리도 좁고 솟았을 수 있어(핑크 잡음에서 11~12dB 이 나온다) 잠깐
     * 따라가기는 하지만, 머물지 않으면 후보가 아니다. 그것까지 화면에
     * 늘어놓으면 목록이 잡음으로 덮인다.
     */
    var candidates: List<FeedbackCandidate> = emptyList()
        private set

    /** 가장 센 후보의 상태. 화면 맨 위에 쓰는 값이다. */
    val state: FeedbackState
        get() = candidates.firstOrNull()?.state ?: FeedbackState.None

    /**
     * 스펙트럼 한 장을 넣는다.
     *
     * [nowMs] 는 **단조 시계** 기준이어야 한다. 벽시계는 뒤로 갈 수 있어
     * 이어진 시간이 음수가 된다.
     */
    fun process(power: DoubleArray, nowMs: Long) = synchronized(lock) {
        // 이미 끝낸 뒤라면 아무것도 하지 않는다. 늦게 도착한 덩어리가
        // 닫아 둔 기록을 되살리면, 멈춘 뒤에도 하울링이 나는 것처럼 보인다.
        if (finished) return@synchronized

        frame++
        val peaks = finder.find(power, minProminenceDb)
            .filter { it.widthBins <= maxWidthBins && amplitudeToDbfs(kotlin.math.sqrt(it.power)).value >= minLevelDbfs }

        // 배음 식구를 먼저 가린다. 같은 프레임 안에서만 본다 — 배음은
        // 기음과 **동시에** 서기 때문이다.
        val harmonic = markHarmonics(peaks)

        // **끊긴 것을 먼저 버린다.** 짝짓기보다 뒤에 하면, 오래 비었다가
        // 다시 나타난 봉우리가 `lastSeenMs` 를 덮어써서 공백 검사를
        // 통과한다 — 관측 두 장뿐인데 2초를 「이어졌다」로 세는 일이
        // 그래서 생겼다(독립 검증 P9-03).
        val stale = tracks.filter { nowMs - it.lastSeenMs > gapMs }
        for (t in stale) t.close(FeedbackCloseReason.SilenceGap)
        tracks.removeAll(stale)

        // **한 프레임에서 한 track 은 한 번만 갱신한다.** 예전에는 가까운
        // 두 봉우리(8000Hz·8080Hz, 17cent 차이)가 같은 track 을 두 번
        // 갱신해 연속성이 2.0 까지 올라갔고, 주파수도 둘 사이로 뭉개졌다
        // (독립 검증 P9-02).
        //
        // 솟은 것부터 짝지어, 가장 센 봉우리가 제 track 을 가져간다.
        val taken = HashSet<Track>()
        for ((i, p) in peaks.withIndex()) {
            // **가장 가까운** track 을 고른다. 예전에는 「허용 범위의 첫
            // track」이라, 가까운 두 봉우리의 솟음 순서가 바뀌기만 해도
            // 정체성이 뒤바뀌었다(독립 검증 C 답변 6번).
            val t = tracks
                .filter { it !in taken && it.matches(p.hz) }
                .minByOrNull { cents(p.hz, it.hz) }
            if (t == null) {
                val fresh = Track(p, nowMs, frame, harmonic[i])
                tracks.add(fresh)
                taken.add(fresh)
            } else {
                t.update(p, nowMs, frame, harmonic[i])
                taken.add(t)
            }
        }

        // **이번 프레임에 실제로 본 것만** 기록을 갱신한다. 유예 안에서
        // 안 보이는 track 까지 갱신하면, 무음이 흐른 시간이 울린 증거로
        // 쓰여 문턱을 못 넘은 소리가 사라진 뒤 승격된다(독립 검증 C03).
        for (t in tracks) if (t.lastFrame == frame) t.record()

        candidates = tracks
            .map { it.toCandidate() }
            .filter { it.state != FeedbackState.None }
            .sortedByDescending { it.prominenceDb }
    }

    /**
     * 측정이 끝났다. **열린 기록을 모두 닫고 내놓는다.**
     *
     * 닫지 않으면 멈춘 뒤에도 「울리는 중」으로 남아, 지금 하울링이
     * 나고 있는 것처럼 읽힌다. 끝난 시각은 **마지막으로 본 때**다 —
     * 멈춘 시각으로 적으면 소리가 그친 뒤 조용했던 시간까지 울린 것으로
     * 센다(독립 검증 P9-01).
     */
    fun finish(): List<FeedbackEvent> = synchronized(lock) {
        for (t in tracks) t.close(FeedbackCloseReason.MeasurementEnded)
        tracks.clear()
        candidates = emptyList()
        finished = true
        log.toList().asReversed()
    }

    fun reset() = synchronized(lock) {
        tracks.clear()
        candidates = emptyList()
        log.clear()
        frame = 0
        finished = false
    }

    /**
     * 어느 봉우리가 배음 식구인가.
     *
     * 2·3·4배 자리에 다른 봉우리가 있으면 둘 다 식구로 본다. 기음만
     * 표시하면 「2kHz 는 하울링, 1kHz 는 악기」처럼 엇갈린 판정이 나온다.
     */
    private fun markHarmonics(peaks: List<SpectralPeak>): BooleanArray {
        val flags = BooleanArray(peaks.size)
        for (i in peaks.indices) {
            for (j in peaks.indices) {
                if (i == j) continue
                val ratio = peaks[j].hz / peaks[i].hz
                if (ratio < 1.5) continue
                val n = ratio.roundToInt()
                if (n < 2 || n > 4) continue
                if (cents(peaks[j].hz, peaks[i].hz * n) <= matchCents * 2) {
                    flags[i] = true
                    flags[j] = true
                }
            }
        }
        return flags
    }

    private fun cents(a: Double, b: Double): Double =
        abs(1200.0 * ln(a / b) / ln(2.0))

    /** 한 주파수에 머무는 소리 하나를 따라간다. */
    private inner class Track(peak: SpectralPeak, now: Long, atFrame: Long, harmonic: Boolean) {
        var hz = peak.hz
        var minHz = peak.hz
        var maxHz = peak.hz
        var power = peak.power
        var prominenceDb = peak.prominenceDb
        val firstSeenMs = now
        var lastSeenMs = now
        val firstFrame = atFrame
        var lastFrame = atFrame
        var framesSeen = 1L
        var harmonicSeen = harmonic

        /** 이 소리가 남긴 기록. 「지속」이 된 뒤에 생긴다. */
        var event: FeedbackEvent? = null

        fun matches(other: Double): Boolean = cents(other, hz) <= matchCents

        fun update(peak: SpectralPeak, now: Long, atFrame: Long, harmonic: Boolean) {
            framesSeen++
            lastFrame = atFrame
            // 주파수는 천천히 따라간다. 한 프레임의 흔들림에 끌려가면
            // 흔들림 자체를 못 보게 된다.
            hz = hz * 0.8 + peak.hz * 0.2
            minHz = min(minHz, peak.hz)
            maxHz = max(maxHz, peak.hz)
            power = max(power, peak.power)
            prominenceDb = max(prominenceDb, peak.prominenceDb)
            lastSeenMs = now
            if (harmonic) harmonicSeen = true
        }

        /**
         * 기록을 만들거나 갱신한다.
         *
         * **만드는 기준과 갱신하는 기준이 다르다.** 만드는 것은 「지속」이
         * 됐을 때뿐이다 — 스쳐 가는 소리까지 남기면 목록이 잡음으로 덮인다.
         * 그러나 **한 번 만든 뒤에는 등급과 무관하게 갱신한다.** 예전에는
         * 「지속」이 아니면 곧바로 돌아갔고, 그래서 판정이 내려간 뒤의
         * 시간과 최대값이 빠져 `start=0 · end=5160 · duration=3870` 처럼
         * 셋이 어긋났다(독립 검증 P9-04).
         */
        fun record() {
            val c = toCandidate()
            val old = event
            if (old == null && c.state != FeedbackState.Persistent) return

            val updated = FeedbackEvent(
                hz = c.hz,
                peakLevelDbfs = c.levelDbfs,
                maxProminenceDb = c.prominenceDb,
                startMs = firstSeenMs,
                endMs = null,
                // **마지막으로 본 때까지**다. 지금 시각으로 적으면 소리가
                // 그친 뒤 조용했던 구간까지 울린 것으로 센다.
                durationMs = lastSeenMs - firstSeenMs,
                hasHarmonics = c.hasHarmonics,
            )
            if (old == null) {
                log.addLast(updated)
                if (log.size > MAX_EVENTS) log.removeFirst()
            } else {
                val i = log.indexOf(old)
                if (i >= 0) log[i] = updated
            }
            event = updated
        }

        /**
         * 소리가 끊겼다. 기록을 마무리한다.
         *
         * 끝난 시각은 **마지막으로 본 때**이고, 이어진 시간은 언제나
         * `end − start` 다. 셋이 서로 맞아야 나중에 리포트에 실어도 된다.
         */
        fun close(reason: FeedbackCloseReason) {
            val e = event ?: return
            val i = log.indexOf(e)
            if (i >= 0) {
                log[i] = e.copy(
                    endMs = lastSeenMs,
                    durationMs = lastSeenMs - e.startMs,
                    closeReason = reason,
                )
            }
            event = null
        }

        /**
         * 지금까지 **관측한** 구간으로 후보를 만든다.
         *
         * `now − firstSeen` 이 아니라 `lastSeen − firstSeen` 이다. 안 보이는
         * 동안 흐른 시간은 울린 시간이 아니다(독립 검증 C03).
         */
        fun toCandidate(): FeedbackCandidate {
            val duration = lastSeenMs - firstSeenMs
            val drift = cents(maxHz, minHz)
            val needed = if (harmonicSeen) (persistentMs * harmonicPatience).toLong() else persistentMs
            val continuity = framesSeen.toDouble() / (lastFrame - firstFrame + 1)
            // **안정성은 「의심」에도 건다**(2026-09-24).
            //
            // 예전에는 흔들림·연속성을 「지속」에서만 봤다. 그래서 「의심」은
            // 사실상 「좁고 솟은 소리가 0.3초 이어졌다」뿐이었고, 그 조건은
            // 말소리·노래가 쉼 없이 만족시킨다. 담당자 지적 — 「의심이 되는
            // 주파수가 너무 많은데 실제로는 피드백이 아니다」.
            //
            // 재 보니 그대로였다(`FalsePositiveProbeTest`):
            //   비브라토 440Hz 10초 → 후보 6개 · 말소리 흉내 30초 → 후보 7개
            //
            // **이 둘이 하울링과 악기음을 실제로 갈라 준다**(이 클래스의 머리말).
            // 하울링은 방이 정하는 주파수라 흔들리지 않고 매 프레임 거기 있다.
            // 「지속」에서만 보던 것을 후보가 되는 문턱으로 올린 것이지, 새
            // 규칙을 만든 것이 아니다.
            //
            // **탐지가 늦어지지는 않는다.** 여전히 0.3초면 「의심」이 된다 —
            // 흔들리지 않은 0.3초여야 할 뿐이다.
            val stable = drift <= maxDriftCents && continuity >= minContinuity
            val state = when {
                !stable -> FeedbackState.None
                duration >= needed -> FeedbackState.Persistent
                duration >= suspectMs -> FeedbackState.Suspect
                else -> FeedbackState.None
            }
            return FeedbackCandidate(
                hz = hz,
                levelDbfs = amplitudeToDbfs(kotlin.math.sqrt(power)).value,
                prominenceDb = prominenceDb,
                durationMs = duration,
                firstSeenMs = firstSeenMs,
                state = state,
                driftCents = drift,
                continuity = continuity,
                hasHarmonics = harmonicSeen,
            )
        }
    }
}
