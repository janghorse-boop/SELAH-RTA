package kr.joa.selahrta.audio

import android.media.AudioRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 캡처 루프의 **순서**를 시험한다(독립 재검증 F02·L01).
 *
 * 여기서 틀리는 것은 전부 「누가 먼저 오느냐」의 문제라 실제 기기로는
 * 재현하기 어렵다. 오히려 가짜 레코더로 순서를 강제하는 쪽이 정확하다.
 */
class CaptureLoopTest {

    private fun loop(
        rec: FakeRecorder,
        running: AtomicBoolean,
        cb: RecordingCallbacks,
        confirmed: Boolean = true,
    ) = runCaptureLoop(
        recorder = rec,
        sampleRate = 48_000,
        running = running,
        callbacks = cb,
        routeAlreadyConfirmed = { confirmed },
        nowNs = { 0L },
        framesPerBlock = 64,
    )

    @Test
    fun `멈추라고 하면 더 읽지 않는다`() {
        val running = AtomicBoolean(false)
        val rec = FakeRecorder(listOf(64))
        val cb = RecordingCallbacks()

        loop(rec, running, cb)

        assertEquals("멈춘 상태로 들어오면 한 번도 안 읽는다", 0, rec.reads.get())
        assertTrue(cb.blocks.isEmpty())
    }

    /**
     * F02 — **읽는 동안 멈추면 그 덩어리를 흘리지 않는다.**
     *
     * `read()` 는 데이터가 찰 때까지 기다린다. 그 사이에 누가 멈췄는데도
     * 덩어리를 내보내면, 그 덩어리는 **다음 측정의 엔진**으로 들어간다.
     * 예전에는 읽고 나서 `running` 을 다시 보지 않아 그럴 수 있었다.
     */
    @Test
    fun `읽는 동안 멈추면 그 덩어리를 내보내지 않는다`() {
        val running = AtomicBoolean(true)
        val rec = FakeRecorder(listOf(64, 64, 64))
        val cb = RecordingCallbacks()
        rec.gate = CountDownLatch(1)

        val t = Thread { loop(rec, running, cb) }
        t.start()

        // 읽기가 문 앞에 닿을 때까지 기다린다.
        assertTrue("읽기가 시작돼야 한다", rec.reachedGate.await(5, TimeUnit.SECONDS))

        // 읽기가 **기다리는 동안** 멈춘다. 실제로는 stop() 이 이 자리에 온다.
        running.set(false)
        rec.gate!!.countDown()
        t.join(5_000)

        assertFalse("루프가 끝나야 한다", t.isAlive)
        assertTrue(
            "멈춘 뒤 돌아온 덩어리는 버려야 한다 (내보낸 덩어리 ${cb.blocks})",
            cb.blocks.isEmpty(),
        )
        assertTrue("사람이 멈춘 것이므로 오류로 알리지 않는다", cb.ended.isEmpty())
    }

    /**
     * L01 — 읽기 오류로 끝나면 **반드시 알리고** 루프를 끝낸다.
     *
     * 예전에는 break 만 하고 아무에게도 알리지 않아 화면이 「측정 중」인
     * 채로 마지막 숫자를 붙들고 있었다.
     */
    @Test
    fun `읽기 오류는 한 번 알리고 끝난다`() {
        val running = AtomicBoolean(true)
        val rec = FakeRecorder(listOf(64, AudioRecord.ERROR_DEAD_OBJECT))
        val cb = RecordingCallbacks()

        loop(rec, running, cb)

        assertEquals("오류는 한 번만 알린다", 1, cb.ended.size)
        assertEquals("죽은 객체는 기기 분리로 옮긴다", CaptureEnd.DeviceLost, cb.ended[0])
        assertFalse("running 을 내려야 한다", running.get())
        // 성한 덩어리 하나 + 버린 덩어리 하나.
        assertEquals(listOf(64, 0), cb.blocks)
    }

    @Test
    fun `오류 코드마다 다른 까닭으로 옮긴다`() {
        for ((code, expected) in listOf(
            AudioRecord.ERROR_INVALID_OPERATION to CaptureEnd.Preempted,
            AudioRecord.ERROR_DEAD_OBJECT to CaptureEnd.DeviceLost,
            AudioRecord.ERROR_BAD_VALUE to CaptureEnd.ReadError,
        )) {
            val cb = RecordingCallbacks()
            loop(FakeRecorder(listOf(code)), AtomicBoolean(true), cb)
            assertEquals("코드 $code", expected, cb.ended.single())
        }
    }

    /** 0 은 멈추는 중이라는 뜻이다. 오류가 아니므로 계속 돈다. */
    @Test
    fun `0 은 오류가 아니라 빈 덩어리다`() {
        val running = AtomicBoolean(true)
        val rec = FakeRecorder(listOf(0, 0, 64, AudioRecord.ERROR_DEAD_OBJECT))
        val cb = RecordingCallbacks()

        loop(rec, running, cb)

        assertEquals(listOf(0, 0, 64, 0), cb.blocks)
        assertEquals(1, cb.ended.size)
    }

    /**
     * R01 — 경로가 아직 확인 안 됐으면 **첫 정상 읽기 뒤에 한 번 더** 묻는다.
     *
     * 녹음을 시작한 직후에는 아직 답을 못 주는 기기가 있다. 한 번만 묻고
     * 포기하면 그 기기에서는 영영 보정을 걸 수 없다.
     */
    @Test
    fun `경로가 미확인이면 첫 덩어리 뒤에 한 번 더 묻는다`() {
        val cb = RecordingCallbacks()
        loop(
            FakeRecorder(listOf(64, 64, 64, AudioRecord.ERROR_DEAD_OBJECT)),
            AtomicBoolean(true),
            cb,
            confirmed = false,
        )
        assertEquals("한 번만 묻는다 — 덩어리마다 물으면 초당 50번이다", 1, cb.confirmed.size)
    }

    @Test
    fun `이미 확인됐으면 다시 묻지 않는다`() {
        val cb = RecordingCallbacks()
        loop(
            FakeRecorder(listOf(64, 64, AudioRecord.ERROR_DEAD_OBJECT)),
            AtomicBoolean(true),
            cb,
            confirmed = true,
        )
        assertTrue(cb.confirmed.isEmpty())
    }

    /** 경로를 끝내 못 잡는 기기에서도 터지지 않는다. */
    @Test
    fun `경로를 못 잡아도 계속 돈다`() {
        val cb = RecordingCallbacks()
        loop(
            FakeRecorder(listOf(64, AudioRecord.ERROR_DEAD_OBJECT), routed = null),
            AtomicBoolean(true),
            cb,
            confirmed = false,
        )
        assertTrue("확인 못 했으면 알리지 않는다", cb.confirmed.isEmpty())
        assertEquals(1, cb.ended.size)
    }
}
