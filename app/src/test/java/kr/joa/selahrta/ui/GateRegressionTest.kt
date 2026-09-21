package kr.joa.selahrta.ui

import kr.joa.selahrta.calibration.ActiveCalibration
import kr.joa.selahrta.calibration.GlobalCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin

/**
 * 독립 검증자(Codex)가 2026-09-21 게이트 재검증에서 만든 회귀 시험.
 *
 * **검증자가 쓴 `docs/review/GateProbe.kt` 의 순서를 그대로 옮겼다.**
 * 검증자는 `check(...)` 로 **결함이 재현되는 것**을 확인했으므로, 여기서는
 * 그 단언을 **고쳐진 뒤의 모습**으로 뒤집어 적었다 — 재는 방법은 손대지
 * 않았다.
 *
 * 두 결함 다 내가 `CaptureController` 를 떼어 내며 만든 것이다:
 *
 * - **G01** — `stop()` 이 읽는 `state` 가 ViewModel 에 있을 때는 **합쳐진**
 *   상태였는데, 옮기고 나서는 **합치기 전** 기본 상태가 됐다. 같은 표현이
 *   다른 뜻이 된 것을 내가 못 봤다.
 * - **G02** — 입구 검사를 **이미 지나** 들어와 있던 옛 콜백이 새 세션의
 *   스냅샷을 옛 것으로 덮는다. C01 과 같은 성질의 결함이다.
 */
class GateRegressionTest {

    private val clock = FakeClock()
    private lateinit var src: FakeSource

    private fun controller() = CaptureController(
        listDevices = { listOf(builtInMic()) },
        openSource = { target, hooks -> FakeSource(target, hooks, clock = clock).also { src = it } },
        post = { it() },
        onRouteConfirmedHook = {},
        onStoppedHook = {},
        nowNs = { clock.ns },
    )

    /**
     * G01 — **멈추면 측정 결과가 통째로 사라진다.**
     *
     * 검증자가 잰 값: 멈추기 직전 SPL 103.0dB · MAX 103.0dB · RTA 있음 ·
     * frames 102400 이던 것이 멈춘 뒤 전부 null / 0 이 됐다.
     */
    @Test
    fun `멈춰도 마지막 측정 결과가 남는다`() {
        val c = controller()
        c.start()
        var sample = 0L
        repeat(100) {
            src.deliverRaw(FloatArray(1024) { (0.2 * sin(2 * PI * 1000 * sample++ / 48000)).toFloat() })
        }

        val before = c.baseState.value.withMeasurement(c.measurement.value)
        assertNotNull("멈추기 전에 SPL 이 있어야 한다", before.meter.currentSpl)
        assertNotNull("멈추기 전에 RTA 가 있어야 한다", before.rta)
        assertTrue("멈추기 전에 프레임이 세어져야 한다", before.diagnostics.frames > 0)

        c.stop()
        val after = c.baseState.value.withMeasurement(c.measurement.value)

        // 검증자의 Gate2Probe 와 같은 세기로 본다 — 값 하나씩이 아니라
        // MeterReading 과 diagnostics 를 **통째로** 견준다.
        assertEquals("계기값이 통째로 남아야 한다", before.meter, after.meter)
        assertEquals("진단도 통째로 남아야 한다", before.diagnostics, after.diagnostics)
        assertNotNull("RTA 가 남아야 한다", after.rta)
    }

    /**
     * G01 — **Leq·Peak 도 같은 객체라 함께 사라졌다.**
     *
     * 검증자가 「feedbackLog 만 검사하면 이 결함을 놓친다」고 적었다.
     * 실제로 내 시험이 그랬다. 보정도 기본값(120dB)이 아닌 값으로 건다 —
     * 예전에 기본값과 같은 값을 줘서 아무것도 보지 않는 시험을 만든 적이
     * 있다.
     */
    @Test
    fun `멈춰도 Leq 와 Peak 와 보정이 남는다`() {
        val c = controller()
        c.start()
        c.update { st ->
            st.copy(
                calibration = ActiveCalibration.from(
                    GlobalCalibration(offsetDb = 95.0, savedAtEpochMs = 0, referenceDb = 80.0, measuredDbfs = -15.0),
                ),
            )
        }
        var sample = 0L
        repeat(200) {
            src.deliverRaw(FloatArray(1024) { (0.2 * sin(2 * PI * 1000 * sample++ / 48000)).toFloat() })
        }

        val before = c.baseState.value.withMeasurement(c.measurement.value)
        assertNotNull("Peak 이 있어야 한다", before.meter.peakSpl)
        val peak = before.meter.peakSpl!!

        c.stop()
        val after = c.baseState.value.withMeasurement(c.measurement.value)

        assertEquals("Peak 이 남아야 한다", peak, after.meter.peakSpl!!, 1e-9)
        // 95dB 로 계산한 값이 남아야 한다. 기본값 120dB 로 다시 계산되면
        // 25dB 이 통째로 튄다.
        assertTrue(
            "멈춘 뒤 숫자가 보정만큼 튀면 안 된다 (전 $peak / 후 ${after.meter.peakSpl})",
            kotlin.math.abs(peak - after.meter.peakSpl!!) < 0.001,
        )
    }

    /**
     * G02 — **입구를 이미 지난 옛 콜백이 새 세션의 스냅샷을 덮는다.**
     *
     * 검증자가 잰 값: `LATE_SNAPSHOT active=3 published=2 visibleCurrent=null`
     * — 새 세션(3)이 내놓은 값이 옛 세션(2)의 값으로 바뀌고, 소비 측은
     * 세대가 안 맞아 기본 상태를 돌려주므로 화면이 빈다.
     *
     * **재는 방법이 핵심이다.** `postToCapture` 큐에 latch 를 넣어
     * **실제 `onBlock` 의 입구 검사를 지난 뒤**에 세운다. 멈춘 뒤에 처음
     * 부르는 시험은 이 조건을 검증하지 못한다(검증자 지적).
     */
    @Test
    fun `입구를 지난 옛 콜백이 새 세션의 값을 덮지 않는다`() {
        val c = controller()
        c.start()
        val old = src
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()

        c.postToCapture {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "풀어 주지 않았다" }
        }
        val worker = Thread {
            try {
                old.deliver(count = 4096)
            } catch (t: Throwable) {
                workerFailure.set(t)
            }
        }
        worker.start()
        assertTrue("옛 콜백이 입구를 지나야 한다", entered.await(5, TimeUnit.SECONDS))

        c.stop()
        c.start()
        repeat(100) { src.deliver() }

        val newId = c.baseState.value.session
        assertEquals("새 세션의 값이 올라와 있어야 한다", newId, c.measurement.value!!.session)
        val expected = c.baseState.value.withMeasurement(c.measurement.value).meter.currentSpl
        assertNotNull("새 세션의 SPL 이 있어야 한다", expected)

        release.countDown()
        worker.join(5_000)
        assertTrue("옛 스레드가 끝나야 한다", !worker.isAlive)
        assertNull("옛 스레드가 터지면 안 된다", workerFailure.get())

        val now = c.measurement.value
        assertEquals("옛 세션의 값이 새 값을 덮으면 안 된다", newId, now!!.session)
        assertEquals(
            "화면에 보이는 값이 사라지면 안 된다",
            expected!!,
            c.baseState.value.withMeasurement(now).meter.currentSpl!!,
            1e-9,
        )
        c.stop()
    }
}
