package kr.joa.selahrta.recording

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 타임라인 파일의 머리와 행 형식.
 *
 * **고정 길이 레코드**다 — 행 번호에서 자리를 바로 셀 수 있어야 seek 가
 * 나눗셈 한 번으로 끝난다(녹음 설계 2차 ②).
 *
 * **버전 바이트가 호환을 만들어 주지는 않는다**(독립 검증 S03). 모르는
 * 판이면 **열지 않고 그렇게 말한다.**
 */
object TimelineFormat {
    /** `SRTL` — SELAH RTa timeLine. */
    const val MAGIC = 0x5352544C
    const val VERSION = 1
    const val BAND_COUNT = 31

    /**
     * 한 행의 바이트 수.
     *
     * `rowIndex(4) flags(2) currentEpoch(2) maxEpoch(2) peakEpoch(2)`
     * `+ current/max/peak (4×3) + bands(4×31)` = **148**
     */
    const val ROW_BYTES = 4 + 2 + 2 + 2 + 2 + 12 + 4 * BAND_COUNT

    const val HEADER_BYTES = 20

    const val FLAG_MISSING = 1 shl 0
    const val FLAG_CLIPPED = 1 shl 1
}

/** 파일 머리에 적는 것. */
data class TimelineHeader(
    val nominalSampleRate: Int,
    val rowMillis: Int,
    val version: Int = TimelineFormat.VERSION,
)

/**
 * 행을 이어 쓴다. **더해 쓰기만 한다** — 고쳐 쓰지 않는다.
 */
class TimelineWriter(private val out: OutputStream, header: TimelineHeader) {

    private val buf = ByteBuffer.allocate(TimelineFormat.ROW_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    init {
        val h = ByteBuffer.allocate(TimelineFormat.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(TimelineFormat.MAGIC)
        h.putShort(header.version.toShort())
        h.putShort(TimelineFormat.ROW_BYTES.toShort())
        // 0 = little endian. 읽는 쪽이 제 짐작으로 풀지 않게 적어 둔다.
        h.put(0)
        h.put(0); h.put(0); h.put(0)
        h.putInt(header.nominalSampleRate)
        h.putShort(header.rowMillis.toShort())
        h.putShort(TimelineFormat.BAND_COUNT.toShort())
        out.write(h.array())
    }

    fun write(row: TimelineRow) {
        require(row.bands.size == TimelineFormat.BAND_COUNT) { "밴드 수가 다르다" }
        buf.clear()
        buf.putInt(row.rowIndex)
        var flags = 0
        if (row.missing) flags = flags or TimelineFormat.FLAG_MISSING
        if (row.clipped) flags = flags or TimelineFormat.FLAG_CLIPPED
        buf.putShort(flags.toShort())
        buf.putShort(row.currentEpoch.toShort())
        buf.putShort(row.maxEpoch.toShort())
        buf.putShort(row.peakEpoch.toShort())
        buf.putFloat(row.currentRaw.toFloat())
        buf.putFloat(row.maxRaw.toFloat())
        buf.putFloat(row.peakRaw.toFloat())
        for (b in row.bands) buf.putFloat(b)
        out.write(buf.array())
    }

    fun flush() = out.flush()
}

/**
 * 읽어서 **보정을 건 값**까지 돌려준다.
 *
 * 행에는 보정 **전** 값이 들어 있다. 여기가 보정을 거는 자리이고,
 * 극값은 **저장된 제 epoch** 로 건다 — 그래야 한 행에 두 epoch 이 섞여도
 * 맞는다(독립 검증 M32).
 */
class TimelineReader(private val input: InputStream, private val epochs: EpochTable) {

    val header: TimelineHeader

    init {
        val h = ByteArray(TimelineFormat.HEADER_BYTES)
        readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val magic = b.int
        require(magic == TimelineFormat.MAGIC) { "타임라인 파일이 아니다" }
        val version = b.short.toInt()
        val rowBytes = b.short.toInt()
        val order = b.get().toInt()
        b.get(); b.get(); b.get()
        val fs = b.int
        val rowMs = b.short.toInt()
        val bands = b.short.toInt()

        // **모르는 판이면 열지 않는다.** 짐작으로 풀면 조용히 틀린 값을 준다.
        require(version == TimelineFormat.VERSION) { "모르는 판: $version" }
        require(rowBytes == TimelineFormat.ROW_BYTES) { "행 길이가 다르다: $rowBytes" }
        require(order == 0) { "바이트 순서가 다르다" }
        require(bands == TimelineFormat.BAND_COUNT) { "밴드 수가 다르다: $bands" }
        header = TimelineHeader(fs, rowMs, version)
    }

    /** 다음 행. 끝이면 null. **끊긴 꼬리는 버린다.** */
    fun next(): TimelineRow? {
        val raw = ByteArray(TimelineFormat.ROW_BYTES)
        var n = 0
        while (n < raw.size) {
            val r = input.read(raw, n, raw.size - n)
            if (r < 0) {
                // 온전한 행만 쓴다. 반 토막은 없는 것으로 본다.
                return null
            }
            n += r
        }
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val index = b.int
        val flags = b.short.toInt()
        val curEpoch = b.short.toInt()
        val maxEpoch = b.short.toInt()
        val peakEpoch = b.short.toInt()
        val cur = b.float.toDouble()
        val max = b.float.toDouble()
        val peak = b.float.toDouble()
        val bands = FloatArray(TimelineFormat.BAND_COUNT) { b.float }
        return TimelineRow(
            rowIndex = index,
            currentRaw = cur, currentEpoch = curEpoch,
            maxRaw = max, maxEpoch = maxEpoch,
            peakRaw = peak, peakEpoch = peakEpoch,
            clipped = flags and TimelineFormat.FLAG_CLIPPED != 0,
            missing = flags and TimelineFormat.FLAG_MISSING != 0,
            bands = bands,
        )
    }

    fun all(): List<TimelineRow> {
        val out = ArrayList<TimelineRow>()
        while (true) out.add(next() ?: break)
        return out
    }

    /** 그 행의 **보정을 건** 최대. 미보정 구간이면 그 사실도 함께 준다. */
    fun calibratedMax(row: TimelineRow): CalibratedValue? =
        if (row.missing) null else value(row.maxRaw, row.maxEpoch)

    fun calibratedPeak(row: TimelineRow): CalibratedValue? =
        if (row.missing) null else value(row.peakRaw, row.peakEpoch)

    fun calibratedCurrent(row: TimelineRow): CalibratedValue? =
        if (row.missing) null else value(row.currentRaw, row.currentEpoch)

    private fun value(raw: Double, epochId: Int): CalibratedValue {
        val e = epochs[epochId]
        return CalibratedValue(raw + e.calibrationOffsetDb, e.isReferenceOnly, epochId)
    }

    private fun readFully(into: ByteArray) {
        var n = 0
        while (n < into.size) {
            val r = input.read(into, n, into.size - n)
            if (r < 0) throw EOFException("머리가 짧다")
            n += r
        }
    }
}

/**
 * 보정을 건 값 하나.
 *
 * **[isReferenceOnly] 를 함께 준다** — 미보정 구간의 숫자를 음압이라
 * 부르지 않게 하려는 것이다.
 */
data class CalibratedValue(
    val db: Double,
    val isReferenceOnly: Boolean,
    val epochId: Int,
)
