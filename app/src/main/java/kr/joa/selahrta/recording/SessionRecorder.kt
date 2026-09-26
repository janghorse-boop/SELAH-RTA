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
 * ## 한 스레드 전용이다
 *
 * 잠금이 없다. 부르는 쪽(캡처 스레드)이 하나임을 전제로 한다 — 이
 * 저장소에서 거듭 틀렸던 자리가 **「이미 들어와 있는 것」과 「새로
 * 시작한 것」이 같은 상태를 만지는 곳**이라, 그 약속을 적어 둔다.
 */
class SessionRecorder(
    val id: String,
    private val nominalSampleRate: Int,
    /** 시작할 때의 보정. 바뀌면 [noteEpoch] 로 새 구간을 연다. */
    startOffsetDb: Double,
    startReferenceOnly: Boolean,
    startLeqWindowMs: Long,
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

    private var finished = false

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
    fun noteEpoch(offsetDb: Double, referenceOnly: Boolean, leqWindowMs: Long): Boolean {
        check(!finished) { "이미 끝낸 기록이다" }
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
        if (ok) note(SessionEventKind.CalibrationChange, "%+.1fdB".format(offsetDb))
        return ok
    }

    /** 사건 하나를 적는다. 시각은 지금까지 잰 길이다. */
    fun note(
        kind: SessionEventKind,
        detailKo: String = "",
        segment: kr.joa.selahrta.domain.ChurchSegment? = null,
    ) {
        check(!finished) { "이미 끝낸 기록이다" }
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
    ): kr.joa.selahrta.dsp.MultiWeightFrame? {
        check(!finished) { "이미 끝낸 기록이다" }
        if (frames <= 0) {
            // 읽기 오류로 버린 덩어리. **행을 채우지 않는다** — 그 자리는
            // 집계기가 `missing` 으로 남긴다.
            droppedPackets++
            return null
        }

        var last: kr.joa.selahrta.dsp.MultiWeightFrame? = null
        val slices = RowSlicer.slice(frame, frames, aggregator.framesPerRow, epochs)
        for (s in slices) {
            val offset = (s.frameStart - frame).toInt()
            rta.process(samples, s.frames, offset)
            val f = spl.process(samples, s.frames, offset)
            last = f
            aggregator.add(
                s.frameStart,
                s.frames,
                s.epochId,
                RowSample(
                    currentRaw = f.a.currentDbfs.value,
                    maxRaw = f.a.maxDbfs.value,
                    peakRaw = f.a.peakDbfs.value,
                    clipped = clipped,
                    bands = rtaBands(rta),
                ),
            )
        }
        frame += frames
        return last
    }

    /** 지금 RTA 밴드. 아직 첫 FFT 가 안 찼으면 조용한 값으로 채운다. */
    private fun rtaBands(rta: RtaEngine): FloatArray {
        val f = rta.frame() ?: return FloatArray(ThirdOctave.BAND_COUNT) {
            kr.joa.selahrta.dsp.SILENCE_DBFS.toFloat()
        }
        return FloatArray(ThirdOctave.BAND_COUNT) { f.bandsDbfs[it].toFloat() }
    }

    /** 다 쌓은 행. 한 번만 부를 수 있다. */
    fun finish(): RecordedSession {
        check(!finished) { "이미 끝낸 기록이다" }
        finished = true
        return RecordedSession(
            rows = aggregator.finish(),
            epochs = epochs.all(),
            events = events.toList(),
            droppedPackets = droppedPackets,
            durationMs = elapsedMs,
        )
    }
}

/** 한 번의 측정이 남긴 것. 겉장은 부르는 쪽이 붙인다. */
data class RecordedSession(
    val rows: List<TimelineRow>,
    val epochs: List<RecordingEpoch>,
    val events: List<SessionEvent>,
    val droppedPackets: Int,
    val durationMs: Long,
)
