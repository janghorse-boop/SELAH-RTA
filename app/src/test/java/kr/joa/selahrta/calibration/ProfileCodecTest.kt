package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.CurvePoint
import kr.joa.selahrta.dsp.QualityVerdict
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.calibrateResponse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
