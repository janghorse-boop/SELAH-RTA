package kr.joa.selahrta.recording

import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.dsp.MultiWeightEngine
import kr.joa.selahrta.dsp.RtaEngine
import kr.joa.selahrta.dsp.ThirdOctave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 측정을 행으로 남기는 자리(Phase 10).
 *
 * **여기서 지키는 것은 시간이다.** 행 하나는 500ms 이고, 그 행의 값은
 * **그 행의 소리만** 담아야 한다. 어긋나면 나중에 「그때 88dB 였다」를
 * 되돌릴 방법이 없다.
 */
class SessionRecorderTest {

    private val fs = 48_000
    private val blockFrames = 2_880 // 60ms — 실제 캡처와 같은 크기

    private fun recorder(offsetDb: Double = 120.0) = SessionRecorder(
        id = "s1",
        nominalSampleRate = fs,
        startOffsetDb = offsetDb,
        startReferenceOnly = true,
        startLeqWindowMs = 60_000,
    )

    private fun engines() =
        MultiWeightEngine(fs, kr.joa.selahrta.dsp.TimeWeight.Fast) to RtaEngine(fs)

    /** 60ms 덩어리 한 개 분량의 소리. */
    private fun block(level: Double, at: Int): FloatArray {
        return FloatArray(blockFrames) {
            val t = (at * blockFrames + it).toDouble() / fs
            (level * sin(2 * PI * 1000.0 * t)).toFloat()
        }
    }

    private fun feed(r: SessionRecorder, blocks: Int, level: Double = 0.2) {
        val (spl, rta) = engines()
        repeat(blocks) { i -> r.onBlock(block(level, i), blockFrames, false, spl, rta) }
    }

    @Test
    fun `행이 500ms 마다 하나씩 생긴다`() {
        val r = recorder()
        // 2초 = 4행. 60ms 덩어리 34개면 2.04초다.
        feed(r, 34)
        val s = r.finish()
        assertEquals("행 번호가 0 부터 이어진다", s.rows.indices.toList(), s.rows.map { it.rowIndex })
        assertEquals("2.04초 → 4행(마지막 행은 열린 채 닫힌다)", 5, s.rows.size)
        assertEquals("길이", 2_040L, s.durationMs)
    }

    /**
     * **행 경계를 걸친 덩어리가 두 행에 나뉘어 들어간다.**
     *
     * 이것이 쪼개는 까닭이다. 안 쪼개면 덩어리 값 하나가 한 행에
     * 통째로 들어가, 최대 60ms 가 옆 행에 섞인다.
     */
    @Test
    fun `경계를 걸친 덩어리도 잃지 않는다`() {
        val r = recorder()
        val (spl, rta) = engines()
        // 24,000 프레임(한 행)을 60ms 로 나누면 8.33개다 — 아홉째가 걸친다.
        repeat(9) { i -> r.onBlock(block(0.2, i), blockFrames, false, spl, rta) }
        val s = r.finish()
        assertEquals("두 행이 나와야 한다", 2, s.rows.size)
        assertFalse("첫 행이 비었다", s.rows[0].missing)
        assertFalse("둘째 행이 비었다", s.rows[1].missing)
    }

    @Test
    fun `읽기 오류는 행을 채우지 않고 세어 둔다`() {
        val r = recorder()
        val (spl, rta) = engines()
        assertNull("버린 덩어리에서 값이 나왔다", r.onBlock(FloatArray(0), 0, false, spl, rta))
        repeat(9) { i -> r.onBlock(block(0.2, i), blockFrames, false, spl, rta) }
        val s = r.finish()
        assertEquals(1, s.droppedPackets)
        assertTrue("행은 그대로 쌓여야 한다", s.rows.isNotEmpty())
    }

    @Test
    fun `보정이 바뀌면 새 구간이 열린다`() {
        val r = recorder(offsetDb = 120.0)
        feed(r, 9)
        assertTrue(r.noteEpoch(100.0, referenceOnly = false, leqWindowMs = 60_000))
        feed(r, 9)
        val s = r.finish()
        assertEquals("구간이 둘이어야 한다", 2, s.epochs.size)
        assertEquals(100.0, s.epochs[1].calibrationOffsetDb, 0.0)
        assertTrue("사건으로도 남아야 한다", s.events.any { it.kind == SessionEventKind.CalibrationChange })
    }

    /** 같은 값으로 다시 부르면 구간을 늘리지 않는다. */
    @Test
    fun `바뀌지 않으면 구간을 늘리지 않는다`() {
        val r = recorder(offsetDb = 120.0)
        feed(r, 9)
        repeat(5) { r.noteEpoch(120.0, referenceOnly = true, leqWindowMs = 60_000) }
        assertEquals(1, r.finish().epochs.size)
    }

    @Test
    fun `구간 바뀜이 시각과 함께 남는다`() {
        val r = recorder()
        feed(r, 9)
        r.note(SessionEventKind.SegmentChange, "찬양", ChurchSegment.Worship)
        val s = r.finish()
        val e = s.events.first { it.kind == SessionEventKind.SegmentChange }
        assertEquals(ChurchSegment.Worship, e.segment)
        assertEquals("9덩어리 = 540ms", 540L, e.atMs)
    }

    @Test
    fun `밴드가 31개로 들어간다`() {
        val r = recorder()
        feed(r, 20)
        val s = r.finish()
        assertTrue(s.rows.all { it.bands.size == ThirdOctave.BAND_COUNT })
    }

    /**
     * **첫 FFT 가 차기 전에도 행이 나온다.**
     *
     * 4096점이 차려면 85ms 가 걸리는데 행은 500ms 다. 그 사이의 행을
     * 비워 두면 기록이 늘 앞이 잘린다 — 조용한 값으로 채운다.
     */
    @Test
    fun `첫 FFT 전에도 행이 나온다`() {
        val r = recorder()
        val (spl, rta) = engines()
        r.onBlock(block(0.2, 0), blockFrames, false, spl, rta)
        val s = r.finish()
        assertEquals(1, s.rows.size)
        assertEquals(ThirdOctave.BAND_COUNT, s.rows[0].bands.size)
    }

    /**
     * **끝낸 뒤의 부름은 조용히 지나간다.**
     *
     * 늦은 콜백은 멈추는 과정의 정상이다 — `close()` 의 join 은 시간
     * 제한이 있어(500ms) 옛 캡처 스레드가 살아남을 수 있다(F02). 거기서
     * 예외를 던지면 오디오 스레드가 죽는다.
     */
    @Test
    fun `끝낸 뒤의 늦은 덩어리는 조용히 지나간다`() {
        val r = recorder()
        feed(r, 5)
        val first = r.finish()
        val (spl, rta) = engines()

        // 예외를 던지지 않는다.
        assertNull(r.onBlock(block(0.2, 0), blockFrames, false, spl, rta))
        r.note(SessionEventKind.Dropout, "늦은 사건")
        r.noteEpoch(50.0, referenceOnly = false, leqWindowMs = 10_000)

        // 그리고 **결과를 바꾸지 않는다.**
        val again = r.finish()
        assertEquals("행이 늘었다", first.rows.size, again.rows.size)
        assertEquals("사건이 늘었다", first.events.size, again.events.size)
        assertEquals("구간이 늘었다", first.epochs.size, again.epochs.size)
    }

    /** 멈추는 길이 둘이라 두 번 불릴 수 있다. 두 번째에 죽으면 안 된다. */
    @Test
    fun `두 번 끝내도 같은 것을 돌려준다`() {
        val r = recorder()
        feed(r, 9)
        val a = r.finish()
        val b = r.finish()
        assertEquals(a.rows.size, b.rows.size)
        assertEquals(a.durationMs, b.durationMs)
    }

    /**
     * **행의 최대는 그 행의 것이다 — 올라가기만 하는 계단이 아니다.**
     *
     * 처음에는 엔진의 누적 최대(`maxDbfs`)를 행에 적었다. 그러면 행마다
     * 값이 같거나 커지기만 해서 **「언제 컸는지」가 사라진다.** 코드를
     * 읽어서는 안 보였고, 기기에서 파일을 뽑아 디코드하고서야 드러났다
     * (79.0 → 79.0 → 79.0 → … → 83.9).
     */
    @Test
    fun `행의 최대는 그 행의 것이다`() {
        val r = recorder()
        val (spl, rta) = engines()
        // 첫 1초는 크게, 그 뒤 2초는 아주 작게.
        repeat(17) { i -> r.onBlock(block(0.5, i), blockFrames, false, spl, rta) }
        repeat(34) { i -> r.onBlock(block(0.005, 17 + i), blockFrames, false, spl, rta) }
        val rows = r.finish().rows

        assertTrue("행이 모자란다", rows.size >= 5)
        val loud = rows[0].maxRaw
        val quiet = rows.last().maxRaw
        assertTrue(
            "조용해졌는데 행의 최대가 안 내려간다(누적값을 적고 있다): $loud → $quiet",
            quiet < loud - 20.0,
        )
    }

    /**
     * **요약은 기록 구간만의 것이다.**
     *
     * 기록은 측정 도중에 시작할 수 있다. 겉장의 MAX 를 화면 계기에서
     * 가져왔더니 **기록하지 않은 구간의 소리**가 「이 기록의 최대」로
     * 적혔다.
     */
    @Test
    fun `기록 전의 소리는 요약에 섞이지 않는다`() {
        val (spl, rta) = engines()
        // 기록하기 **전에** 큰 소리로 측정이 돌고 있었다. 그리고 조용해진
        // 뒤에야 기록을 시작한다 — 시간가중이 내려앉을 틈을 준다. 그래야
        // 「감쇠 꼬리」가 아니라 **누적을 적었는가**만 걸린다.
        repeat(34) { i -> spl.process(block(0.5, i), blockFrames) }
        val loudMax = spl.process(block(0.005, 34), blockFrames).a.maxDbfs.value
        repeat(17) { i -> spl.process(block(0.005, 35 + i), blockFrames) }

        val r = recorder()
        repeat(34) { i -> r.onBlock(block(0.005, 52 + i), blockFrames, false, spl, rta) }
        val s = r.finish().summary

        val max = requireNotNull(s.maxDb) { "요약에 최대가 없다" }
        assertTrue(
            "기록 전의 큰 소리가 요약에 섞였다: 요약 $max · 엔진 누적 ${loudMax + 120.0}",
            max < loudMax + 120.0 - 20.0,
        )
        val leq = requireNotNull(s.leqDb) { "요약에 Leq 가 없다" }
        assertTrue("Leq 에도 섞였다: $leq", leq < loudMax + 120.0 - 20.0)
    }

    /** 구간이 둘 이상이면 Leq 를 음압으로 바꾸지 않는다. */
    @Test
    fun `보정이 바뀌면 Leq 를 음압으로 적지 않는다`() {
        val r = recorder(offsetDb = 120.0)
        feed(r, 9)
        r.noteEpoch(100.0, referenceOnly = false, leqWindowMs = 60_000)
        feed(r, 9)
        assertNull("구간이 둘인데 Leq 를 하나로 적었다", r.finish().summary.leqDb)
    }

    /**
     * **결과가 제 신원을 지니고 다닌다.**
     *
     * 쓰는 쪽이 화면 상태에서 신원을 읽게 했더니 파일이 한 줄도 쓰이지
     * 않았다(기기에서 확인). 기록을 멈추면 화면은 곧바로 「기록 아님」이
     * 되는데 쓰기 콜백은 캡처 스레드를 거쳐 **그 뒤에** 오기 때문이다.
     */
    @Test
    fun `결과가 제 신원을 지니고 다닌다`() {
        val r = recorder()
        feed(r, 9)
        assertEquals("s1", r.finish().id)
    }

    /** 잰 길이는 **프레임으로 센다.** 벽시계가 뒤로 가도 흔들리지 않는다. */
    @Test
    fun `길이를 프레임으로 센다`() {
        val r = recorder()
        assertEquals(0L, r.elapsedMs)
        feed(r, 10)
        assertEquals(600L, r.elapsedMs)
        assertNotNull(r.finish())
    }
}
