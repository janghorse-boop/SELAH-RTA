package kr.joa.selahrta.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kr.joa.selahrta.calibration.CalibrationSource
import kr.joa.selahrta.domain.CalibrationState
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import kr.joa.selahrta.recording.MeasurementConditions
import kr.joa.selahrta.recording.SessionMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * **PDF 가 받는 앱까지 실제로 건너가는가**(Phase 11).
 *
 * `ReportPdfTest` 는 **파일이 제대로 만들어지는가**를 본다. 여기서는 그
 * 파일이 **다른 앱이 열 수 있는 형태로 건네지는가**를 본다 — 둘은 다른
 * 일이고, 실제로 깨지는 자리는 뒤쪽이다.
 *
 * `FileProvider` 가 그 폴더를 모르면 **내보내기 단추를 눌러도 받는 쪽에서
 * 열리지 않는다.** 우리 앱 안에서는 파일이 멀쩡하므로 눈으로는 못 잡는다.
 * 그래서 **받는 앱이 하듯 `ContentResolver` 로 열어 본다.**
 */
class ReportPdfShareTest {

    private fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable { block() })
        Handler(Looper.getMainLooper()).post(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    private fun field(obj: Any, name: String): Any? =
        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)

    private val app: Application
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application

    private fun meta() = SessionMeta(
        id = "share-1",
        startedAtEpochMs = 1_700_000_000_000L,
        endedAtEpochMs = 1_700_000_060_000L,
        durationMs = 60_000L,
        deviceKey = "BuiltIn|SM-S918N",
        deviceLabel = "SM-S918N",
        micKind = MicKind.BuiltIn,
        sampleRate = 48_000,
        encoding = "Float",
        channelCount = 1,
        channelIndex = 0,
        calibrationOffsetDb = 118.0,
        referenceOnly = false,
        curveApplied = false,
        curveLabel = "",
        weighting = Weighting.A,
        timeWeight = TimeWeight.Fast,
        leqWindowMs = 10_000L,
        leqDb = 72.3,
        minDb = 55.0,
        maxDb = 88.0,
        peakDb = 96.0,
        memo = "주일 2부",
        events = emptyList(),
        droppedPackets = 0,
        clippedRows = 0,
        conditions = MeasurementConditions(
            calibrationState = CalibrationState.GlobalCalibrated,
            calibrationSource = CalibrationSource.Calibrator,
        ),
    )

    private fun shareUris(vm: CaptureViewModel): List<android.net.Uri> {
        val controller = field(vm, "controller") as CaptureController
        return controller.baseState.value.shareUris
    }

    @Test
    fun 리포트_PDF_가_받는_앱이_열_수_있는_형태로_건네진다() {
        val store = ViewModelStore()
        val vm = main {
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[
                CaptureViewModel::class.java
            ]
        }
        try {
            main { vm.exportReportPdf(meta()) }

            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (shareUris(vm).isEmpty() && System.nanoTime() < until) Thread.sleep(20)

            val uris = shareUris(vm)
            assertEquals("PDF 하나만 보낸다 — 표와 소리는 따로다", 1, uris.size)

            // **받는 앱이 하듯 연다.** 우리 파일 시스템으로 읽으면
            // FileProvider 가 빠져 있어도 통과해 버린다.
            val head = app.contentResolver.openInputStream(uris[0])!!.use { input ->
                ByteArray(4).also { input.read(it) }
            }
            assertEquals("%PDF", head.toString(Charsets.US_ASCII))

            val type = app.contentResolver.getType(uris[0])
            assertEquals("받는 쪽이 PDF 로 알아보아야 한다", "application/pdf", type)

            assertTrue(
                "파일 이름이 기록의 시각을 담아야 한다",
                uris[0].toString().endsWith(".pdf"),
            )
        } finally {
            main { store.clear() }
        }
    }
}
