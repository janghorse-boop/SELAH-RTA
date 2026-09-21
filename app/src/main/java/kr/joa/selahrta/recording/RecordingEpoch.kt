package kr.joa.selahrta.recording

/**
 * 녹음 도중 **바뀌지 않는 한 구간**의 보정·설정(녹음 설계 2차 ④).
 *
 * 보정값이나 설정이 하나라도 바뀌면 그 프레임에서 **새 epoch** 이
 * 시작한다. 타임라인의 행은 값과 함께 `epochId` 만 지니고, **보정은
 * 읽을 때** 건다 — 그래야 나중에 보정이 바뀌어도 옛 행의 뜻이 지켜진다.
 *
 * @param startFrame 이 epoch 이 시작하는 **녹음 기준 프레임 번호**.
 */
data class RecordingEpoch(
    val id: Int,
    val startFrame: Long,
    /** 이 구간에서 dBFS 에 더해 음압으로 옮기던 값. */
    val calibrationOffsetDb: Double,
    /**
     * 그 보정이 **짐작한 눈금**이었는가.
     *
     * 미보정 구간의 숫자를 나중에 「음압」이라 부르지 않게 하려고
     * 행마다 따라다녀야 한다.
     */
    val isReferenceOnly: Boolean,
    /** 긴 Leq 의 창(ms). 10초·1분·5분 중 하나다. */
    val leqWindowMs: Long,
)

/**
 * 한 녹음의 epoch 들. **덮어쓰지 않는다.**
 *
 * 과거 행이 가리키는 epoch 가 사라지면 그 행의 뜻을 되살릴 수 없다.
 * 그래서 [MAX_EPOCHS] 를 넘으면 **녹음을 실패로 끝낸다** — 말없이
 * 잃는 것보다 낫다(녹음 설계 2차 ⑤).
 *
 * **한 스레드 전용이다.** [add] 와 읽기를 다른 스레드에서 섞어
 * 부르지 않는다는 전제다. 잠금을 두어 가리지 않고 **적어 둔다.**
 */
class EpochTable(private val max: Int = MAX_EPOCHS) {

    private val items = ArrayList<RecordingEpoch>()

    val size: Int get() = items.size

    /** 넣는다. 넘치면 false — 부르는 쪽이 녹음을 끝낸다. */
    fun add(epoch: RecordingEpoch): Boolean {
        // **개수 상한이 id 범위를 대신하지 못한다**(독립 검증 RA03).
        // 256개만 담아도 id 가 65536 이면 파일에서 Short 로 잘려 0 이
        // 된다. 그러면 재생이 다른 epoch 의 보정을 걸어 90dB 이 70dB 로
        // 나온다. 범위를 따로 막는다 — 부르는 쪽의 잘못이므로 false 가
        // 아니라 예외다. 조용히 넘어가면 잘린 id 로 녹음이 이어진다.
        require(epoch.id in ID_RANGE) {
            "epoch id 가 범위를 벗어난다: ${epoch.id} (허용 $ID_RANGE)"
        }
        if (items.size >= max) return false
        require(items.isEmpty() || epoch.startFrame >= items.last().startFrame) {
            "epoch 은 프레임 순서대로 와야 한다"
        }
        require(items.none { it.id == epoch.id }) { "epoch id 가 겹친다: ${epoch.id}" }
        items.add(epoch)
        return true
    }

    operator fun get(id: Int): RecordingEpoch =
        items.firstOrNull { it.id == id } ?: error("모르는 epoch: $id")

    /** 그 id 의 epoch. 없으면 null — 읽는 쪽이 짐작하지 않게 한다. */
    fun find(id: Int): RecordingEpoch? = items.firstOrNull { it.id == id }

    fun all(): List<RecordingEpoch> = items.toList()

    /** 이 프레임에 걸린 epoch. */
    fun at(frame: Long): RecordingEpoch =
        items.lastOrNull { it.startFrame <= frame } ?: error("$frame 에 걸린 epoch 이 없다")

    /** 이 프레임에 걸린 epoch 의 id. 없으면 null. */
    fun idAt(frame: Long): Int? = items.lastOrNull { it.startFrame <= frame }?.id

    /** dBFS 를 그 epoch 의 눈금으로 옮긴다. **유일한 통로다.** */
    fun calibrated(raw: Double, epochId: Int): Double = raw + get(epochId).calibrationOffsetDb

    companion object {
        /**
         * 한 녹음에 담을 epoch 상한.
         *
         * **재지 않고 고른 값이다.** 예배 도중 설정을 256번 바꾸는 일은
         * 사실상 없지만, 그 근거를 잰 적은 없다.
         */
        const val MAX_EPOCHS = 256

        /**
         * 쓸 수 있는 epoch id.
         *
         * 파일은 id 를 **2바이트**로 적고, −1 은 「측정 없음」
         * 전용이다. [MAX_EPOCHS] 개를 담으려면 0..255 면 충분하고,
         * 넘어가면 잘리므로 여기서 막는다(독립 검증 RA03).
         */
        val ID_RANGE = 0..255
    }
}
