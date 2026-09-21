package kr.joa.selahrta.recording

import kr.joa.selahrta.dsp.BandAnalyzer
import kr.joa.selahrta.dsp.ThirdOctave
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow

/**
 * **검증자 probe 그대로**(`docs/review/RowEqIndependentProbe.kt`).
 *
 * 검증자가 보낸 `fun main()` 의 본문을 손대지 않고 옮겼다. 바꾼 것은
 * JUnit 으로 돌리기 위한 껍데기(클래스·`@Test`)뿐이다. 고치기 **전에**
 * 결함이 이 코드로 재현되는 것을 먼저 확인하려는 것이다.
 *
 * 검증자가 받은 출력(`docs/review/row-eq-independent-jvm.txt`):
 *
 * ```
 * EPOCH_CROSS accepted=true calibrated=CalibratedValue(db=80.0, isReferenceOnly=false, epochId=0)
 * REVERSE current=-20.0 epoch=0
 * FINISH first=1 second=2
 * PARTIAL_GAP missing=false
 * ID_TRUNCATE expected=90 actual=CalibratedValue(db=70.0, isReferenceOnly=false, epochId=0)
 * BAD_HEADER accepted=true
 * BANDS fs=48000 differences=[125.0]
 * BANDS fs=44100 differences=[125.0]
 * DENSITY exactCenter=true difference=4.884981308350689E-15
 * DENSITY exactCenter=false difference=-0.017240061162444587
 * ```
 *
 * **고친 뒤에는 앞의 여섯 줄 중 다섯이 예외로 바뀐다.** 그래서 이 시험은
 * 고치기 전 상태를 재현하는 용도로만 두고, 고친 뒤의 계약은
 * [RowAggregatorGuardTest] 가 단언한다.
 */
class ReviewerProbeTest {

    @Test
    fun `검증자 probe`() {
        fun table() = EpochTable().apply {
            add(RecordingEpoch(0, 0, 100.0, false, 60000)); add(RecordingEpoch(1, 12000, 120.0, false, 60000))
        }
        fun s(v: Double) = RowSample(v, v, v, false, FloatArray(31))
        fun trip(a: RowAggregator, t: EpochTable): Pair<TimelineReader, List<TimelineRow>> {
            val b = ByteArrayOutputStream(); val w = TimelineWriter(b, TimelineHeader(48000, 500))
            a.finish().forEach { w.write(it) }
            val r = TimelineReader(ByteArrayInputStream(b.toByteArray()), t); return r to r.all()
        }
        val t = table(); val a = RowAggregator(48000, t)
        val cross = runCatching { a.add(0, 24000, 0, s(-20.0)) }.exceptionOrNull()
        println("EPOCH_CROSS accepted=${cross == null} calibrated=${runCatching { trip(a, t).let { it.first.calibratedMax(it.second[0]) } }.getOrElse { it }}")
        val b = RowAggregator(48000, t)
        b.add(12000, 12000, 1, s(-30.0))
        println("REVERSE current=${runCatching { b.add(0, 12000, 0, s(-20.0)); b.finish().single().currentRaw }.getOrElse { it }} epoch=${b.finish().last().currentEpoch}")
        val c = RowAggregator(48000, t); c.add(0, 100, 0, s(-20.0))
        println("FINISH first=${c.finish().size} second=${c.finish().size}")
        val d = RowAggregator(48000, t); d.add(0, 100, 0, s(-20.0)); d.add(1000, 100, 0, s(-30.0))
        println("PARTIAL_GAP missing=${d.finish().single().missing}")
        val ids = runCatching {
            EpochTable().apply {
                add(RecordingEpoch(0, 0, 100.0, false, 60000)); add(RecordingEpoch(65536, 12000, 120.0, false, 60000))
            }
        }
        println("ID_TRUNCATE expected=90 actual=${ids.fold({ table ->
            val idAgg = RowAggregator(48000, table); idAgg.add(12000, 12000, 65536, s(-30.0))
            val result = trip(idAgg, table); result.first.calibratedMax(result.second.single())
        }, { it })}")
        val bad = ByteArrayOutputStream()
        println("BAD_HEADER accepted=${runCatching { TimelineWriter(bad, TimelineHeader(0, 0)); TimelineReader(ByteArrayInputStream(bad.toByteArray()), t) }.isSuccess}")
        for (fs in listOf(48000, 44100)) {
            val ba = BandAnalyzer(4096, fs)
            val changes = (0 until 31).filter {
                ba.bandResolved[it] != ((ThirdOctave.upperEdge(it) - ThirdOctave.lowerEdge(it) >= 3.0 * fs / 4096) && (ThirdOctave.upperEdge(it) <= fs / 2.0))
            }
            println("BANDS fs=$fs differences=${changes.map { ThirdOctave.CENTERS_HZ[it] }}")
        }
        val target = listOf(12, 13, 14); val refs = listOf(10, 11, 15, 16)
        fun prominence(exact: Boolean, density: Boolean): Double {
            fun x(i: Int) = log2(if (exact) ThirdOctave.exactCenter(i) else ThirdOctave.CENTERS_HZ[i])
            fun q(i: Int): Double {
                val bw = ThirdOctave.upperEdge(i) - ThirdOctave.lowerEdge(i); val power = 1.0 + bw * 0.01
                return 10 * log10(power / (if (density) bw else 1.0))
            }
            val mx = refs.map { x(it) }.average(); val my = refs.map { q(it) }.average()
            val slope = refs.sumOf { (x(it) - mx) * (q(it) - my) } / refs.sumOf { (x(it) - mx).pow(2) }
            return target.map { q(it) - (my + slope * (x(it) - mx)) }.sorted()[1]
        }
        for (exact in listOf(true, false)) println("DENSITY exactCenter=$exact difference=${prominence(exact, true) - prominence(exact, false)}")
    }
}
