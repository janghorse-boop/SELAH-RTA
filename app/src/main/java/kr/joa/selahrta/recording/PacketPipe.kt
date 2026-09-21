package kr.joa.selahrta.recording

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
 * 빈 번호를 담는 자리도 링이다 — 잡는 쪽이 하나(오디오 스레드),
 * 돌려주는 쪽이 하나(기록 스레드)이므로 같은 구조가 그대로 쓰인다.
 */
class BufferPool(val count: Int, val frames: Int) {

    init {
        require(count > 0) { "버퍼 수가 0 이하다: $count" }
        require(frames > 0) { "버퍼 길이가 0 이하다: $frames" }
    }

    private val buffers = Array(count) { FloatArray(frames) }

    /** 빈 버퍼 번호들. 처음에는 전부 비어 있다. */
    private val free = IntArray(count) { it }

    /** 잡은 횟수. **잡는 쪽만** 올린다. */
    @Volatile
    private var taken = 0L

    /** 돌려준 횟수. **돌려주는 쪽만** 올린다. 처음엔 전부 들어 있다. */
    @Volatile
    private var given = count.toLong()

    /** 지금 빌려 나간 수. 시험과 진단이 본다. */
    val inUse: Int get() = (count - (given - taken)).toInt()

    /**
     * 버퍼 하나를 잡는다. 없으면 **−1**.
     *
     * **−1 은 정상이다.** 기록이 못 따라온다는 뜻이고, 그것이 곧 손실이다 —
     * 세어서 남길 일이지 막을 일이 아니다(설계 4차).
     */
    fun acquire(): Int {
        val t = taken
        // 상대가 올린 색인을 acquire-load 한다. 내 색인을 읽어 봐야
        // 상대가 쓴 것과 맞물리지 않는다(독립 검증 M41).
        if (t >= given) return -1
        val index = free[(t % count).toInt()]
        taken = t + 1
        return index
    }

    /** 다 쓴 버퍼를 돌려준다. **writer 가 PCM 을 다 쓴 뒤**다. */
    fun release(index: Int) {
        require(index in 0 until count) { "버퍼 번호가 범위를 벗어난다: $index" }
        val g = given
        check(g - taken < count) { "돌려준 것이 잡은 것보다 많다 — 두 번 돌려줬다" }
        free[(g % count).toInt()] = index
        given = g + 1
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
