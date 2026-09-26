package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.QualityVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **저장할 때의 경로와 지금의 경로가 같은가**(S23 지시서 6장).
 *
 * 틀리면 조용히 나쁘다 — 다른 마이크의 보정이 걸려도 화면은 멀쩡하고,
 * 숫자만 몇 dB 씩 틀어진다. 그래서 막는 쪽을 촘촘히 잰다.
 */
class MeasuredProfileTest {

    /**
     * **자리가 다르면 저절로 걸지 않는다**(독립 재검토 CA-R03).
     *
     * 내장 마이크의 열쇠에는 주소가 없어 하단·후면이 **같은 열쇠**다.
     * 그래서 이 판정이 유일한 방어다 — 여기서 막지 않으면 후면으로
     * 열린 채 하단의 보정이 그대로 걸린다.
     */
    @Test
    fun `마이크 자리가 다르면 저절로 걸지 않는다`() {
        val m = judgeProfileApply(
            profile(env(deviceKey = "BuiltIn|SM-S918N", address = "bottom")),
            env(deviceKey = "BuiltIn|SM-S918N", address = "back"),
        )
        assertFalse("자리가 다른데 저절로 걸린다", m.mayAutoApply)
        assertFalse("막지는 않는다 — 사람이 정할 수 있어야 한다", m.blocked)
    }

    /**
     * **모르면 「같다」가 아니다**(독립 재검토 CA-R03).
     *
     * 옛 프로파일에는 주소가 없다. 그것을 「같은 자리」로 읽으면 지금까지
     * 있던 결함이 그대로 남는다.
     */
    @Test
    fun `자리를 모르면 저절로 걸지 않는다`() {
        val m = judgeProfileApply(
            profile(env(deviceKey = "BuiltIn|SM-S918N", address = "")),
            env(deviceKey = "BuiltIn|SM-S918N", address = "back"),
        )
        assertFalse("모르는데 저절로 걸린다", m.mayAutoApply)
    }

    /**
     * **자리 경고는 내장에만 붙는다.** USB 는 열쇠에 주소가 들어 있어
     * 열쇠 비교가 이미 가른다.
     */
    @Test
    fun `USB 에는 자리 경고가 붙지 않는다`() {
        val m = judgeProfileApply(
            profile(env(deviceKey = "Usb|UMC|a", address = "", kind = MicKind.Usb)),
            env(deviceKey = "Usb|UMC|a", address = "", kind = MicKind.Usb),
        )
        assertFalse(
            "USB 에 자리 경고가 붙었다: ${m.reasonsKo}",
            m.reasonsKo.any { it.contains("마이크 자리") },
        )
    }

    private fun env(
        deviceKey: String = "BuiltIn|SM-S918N|bottom",
        address: String = "bottom",
        kind: MicKind = MicKind.BuiltIn,
        source: CaptureSource = CaptureSource.Unprocessed,
        sampleRate: Int = 48_000,
        channelCount: Int = 1,
        channelIndex: Int = 0,
        osBuild: String = "UP1A.231005.007",
    ) = ProfileEnvironment(
        deviceKey = deviceKey,
        deviceAddress = address,
        micKind = kind,
        audioSource = source,
        sampleRate = sampleRate,
        channelCount = channelCount,
        channelIndex = channelIndex,
        manufacturer = "samsung",
        model = "SM-S918N",
        osBuild = osBuild,
    )

    private fun profile(
        environment: ProfileEnvironment = env(),
        separation: MicSeparation = MicSeparation.Separable,
        verdict: QualityVerdict = QualityVerdict.Pass,
        enabled: Boolean = true,
        schemaVersion: Int = PROFILE_SCHEMA_VERSION,
        algorithmVersion: Int = CALIBRATION_ALGORITHM_VERSION,
        caseRemoved: Boolean? = null,
    ) = MeasuredProfile(
        id = "11111111-2222-3333-4444-555555555555",
        schemaVersion = schemaVersion,
        algorithmVersion = algorithmVersion,
        createdAtEpochMs = 1_700_000_000_000L,
        updatedAtEpochMs = 1_700_000_000_000L,
        environment = environment,
        separation = separation,
        reference = ReferenceRecord(
            calFileName = "17860.txt",
            calSha256 = "abc123",
            inputChannelIndex = 0,
            micName = "EMM-6",
        ),
        quality = ProfileQuality(
            verdict = verdict,
            repeatStdevDb = 0.8,
            referenceDriftDb = 0.2,
            usableBandRatio = 0.9,
            worstSnrDb = 14.0,
            dspVerifiedBySignal = true,
        ),
        levelOffsetDb = -23.4,
        normalizeBandLowHz = 300.0,
        normalizeBandHighHz = 3_000.0,
        smoothingFraction = 6.0,
        maxCorrectionDb = 12.0,
        validFromHz = 50.0,
        validToHz = 16_000.0,
        curvesFileName = "profile-1111.curves",
        caseRemoved = caseRemoved,
        enabled = enabled,
    )

    // ------------------------------------------------------------------
    // 같으면 건다
    // ------------------------------------------------------------------

    @Test
    fun `경로가 그대로면 건다`() {
        val m = judgeProfileApply(profile(), env())
        assertEquals(ProfileApply.Apply, m.apply)
        assertTrue("걸 수 있을 때는 할 말이 없다", m.reasonsKo.isEmpty())
        assertTrue(m.mayAutoApply)
    }

    // ------------------------------------------------------------------
    // 막는 자리
    // ------------------------------------------------------------------

    /**
     * **가장 중요한 시험.** 하단에서 잰 보정을 후면에 거는 것이 이 기능
     * 전체에서 가장 나쁜 실패다 — 두 마이크는 이름이 같아서 주소로만
     * 갈린다(실기기 확인).
     */
    @Test
    fun `다른 내장 마이크에는 걸지 않는다`() {
        val bottom = profile(env(deviceKey = "BuiltIn|SM-S918N|bottom", address = "bottom"))
        val nowBack = env(deviceKey = "BuiltIn|SM-S918N|back", address = "back")

        val m = judgeProfileApply(bottom, nowBack)
        assertEquals(ProfileApply.Block, m.apply)
        assertFalse(m.mayAutoApply)
        assertTrue(
            "어느 마이크인지 말해 줘야 한다: ${m.reasonsKo}",
            m.reasonsKo.any { it.contains("하단") && it.contains("후면") },
        )
    }

    @Test
    fun `입력 경로가 바뀌면 막는다`() {
        val m = judgeProfileApply(
            profile(env(source = CaptureSource.Unprocessed)),
            env(source = CaptureSource.Mic),
        )
        assertEquals(ProfileApply.Block, m.apply)
        assertTrue(m.reasonsKo.any { it.contains("입력 경로") })
    }

    @Test
    fun `여러 채널일 때 채널이 바뀌면 막는다`() {
        val onCh0 = profile(
            env(deviceKey = "Usb|UMC404HD|", address = "", kind = MicKind.Usb, channelCount = 4),
        )
        val nowCh2 = env(
            deviceKey = "Usb|UMC404HD|", address = "", kind = MicKind.Usb,
            channelCount = 4, channelIndex = 2,
        )
        val m = judgeProfileApply(onCh0, nowCh2)
        assertEquals(ProfileApply.Block, m.apply)
        assertTrue(
            "몇 번 입력인지 사람이 세는 수로: ${m.reasonsKo}",
            m.reasonsKo.any { it.contains("1번") && it.contains("3번") },
        )
    }

    /**
     * **모노에서는 채널이 신원이 아니다.**
     *
     * [CalibrationKey.of] 가 그렇게 정해 두었다. 여기서 따로 세면 두 규칙이
     * 갈라지고, 지금까지 저장된 내장 마이크 프로파일이 전부 막힌다.
     */
    @Test
    fun `모노면 채널 번호가 달라도 막지 않는다`() {
        val m = judgeProfileApply(
            profile(env(channelCount = 1, channelIndex = 0)),
            env(channelCount = 1, channelIndex = 1),
        )
        assertEquals(ProfileApply.Apply, m.apply)
    }

    @Test
    fun `샘플레이트가 다르면 막는다`() {
        val m = judgeProfileApply(profile(env(sampleRate = 48_000)), env(sampleRate = 44_100))
        assertEquals(ProfileApply.Block, m.apply)
        assertTrue(
            "두 값을 다 보여 줘야 한다: ${m.reasonsKo}",
            m.reasonsKo.any { it.contains("48000") && it.contains("44100") },
        )
    }

    @Test
    fun `꺼 둔 프로파일은 걸지 않는다`() {
        val m = judgeProfileApply(profile(enabled = false), env())
        assertEquals(ProfileApply.Block, m.apply)
        assertTrue(m.reasonsKo.any { it.contains("꺼 두었") })
    }

    /** 지시서 4장: 기준을 통과하지 못하면 자동 적용을 막는다. */
    @Test
    fun `품질이 통과가 아니면 자동으로 걸지 않는다`() {
        for (v in listOf(QualityVerdict.Degraded, QualityVerdict.Fail)) {
            val m = judgeProfileApply(profile(verdict = v), env())
            assertEquals("$v 는 막아야 한다", ProfileApply.Block, m.apply)
            assertTrue(
                "어느 판정인지 적어야 한다: ${m.reasonsKo}",
                m.reasonsKo.any { it.contains(v.labelKo) },
            )
        }
    }

    @Test
    fun `더 새 판에서 만든 프로파일은 읽지 않는다`() {
        val m = judgeProfileApply(profile(schemaVersion = PROFILE_SCHEMA_VERSION + 1), env())
        assertEquals(ProfileApply.Block, m.apply)
        assertTrue(m.reasonsKo.any { it.contains("새 판") })
    }

    /** **막는 까닭이 여럿이면 다 적는다** — 하나만 고치고 또 막히지 않게. */
    @Test
    fun `막는 까닭을 전부 적는다`() {
        val m = judgeProfileApply(
            profile(env(sampleRate = 48_000), verdict = QualityVerdict.Fail, enabled = false),
            env(sampleRate = 44_100, source = CaptureSource.Mic),
        )
        assertEquals(ProfileApply.Block, m.apply)
        assertTrue("네 가지가 다 있어야 한다: ${m.reasonsKo}", m.reasonsKo.size >= 4)
        assertTrue(m.reasonsKo.any { it.contains("입력 경로") })
        assertTrue(m.reasonsKo.any { it.contains("샘플레이트") })
        assertTrue(m.reasonsKo.any { it.contains("꺼 두었") })
        assertTrue(m.reasonsKo.any { it.contains("품질") })
    }

    // ------------------------------------------------------------------
    // 걸되 알리는 자리
    // ------------------------------------------------------------------

    @Test
    fun `OS 가 바뀌면 걸되 재검증을 권한다`() {
        val m = judgeProfileApply(
            profile(env(osBuild = "UP1A.231005.007")),
            env(osBuild = "AP3A.240905.015"),
        )
        assertEquals(ProfileApply.ApplyWithWarning, m.apply)
        assertFalse("자동 적용은 아니다", m.mayAutoApply)
        assertFalse("그렇다고 막지도 않는다", m.blocked)
        assertTrue(m.reasonsKo.any { it.contains("재검증") })
    }

    /** OS 빌드를 적어 두지 않았으면 **바뀐 줄도 모른다** — 없는 것으로 겁주지 않는다. */
    @Test
    fun `OS 빌드를 모르면 경고하지 않는다`() {
        val m = judgeProfileApply(profile(env(osBuild = "")), env(osBuild = ""))
        assertEquals(ProfileApply.Apply, m.apply)
    }

    @Test
    fun `옛 계산으로 만든 보정은 다시 재기를 권한다`() {
        val m = judgeProfileApply(
            profile(algorithmVersion = CALIBRATION_ALGORITHM_VERSION - 1),
            env(),
        )
        assertEquals(ProfileApply.ApplyWithWarning, m.apply)
        assertTrue(m.reasonsKo.any { it.contains("옛 계산") })
    }

    /**
     * **후면은 케이스를 확인할 길이 없다.**
     *
     * 안드로이드에 케이스를 묻는 API 가 없으므로, 적어 둔 것을 걸 때마다
     * 되짚어 주는 것이 우리가 할 수 있는 전부다.
     */
    @Test
    fun `후면 마이크는 케이스 상태를 늘 되짚는다`() {
        val rear = env(deviceKey = "BuiltIn|SM-S918N|back", address = "back")
        for (state in listOf(true, false, null)) {
            val m = judgeProfileApply(profile(rear, caseRemoved = state), rear)
            assertEquals("케이스=$state", ProfileApply.ApplyWithWarning, m.apply)
            assertTrue(
                "케이스를 말해야 한다: ${m.reasonsKo}",
                m.reasonsKo.any { it.contains("케이스") },
            )
        }
    }

    @Test
    fun `하단 마이크에는 케이스 이야기를 하지 않는다`() {
        val m = judgeProfileApply(profile(), env())
        assertFalse(m.reasonsKo.any { it.contains("케이스") })
    }

    /** 막을 때도 **알림은 함께 보여 준다** — 고치고 나서 또 걸리지 않게. */
    @Test
    fun `막을 때도 알림을 함께 적는다`() {
        val rear = env(deviceKey = "BuiltIn|SM-S918N|back", address = "back")
        val m = judgeProfileApply(profile(rear, enabled = false), rear)
        assertEquals(ProfileApply.Block, m.apply)
        assertTrue(m.reasonsKo.any { it.contains("꺼 두었") })
        assertTrue("알림도 남아야 한다: ${m.reasonsKo}", m.reasonsKo.any { it.contains("케이스") })
    }

    // ------------------------------------------------------------------
    // 이름 붙이기 (지시서 1장)
    // ------------------------------------------------------------------

    /** 갈라진다는 확인이 **있을 때만** 물리 위치를 적는다. */
    @Test
    fun `갈라짐이 확인되면 위치를 적는다`() {
        val p = profile(
            env(deviceKey = "BuiltIn|SM-S918N|back", address = "back"),
            separation = MicSeparation.Separable,
        )
        assertEquals("내장 마이크 (후면)", p.labelKo)
    }

    @Test
    fun `갈라짐이 확인되지 않으면 위치를 적지 않는다`() {
        for (s in listOf(MicSeparation.LogicalOnly, MicSeparation.Indistinguishable)) {
            val p = profile(
                env(deviceKey = "BuiltIn|SM-S918N|back", address = "back"),
                separation = s,
            )
            assertFalse("$s 에서 「후면」이라 부르면 안 된다: ${p.labelKo}", p.labelKo.contains("후면"))
            assertFalse(p.labelKo.contains("하단"))
        }
    }

    @Test
    fun `여러 채널이면 입력 번호로 부른다`() {
        val p = profile(
            env(
                deviceKey = "Usb|UMC404HD|", address = "", kind = MicKind.Usb,
                channelCount = 4, channelIndex = 2,
            ),
            separation = MicSeparation.LogicalOnly,
        )
        assertEquals("USB 마이크 · 입력 3", p.labelKo)
    }

    @Test
    fun `이름에 별표가 새지 않는다`() {
        val rear = env(deviceKey = "BuiltIn|SM-S918N|back", address = "back")
        val m = judgeProfileApply(profile(rear, enabled = false), env(sampleRate = 44_100))
        (m.reasonsKo + profile().labelKo).forEach {
            assertFalse(it, it.contains("*"))
        }
    }

    // ------------------------------------------------------------------
    // 신원 규칙이 CalibrationKey 와 같은 것을 쓰는가
    // ------------------------------------------------------------------

    @Test
    fun `환경의 열쇠는 보정 열쇠와 같다`() {
        val e = env(channelCount = 4, channelIndex = 2)
        val k = e.key()
        assertEquals(e.deviceKey, k.deviceKey)
        assertEquals(e.audioSource, k.source)
        assertEquals(2, k.channelIndex)

        val mono = env(channelCount = 1, channelIndex = 3).key()
        assertEquals("모노면 채널을 열쇠에 넣지 않는다", null, mono.channelIndex)
    }
}
