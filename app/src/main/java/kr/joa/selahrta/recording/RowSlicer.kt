package kr.joa.selahrta.recording

/**
 * 덩어리 하나를 **행·epoch 경계에서 쪼갠다**.
 *
 * [RowAggregator] 는 경계를 걸친 조각을 거부한다 — 걸친 채로 받으면 한
 * 행이 두 구간의 값을 섞거나, epoch 이 바뀌는 자리에서 옛 보정을 받은
 * 값이 새 보정의 행에 들어간다(독립 검증 `EPOCH_CROSS`). 「부르는 쪽이
 * 쪼갠다」가 그 계약이고, 이것이 그 부르는 쪽이다.
 *
 * ## 왜 따로 떼어 두는가
 *
 * 쪼개는 셈은 **순수하다** — 프레임 번호만 있으면 된다. 캡처 반복문
 * 안에 두면 오디오 스레드를 돌려야만 시험할 수 있는데, 이 저장소에서
 * 잡은 결함은 거의 전부 JVM 에서 순서를 고정해 잡았다.
 *
 * ## 덩어리가 걸치는 일은 드물지만 반드시 온다
 *
 * 덩어리는 60ms 남짓, 행은 500ms 다. 여덟에 하나쯤이 걸친다 — 드물다고
 * 안 다루면 **예배 두 시간에 천 번** 틀린다.
 */
object RowSlicer {

    /** 쪼갠 조각 하나. [RowAggregator.add] 의 인자 그대로다. */
    data class Slice(
        val frameStart: Long,
        val frames: Int,
        val epochId: Int,
    )

    /**
     * [frameStart] 부터 [frames] 개를 경계에서 쪼갠다.
     *
     * 경계는 둘이다 — **행**([framesPerRow] 배수)과 **epoch 시작**.
     * 둘 다 넘지 않는 가장 긴 조각을 차례로 낸다.
     *
     * epoch 이 아직 없는 구간은 **버린다**(빈 목록의 일부가 된다).
     * 보정이 무엇인지 모르는 값을 기록에 넣을 수 없기 때문이다 —
     * 나중에 그 행은 `missing` 으로 채워진다.
     */
    fun slice(
        frameStart: Long,
        frames: Int,
        framesPerRow: Long,
        epochs: EpochTable,
    ): List<Slice> {
        require(frames >= 0) { "조각 길이가 음수다: $frames" }
        require(framesPerRow > 0) { "한 행이 0 프레임이다: $framesPerRow" }
        require(frameStart >= 0) { "프레임이 음수다: $frameStart" }
        if (frames == 0) return emptyList()

        val end = frameStart + frames
        val out = ArrayList<Slice>(2)
        var at = frameStart
        while (at < end) {
            val epochId = epochs.idAt(at)
            if (epochId == null) {
                // 아직 epoch 이 없다. **지어내지 않는다** — 다음 경계까지
                // 건너뛰고, 그 자리는 비어 있는 채로 둔다.
                at = nextBoundary(at, end, framesPerRow, epochs)
                continue
            }
            val stop = nextBoundary(at, end, framesPerRow, epochs)
            out += Slice(at, (stop - at).toInt(), epochId)
            at = stop
        }
        return out
    }

    /**
     * [at] 다음에 오는 **가장 가까운 경계**(exclusive). [end] 를 넘지 않는다.
     *
     * 행 경계와 epoch 시작 중 이른 쪽이다.
     */
    private fun nextBoundary(
        at: Long,
        end: Long,
        framesPerRow: Long,
        epochs: EpochTable,
    ): Long {
        val nextRow = (at / framesPerRow + 1) * framesPerRow
        var stop = minOf(nextRow, end)
        // **이 구간 안에서 시작하는 epoch** 이 있으면 거기서 끊는다.
        for (e in epochs.all()) {
            if (e.startFrame in (at + 1) until stop) stop = e.startFrame
        }
        return stop
    }
}
