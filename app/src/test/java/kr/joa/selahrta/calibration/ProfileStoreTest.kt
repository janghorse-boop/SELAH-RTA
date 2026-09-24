package kr.joa.selahrta.calibration

import kotlinx.coroutines.runBlocking
import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.CurvePoint
import kr.joa.selahrta.dsp.QualityVerdict
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.calibrateResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * **디스크를 실제로 쓴다.**
 *
 * [ProfileStore] 가 [android.content.Context] 대신 폴더를 받는 까닭이
 * 여기 있다 — 안드로이드를 띄워야만 확인되는 코드는 결국 확인되지
 * 않는다. 여기서는 진짜 임시 폴더에 진짜 파일을 쓰고, 손으로 망가뜨린
 * 뒤 다시 읽는다.
 */
class ProfileStoreTest {

    private fun tempDir(): File =
        Files.createTempDirectory("selah-profiles").toFile().also { it.deleteOnExit() }

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
        id: String = "11111111-2222-3333-4444-555555555555",
        createdAt: Long = 1_700_000_000_000L,
        enabled: Boolean = true,
        curvesFileName: String = ProfileStore.curvesFileNameFor(id),
    ) = MeasuredProfile(
        id = id,
        createdAtEpochMs = createdAt,
        updatedAtEpochMs = createdAt,
        environment = env(),
        separation = MicSeparation.Separable,
        reference = ReferenceRecord("17860.txt", "abc123", 0, "EMM-6"),
        quality = ProfileQuality(
            verdict = QualityVerdict.Pass,
            repeatStdevDb = 0.8,
            referenceDriftDb = -0.2,
            usableBandRatio = 0.9,
            worstSnrDb = 13.75,
            dspVerifiedBySignal = true,
        ),
        levelOffsetDb = -23.4,
        normalizeBandLowHz = 300.0,
        normalizeBandHighHz = 3_000.0,
        smoothingFraction = 6.0,
        maxCorrectionDb = 12.0,
        validFromHz = 50.0,
        validToHz = 16_000.0,
        curvesFileName = curvesFileName,
        caseRemoved = true,
        enabled = enabled,
    )

    private fun outcome() = calibrateResponse(
        referencePoints = (0 until ThirdOctave.BAND_COUNT).map {
            CurvePoint(ThirdOctave.exactCenter(it), 70.0)
        },
        internalPoints = (0 until ThirdOctave.BAND_COUNT).map {
            CurvePoint(ThirdOctave.exactCenter(it), 50.0 + if (it >= 24) -6.0 else 0.0)
        },
    )

    // ------------------------------------------------------------------
    // 왕복
    // ------------------------------------------------------------------

    @Test
    fun `저장한 것이 파일을 거쳐 그대로 돌아온다`() = runBlocking {
        val store = ProfileStore(tempDir())
        val p = profile()
        store.save(p, outcome()).getOrThrow()

        val listed = store.list()
        assertEquals(1, listed.size)
        val ok = listed.single() as StoredProfile.Ok
        assertEquals(p, ok.profile)
    }

    @Test
    fun `곡선도 파일을 거쳐 그대로 돌아온다`() = runBlocking {
        val store = ProfileStore(tempDir())
        val p = profile()
        val o = outcome()
        store.save(p, o).getOrThrow()

        val back = store.loadCurves(p).getOrThrow()
        assertArrayAlmost(o.correction.db, back.correction.db)
        assertArrayAlmost(o.correction.hz, back.correction.hz)
        assertArrayAlmost(o.reference.db, back.reference.db)
    }

    /**
     * 그 자리에 **쓰지 못하게** 막는다.
     *
     * **빈 폴더로는 막히지 않는다.** 이 JVM(Windows)에서 `renameTo` 는
     * 같은 이름의 **빈 폴더를 그냥 갈아치운다** — 직접 돌려 확인했다.
     * 폴더 안에 파일이 하나라도 있어야 옮기기가 실패한다. 그러니 이
     * 줄을 「폴더만 만들면 되지」로 줄이면 시험이 **조용히 아무것도 재지
     * 않게** 된다.
     */
    private fun blockWriting(target: File) {
        assertTrue(target.mkdirs())
        File(target, "in-the-way").writeText("이 파일이 옮기기를 막는다")
    }

    private fun assertArrayAlmost(a: DoubleArray, b: DoubleArray) {
        assertEquals("길이", a.size, b.size)
        for (i in a.indices) assertEquals("[$i]", a[i], b[i], 1e-9)
    }

    /**
     * **목록은 곡선을 읽지 않는다.**
     *
     * 곡선 파일이 통째로 깨져도 목록은 나와야 한다 — 그러지 않으면
     * 파일 하나 때문에 「다시 재기」로 가는 길까지 막힌다.
     */
    @Test
    fun `곡선이 깨져도 목록은 나온다`() = runBlocking {
        val dir = tempDir()
        val store = ProfileStore(dir)
        val p = profile()
        store.save(p, outcome()).getOrThrow()
        File(dir, p.curvesFileName).writeText("이건 곡선이 아니다")

        assertTrue(store.list().single() is StoredProfile.Ok)
        assertTrue("곡선은 실패해야 한다", store.loadCurves(p).isFailure)
    }

    // ------------------------------------------------------------------
    // 못 읽는 파일
    // ------------------------------------------------------------------

    @Test
    fun `망가진 파일은 지워지지 않고 까닭과 함께 남는다`() = runBlocking {
        val dir = tempDir()
        val store = ProfileStore(dir)
        File(dir, "broken.profile").writeText("schemaVersion=1\n아무것도 없다\n")

        val damaged = store.list().single() as StoredProfile.Damaged
        assertEquals("broken.profile", damaged.fileName)
        assertTrue("까닭이 있어야 한다", damaged.reasonKo.isNotBlank())
        assertTrue("파일은 남아 있어야 한다", File(dir, "broken.profile").exists())
    }

    @Test
    fun `더 새 판은 망가진 것으로 오지 손실되지 않는다`() = runBlocking {
        val dir = tempDir()
        val store = ProfileStore(dir)
        val text = encodeProfile(profile()).replace("schemaVersion=1", "schemaVersion=99")
        File(dir, "future.profile").writeText(text)

        val damaged = store.list().single() as StoredProfile.Damaged
        assertTrue(damaged.reasonKo, damaged.reasonKo.contains("v99"))
        assertTrue(File(dir, "future.profile").exists())
    }

    /**
     * **파일 안에 적힌 이름을 그대로 믿지 않는다.**
     *
     * `curvesFileName` 은 파일에서 읽은 값이다. 손댄 파일이 `../` 를
     * 담고 있으면 앱 폴더 밖을 가리킨다.
     */
    @Test
    fun `곡선 이름이 폴더를 벗어나면 망가진 것으로 본다`() = runBlocking {
        val dir = tempDir()
        val store = ProfileStore(dir)
        val p = profile()
        val text = encodeProfile(p)
            .replace("curvesFileName=${p.curvesFileName}", "curvesFileName=../../secret.txt")
        File(dir, "evil.profile").writeText(text)

        val damaged = store.list().single() as StoredProfile.Damaged
        assertTrue(damaged.reasonKo, damaged.reasonKo.contains("이름"))
    }

    /**
     * **밖에 진짜 읽히는 파일을 둔다.**
     *
     * 없는 자리를 가리키게 해 두면 「파일이 없어서」 실패하고, 검사를
     * 통째로 빼도 시험은 그대로 통과한다 — 돌연변이로 확인했다. 그러니
     * 벗어난 경로가 **실제로 가리키는 자리**에 읽을 수 있는 곡선 파일을
     * 두고, 실패한 **까닭**까지 본다.
     */
    @Test
    fun `폴더를 벗어나는 이름은 곡선도 읽지 않는다`() = runBlocking {
        val root = tempDir()
        val store = ProfileStore(File(root, "profiles"))
        val outside = File(root, "secret.curves")
        outside.writeText(encodeCurves(outcome()))
        val evil = profile(curvesFileName = "../secret.curves")
        assertEquals(
            "시험이 밖을 가리키지 않는다",
            outside.canonicalFile,
            File(File(root, "profiles"), evil.curvesFileName).canonicalFile,
        )

        val r = store.loadCurves(evil)
        assertTrue("폴더 밖 파일을 읽었다", r.isFailure)
        assertTrue(
            r.exceptionOrNull()?.message.orEmpty(),
            r.exceptionOrNull()?.message.orEmpty().contains("이상합니다"),
        )
    }

    @Test
    fun `폴더를 벗어나는 id 는 저장하지 않는다`() = runBlocking {
        val root = tempDir()
        val store = ProfileStore(File(root, "profiles"))
        val evilId = "../escaped"
        val r = store.save(
            profile(id = evilId, curvesFileName = ProfileStore.curvesFileNameFor(evilId)),
            outcome(),
        )

        assertTrue("밖으로 저장됐다", r.isFailure)
        assertFalse(File(root, "escaped.profile").exists())
        assertFalse(File(root, "escaped.curves").exists())
    }

    @Test
    fun `곡선 파일이 없으면 빈 곡선이 아니라 실패다`() = runBlocking {
        val dir = tempDir()
        val store = ProfileStore(dir)
        val p = profile()
        store.save(p, outcome()).getOrThrow()
        assertTrue(File(dir, p.curvesFileName).delete())

        val r = store.loadCurves(p)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()?.message.orEmpty().contains("없습니다"))
    }

    // ------------------------------------------------------------------
    // 이름 규칙
    // ------------------------------------------------------------------

    @Test
    fun `곡선 이름이 프로파일과 맞지 않으면 저장하지 않는다`() = runBlocking {
        val store = ProfileStore(tempDir())
        val r = store.save(profile(curvesFileName = "somebody-elses.curves"), outcome())
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()?.message.orEmpty().contains("맞지 않습니다"))
    }

    @Test
    fun `프로파일을 짓는 길이 저장소와 같은 이름을 쓴다`() {
        val p = profile(id = "abc-123")
        assertEquals("abc-123.curves", ProfileStore.curvesFileNameFor(p.id))
    }

    // ------------------------------------------------------------------
    // 지우기 · 켜고 끄기
    // ------------------------------------------------------------------

    @Test
    fun `지우면 곡선까지 함께 사라진다`() = runBlocking {
        val dir = tempDir()
        val store = ProfileStore(dir)
        val p = profile()
        store.save(p, outcome()).getOrThrow()
        val name = (store.list().single() as StoredProfile.Ok).fileName

        assertTrue(store.delete(name))
        assertTrue(store.list().isEmpty())
        assertFalse("곡선이 남았다", File(dir, p.curvesFileName).exists())
    }

    /** 못 읽는 파일도 지울 수 있어야 한다 — 아니면 목록에 영영 남는다. */
    @Test
    fun `망가진 파일도 지울 수 있다`() = runBlocking {
        val dir = tempDir()
        val store = ProfileStore(dir)
        File(dir, "broken.profile").writeText("쓰레기")
        File(dir, "broken.curves").writeText("쓰레기")

        assertTrue(store.delete("broken.profile"))
        assertTrue(store.list().isEmpty())
        assertFalse(File(dir, "broken.curves").exists())
    }

    @Test
    fun `폴더를 벗어나는 이름은 지우지 않는다`() = runBlocking {
        val root = tempDir()
        val store = ProfileStore(File(root, "profiles"))
        val outside = File(root, "outside.profile")
        outside.writeText("건드리면 안 된다")
        // **시험이 정말 밖을 가리키는지 먼저 못 박는다.** 엉뚱한 자리를
        // 가리키면 「없어서 실패」하고, 검사를 빼도 통과한다.
        assertEquals(
            "시험이 밖을 가리키지 않는다",
            outside.canonicalFile,
            File(File(root, "profiles"), "../outside.profile").canonicalFile,
        )

        assertFalse(store.delete("../outside.profile"))
        assertTrue("밖의 파일이 지워졌다", outside.exists())
    }

    @Test
    fun `끄면 파일에 남고 곡선은 그대로다`() = runBlocking {
        val dir = tempDir()
        val store = ProfileStore(dir)
        val p = profile()
        store.save(p, outcome()).getOrThrow()
        val curvesBefore = File(dir, p.curvesFileName).readText()

        store.setEnabled(p, enabled = false, nowEpochMs = 1_800_000_000_000L).getOrThrow()

        val back = (store.list().single() as StoredProfile.Ok).profile
        assertFalse(back.enabled)
        assertEquals(1_800_000_000_000L, back.updatedAtEpochMs)
        assertEquals("곡선이 바뀌었다", curvesBefore, File(dir, p.curvesFileName).readText())
    }

    // ------------------------------------------------------------------
    // 목록
    // ------------------------------------------------------------------

    @Test
    fun `최근 것이 앞에 오고 망가진 것은 뒤로 간다`() = runBlocking {
        val dir = tempDir()
        val store = ProfileStore(dir)
        val old = profile(id = "aaaa-1111", createdAt = 1_000L)
        val recent = profile(id = "bbbb-2222", createdAt = 9_000L)
        store.save(old, outcome()).getOrThrow()
        store.save(recent, outcome()).getOrThrow()
        File(dir, "broken.profile").writeText("쓰레기")

        val listed = store.list()
        assertEquals(3, listed.size)
        assertEquals("bbbb-2222", (listed[0] as StoredProfile.Ok).profile.id)
        assertEquals("aaaa-1111", (listed[1] as StoredProfile.Ok).profile.id)
        assertTrue(listed[2] is StoredProfile.Damaged)
    }

    @Test
    fun `두 프로파일이 서로의 곡선을 덮지 않는다`() = runBlocking {
        val dir = tempDir()
        val store = ProfileStore(dir)
        val rear = profile(id = "aaaa-1111")
        val bottom = profile(id = "bbbb-2222")
        store.save(rear, outcome()).getOrThrow()
        store.save(bottom, outcome()).getOrThrow()

        assertTrue(store.delete(ProfileStore.profileFileNameFor(rear.id)))
        // 하나를 지워도 다른 하나의 곡선은 남아 있어야 한다(지시서 6장:
        // 「후면·하단 프로파일은 서로 덮어쓰지 않으며」).
        assertNotNull(store.loadCurves(bottom).getOrNull())
    }

    @Test
    fun `남은 임시 파일은 목록에 끼지 않는다`() = runBlocking {
        val dir = tempDir()
        val store = ProfileStore(dir)
        store.save(profile(), outcome()).getOrThrow()
        File(dir, "x.profile.12345.tmp").writeText("반쯤 쓰인 것")

        assertEquals(1, store.list().size)
    }

    // ------------------------------------------------------------------
    // 실패했을 때
    // ------------------------------------------------------------------

    /**
     * **곡선을 먼저 쓰는 까닭.**
     *
     * 프로파일이 먼저 놓이면 그 사이에 죽었을 때 곡선 없는 프로파일이
     * 남는다 — 목록에는 멀쩡히 보이면서 열면 비어 있다. 여기서는
     * 프로파일 자리에 폴더를 두어 쓰기를 실패시키고, 그때 **이번에 만든
     * 곡선이 남지 않는지**를 본다.
     */
    @Test
    fun `프로파일 쓰기가 실패하면 새로 만든 곡선을 걷어낸다`() = runBlocking {
        val dir = tempDir()
        val store = ProfileStore(dir)
        val p = profile()
        blockWriting(File(dir, ProfileStore.profileFileNameFor(p.id)))

        val r = store.save(p, outcome())
        assertTrue("실패해야 한다", r.isFailure)
        assertFalse("곡선이 남았다", File(dir, p.curvesFileName).exists())
    }

    /**
     * **차례를 못 박는다.**
     *
     * 위 시험은 「실패하면 곡선이 안 남는다」만 본다 — 프로파일을 먼저
     * 쓰도록 바꿔도 그 시험은 통과한다(곡선이 아예 안 쓰이니까). 차례가
     * 뒤집힌 것을 잡으려면 **곡선 쓰기를 실패시키고 프로파일이 남지
     * 않는지**를 봐야 한다. 곡선 없는 프로파일은 목록에서 멀쩡해 보이면서
     * 열면 비어 있다.
     */
    @Test
    fun `곡선을 쓰지 못하면 프로파일도 남지 않는다`() = runBlocking {
        val dir = tempDir()
        val store = ProfileStore(dir)
        val p = profile()
        blockWriting(File(dir, p.curvesFileName))

        assertTrue("실패해야 한다", store.save(p, outcome()).isFailure)
        assertFalse(
            "곡선 없는 프로파일이 남았다",
            File(dir, ProfileStore.profileFileNameFor(p.id)).isFile,
        )
    }

    /** 먼저 있던 곡선은 실패해도 **지우지 않는다.** */
    @Test
    fun `다시 저장하다 실패해도 예전 곡선은 남는다`() = runBlocking {
        val dir = tempDir()
        val store = ProfileStore(dir)
        val p = profile()
        store.save(p, outcome()).getOrThrow()

        val target = File(dir, ProfileStore.profileFileNameFor(p.id))
        assertTrue(target.delete())
        blockWriting(target)

        assertTrue(store.save(p, outcome()).isFailure)
        assertTrue("예전 곡선이 사라졌다", File(dir, p.curvesFileName).exists())
    }

    @Test
    fun `폴더가 아직 없어도 목록은 비어 있을 뿐이다`() = runBlocking {
        val store = ProfileStore(File(tempDir(), "아직-없는-폴더"))
        assertTrue(store.list().isEmpty())
        assertNull(store.list().firstOrNull())
    }
}
