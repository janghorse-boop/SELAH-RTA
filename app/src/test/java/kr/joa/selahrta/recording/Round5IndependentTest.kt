package kr.joa.selahrta.recording

import java.io.File
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.*
import org.junit.Test

class Round5IndependentTest {
    @Test fun acknowledgedMemoMustSurviveAnalysisPublication() {
        val fixture = SessionReanalyzerTest()
        fixture.tmp.create()
        try {
            val method = fixture.javaClass.getDeclaredMethod("seed", String::class.java, Double::class.javaPrimitiveType).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val pair = method.invoke(fixture, "s1", 100.0) as Pair<SessionMeta, File>
            val store = fixture.javaClass.getDeclaredField("store").apply { isAccessible = true }.get(fixture) as SessionStore
            var edit: java.util.concurrent.CompletableFuture<Void>? = null
            SessionReanalyzer(store).run(pair.first.id, pair.second,
                ReanalysisSettings(120.0, true, 10000L, Weighting.C, TimeWeight.Fast, 4096), 2L
            ) {
                // Separate writer, admitted while analysis is in progress. A correct lock
                // may defer it until publication; do not deadlock such an implementation.
                if (edit == null) {
                    val started = java.util.concurrent.CountDownLatch(1)
                    edit = java.util.concurrent.CompletableFuture.runAsync {
                        started.countDown()
                        store.setMemo(pair.first.id, "memo saved during analysis").getOrThrow()
                    }
                    assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    try { edit!!.get(1, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: java.util.concurrent.TimeoutException) { /* lock may defer edit */ }
                }
            }.getOrThrow()
            assertNotNull(edit)
            edit!!.get(5, java.util.concurrent.TimeUnit.SECONDS)
            val after = store.readMeta(pair.first.id).getOrThrow()
            println("R5_MEMO saveAcknowledged=true finalMemo=${after.memo}")
            assertEquals("memo saved during analysis", after.memo)
        } finally { fixture.tmp.delete() }
    }
}
