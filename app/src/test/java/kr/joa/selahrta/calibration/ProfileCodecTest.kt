package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.CalibrationOutcome
import kr.joa.selahrta.dsp.CalibrationSession
import kr.joa.selahrta.dsp.CurvePoint
import kr.joa.selahrta.dsp.MeasureStep
import kr.joa.selahrta.dsp.QualityVerdict
import kr.joa.selahrta.dsp.ResponseCurve
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.calibrateFromSession
import kr.joa.selahrta.dsp.calibrateResponse
import kr.joa.selahrta.dsp.judgeCalibration
import kr.joa.selahrta.dsp.qualityFromSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **적은 것을 그대로 되읽는가.**
 *
 * 손으로 짠 해석기가 조용히 틀리는 자리를 잰다 — 값에 든 `=`·역슬래시·
 * 한국어, 「빈 값」과 「없음」의 구별, 소수점 자리, 길이가 어긋난 곡선.
 * 이런 것은 대개 실기기에서야 드러나고, 드러날 때는 프로파일이 이미
 * 여럿 쌓여 있다.
 */
class ProfileCodecTest {

    private fun env() = ProfileEnvironment(
        deviceKey = "BuiltIn|SM-S918N|back",
        deviceAddress = "back",
        micKind = MicKind.BuiltIn,
        audioSource = CaptureSource.Unprocessed,
        sampleRate = 48_000,
        channelCount = 1,
        channelIndex = 0,
        manufacturer = "samsung",
        model = "SM-S918N",
        osBuild = "UP1A.231005.007",
    )

    private fun profile(
        environment: ProfileEnvironment = env(),
        reference: ReferenceRecord = ReferenceRecord("17860.txt", "abc123", 0, "EMM-6"),
        caseRemoved: Boolean? = true,
        validFromHz: Double? = 50.0,
        validToHz: Double? = 16_000.0,
        worstSnrDb: Double? = 13.75,
    ) = MeasuredProfile(
        id = "11111111-2222-3333-4444-555555555555",
        createdAtEpochMs = 1_700_000_000_123L,
        updatedAtEpochMs = 1_700_000_999_456L,
        environment = environment,
        separation = MicSeparation.Separable,
        reference = reference,
        quality = ProfileQuality(
            verdict = QualityVerdict.Pass,
            repeatSpreadDb = 0.8123456789,
            referenceDriftDb = -0.2,
            usableBandRatio = 0.9032258064516129,
            worstSnrDb = worstSnrDb,
            dspVerifiedBySignal = true,
        ),
        levelOffsetDb = -23.456789,
        normalizeBandLowHz = 300.0,
        normalizeBandHighHz = 3_000.0,
        smoothingFraction = 6.0,
        maxCorrectionDb = 12.0,
        validFromHz = validFromHz,
        validToHz = validToHz,
        curvesFileName = "profile-1111.curves",
        caseRemoved = caseRemoved,
        enabled = true,
    )

    // ------------------------------------------------------------------
    // 프로파일 왕복
    // ------------------------------------------------------------------

    @Test
    fun `적은 그대로 되읽는다`() {
        val p = profile()
        val back = decodeProfile(encodeProfile(p)).getOrThrow()
        assertEquals(p, back)
    }

    /** 소수는 **자릿수까지** 같아야 한다. 반올림하면 보정값이 미세하게 달라진다. */
    @Test
    fun `소수가 자릿수까지 살아남는다`() {
        val p = profile()
        val back = decodeProfile(encodeProfile(p)).getOrThrow()
        assertEquals(p.quality.repeatSpreadDb, back.quality.repeatSpreadDb, 0.0)
        assertEquals(p.quality.usableBandRatio, back.quality.usableBandRatio, 0.0)
        assertEquals(p.levelOffsetDb, back.levelOffsetDb, 0.0)
    }

    /**
     * **「빈 값」과 「없음」은 다르다.**
     *
     * CAL 파일 이름이 빈 문자열인 것과 아예 적지 않은 것은 뜻이 다르다 —
     * 앞은 「이름이 없는 파일」, 뒤는 「기준 파일을 쓰지 않았다」다.
     */
    @Test
    fun `빈 값과 없음을 가른다`() {
        val empty = profile(reference = ReferenceRecord("", "", null, ""))
        val backEmpty = decodeProfile(encodeProfile(empty)).getOrThrow()
        assertEquals("", backEmpty.reference.calFileName)
        assertEquals("", backEmpty.reference.calSha256)
        assertNull(backEmpty.reference.inputChannelIndex)

        val none = profile(reference = ReferenceRecord(null, null, null, ""))
        val backNone = decodeProfile(encodeProfile(none)).getOrThrow()
        assertNull(backNone.reference.calFileName)
        assertNull(backNone.reference.calSha256)
    }

    @Test
    fun `없는 값들이 없는 채로 돌아온다`() {
        val p = profile(caseRemoved = null, validFromHz = null, validToHz = null, worstSnrDb = null)
        val back = decodeProfile(encodeProfile(p)).getOrThrow()
        assertNull(back.caseRemoved)
        assertNull(back.validFromHz)
        assertNull(back.validToHz)
        assertNull(back.quality.worstSnrDb)
        assertEquals(p, back)
    }

    @Test
    fun `케이스 여부가 세 상태 그대로 돌아온다`() {
        for (state in listOf(true, false, null)) {
            val back = decodeProfile(encodeProfile(profile(caseRemoved = state))).getOrThrow()
            assertEquals("케이스=$state", state, back.caseRemoved)
        }
    }

    /** 값에 `=` 가 들어도 된다 — **처음 하나만** 가른다. */
    @Test
    fun `값에 등호가 들어도 된다`() {
        val p = profile(reference = ReferenceRecord("cal=17860=v2.txt", "a=b", 1, "EMM-6 (좌=1번)"))
        val back = decodeProfile(encodeProfile(p)).getOrThrow()
        assertEquals("cal=17860=v2.txt", back.reference.calFileName)
        assertEquals("a=b", back.reference.calSha256)
        assertEquals("EMM-6 (좌=1번)", back.reference.micName)
    }

    @Test
    fun `값에 역슬래시와 줄바꿈이 들어도 된다`() {
        val nasty = "C:\\cal\\17860.txt\n두 번째 줄\r끝"
        val p = profile(reference = ReferenceRecord(nasty, null, null, "마이크\\1"))
        val back = decodeProfile(encodeProfile(p)).getOrThrow()
        assertEquals(nasty, back.reference.calFileName)
        assertEquals("마이크\\1", back.reference.micName)
    }

    /** 줄바꿈이 든 값이 **줄을 늘리지 않아야** 한다 — 늘어나면 뒤가 밀린다. */
    @Test
    fun `줄바꿈이 든 값이 파일을 망가뜨리지 않는다`() {
        val p = profile(reference = ReferenceRecord("a\nenabled=false\nb", null, null, ""))
        val text = encodeProfile(p)
        assertFalse("다음 줄로 새면 안 된다", text.contains("\nenabled=false\nb"))
        val back = decodeProfile(text).getOrThrow()
        assertTrue("enabled 가 덮이면 안 된다", back.enabled)
        assertEquals("a\nenabled=false\nb", back.reference.calFileName)
    }

    @Test
    fun `한국어가 그대로 돌아온다`() {
        val p = profile(reference = ReferenceRecord("측정용마이크.txt", null, 0, "데이턴 EMM-6 (후면용)"))
        val back = decodeProfile(encodeProfile(p)).getOrThrow()
        assertEquals("측정용마이크.txt", back.reference.calFileName)
        assertEquals("데이턴 EMM-6 (후면용)", back.reference.micName)
    }

    @Test
    fun `열거형이 이름으로 돌아온다`() {
        for (kind in MicKind.entries) {
            for (sep in MicSeparation.entries) {
                val p = profile(env().copy(micKind = kind)).copy(separation = sep)
                val back = decodeProfile(encodeProfile(p)).getOrThrow()
                assertEquals(kind, back.environment.micKind)
                assertEquals(sep, back.separation)
            }
        }
    }

    // ------------------------------------------------------------------
    // 읽지 못하는 것은 실패한다
    // ------------------------------------------------------------------

    @Test
    fun `프로파일이 아니면 실패한다`() {
        val e = decodeProfile("그냥 글입니다\n안녕하세요").exceptionOrNull()
        assertTrue("$e", e?.message?.contains("판 번호") == true)
    }

    /** **기본값으로 메우지 않는다.** 빠진 것을 Fail 로 채우면 엉뚱한 곳을 고치게 된다. */
    @Test
    fun `항목이 빠지면 실패하고 무엇이 빠졌는지 말한다`() {
        val text = encodeProfile(profile())
            .lineSequence().filterNot { it.startsWith("quality.verdict=") }
            .joinToString("\n")
        val e = decodeProfile(text).exceptionOrNull()
        assertTrue("$e", e?.message?.contains("quality.verdict") == true)
    }

    @Test
    fun `숫자가 깨지면 실패하고 어느 항목인지 말한다`() {
        val text = encodeProfile(profile()).replace("env.sampleRate=48000", "env.sampleRate=사팔천")
        val e = decodeProfile(text).exceptionOrNull()
        assertTrue("$e", e?.message?.contains("env.sampleRate") == true)
    }

    @Test
    fun `모르는 열거형 값이면 실패한다`() {
        val text = encodeProfile(profile()).replace("separation=Separable", "separation=Maybe")
        val e = decodeProfile(text).exceptionOrNull()
        assertTrue("$e", e?.message?.contains("separation") == true)
    }

    @Test
    fun `더 새 판은 읽지 않는다`() {
        val text = encodeProfile(profile())
            .replace("schemaVersion=$PROFILE_SCHEMA_VERSION", "schemaVersion=${PROFILE_SCHEMA_VERSION + 1}")
        val e = decodeProfile(text).exceptionOrNull()
        assertTrue("$e", e?.message?.contains("새 판") == true)
    }

    @Test
    fun `주석과 빈 줄은 건너뛴다`() {
        val text = encodeProfile(profile())
            .replace("\nid=", "\n# 사람이 적어 둔 메모\n\nid=")
        assertEquals(profile(), decodeProfile(text).getOrThrow())
    }

    // ------------------------------------------------------------------
    // 곡선 넷
    // ------------------------------------------------------------------

    private fun outcome() = calibrateResponse(
        referencePoints = (0 until ThirdOctave.BAND_COUNT).map {
            CurvePoint(ThirdOctave.exactCenter(it), 70.0)
        },
        internalPoints = (0 until ThirdOctave.BAND_COUNT).map {
            CurvePoint(ThirdOctave.exactCenter(it), 50.0 + if (it >= 24) -6.0 else 0.0)
        },
    )

    @Test
    fun `곡선 넷이 그대로 되읽힌다`() {
        val o = outcome()
        val back = decodeCurves(encodeCurves(o)).getOrThrow()

        assertArrayEquals(o.reference.hz, back.reference.hz, 0.0)
        for ((a, b) in listOf(
            o.reference to back.reference,
            o.internalRaw to back.internalRaw,
            o.internalNormalized.curve to back.internalNormalized.curve,
            o.correction to back.correction,
            o.corrected to back.corrected,
        )) {
            assertArrayEquals(a.db, b.db, 0.0)
            assertArrayEquals(a.valid, b.valid)
        }
        assertEquals(o.internalNormalized.offsetDb, back.internalNormalized.offsetDb, 0.0)
        assertEquals(o.internalNormalized.pointsUsed, back.internalNormalized.pointsUsed)
        assertEquals(o.settings, back.settings)
    }

    /** 다섯 곡선이 **한 축**을 쓴다. 축을 한 번만 적는 근거다. */
    @Test
    fun `되읽은 곡선들이 한 축을 쓴다`() {
        val back = decodeCurves(encodeCurves(outcome())).getOrThrow()
        val axis = back.reference.hz
        assertArrayEquals(axis, back.internalRaw.hz, 0.0)
        assertArrayEquals(axis, back.internalNormalized.curve.hz, 0.0)
        assertArrayEquals(axis, back.correction.hz, 0.0)
        assertArrayEquals(axis, back.corrected.hz, 0.0)
    }

    /**
     * 잰 범위가 좁으면 **양끝이 못 믿는 자리가 된다.** 실제 CAL 파일이
     * 그렇다 — 20Hz~20kHz 를 다 덮는 것은 드물다.
     */
    private fun narrowOutcome() = calibrateResponse(
        // 200Hz~10kHz 만 쟀다고 치자. 축(20Hz~20kHz)의 양끝은 잰 적이 없다.
        referencePoints = (10..27).map { CurvePoint(ThirdOctave.exactCenter(it), 70.0) },
        internalPoints = (10..27).map {
            CurvePoint(ThirdOctave.exactCenter(it), 50.0 + if (it >= 24) -6.0 else 0.0)
        },
    )

    @Test
    fun `믿을 수 없는 자리 표시가 살아남는다`() {
        val o = narrowOutcome()
        val back = decodeCurves(encodeCurves(o)).getOrThrow()

        // **전제부터 확인한다.** 못 믿는 자리가 하나도 없으면 이 시험은
        // 「전부 참」을 적는 엉터리 부호기도 통과시킨다.
        assertTrue(
            "보정 곡선에 못 믿는 자리가 있어야 한다",
            o.correction.valid.any { !it },
        )
        assertTrue("믿을 수 있는 자리도 있어야 한다", o.correction.valid.any { it })

        assertArrayEquals(o.correction.valid, back.correction.valid)
        assertArrayEquals(o.reference.valid, back.reference.valid)
        assertArrayEquals(o.internalRaw.valid, back.internalRaw.valid)
    }

    /** **길이가 어긋나면 만들지 않는다.** 짧은 쪽이 조용히 잘린 채 그려지면 안 된다. */
    @Test
    fun `길이가 어긋난 곡선은 실패한다`() {
        val text = encodeCurves(outcome())
        val broken = text.lineSequence().joinToString("\n") { line ->
            if (line.startsWith("correction.db=")) {
                line.substringBefore('=') + "=" + line.substringAfter('=').split(',').drop(3).joinToString(",")
            } else {
                line
            }
        }
        val e = decodeCurves(broken).exceptionOrNull()
        assertTrue("$e", e?.message?.contains("correction") == true)
    }

    @Test
    fun `유효 표시가 0과 1이 아니면 실패한다`() {
        val text = encodeCurves(outcome())
        val broken = text.replace(Regex("(?m)^correction\\.valid=.*$"), "correction.valid=11x1")
        val e = decodeCurves(broken).exceptionOrNull()
        assertTrue("$e", e?.message?.contains("correction.valid") == true)
    }

    @Test
    fun `축이 없으면 실패한다`() {
        val text = encodeCurves(outcome()).lineSequence()
            .filterNot { it.startsWith("axis=") }.joinToString("\n")
        val e = decodeCurves(text).exceptionOrNull()
        assertTrue("$e", e?.message?.contains("주파수 축") == true)
    }

    @Test
    fun `곡선 파일이 아니면 실패한다`() {
        val e = decodeCurves("아무 글이나").exceptionOrNull()
        assertTrue("$e", e?.message?.contains("판 번호") == true)
    }

    // ------------------------------------------------------------------
    // CP03 — 길이가 맞는 것과 뜻이 맞는 것은 다르다 (독립 검증 2026-09-23)
    // ------------------------------------------------------------------

    private fun curvesWith(key: String, value: String): String =
        encodeCurves(outcome()).lineSequence().joinToString("\n") {
            if (it.startsWith("$key=")) "$key=$value" else it
        }

    /**
     * **`toDoubleOrNull` 은 `NaN` 을 성공으로 읽는다.**
     *
     * Codex probe 가 잡은 자리다 — 손상된 파일이 「길이가 맞는 정상
     * 곡선」으로 읽혀 valid=true 인 NaN 점 120개를 돌려주었다.
     */
    @Test
    fun `NaN 은 곡선으로 받지 않는다`() {
        val n = outcome().correction.db.size
        val r = decodeCurves(curvesWith("correction.db", List(n) { "NaN" }.joinToString(",")))
        assertTrue("실패해야 한다", r.isFailure)
        assertTrue("$r", r.exceptionOrNull()?.message?.contains("correction.db") == true)
    }

    @Test
    fun `무한대는 곡선으로 받지 않는다`() {
        val n = outcome().correction.db.size
        for (bad in listOf("Infinity", "-Infinity")) {
            val line = (List(n - 1) { "0.0" } + bad).joinToString(",")
            val r = decodeCurves(curvesWith("correction.db", line))
            assertTrue("$bad 는 실패해야 한다: $r", r.isFailure)
        }
    }

    @Test
    fun `프로파일의 숫자도 NaN 과 무한대를 받지 않는다`() {
        for (bad in listOf("NaN", "Infinity", "-Infinity")) {
            val text = encodeProfile(profile())
                .replace(Regex("(?m)^levelOffsetDb=.*$"), "levelOffsetDb=$bad")
            val r = decodeProfile(text)
            assertTrue("$bad 는 실패해야 한다", r.isFailure)
            assertTrue("$r", r.exceptionOrNull()?.message?.contains("levelOffsetDb") == true)
        }
    }

    /** 축이 뒤섞여 있어도 **길이 검사는 통과한다.** 그래서 따로 본다. */
    @Test
    fun `축이 커지지 않으면 실패한다`() {
        val n = outcome().reference.hz.size
        val rising = List(n) { (it + 1).toDouble() }
        val cases = mapOf(
            "역순" to rising.reversed().joinToString(","),
            // 5번과 6번을 같은 값으로 — 같은 주파수가 두 번 나온다.
            "중복" to rising.toMutableList().also { it[5] = it[4] }.joinToString(","),
        )
        for ((why, axis) in cases) {
            val r = decodeCurves(curvesWith("axis", axis))
            assertTrue("$why 축은 실패해야 한다: $r", r.isFailure)
            assertTrue("$why: $r", r.exceptionOrNull()?.message?.contains("커지지 않") == true)
        }
    }

    @Test
    fun `축에 0 이하가 있으면 실패한다`() {
        val n = outcome().reference.hz.size
        val axis = (0 until n).map { it.toDouble() }.joinToString(",") // 첫 점이 0.0
        val r = decodeCurves(curvesWith("axis", axis))
        assertTrue("$r", r.exceptionOrNull()?.message?.contains("0 이하") == true)
    }

    @Test
    fun `말이 안 되는 프로파일 값은 실패한다`() {
        val cases = mapOf(
            "env.sampleRate" to "0",
            "env.channelCount" to "0",
            "quality.usableBandRatio" to "1.5",
            "smoothingFraction" to "0.0",
            "maxCorrectionDb" to "0.0",
            "algorithmVersion" to "0",
        )
        for ((key, value) in cases) {
            val text = encodeProfile(profile()).replace(Regex("(?m)^$key=.*$"), "$key=$value")
            val r = decodeProfile(text)
            assertTrue("$key=$value 는 실패해야 한다", r.isFailure)
            assertTrue("$key: $r", r.exceptionOrNull()?.message?.contains(key) == true)
        }
    }

    /** 채널 번호가 채널 수 밖이면 어느 입력으로 쟀는지 말할 수 없다. */
    @Test
    fun `채널 번호가 채널 수 밖이면 실패한다`() {
        val text = encodeProfile(profile(env().copy(channelCount = 2, channelIndex = 5)))
        val r = decodeProfile(text)
        assertTrue("$r", r.exceptionOrNull()?.message?.contains("env.channelIndex") == true)
    }

    /** 「어디부터 믿을 수 있는지」는 **둘 다 있거나 둘 다 없어야** 한다. */
    @Test
    fun `유효 범위가 한쪽만 있으면 실패한다`() {
        val text = encodeProfile(profile()).lineSequence()
            .filterNot { it.startsWith("validToHz=") }.joinToString("\n")
        val r = decodeProfile(text)
        assertTrue("$r", r.exceptionOrNull()?.message?.contains("한쪽만") == true)
    }

    @Test
    fun `유효 범위가 뒤집혀 있으면 실패한다`() {
        val text = encodeProfile(profile(validFromHz = 16_000.0, validToHz = 50.0))
        val r = decodeProfile(text)
        assertTrue("$r", r.exceptionOrNull()?.message?.contains("validToHz") == true)
    }

    @Test
    fun `고친 때가 만든 때보다 이르면 실패한다`() {
        val text = encodeProfile(profile())
            .replace(Regex("(?m)^updatedAtEpochMs=.*$"), "updatedAtEpochMs=1")
        val r = decodeProfile(text)
        assertTrue("$r", r.exceptionOrNull()?.message?.contains("updatedAtEpochMs") == true)
    }

    /**
     * **적기 전에 축이 같은지 본다.**
     *
     * `calibrateResponse` 는 늘 같은 축을 쓰지만 `CalibrationOutcome` 은
     * 공개 타입이다. 길이만 같고 값이 다른 축이 적히면, 되읽을 때 길이
     * 검사를 통과해 **없던 자리에 값이 놓인다.**
     */
    @Test
    fun `길이만 같고 다른 축은 적지 않는다`() {
        val o = outcome()
        val n = o.reference.hz.size
        val shifted = ResponseCurve(
            DoubleArray(n) { o.reference.hz[it] * 1.01 },
            o.correction.db.copyOf(),
            o.correction.valid.copyOf(),
        )
        val e = runCatching { encodeCurves(o.copy(correction = shifted)) }.exceptionOrNull()
        assertTrue("$e", e?.message?.contains("correction") == true)
    }

    // ------------------------------------------------------------------
    // L01-R — 왕복이 까닭을 잃지 않는다 (독립 검증 2026-09-23)
    // ------------------------------------------------------------------

    /** 상한이 실제로 자른 결과. 톱니형 기준이 상한을 넘게 만든다. */
    private fun limitedOutcome(): CalibrationOutcome {
        val ref = DoubleArray(31) {
            if (it < 2 || it > 28) 70.0 else if (it % 2 == 0) 100.0 else 40.0
        }
        val s = CalibrationSession(referenceCalApplied = true)
        repeat(8) {
            s.record(MeasureStep.ReferenceBefore, ref)
            s.record(MeasureStep.ReferenceAfter, ref)
            s.record(MeasureStep.Target, DoubleArray(31) { 70.0 })
        }
        val r = s.result()!!
        val q = qualityFromSession(
            r, DoubleArray(31) { 0.0 }, DoubleArray(31) { 0.0 },
            referenceCalRangeHz = 20.0..20_000.0, dspVerifiedBySignal = true,
        )
        return calibrateFromSession(r, q).getOrThrow()
    }

    /**
     * **왕복 한 번에 까닭이 뒤바뀌었다.**
     *
     * 상한 표시가 적히지 않아 되읽으면 전부 false 가 되고, 그것을
     * 「상한 아님」으로 읽어 SNR·CAL 탓으로 분류했다.
     */
    @Test
    fun `상한 원인이 왕복에서 살아남는다`() {
        val o = limitedOutcome()
        // 전제: 정말 상한이 잘랐는가.
        val before = o.limitedByMaxCorrection!!.count { it }
        assertTrue("상한이 자른 점이 있어야 한다", before > 0)
        assertTrue("까닭에 상한이 있어야 한다", o.unsupportedReasonsKo().any { it.contains("상한") })

        val back = decodeCurves(encodeCurves(o)).getOrThrow()
        assertEquals("표시 수가 같아야 한다", before, back.limitedByMaxCorrection!!.count { it })
        assertArrayEquals(o.limitedByMaxCorrection, back.limitedByMaxCorrection)
        assertTrue(
            "까닭도 살아남아야 한다: ${back.unsupportedReasonsKo()}",
            back.unsupportedReasonsKo().any { it.contains("상한") },
        )
    }

    /** CAL 만 좁은 결과는 왕복해도 **상한을 탓하지 않는다.** */
    @Test
    fun `상한이 없던 결과는 왕복해도 상한이 생기지 않는다`() {
        val s = CalibrationSession(referenceCalApplied = true)
        repeat(8) {
            for (step in MeasureStep.entries) s.record(step, DoubleArray(31) { 70.0 })
        }
        val r = s.result()!!
        val q = qualityFromSession(
            r, DoubleArray(31) { 0.0 }, DoubleArray(31) { 0.0 },
            referenceCalRangeHz = 20.0..1_300.0, dspVerifiedBySignal = true,
        )
        val o = calibrateFromSession(r, q).getOrThrow()
        assertFalse("상한이 자른 점이 없어야 한다", o.limitedByMaxCorrection!!.any { it })

        val back = decodeCurves(encodeCurves(o)).getOrThrow()
        assertFalse(
            "왕복 뒤에도 상한을 탓하면 안 된다: ${back.unsupportedReasonsKo()}",
            back.unsupportedReasonsKo().any { it.contains("상한") },
        )
    }

    /**
     * **까닭을 안 적은 파일은 「상한 아님」이 아니라 「모른다」다.**
     *
     * 없는 것을 false 로 읽으면 없는 사실을 단언하게 된다.
     */
    @Test
    fun `까닭을 적지 않은 곡선은 모른다로 읽는다`() {
        val o = limitedOutcome()
        val text = encodeCurves(o).lineSequence()
            .filterNot { it.startsWith("correction.limited=") }.joinToString("\n")
        val back = decodeCurves(text).getOrThrow()

        assertNull("표시가 없으면 null 이어야 한다", back.limitedByMaxCorrection)
        val why = back.unsupportedReasonsKo()
        assertFalse("상한을 단언하면 안 된다: $why", why.any { it.contains("상한") })
        assertTrue("모른다고 말해야 한다: $why", why.any { it.contains("까닭 정보 없음") })
    }

    /**
     * **「모른다」를 다시 적을 때 없는 사실을 지어내지 않는다.**
     *
     * 표시 없는 파일을 읽어 그대로 다시 적으면 여전히 표시가 없어야
     * 한다 — 빈 배열을 적으면 「상한 아님」이라는 **없던 단언**이 생긴다.
     */
    @Test
    fun `모르는 것을 다시 적어도 모르는 채로 둔다`() {
        val o = limitedOutcome()
        val text = encodeCurves(o).lineSequence()
            .filterNot { it.startsWith("correction.limited=") }.joinToString("\n")
        val legacy = decodeCurves(text).getOrThrow()

        assertNull(legacy.limitedByMaxCorrection)
        assertFalse(
            "없던 표시를 지어내면 안 된다",
            encodeCurves(legacy).contains("correction.limited="),
        )
    }

    /** 길이가 맞는 **전부 0** 은 null 과 다르다 — 「상한 아님」을 안다는 뜻이다. */
    @Test
    fun `전부 0 인 표시는 모른다가 아니라 상한 아님이다`() {
        val o = limitedOutcome()
        val text = encodeCurves(o).lineSequence().joinToString("\n") {
            if (it.startsWith("correction.limited=")) {
                "correction.limited=" + "0".repeat(o.correction.size)
            } else {
                it
            }
        }
        val back = decodeCurves(text).getOrThrow()

        assertNotNull("null 이 아니어야 한다", back.limitedByMaxCorrection)
        assertFalse("전부 false 여야 한다", back.limitedByMaxCorrection!!.any { it })
        val why = back.unsupportedReasonsKo()
        assertFalse("모른다고 하면 안 된다: $why", why.any { it.contains("까닭 정보 없음") })
    }

    /** 빈 값·짧은 값·`0`/`1` 이 아닌 글자는 모두 거절한다. */
    @Test
    fun `망가진 까닭 표시는 거절한다`() {
        val o = limitedOutcome()
        val cases = listOf("", "1", "2".repeat(o.correction.size))
        for (value in cases) {
            val text = encodeCurves(o).lineSequence().joinToString("\n") {
                if (it.startsWith("correction.limited=")) "correction.limited=$value" else it
            }
            assertTrue("「$value」는 거절해야 한다", decodeCurves(text).isFailure)
        }
    }

    @Test
    fun `까닭 표시 길이가 축과 다르면 실패한다`() {
        val o = limitedOutcome()
        val short = "0".repeat(o.correction.size - 3)
        val text = encodeCurves(o).lineSequence().joinToString("\n") {
            if (it.startsWith("correction.limited=")) "correction.limited=$short" else it
        }
        val e = decodeCurves(text).exceptionOrNull()
        assertTrue("$e", e?.message?.contains("correction.limited") == true)
    }

    /** 왕복이 **판정**을 바꾸지 않는 것도 함께 본다. */
    @Test
    fun `왕복해도 판정이 같다`() {
        val ref = DoubleArray(31) {
            if (it < 2 || it > 28) 70.0 else if (it % 2 == 0) 100.0 else 40.0
        }
        val s = CalibrationSession(referenceCalApplied = true)
        repeat(8) {
            s.record(MeasureStep.ReferenceBefore, ref)
            s.record(MeasureStep.ReferenceAfter, ref)
            s.record(MeasureStep.Target, DoubleArray(31) { 70.0 })
        }
        val r = s.result()!!
        val q = qualityFromSession(
            r, DoubleArray(31) { 0.0 }, DoubleArray(31) { 0.0 },
            referenceCalRangeHz = 20.0..20_000.0, dspVerifiedBySignal = true,
        )
        val o = calibrateFromSession(r, q).getOrThrow()
        val back = decodeCurves(encodeCurves(o)).getOrThrow()
        assertEquals(judgeCalibration(q, o).verdict, judgeCalibration(q, back).verdict)
    }

    /** 실패는 **예외가 새는 게 아니라** `Result.failure` 로 돌아와야 한다. */
    @Test
    fun `망가진 입력에 예외가 새지 않는다`() {
        val broken = listOf(
            "",
            "schemaVersion=1",
            "schemaVersion=1\naxis=",
            "schemaVersion=1\naxis=1,2,3\nreference.db=1,2",
            encodeCurves(outcome()).replace(",", ";"),
        )
        for (text in broken) {
            val r = runCatching { decodeCurves(text) }
            assertTrue("예외가 새면 안 된다: $text", r.isSuccess)
            assertTrue("실패로 돌아와야 한다: $text", r.getOrThrow().isFailure)
        }
    }
}
