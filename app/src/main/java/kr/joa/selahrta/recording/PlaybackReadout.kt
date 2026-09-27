package kr.joa.selahrta.recording

/**
 * **소리를 들으며 그 순간의 값을 함께 본다**(담당자 지시 2026-09-27).
 *
 * 숫자만 보면 「95dB 이 찍혔다」까지는 알아도 **그때 무슨 소리였는지**는
 * 모른다. 소리만 들으면 반대다. 둘을 같은 시각으로 묶어야 「이 자리가
 * 그 자리」라고 말할 수 있다.
 *
 * ## 여기서 셈하지 않는다
 *
 * 행에 적힌 값에 **그 행의 보정**을 걸어 돌려줄 뿐이다. 평균을 새로
 * 내거나 없는 값을 채우지 않는다.
 *
 * ## 보정은 행마다 다를 수 있다
 *
 * 녹음 도중 보정이 바뀌면 새 epoch 이 시작한다. 겉장의 한 값을 모든
 * 행에 걸면 바뀐 뒤 구간이 조용히 틀린다 — 내보내기와 같은 규칙이다.
 */
data class PlaybackValues(
    /** 이 값이 몇 번째 행인가. 없으면 -1. */
    val rowIndex: Int,
    /** 소리 시작부터 몇 ms 인가. */
    val atMs: Long,
    /** 그 순간의 현재값(dB). 풀 수 없거나 빈 행이면 null. */
    val currentDb: Double?,
    /** 그 행의 최대(dB). */
    val maxDb: Double?,
    /** 그 행의 순간최고(dB). */
    val peakDb: Double?,
    /** 31밴드(dB). 빈 행이면 null. */
    val bands: DoubleArray?,
    /** 그 자리의 소리가 찌그러졌는가. **그 구간의 값은 전부 하한이다.** */
    val clipped: Boolean,
    /** 그 구간이 미보정이었는가. */
    val referenceOnly: Boolean,
) {
    // DoubleArray 를 들고 있으므로 equals/hashCode 를 손으로 적는다.
    // 기본 구현은 배열의 **주소**를 견주어, 값이 같아도 다르다고 한다.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlaybackValues) return false
        return rowIndex == other.rowIndex &&
            atMs == other.atMs &&
            currentDb == other.currentDb &&
            maxDb == other.maxDb &&
            peakDb == other.peakDb &&
            clipped == other.clipped &&
            referenceOnly == other.referenceOnly &&
            (bands?.contentEquals(other.bands) ?: (other.bands == null))
    }

    override fun hashCode(): Int {
        var h = rowIndex
        h = 31 * h + atMs.hashCode()
        h = 31 * h + (currentDb?.hashCode() ?: 0)
        h = 31 * h + (bands?.contentHashCode() ?: 0)
        return h
    }
}

/**
 * 그 시각에 걸린 행의 값.
 *
 * @param atMs 소리 시작부터의 시각. 음수면 첫 행으로 본다.
 * @return 그 자리에 행이 없으면 null — **짐작해서 옆 행을 주지 않는다.**
 */
fun playbackValuesAt(
    meta: SessionMeta,
    rows: List<TimelineRow>,
    atMs: Long,
): PlaybackValues? {
    if (rows.isEmpty()) return null
    val index = (atMs.coerceAtLeast(0L) / TimelineFormat.ROW_MILLIS).toInt()
    // **행 번호로 찾지 않고 자리로 찾는다.** 놓친 구간이 있으면 행
    // 번호와 자리가 어긋난다.
    val row = rows.getOrNull(index) ?: return null
    val offset = offsetFor(meta, row.currentEpoch)
    val rowMs = row.rowIndex.toLong() * TimelineFormat.ROW_MILLIS

    if (row.missing || offset == null) {
        // 놓친 자리다. **0 으로 채우지 않는다** — 「아주 조용했다」가 된다.
        return PlaybackValues(
            rowIndex = row.rowIndex,
            atMs = rowMs,
            currentDb = null,
            maxDb = null,
            peakDb = null,
            bands = null,
            clipped = row.clipped,
            referenceOnly = meta.referenceOnly,
        )
    }
    return PlaybackValues(
        rowIndex = row.rowIndex,
        atMs = rowMs,
        currentDb = row.currentRaw + offset,
        maxDb = row.maxRaw + (offsetFor(meta, row.maxEpoch) ?: offset),
        peakDb = row.peakRaw + (offsetFor(meta, row.peakEpoch) ?: offset),
        bands = DoubleArray(row.bands.size) { row.bands[it] + offset },
        clipped = row.clipped,
        referenceOnly = referenceOnlyFor(meta, row.currentEpoch),
    )
}

/**
 * 그 epoch 의 보정값. 표가 없으면 겉장의 한 값.
 *
 * **표에 없는 epoch 은 null 이다.** 0번 것을 대신 걸면 그럴듯하게
 * 틀린 값이 화면에 뜬다.
 */
private fun offsetFor(meta: SessionMeta, epochId: Int): Double? {
    if (meta.epochs.isEmpty()) return meta.calibrationOffsetDb
    if (epochId == TimelineFormat.EPOCH_NONE) return null
    return meta.epochs.firstOrNull { it.id == epochId }?.calibrationOffsetDb
}

private fun referenceOnlyFor(meta: SessionMeta, epochId: Int): Boolean {
    if (meta.epochs.isEmpty()) return meta.referenceOnly
    return meta.epochs.firstOrNull { it.id == epochId }?.isReferenceOnly ?: meta.referenceOnly
}
