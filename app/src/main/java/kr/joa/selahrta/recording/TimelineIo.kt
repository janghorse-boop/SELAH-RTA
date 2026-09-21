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
 *
 * **판 1 은 완성형이 아니다.** 녹음 설계가 요구한 Leq·시간가중·곡선 해시
 * 같은 메타데이터가 아직 행과 epoch 에 없다(독립 검증 RA-3번 답). 필드를
 * 더할 때는 판을 올리고 호환 처리를 함께 만든다.
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

    /** 「이 값에는 epoch 이 없다」. **`missing` 행 전용이다.** */
    const val EPOCH_NONE = -1

    /**
     * 판 1 의 행 간격(ms). **고정이다.**
     *
     * 머리에 적기는 하지만 읽는 쪽이 검사하지 않으면, 500 이 아닌 파일을
     * 500 으로 가정한 화면이 **조용히 틀린 시간축**을 그린다(독립 검증
     * RA04). 간격을 바꾸려면 판을 올린다.
     */
    const val ROW_MILLIS = 500

    /** 받아들일 샘플레이트. 위끝은 Android 가 다루는 범위를 넉넉히 덮는다. */
    val SAMPLE_RATE_RANGE = 1_000..384_000
}

/** 파일 머리에 적는 것. */
data class TimelineHeader(
    val nominalSampleRate: Int,
    val rowMillis: Int,
    val version: Int = TimelineFormat.VERSION,
)

/**
 * 행을 이어 쓴다. **더해 쓰기만 한다** — 고쳐 쓰지 않는다.
 *
 * **[flush] 는 복구를 보장하지 않는다**(독립 검증 RA-통합). 끊겼을 때
 * 꼬리 행만 잃는다고 말할 수 없다 — 머리나 앞선 행이 아직 디스크에
 * 닿지 않았을 수 있다. 체크포인트 단계 전에는 「복구됨」이라고 적지 않는다.
 */
class TimelineWriter(private val out: OutputStream, header: TimelineHeader) {

    private val buf = ByteBuffer.allocate(TimelineFormat.ROW_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    init {
        // **말이 안 되는 머리를 쓰지 않는다**(독립 검증 RA04). 예전에는
        // `TimelineHeader(0, 0)` 으로 만든 파일을 읽는 쪽이 그대로 열었다.
        require(header.version == TimelineFormat.VERSION) {
            "이 writer 는 판 ${TimelineFormat.VERSION} 만 쓴다: ${header.version}"
        }
        require(header.nominalSampleRate in TimelineFormat.SAMPLE_RATE_RANGE) {
            "샘플레이트가 범위를 벗어난다: ${header.nominalSampleRate}"
        }
        require(header.rowMillis == TimelineFormat.ROW_MILLIS) {
            "판 ${TimelineFormat.VERSION} 의 행 간격은 ${TimelineFormat.ROW_MILLIS}ms 고정이다: ${header.rowMillis}"
        }
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
        require(row.rowIndex >= 0) { "행 번호가 음수다: ${row.rowIndex}" }
        // **id 를 잘라서 쓰지 않는다**(독립 검증 RA03). 예전에는 65536 이
        // `.toShort()` 로 0 이 되어, 재생이 다른 epoch 의 보정을 걸었다 —
        // 90dB 이어야 할 값이 70dB 로 나왔다.
        checkEpoch(row.currentEpoch, row.missing, "current")
        checkEpoch(row.maxEpoch, row.missing, "max")
        checkEpoch(row.peakEpoch, row.missing, "peak")
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

    private fun checkEpoch(id: Int, missing: Boolean, what: String) {
        if (missing) {
            require(id == TimelineFormat.EPOCH_NONE) {
                "측정이 없는 행의 $what epoch 은 ${TimelineFormat.EPOCH_NONE} 이어야 한다: $id"
            }
        } else {
            require(id in EpochTable.ID_RANGE) {
                "$what epoch id 가 범위를 벗어난다: $id (허용 ${EpochTable.ID_RANGE})"
            }
        }
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
        // **시간축도 검사한다**(독립 검증 RA04). 여기를 비워 두면 fs=0 이나
        // rowMillis=0 인 파일이 정상으로 열린다.
        require(fs in TimelineFormat.SAMPLE_RATE_RANGE) { "샘플레이트가 범위를 벗어난다: $fs" }
        require(rowMs == TimelineFormat.ROW_MILLIS) { "행 간격이 다르다: $rowMs" }
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

    /** 그 행의 **보정을 건** 최대. */
    fun calibratedMax(row: TimelineRow): RowValue = value(row, row.maxRaw, row.maxEpoch)

    fun calibratedPeak(row: TimelineRow): RowValue = value(row, row.peakRaw, row.peakEpoch)

    fun calibratedCurrent(row: TimelineRow): RowValue = value(row, row.currentRaw, row.currentEpoch)

    private fun value(row: TimelineRow, raw: Double, epochId: Int): RowValue {
        if (row.missing) return RowValue.Missing
        // **없는 epoch 을 짐작하지 않는다**(독립 검증 RA03). 다른 writer 가
        // 잘린 id 를 남겼을 수 있고, 그때 0 번 epoch 의 보정을 걸면
        // 그럴듯하게 틀린 음압이 나온다.
        if (epochId == TimelineFormat.EPOCH_NONE) {
            return RowValue.Unresolvable(epochId, "측정이 있는 행인데 epoch 이 없다")
        }
        val e = epochs.find(epochId)
            ?: return RowValue.Unresolvable(epochId, "표에 없는 epoch 이다")
        return RowValue.Calibrated(raw + e.calibrationOffsetDb, e.isReferenceOnly, epochId)
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
 * 행에서 읽어 낸 값 하나.
 *
 * **세 가지가 서로 다른 일이다** — 잰 값이 있는 것, 측정이 없던 구간,
 * 그리고 적힌 epoch 을 되살릴 수 없는 것. 앞의 둘만 구별하면 세 번째가
 * 조용히 「그럴듯한 숫자」로 섞인다(독립 검증 RA03).
 */
sealed interface RowValue {

    /** 측정이 없던 행. 잰 무음이 아니다. */
    object Missing : RowValue

    /**
     * 적힌 epoch 을 되살릴 수 없다. **값을 짐작하지 않는다.**
     *
     * 화면은 숫자 대신 이 사실을 적는다.
     */
    data class Unresolvable(val epochId: Int, val reason: String) : RowValue

    /**
     * 보정을 건 값.
     *
     * **[isReferenceOnly] 를 함께 준다** — 미보정 구간의 숫자를 음압이라
     * 부르지 않게 하려는 것이다.
     */
    data class Calibrated(
        val db: Double,
        val isReferenceOnly: Boolean,
        val epochId: Int,
    ) : RowValue
}

/** 잰 값이면 그 dB, 아니면 null. 시험과 화면이 짧게 쓰려고 둔다. */
val RowValue.dbOrNull: Double? get() = (this as? RowValue.Calibrated)?.db
