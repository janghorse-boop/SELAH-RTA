package kr.joa.selahrta.transfer

import kr.joa.selahrta.audio.AudioBlock
import kr.joa.selahrta.dsp.BlockStats
import kr.joa.selahrta.dsp.TransferEngine

/**
 * 측정 블록 하나가 결과 창에 들었을 때 넘쳤는가(TF 설계 9장).
 */
enum class ClipVerdict {
    /** 결과 창과 겹치는 블록에 넘친 것이 없다. */
    None,

    /** 결과 창과 겹치는 블록 가운데 넘친 것이 있다 — 정확한 잘린 자리를 구한 것은 아니다. */
    Clipped,

    /** 결과 창의 앞쪽 기록이 이미 사라졌다. 「없음」이라 하지 않는다. */
    Unknown,
}

/**
 * 측정 블록의 끝 표본 번호와 넘침 여부를 짧게 적어 두는 고리(TF 설계 9장·15장).
 *
 * 표본 번호는 [TransferIngest] 가 엔진에 넣은 측정 표본 수 — 엔진이 쓰는 좌표와 같다(같은 `reset()` 뒤).
 * 결과의 평균 창 `[windowEnd − span, windowEnd)` 과 겹치는 블록을 보고 판정한다. 1판의 「지난 1초」는 창
 * 앞쪽 약 0.45초를 놓쳤다(30회차 R30-04).
 */
class ClipLedger(private val capacity: Int = DEFAULT_CAPACITY) {

    private val starts = LongArray(capacity)
    private val ends = LongArray(capacity)
    private val clipped = BooleanArray(capacity)
    private var head = 0
    private var size = 0

    fun clear() {
        head = 0
        size = 0
    }

    /** 블록 하나 — `[start, end)` 표본. */
    fun add(start: Long, end: Long, wasClipped: Boolean) {
        val i = (head + size) % capacity
        if (size == capacity) {
            head = (head + 1) % capacity
        } else {
            size++
        }
        starts[i] = start
        ends[i] = end
        clipped[i] = wasClipped
    }

    /** 결과 창 `[windowEnd − span, windowEnd)` 의 넘침 판정. */
    fun verdict(windowEnd: Long, span: Long): ClipVerdict {
        val windowStart = windowEnd - span
        if (size == 0) return ClipVerdict.Unknown
        // 창 앞쪽 기록이 남아 있는가 — 가장 오래된 블록이 창 시작보다 늦게 시작하면 모른다.
        if (starts[head] > windowStart.coerceAtLeast(0L)) return ClipVerdict.Unknown
        for (k in 0 until size) {
            val i = (head + k) % capacity
            if (clipped[i] && starts[i] < windowEnd && ends[i] > windowStart) return ClipVerdict.Clipped
        }
        return ClipVerdict.None
    }

    companion object {
        /** 1024 프레임 블록으로 약 5.5초 — 엔진이 뒤로 뜨는 창(93,632표본)과 계산 하나를 넉넉히 덮는다. */
        const val DEFAULT_CAPACITY = 256
    }
}

/**
 * Transfer Function 엔진에 표본이 들어가는 **한 경계**(TF 설계 3.3, 32회차 R32-03 · 33회차 R33-04).
 *
 * 기준 넣기·측정 넣기·[ClipLedger] 기록·엔진 `reset()`·무장·무장 풀기를 **모두 같은 자물쇠 안에서** 한다.
 * 그래서 「검사 → reset → 넣기」 사이에 다른 스레드가 끼지 못한다.
 *
 * - **무장** = 그 순간 엔진 `reset()` · 넘침 기록 비움 · 캡처 번호와 읽기 번호 경계를 기억. 그 뒤로 기준·
 *   측정 표본의 0 번이 같이 시작한다(엔진의 전제).
 * - 측정 블록을 받는 조건: 무장됨 · 같은 캡처 · `readSeq > 경계`. 경계는 스냅샷이 뜬 순간까지 **예약된**
 *   읽기 번호라, 통지 때 읽는 중이던 블록도 버려진다.
 * - 기준 표본을 받는 조건: 무장됨 · 같은 TF 세션.
 * - **경계 밖**: `AudioRecord` 안의 버퍼에 이미 들어 있던 표본은 블록 번호로 가를 수 없다 — 보장 밖이고
 *   그 크기는 재지 않았다.
 */
class TransferIngest(
    private val engine: TransferEngine,
    private val clips: ClipLedger = ClipLedger(),
) {
    /** 무장 상태. */
    data class Arm(val session: Long, val captureId: Long, val readSeqFloor: Long)

    private val lock = Any()
    private var armed: Arm? = null
    private var measuredSamples = 0L

    /** 지금 무장. 없으면 null. */
    val arm: Arm? get() = synchronized(lock) { armed }

    /** 무장한다 — 엔진을 비우고 경계를 기억한다. */
    fun arm(session: Long, captureId: Long, readSeqFloor: Long) = synchronized(lock) {
        engine.reset()
        clips.clear()
        measuredSamples = 0L
        armed = Arm(session, captureId, readSeqFloor)
    }

    /** 무장을 푼다. 그 뒤로는 어떤 표본도 받지 않는다. */
    fun disarm() = synchronized(lock) {
        armed = null
    }

    /** 다시 맞추기 — 무장은 그대로, 엔진과 넘침 기록만 새로(새 epoch·새 좌표). */
    fun resetKeepingArm() = synchronized(lock) {
        engine.reset()
        clips.clear()
        measuredSamples = 0L
    }

    /** 기준 표본(출력 탭). **출력 스레드.** */
    fun offerReference(session: Long, buf: FloatArray, offset: Int, count: Int) = synchronized(lock) {
        val a = armed ?: return@synchronized
        if (a.session != session) return@synchronized
        engine.offerReference(buf, offset, count)
    }

    /**
     * 측정 블록. **캡처 스레드.** 받았으면 true.
     */
    fun offerMeasurement(captureId: Long, block: AudioBlock, stats: BlockStats): Boolean = synchronized(lock) {
        val a = armed ?: return@synchronized false
        if (captureId != a.captureId) return@synchronized false
        if (block.readSeq <= a.readSeqFloor) return@synchronized false
        if (block.frames <= 0) return@synchronized false
        engine.offerMeasurement(block.samples, 0, block.frames)
        val start = measuredSamples
        measuredSamples += block.frames
        clips.add(start, measuredSamples, stats.clipped)
        true
    }

    /** 결과 창의 넘침 판정. */
    fun clipVerdict(windowEnd: Long, span: Long): ClipVerdict = synchronized(lock) {
        clips.verdict(windowEnd, span)
    }
}
