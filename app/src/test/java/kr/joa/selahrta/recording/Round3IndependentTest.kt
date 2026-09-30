package kr.joa.selahrta.recording

import kr.joa.selahrta.domain.CalibrationState
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Round3IndependentTest {
    private fun settings() = ReanalysisSettings(120.0, true, 10000L, Weighting.C, TimeWeight.Fast, 4096)

    private fun seeded(block: (SessionStore, SessionMeta, File) -> Unit) {
        val fixture = SessionReanalyzerTest()
        fixture.tmp.create()
        try {
            val method = fixture.javaClass.getDeclaredMethod("seed", String::class.java, Double::class.javaPrimitiveType).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val pair = method.invoke(fixture, "s1", 100.0) as Pair<SessionMeta, File>
            val store = fixture.javaClass.getDeclaredField("store").apply { isAccessible = true }.get(fixture) as SessionStore
            block(store, pair.first, pair.second)
        } finally { fixture.tmp.delete() }
    }

    @Test fun metaWriteFailureMustNotReplaceTimeline() = seeded { store, meta, audio ->
        val before = store.timelineFile(meta.id).readBytes()
        // **주입 지점을 새 경로로 옮겼다. 묻는 것은 그대로다.**
        //
        // 검토자가 쓸 때는 겉장을 `meta.txt.tmp` 로 쓰다 실패시켰다.
        // 고치면서 그 경로가 없어졌다(옆에 다 만들고 표시 파일 하나로
        // 게시한다). 그래서 **게시 그 자체를 실패시킨다** — 묻는 것은
        // 여전히 「실패했는데 활성 타임라인이 바뀌었는가」다.
        //
        // 주입 지점을 안 옮기면 이 시험은 **아무 위험도 건드리지 않고**
        // 통과한다. 그렇게 통과시키는 것이 더 나쁘다.
        File(store.dirOf(meta.id), SessionStore.READY_NAME).mkdirs()
        File(File(store.dirOf(meta.id), SessionStore.READY_NAME), "막는다").writeText("x")
        val r = SessionReanalyzer(store).run(meta.id, audio, settings(), 2L)
        val same = before.contentEquals(store.timelineFile(meta.id).readBytes())
        val after = store.readMeta(meta.id).getOrThrow()
        println("R3_ATOMIC failure=${r.isFailure} sameTimeline=$same metaWeight=${after.weighting} oldWeight=${meta.weighting}")
        assertTrue(r.isFailure)
        assertTrue("Failed commit changed the active timeline", same)
    }

    @Test fun originalEpochMappingMustRemainRecoverable() = seeded { store, meta, audio ->
        val before = store.timelineFile(meta.id).readBytes()
        SessionReanalyzer(store).run(meta.id, audio, settings(), 2L).getOrThrow()
        // **경로만 새 구조로 맞췄다. 단언의 뜻은 그대로다.**
        // 검토자가 쓸 때 원본은 `timeline-v1.bin` 한 파일이었고, 고치면서
        // `original/` 폴더(겉장 + 타임라인)로 바뀌었다. 「원본 바이트가
        // 남는가」와 「그것을 풀 표가 남는가」를 묻는 것은 같다.
        val original = store.originalTimelineFile(meta.id)
        assertArrayEquals(before, original.readBytes())
        // Find a persisted SessionMeta retaining the original epoch table, not just raw rows.
        // 폴더가 생겼으므로 **하위까지** 훑는다.
        val metadatas = store.dirOf(meta.id).walkTopDown().filter { it.extension == "txt" }
            .mapNotNull { decodeSessionMeta(it.readText()).getOrNull() }.toList()
        val retained = metadatas.any { it.epochs == meta.epochs && it.calibrationOffsetDb == meta.calibrationOffsetDb }
        println("R3_ORIGINAL retainedEpochs=$retained files=${store.dirOf(meta.id).list()!!.toList()} oldOffset=${meta.calibrationOffsetDb} activeOffset=${metadatas.first().calibrationOffsetDb}")
        assertTrue("Original timeline without original epoch mapping is not the original measurement", retained)
    }

    @Test fun newReferenceOnlyResultMustHaveReferenceWarning() = seeded { store, meta, audio ->
        store.writeMeta(meta.copy(conditions = meta.conditions.copy(calibrationState = CalibrationState.GlobalCalibrated))).getOrThrow()
        val after = SessionReanalyzer(store).run(meta.id, audio, settings(), 2L).getOrThrow()
        val warnings = reportWarningsKo(after)
        println("R3_TRUST referenceOnly=${after.referenceOnly} calibrationState=${after.conditions.calibrationState} warnings=$warnings")
        assertTrue(after.referenceOnly)
        assertTrue("New reference-only result has lost its uncalibrated warning", warnings.any { it.contains("미보정") })
    }

    /**
     * **여기서 기대값을 바꿨습니다 — 까닭을 적습니다.**
     *
     * 검토자의 원래 단언은 `assertEquals(Weighting.C, peakWeighting)` —
     * 「peak 라벨이 고른 SPL 가중을 따라야 한다」였습니다. **지적 자체는
     * 맞습니다**(옛 A 가 그대로 남아 있었다). 다만 **따라갈 대상이
     * C 가 아닙니다.**
     *
     * 재서 확인했습니다. 기록에 남는 peak 는 `SplFrame.blockPeakDbfs` 이고
     * 그 값은 **A·C·Z 가 모두 같습니다**:
     *
     * ```
     * 100Hz 순음(A 와 C 가 19dB 갈리는 자리)
     *   blockPeak  A=-10.4576  C=-10.4576  Z=-10.4576   ← 같다
     * ```
     *
     * `SplEngine` 도 그렇게 적어 두었습니다 — 「Peak 는 가중 전에 잰다」.
     * 그러니 C 를 적으면 **걸지 않은 가중을 걸었다고 말하는 것**입니다.
     *
     * 그래서 **Z**(안 걸림)로 단언합니다. 「라벨이 셈을 따라가야 한다」는
     * 뜻은 그대로 지킵니다.
     *
     * **라이브 기록에도 같은 거짓이 있었습니다** — 화면 설정값(기본 C)을
     * 그대로 적고 있었고, 그쪽도 함께 고쳤습니다.
     */
    @Test fun peakWeightMetadataMustFollowReanalysis() = seeded { store, meta, audio ->
        store.writeMeta(meta.copy(peakWeighting = Weighting.A)).getOrThrow()
        val after = SessionReanalyzer(store).run(meta.id, audio, settings(), 2L).getOrThrow()
        println("R3_WEIGHT selected=${after.weighting} peakLabel=${after.peakWeighting}")
        assertEquals("peak 는 가중 전에 잰다 — 안 건 가중을 적으면 안 된다", Weighting.Z, after.peakWeighting)
    }
}
