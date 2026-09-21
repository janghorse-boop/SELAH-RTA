package kr.joa.selahrta.recording

import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * 오디오 스레드에서 기록 스레드로 PCM 을 넘기는 길(녹음 설계 4차 M31).
 *
 * **자물쇠도, 대기도, 할당도 없다.** 오디오 스레드가 한 번이라도 멈추면
 * 그 자리의 소리가 사라진다 — 되돌릴 방법이 없다. 그래서 표준
 * blocking queue 를 쓰지 않는다. 용량이 남아 있어도 `ArrayBlockingQueue`
 * 는 interrupt 가 걸린 스레드에서 던진다(검증자가 보인 반례:
 * `accepted=1, consumed=0, interrupted=true`).
 *
 * **생산자 하나(오디오 스레드), 소비자 하나(기록 스레드)** 다. 그래서
 * 색인 두 개로 충분하다.
 */

/**
 * 미리 잡아 둔 PCM 버퍼들.
 *
 * **버퍼 하나 ↔ 링 칸 하나**, 그리고 용량이 같다. 그래서 버퍼를
 * 잡았다면 빈 칸이 **반드시** 있다(설계 4차 「왜 실패할 수 없는가」).
 *
 * ## 누가 어디에 돌려주는가 — 이것이 요점이다 (독립 검증 REC01)
 *
 * 빈 번호를 담는 자리(free-ring)도 링이고, **거기에 넣는 쪽은 writer
 * 하나뿐이다.** 처음에는 생산자도 같은 자리에 넣게 두었다가 깨졌다 —
 * 두 스레드가 같은 `given` 을 읽고 같은 칸을 덮어 **반환이 사라졌다**
 * (검증자 probe: 30,000회 중 1~3회).
 *
 * 그래서 나눈다:
 *
 * | 누가 | 언제 | 어디로 |
 * |---|---|---|
 * | writer | PCM 을 다 쓴 뒤 | [release] → free-ring |
 * | 생산자 | 게시하지 못하고 물러날 때 | [cancel] → 제 자리([producerHeld]) |
 *
 * 생산자가 되돌린 버퍼는 **생산자가 다음에 다시 쓴다.** free-ring 을
 * 거치지 않으므로 writer 와 다툴 일이 없다.
 */
class BufferPool(val count: Int, val frames: Int) {

    init {
        require(count > 0) { "버퍼 수가 0 이하다: $count" }
        require(frames > 0) { "버퍼 길이가 0 이하다: $frames" }
    }

    private val buffers = Array(count) { FloatArray(frames) }

    /** 빈 버퍼 번호들. 처음에는 전부 비어 있다. */
    private val free = IntArray(count) { it }

    /** 잡은 횟수. **잡는 쪽(생산자)만** 올린다. */
    @Volatile
    private var taken = 0L

    /** 돌려준 횟수. **돌려주는 쪽(writer)만** 올린다. 처음엔 전부 들어 있다. */
    @Volatile
    private var given = count.toLong()

    /**
     * 생산자가 게시하지 못해 되돌려 둔 버퍼. 없으면 −1.
     *
     * **생산자만 만진다.** `@Volatile` 은 진단이 읽을 수 있게 하려는
     * 것이지 다툼을 막으려는 것이 아니다.
     */
    @Volatile
    private var producerHeld = -1

    /**
     * 지금 빌려 나간 번호들. **같은 번호를 두 번 돌려주는 것**을 막는다.
     *
     * 예전에는 개수만 보고 번호를 보지 않아, 둘을 빌린 상태에서 같은
     * 번호를 두 번 돌려줘도 통과했고 그 뒤 `acquire` 가 `0, 0` 을
     * 내줬다(독립 검증 REC01의 두 번째 지적).
     */
    private val borrowed = AtomicIntegerArray(count)

    /** writer 에게 나가 있는 수. 생산자가 되돌려 둔 것은 빼고 센다. */
    val inUse: Int
        get() {
            val out = (count - (given - taken)).toInt()
            return if (producerHeld >= 0) out - 1 else out
        }

    /** 생산자가 되돌려 쥐고 있는 버퍼가 있는가. 시험과 진단이 본다. */
    val hasProducerHeld: Boolean get() = producerHeld >= 0

    /**
     * free-ring 으로 돌아온 총 횟수. **[release] 만 올린다.**
     *
     * 시험이 「생산자가 free-ring 을 건드리지 않았다」를 **타이밍에 기대지
     * 않고** 확인하려고 둔다. 생산자가 [cancel] 대신 [release] 를 부르면
     * 이 수가 는다 — 두 반환이 실제로 겹쳤는지와 무관하게 드러난다.
     */
    val releasedToFree: Long get() = given - count

    /**
     * 버퍼 하나를 잡는다. 없으면 **−1**.
     *
     * **−1 은 정상이다.** 기록이 못 따라온다는 뜻이고, 그것이 곧 손실이다 —
     * 세어서 남길 일이지 막을 일이 아니다(설계 4차).
     *
     * **생산자만 부른다.**
     */
    fun acquire(): Int {
        // 되돌려 둔 것이 있으면 그것부터 쓴다. free-ring 을 건드리지 않는다.
        val held = producerHeld
        if (held >= 0) {
            producerHeld = -1
            return held
        }
        val t = taken
        // 상대가 올린 색인을 본다. 내 색인을 읽어 봐야 상대가 쓴 것과
        // 맞물리지 않는다(독립 검증 M41).
        if (t >= given) return -1
        val index = free[(t % count).toInt()]
        borrowed.set(index, 1)
        taken = t + 1
        return index
    }

    /**
     * 게시하지 못한 버퍼를 생산자가 되돌린다. **생산자만 부른다.**
     *
     * free-ring 에 넣지 않는다 — 거기 넣는 쪽은 writer 하나여야 한다.
     */
    fun cancel(index: Int) {
        require(index in 0 until count) { "버퍼 번호가 범위를 벗어난다: $index" }
        check(borrowed.get(index) == 1) { "빌리지 않은 버퍼를 되돌린다: $index" }
        check(producerHeld < 0) { "이미 되돌려 둔 것이 있다 — 한 번에 하나만 쥔다" }
        producerHeld = index
    }

    /**
     * 다 쓴 버퍼를 돌려준다. **writer 가 PCM 을 다 쓴 뒤**이고,
     * **writer 만 부른다.**
     */
    fun release(index: Int) {
        require(index in 0 until count) { "버퍼 번호가 범위를 벗어난다: $index" }
        check(borrowed.getAndSet(index, 0) == 1) {
            "빌리지 않은 버퍼를 돌려준다: $index (두 번 돌려줬거나 남의 것이다)"
        }
        val g = given
        check(g - taken < count) { "돌려준 것이 잡은 것보다 많다" }
        free[(g % count).toInt()] = index
        given = g + 1
    }

    /**
     * 생산자가 쥐고 있던 버퍼를 free-ring 으로 넘긴다.
     *
     * **생산자가 확실히 멈춘 뒤에만 부른다** — 그때는 다투는 상대가 없다.
     * 끝낼 때 이것을 빠뜨리면 버퍼 하나가 영영 돌아오지 않는다
     * (독립 검증 REC01 「종료 시 인수인계 규약」).
     */
    fun handOverHeld() {
        val held = producerHeld
        if (held < 0) return
        producerHeld = -1
        release(held)
    }

    fun buffer(index: Int): FloatArray = buffers[index]
}

/**
 * 링 한 칸. **미리 만들어 두고 돌려 쓴다** — 게시할 때 새로 만들지 않는다.
 *
 * 오디오 경로에 할당을 남기지 않으려는 것이다(설계 4차 M31).
 */
class PacketSlot {
    var seq: Long = -1L
    var captureFrameStart: Long = 0L
    var frames: Int = 0
    var epochId: Int = -1
    var bufferIndex: Int = -1

    fun copyFrom(other: PacketSlot) {
        seq = other.seq
        captureFrameStart = other.captureFrameStart
        frames = other.frames
        epochId = other.epochId
        bufferIndex = other.bufferIndex
    }
}

/**
 * 전용 SPSC 링.
 *
 * 순서는 설계 4차가 적은 그대로다:
 *
 * ```
 * producer: slot 필드 채움         → writeIndex  release-store
 * consumer: writeIndex acquire-load → 게시된 slot 읽기
 * consumer: slot 사용을 마침        → readIndex   release-store
 * producer: readIndex acquire-load  → 회수된 slot 재사용
 * ```
 *
 * `@Volatile` 쓰기는 release 보다 세고, `@Volatile` 읽기는 acquire 보다
 * 세다. 필요한 것보다 세게 잡되, **한쪽만 쓰는 색인**이라 경합이 없다.
 */
class PacketRing(val capacity: Int) {

    init {
        require(capacity > 0) { "용량이 0 이하다: $capacity" }
    }

    private val slots = Array(capacity) { PacketSlot() }

    /** 게시한 수. **생산자만** 쓴다. */
    @Volatile
    private var writeIndex = 0L

    /** 인수한 수. **소비자만** 쓴다. */
    @Volatile
    private var readIndex = 0L

    /** 지금 들어 있는 수. */
    val size: Int get() = (writeIndex - readIndex).toInt()

    /**
     * 칸을 채우고 게시한다.
     *
     * **거짓을 돌려주면 불변식이 깨진 것이다.** 버퍼 하나에 칸 하나이고
     * 용량이 같으므로, 버퍼를 잡은 뒤라면 빈 칸이 반드시 있다. 부르는
     * 쪽은 이것을 손실로 세지 말고 **터뜨려야** 한다(설계 4차의
     * 「번호 받은 뒤 게시 실패 = 불변식 위반」).
     */
    fun publish(
        seq: Long,
        captureFrameStart: Long,
        frames: Int,
        epochId: Int,
        bufferIndex: Int,
    ): Boolean {
        val w = writeIndex
        // 상대가 올린 색인을 본다.
        if (w - readIndex >= capacity) return false
        val s = slots[(w % capacity).toInt()]
        s.seq = seq
        s.captureFrameStart = captureFrameStart
        s.frames = frames
        s.epochId = epochId
        s.bufferIndex = bufferIndex
        // **필드를 다 채운 뒤에** 색인을 올린다. 순서가 뒤바뀌면 소비자가
        // 반쯤 채워진 칸을 읽는다.
        writeIndex = w + 1
        return true
    }

    /**
     * 게시된 칸 하나를 [out] 으로 **베껴** 온다. 없으면 false.
     *
     * **베끼는 까닭**: 색인을 올리는 순간 그 칸은 생산자의 것이 된다.
     * 참조를 들고 있으면 다음 블록이 그 위를 덮는다.
     *
     * **버퍼는 여기서 돌려주지 않는다.** 칸은 인수한 즉시 돌려주지만,
     * PCM 은 writer 가 다 쓴 뒤에 돌려준다 — 둘을 같은 시점으로 묶으면
     * 아직 쓰고 있는 PCM 이 덮인다(설계 4차).
     */
    fun poll(out: PacketSlot): Boolean {
        val r = readIndex
        // 상대가 올린 색인을 본다.
        if (r >= writeIndex) return false
        out.copyFrom(slots[(r % capacity).toInt()])
        readIndex = r + 1
        return true
    }
}

/**
 * 번호를 내어 주는 곳. **닫히면 −1 을 준다.**
 *
 * 번호를 받았다는 것은 「이 조각은 기록에 들어간다」는 뜻이다. 닫힌 뒤에
 * 받으면 안 되고, 받은 뒤에 닫혀도 그 조각은 들어가야 한다 — 그래야
 * 비우기가 끝나는 지점이 정해진다.
 */
class Admission {

    @Volatile
    private var closed = false

    private var next = 0L

    /** 다음 번호. 닫혀 있으면 **−1**. **생산자만** 부른다. */
    fun next(): Long {
        if (closed) return -1L
        return next++
    }

    /** 더 받지 않는다. 이미 나간 번호는 그대로 유효하다. */
    fun close() {
        closed = true
    }

    val isClosed: Boolean get() = closed
}

/**
 * 풀·링·번호를 한 덩어리로 묶은 길.
 *
 * **소유권 규칙을 여기 한 곳에 둔다.** 예전에는 이 순서를 시험 안에만
 * 적어 두었는데, 그러면 제품에서 다르게 쓰여도 아무도 모른다 — 실제로
 * 시험의 예시가 「돌려주는 쪽은 하나」라는 전제를 깨고 있었다
 * (독립 검증 REC01: *「실제 offer/취소 로직을 production 경계로 옮겨
 * 같은 코드를 시험한다」*).
 */
class PacketPipe(bufferCount: Int, frames: Int) {

    val pool = BufferPool(bufferCount, frames)
    val ring = PacketRing(bufferCount)
    private val admission = Admission()

    /** [offer] 의 결과. 무엇이 정상이고 무엇이 불변식 위반인지 가른다. */
    enum class Offer {
        /** 들어갔다. */
        Published,

        /** 풀이 말랐다. **정상**이고, 그것이 곧 손실이다. */
        Dropped,

        /** 닫혔다. 더 받지 않는다. */
        Closed,

        /** 옮겨 담다 터졌다. 번호를 받기 전이라 아무것도 남지 않는다. */
        CopyFailed,

        /** **불변식 위반.** 번호를 받았는데 게시하지 못했다. */
        PublishFailed,
    }

    /** 지금까지 풀이 말라 버린 수. 화면과 기록이 본다. */
    @Volatile
    var dropped: Long = 0L
        private set

    /**
     * 조각 하나를 넣는다. **오디오 스레드가 부른다.**
     *
     * 순서가 설계 그대로여야 한다 — **풀에서 잡기 → 옮겨 담기 → 번호
     * 받기 → 게시.** 번호 받기와 게시 사이에는 필드 대입과 색인 저장뿐이다.
     */
    fun offer(
        captureFrameStart: Long,
        frames: Int,
        epochId: Int,
        fill: (FloatArray) -> Unit,
    ): Offer {
        val bufferIndex = pool.acquire()
        if (bufferIndex < 0) {
            dropped++
            return Offer.Dropped
        }

        try {
            fill(pool.buffer(bufferIndex))
        } catch (t: Throwable) {
            // 아직 번호를 받기 전이다. **생산자 제 자리로** 되돌린다.
            pool.cancel(bufferIndex)
            return Offer.CopyFailed
        }

        val seq = admission.next()
        if (seq < 0) {
            pool.cancel(bufferIndex)
            return Offer.Closed
        }

        return if (ring.publish(seq, captureFrameStart, frames, epochId, bufferIndex)) {
            Offer.Published
        } else {
            Offer.PublishFailed
        }
    }

    /** 게시된 것 하나를 인수한다. **기록 스레드가 부른다.** */
    fun poll(out: PacketSlot): Boolean = ring.poll(out)

    /** PCM 을 다 쓴 뒤 버퍼를 돌려준다. **기록 스레드만 부른다.** */
    fun releaseAfterWrite(bufferIndex: Int) = pool.release(bufferIndex)

    /** 더 받지 않는다. 이미 나간 번호는 그대로 유효하다. */
    fun close() = admission.close()

    val isClosed: Boolean get() = admission.isClosed

    /**
     * 끝낸다. **생산자가 확실히 멈춘 뒤**에 부른다.
     *
     * 생산자가 쥐고 있던 버퍼를 free-ring 으로 넘긴다. 빠뜨리면 버퍼
     * 하나가 영영 돌아오지 않는다.
     */
    fun handOverProducerHeld() = pool.handOverHeld()
}
