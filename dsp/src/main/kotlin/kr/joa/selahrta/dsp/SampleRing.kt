package kr.joa.selahrta.dsp

/**
 * 모노 표본을 담아 두는 고리 버퍼.
 *
 * **잠금을 스스로 잡지 않는다.** 기준과 측정 두 고리를 **같은 순간에**
 * 떠야 하는데, 고리마다 따로 잠그면 그 사이에 한쪽만 더 들어와
 * 시간차가 생긴다. 그 시간차가 바로 우리가 재려는 값이라 섞이면 안 된다.
 * 잠금은 [TransferEngine] 이 하나로 잡는다.
 */
class SampleRing(private val capacity: Int) {
    init { require(capacity > 0) { "capacity 는 1 이상이라야 한다: $capacity" } }

    private val buf = FloatArray(capacity)
    private var head = 0

    /** 지금까지 들어온 총 개수. 덮어쓴 것도 센다. */
    var written: Long = 0L
        private set

    fun write(src: FloatArray, offset: Int, count: Int) {
        require(offset >= 0 && count >= 0 && offset + count <= src.size) {
            "offset=$offset count=$count 가 크기 ${src.size} 를 벗어난다"
        }
        for (i in 0 until count) {
            buf[head] = src[offset + i]
            head = (head + 1) % capacity
        }
        written += count
    }

    /**
     * 가장 최근에서 [lagBack] 만큼 거슬러 올라간 자리를 끝으로 하여,
     * `out.size` 개를 **시간 순으로** 담는다.
     *
     * 아직 그만큼 안 들어왔으면 **앞쪽을 0 으로 두고 뒤에 채운다** —
     * 창의 **끝이 기준 시각**이어야 상호상관의 지연이 뒤집히지 않는다.
     *
     * **[lagBack] 이 있는 까닭**: 측정은 기준보다 늦게 들어온다. 두 창을
     * 그냥 뜨면 그 지연만큼 어긋난 채로 스펙트럼을 곱하게 되고
     * **Coherence 가 통째로 무너진다.** 기준 쪽을 지연만큼 거슬러 떠서
     * 시간을 맞춘다.
     *
     * **[TransferEngine] 은 지연 보정에는 이 매개변수를 쓰지 않는다** — 잠금을
     * 두 번 잡지 않으려고 **한 번에 길게 떠서 배열 안에서 자른다.** 대신
     * **앞서간 쪽을 같은 표본 번호로 되돌리는 데** 쓴다(독립 검토 R6-01).
     *
     * @return 실제로 채운 개수.
     */
    fun snapshot(out: DoubleArray, lagBack: Int = 0): Int {
        require(lagBack >= 0) { "lagBack 은 0 이상이라야 한다: $lagBack" }
        val have = minOf(written, capacity.toLong()).toInt()
        val usable = (have - lagBack).coerceAtLeast(0)
        val take = minOf(usable, out.size)
        java.util.Arrays.fill(out, 0, out.size - take, 0.0)
        // head 는 **다음에 쓸 자리**다. 거기서 lagBack + take 만큼 거슬러 간다.
        var idx = ((head - lagBack - take) % capacity + capacity) % capacity
        for (i in out.size - take until out.size) {
            out[i] = buf[idx].toDouble()
            idx = (idx + 1) % capacity
        }
        return take
    }

    fun clear() {
        java.util.Arrays.fill(buf, 0f)
        head = 0
        written = 0L
    }
}
