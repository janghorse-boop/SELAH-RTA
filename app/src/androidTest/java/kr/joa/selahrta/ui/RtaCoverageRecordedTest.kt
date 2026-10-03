package kr.joa.selahrta.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kr.joa.selahrta.audio.AudioInterruptions
import kr.joa.selahrta.audio.SignalPlayer
import kr.joa.selahrta.audio.SignalSink
import kr.joa.selahrta.data.rta.RtaMeasurementStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * **붙였다고 값이 모이는 것은 아니다**(독립 검토 PND-03).
 *
 * 탭을 붙이는 코드는 컴파일만으로 통과한다. **정말 분석 스레드가 그 자리를
 * 부르는지**, 그리고 그 값이 **저장 기록에 남는지**는 마이크를 열고 재 봐야
 * 안다.
 *
 * ## 이번 단계에서 보지 않는 것
 *
 * **평균이 여기서 나온다**(단계 B). coverage **수치**는 적히기만 하고
 * 판정에 쓰이지 않는다(단계 C 에서 분포를 모으는 중). 그러므로 여기서
 * 보는 것은 **모이는가**이지 「평균이 맞다」가 아니다.
 *
 * ## 실제 마이크를 연다
 *
 * 합성 장을 밀어 넣는 다른 시험과 다르다 — **분석 스레드가 실제로 도는
 * 것**이 이 시험의 요점이라, 마이크 권한이 없으면 **건너뛴다.**
 */
class RtaCoverageRecordedTest {

    private fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable { block() })
        Handler(Looper.getMainLooper()).post(task)
        return task.get(30, TimeUnit.SECONDS)
    }

    private fun field(obj: Any, name: String): Any? =
        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)

    private fun setField(obj: Any, name: String, value: Any) =
        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(obj, value)

    private class Sink : SignalSink {
        override fun open(sampleRate: Int, frames: Int, channels: Int) = true
        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            Thread.sleep(2)
            return frames
        }
        override fun stop() {}
        override fun release() = true
    }

    @Test
    fun 잰_기록에_coverage_가_남는다() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            app,
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        org.junit.Assume.assumeTrue("마이크 권한이 있어야 실제 분석이 돈다", granted)

        val owner = ViewModelStore()
        val vm = main {
            ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[
                CaptureViewModel::class.java
            ]
        }
        val root = File(app.cacheDir, "cov-" + UUID.randomUUID())
        val executor = field(field(field(vm, "signals")!!, "commands")!!, "executor") as ExecutorService
        val old = field(field(vm, "signals")!!, "player") as SignalPlayer

        @Suppress("UNCHECKED_CAST")
        val callback = field(old, "onEnded") as (Long, String) -> Unit
        val player = SignalPlayer(onEnded = callback, openSink = { Sink() }, warn = {})
        main {
            setField(field(vm, "signals")!!, "player", player)
            setField(vm, "rtaStore", RtaMeasurementStore(root))
        }

        try {
            main { vm.start() }
            // 마이크가 실제로 열릴 때까지 기다린다.
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (main { vm.rtaSpec() } == null && System.nanoTime() < until) Thread.sleep(100)
            org.junit.Assume.assumeTrue("마이크를 못 열었다", main { vm.rtaSpec() } != null)

            main { vm.startRtaCapture("coverage", null) }
            Thread.sleep(13_500)
            main {}

            val saved = RtaMeasurementStore(root).list()
            val notice = main { (field(vm, "controller") as CaptureController).baseState.value.rtaSaveNoticeKo }
            println("COVERAGE saved=${saved.size} cov=${saved.firstOrNull()?.coverage} " +
                "windows=${saved.firstOrNull()?.windows} hop=${saved.firstOrNull()?.hopFrames} notice=$notice")
            assertEquals("조용한 방이어도 저장은 된다(판정은 아직 안 바뀐다)", 1, saved.size)

            val m = saved.single()
            assertNotNull("coverage 가 안 적혔다", m.coverage)
            assertNotNull("창 수가 안 적혔다", m.windows)
            assertNotNull("건너뛰는 폭이 안 적혔다", m.hopFrames)
            assertEquals("analysis-tap-v1", m.conditions.averageVersion)

            // **화면이 받은 장보다 분석 장이 많아야 한다** — 이것이 이 작업의
            // 까닭이다. 화면은 66ms 에 한 번만 받는다.
            assertTrue(
                "분석 장(${m.windows})이 화면 장(${m.averagedFrames})보다 많아야 한다",
                m.windows!! > m.averagedFrames,
            )
            assertTrue("coverage 가 0 이다", m.coverage!! > 0.0)
        } finally {
            main { vm.cancelRtaCapture(); vm.stop(); owner.clear() }
            executor.awaitTermination(5, TimeUnit.SECONDS)
            player.stop()
            (field(vm, "interruptions") as AudioInterruptions).release()
            root.deleteRecursively()
        }
    }
}
