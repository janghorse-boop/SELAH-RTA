package kr.joa.selahrta.recording

import kr.joa.selahrta.dsp.MultiWeightEngine
import kr.joa.selahrta.dsp.RtaEngine
import kr.joa.selahrta.dsp.ThirdOctave

/**
 * 측정을 500ms 격자의 **행**으로 남긴다(명세 12장 · Phase 10).
 *
 * ## 무엇을 하는가
 *
 * 덩어리가 올 때마다 **행·epoch 경계에서 쪼개** DSP 에 넣고, 조각마다
 * 나온 값을 집계기에 쌓는다. 쪼개는 까닭은 그 행의 값이 **그 행의
 * 소리만** 담게 하려는 것이다 — 안 쪼개면 경계를 걸친 덩어리(여덟에
 * 하나)의 최대 60ms 가 옆 행 값에 섞인다.
 *
 * ## 오디오 스레드에서 돈다
 *
 * **파일을 쓰지 않는다**(녹음 설계 §2). 파일 IO 는 수십 ms 씩 멎을 수
 * 있고, 그동안 읽기가 밀리면 측정이 끊긴다. 행은 메모리에 쌓고
 * [finish] 에서 한 번에 내놓는다.
 *
 * 긴 녹음에서 완결된 행을 순차로 흘려보내는 경계는 **아직 만들지
 * 않았다**([RowAggregator] 의 같은 한계). 2시간이면 14,400행 × 148바이트
 * = 2.1MB 라 당장은 견딘다.
 *
 * ## 스스로 직렬화한다
 *
 * 처음에는 「한 스레드 전용」으로 적었다가 고쳤다. 끝낼 때
 * [finish] 를 부르는 것은 **주 스레드**인데, 그때 캡처 스레드의
 * [onBlock] 이 아직 들어와 있을 수 있기 때문이다.
 *
 * `MicSource.close()` 의 `join` 은 **시간 제한이 있다**(500ms). 그것을
 * 넘겨 옛 스레드가 살아남는 일을 이 저장소가 실제로 겪었다(독립 재검증
 * F02). 「입구 검사가 지켜 준다」고 믿고 적었던 주석이 C01 을 낳았고,
 * 그래서 [kr.joa.selahrta.dsp.FeedbackDetector] 는 제 자물쇠로
 * 직렬화한다. 여기서도 같게 한다.
 *
 * **끝낸 뒤의 부름은 조용히 지나간다.** 늦은 콜백은 멈추는 과정의
 * 정상이지 잘못이 아니다 — 거기서 예외를 던지면 오디오 스레드가 죽는다.
 */
class SessionRecorder(
    val id: String,
    private val nominalSampleRate: Int,
    /** 시작할 때의 보정. 바뀌면 [noteEpoch] 로 새 구간을 연다. */
    startOffsetDb: Double,
    startReferenceOnly: Boolean,
    startLeqWindowMs: Long,
    /** 요약(Leq·MIN·MAX·PEAK)을 어느 가중치로 낼 것인가. */
    val weighting: kr.joa.selahrta.dsp.Weighting = kr.joa.selahrta.dsp.Weighting.A,
) {
    private val epochs = EpochTable()
    private val aggregator = RowAggregator(nominalSampleRate, epochs)

    /** 녹음 기준 프레임. 덩어리가 올 때마다 늘어난다. */
    private var frame = 0L

    /** 사건 목록. 구간 바뀜·기기 전환·끊김. */
    private val events = ArrayList<SessionEvent>()

    /** 큐가 아니라 **엔진이** 못 따라온 것. 읽기 오류로 버린 덩어리 수다. */
    var droppedPackets: Int = 0
        private set

    @Volatile
    private var finished = false

    /** 캡처 스레드와 주 스레드를 가른다. 위 머리말 참고. */
    private val lock = Any()

    /** [finish] 가 만든 것. 두 번 불러도 같은 것을 돌려준다. */
    private var result: RecordedSession? = null

    /**
     * **요약은 기록 자신의 것이어야 한다.**
     *
     * 처음에는 겉장의 Leq·MIN·MAX·PEAK 를 화면의 계기에서 가져왔다. 그런데
     * 계기는 **측정 전체**를 재고 있고 기록은 그 도중에 시작할 수 있다 —
     * 기록 전 6초의 소리가 「이 기록의 최대」로 적혔다(기기에서 확인).
     *
     * 그래서 여기서 직접 센다. Leq 만은 세지 않고 **엔진의 누적을 두 번 떠
     * 차를 낸다** — 시간가중 값을 평균해도 등가소음도가 되지 않기 때문이다.
     */
    private var startSpan: kr.joa.selahrta.dsp.EnergySpan? = null
    private var lastSpan: kr.joa.selahrta.dsp.EnergySpan? = null

    // **보정을 건 값으로 견준다**(행 집계와 같은 규칙, 독립 검증 M32).
    // 구간이 바뀌면 raw 끼리의 대소가 실제 음압의 대소와 뒤집힌다.
    private var minCal = Double.POSITIVE_INFINITY
    private var maxCal = Double.NEGATIVE_INFINITY
    private var peakCal = Double.NEGATIVE_INFINITY

    init {
        epochs.add(
            RecordingEpoch(
                id = 0,
                startFrame = 0,
                calibrationOffsetDb = startOffsetDb,
                isReferenceOnly = startReferenceOnly,
                leqWindowMs = startLeqWindowMs,
            ),
        )
    }

    /** 지금까지 잰 길이(ms). 벽시계가 아니라 **프레임으로 센다**. */
    val elapsedMs: Long get() = frame * 1000L / nominalSampleRate

    /**
     * 보정이나 설정이 바뀌었다. **여기서 새 구간이 열린다.**
     *
     * 행은 값과 `epochId` 만 지니고 **보정은 읽을 때** 건다. 그래야
     * 나중에 보정이 바뀌어도 옛 행의 뜻이 지켜진다.
     *
     * 구간이 [EpochTable.MAX_EPOCHS] 를 넘으면 **거짓을 돌려준다** —
     * 부르는 쪽이 기록을 끝내야 한다. 과거 행이 가리키는 구간이 사라지면
     * 그 행의 뜻을 되살릴 수 없으므로, 말없이 잃는 것보다 낫다.
     */
    fun noteEpoch(
        offsetDb: Double,
        referenceOnly: Boolean,
        leqWindowMs: Long,
    ): Boolean = synchronized(lock) {
        if (finished) return true
        val last = epochs.all().lastOrNull()
        if (last != null &&
            last.calibrationOffsetDb == offsetDb &&
            last.isReferenceOnly == referenceOnly &&
            last.leqWindowMs == leqWindowMs
        ) {
            return true
        }
        val ok = epochs.add(
            RecordingEpoch(
                id = epochs.all().size,
                startFrame = frame,
                calibrationOffsetDb = offsetDb,
                isReferenceOnly = referenceOnly,
                leqWindowMs = leqWindowMs,
            ),
        )
        if (ok) {
            events += SessionEvent(
                elapsedMs,
                SessionEventKind.CalibrationChange,
                "%+.1fdB".format(offsetDb),
            )
        }
        return ok
    }

    /** 사건 하나를 적는다. 시각은 지금까지 잰 길이다. */
    fun note(
        kind: SessionEventKind,
        detailKo: String = "",
        segment: kr.joa.selahrta.domain.ChurchSegment? = null,
    ) = synchronized(lock) {
        if (finished) return
        events += SessionEvent(elapsedMs, kind, detailKo, segment)
    }

    /**
     * 덩어리 하나를 **쪼개어** 엔진에 넣고 행에 쌓는다.
     *
     * **엔진을 여기서 돌린다.** 부르는 쪽이 이미 돌린 뒤에 값을 넘기면,
     * 경계를 걸친 덩어리의 값이 한 덩어리분으로 뭉뚱그려진다.
     *
     * @return 마지막 조각의 SPL 프레임. 화면이 쓴다. 넣을 것이 없으면 null.
     */
    fun onBlock(
        samples: FloatArray,
        frames: Int,
        clipped: Boolean,
        spl: MultiWeightEngine,
        rta: RtaEngine,
    ): kr.joa.selahrta.dsp.MultiWeightFrame? = synchronized(lock) {
        // **늦은 콜백은 조용히 지나간다.** 멈추는 과정의 정상이다.
        if (finished) return null
        if (frames <= 0) {
            // 읽기 오류로 버린 덩어리. **행을 채우지 않는다** — 그 자리는
            // 집계기가 `missing` 으로 남긴다.
            droppedPackets++
            return null
        }

        // **기록이 시작된 자리를 떠 둔다.** 측정은 이미 돌고 있었을 수
        // 있으므로, 기록의 Leq 는 여기서부터 센 것이어야 한다.
        if (startSpan == null) startSpan = spl.energySpan(weighting)

        var last: kr.joa.selahrta.dsp.MultiWeightFrame? = null
        val slices = RowSlicer.slice(frame, frames, aggregator.framesPerRow, epochs)
        for (s in slices) {
            val offset = (s.frameStart - frame).toInt()
            rta.process(samples, s.frames, offset)
            val f = spl.process(samples, s.frames, offset)
            last = f
            val w = f.of(weighting)
            // **조각 안의 값을 적는다.** 엔진의 누적값(`maxDbfs`·`peakDbfs`)
            // 을 적으면 행이 올라가기만 하는 계단이 되어 「언제 컸는지」가
            // 사라진다 — 실제로 그렇게 기록했다가 기기에서 뽑아 보고 알았다.
            aggregator.add(
                s.frameStart,
                s.frames,
                s.epochId,
                RowSample(
                    currentRaw = w.currentDbfs.value,
                    maxRaw = w.blockMaxDbfs.value,
                    peakRaw = w.blockPeakDbfs.value,
                    // 부르는 쪽의 깃발은 덩어리 하나(최대 60ms)를 가리키므로
                    // 경계에서 옆 행까지 번질 수 있다. **많이 알리는 쪽으로**
                    // 틀린다 — 잘림을 놓치는 것보다 낫다.
                    clipped = clipped || w.blockClipped,
                    bands = rtaBands(rta),
                ),
            )
            noteExtremes(w, s.epochId)
        }
        lastSpan = spl.energySpan(weighting)
        frame += frames
        return last
    }

    /** 요약용 극값. **보정을 건 값으로** 견준다. */
    private fun noteExtremes(w: kr.joa.selahrta.dsp.SplFrame, epochId: Int) {
        val mx = epochs.calibrated(w.blockMaxDbfs.value, epochId)
        if (mx > maxCal) maxCal = mx
        val pk = epochs.calibrated(w.blockPeakDbfs.value, epochId)
        if (pk > peakCal) peakCal = pk
        val mn = w.blockMinDbfs ?: return
        val mnCal = epochs.calibrated(mn.value, epochId)
        if (mnCal < minCal) minCal = mnCal
    }

    /** 지금 RTA 밴드. 아직 첫 FFT 가 안 찼으면 조용한 값으로 채운다. */
    private fun rtaBands(rta: RtaEngine): FloatArray {
        val f = rta.frame() ?: return FloatArray(ThirdOctave.BAND_COUNT) {
            kr.joa.selahrta.dsp.SILENCE_DBFS.toFloat()
        }
        return FloatArray(ThirdOctave.BAND_COUNT) { f.bandsDbfs[it].toFloat() }
    }

    /**
     * 다 쌓은 행. **두 번 불러도 같은 것을 돌려준다.**
     *
     * 멈추는 길이 둘(사람이 멈춤·기기가 빠짐)이라 두 번 불릴 수 있다.
     * 두 번째에 예외를 던지면 그 길 하나가 죽는다.
     */
    fun finish(): RecordedSession = synchronized(lock) {
        result?.let { return it }
        finished = true
        return RecordedSession(
            id = id,
            rows = aggregator.finish(),
            epochs = epochs.all(),
            events = events.toList(),
            droppedPackets = droppedPackets,
            durationMs = elapsedMs,
            summary = summary(),
        ).also { result = it }
    }

    private fun summary(): RecordedSummary {
        val s = startSpan
        val l = lastSpan
        val leqDbfs = if (s != null && l != null) l.since(s)?.value else null
        // **구간이 둘 이상이면 Leq 를 음압으로 바꾸지 않는다.** 에너지
        // 평균은 구간마다 다른 보정을 걸 수 없고, 아무 구간의 보정을
        // 골라 걸면 그럴듯하게 틀린 값이 된다. 없는 값을 셈해 넣지 않는다.
        val single = epochs.all().singleOrNull()
        return RecordedSummary(
            weighting = weighting,
            leqDb = if (leqDbfs != null && single != null) {
                leqDbfs + single.calibrationOffsetDb
            } else {
                null
            },
            minDb = minCal.takeIf { it.isFinite() },
            maxDb = maxCal.takeIf { it.isFinite() },
            peakDb = peakCal.takeIf { it.isFinite() },
            referenceOnly = epochs.all().any { it.isReferenceOnly },
        )
    }
}

/**
 * **이 기록만의** 요약. 화면의 계기가 아니라 기록기가 센 것이다.
 *
 * 계기는 측정 전체를 재고 기록은 그 도중에 시작할 수 있어, 계기 값을
 * 겉장에 적으면 기록하지 않은 구간까지 섞인다(2026-09-26 기기에서 확인).
 *
 * 값이 없을 수 있다 — **없는 값을 셈해 넣지 않는다.**
 */
data class RecordedSummary(
    val weighting: kr.joa.selahrta.dsp.Weighting,
    /** 기록 구간의 등가소음도. 보정 구간이 둘 이상이면 null. */
    val leqDb: Double?,
    val minDb: Double?,
    val maxDb: Double?,
    val peakDb: Double?,
    /** 한 구간이라도 미보정이면 참. 숫자를 음압이라 부르지 않게 하려는 것. */
    val referenceOnly: Boolean,
)

/**
 * 한 번의 측정이 남긴 것. 겉장은 부르는 쪽이 붙인다.
 *
 * **[id] 를 결과가 지니고 다닌다.** 처음에는 쓰는 쪽이 화면 상태에서
 * 읽게 했다가 고쳤다 — 기록을 멈추면 화면은 곧바로 「기록 아님」으로
 * 돌아가는데, 쓰기 콜백은 캡처 스레드를 거쳐 **그 뒤에** 온다. 그
 * 사이에 신원이 사라져 파일이 한 줄도 쓰이지 않았다(기기에서 확인).
 */
data class RecordedSession(
    val id: String,
    val rows: List<TimelineRow>,
    val epochs: List<RecordingEpoch>,
    val events: List<SessionEvent>,
    val droppedPackets: Int,
    val durationMs: Long,
    val summary: RecordedSummary,
)
