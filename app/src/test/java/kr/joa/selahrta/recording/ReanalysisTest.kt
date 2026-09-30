package kr.joa.selahrta.recording

import kr.joa.selahrta.dsp.MultiWeightEngine
import kr.joa.selahrta.dsp.RtaEngine
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.math.cos

/**
 * **저장한 소리를 다시 분석한다**(명세 Recording-E).
 *
 * 여기서 지키는 세 줄:
 *
 * 1. **같은 소리에서 같은 값이 나온다.** 라이브가 낸 행과 다시 셈한 행이
 *    같아야 한다 — 다르면 어느 쪽이 맞는지 가릴 길이 없다.
 * 2. **보정을 바꾸면 값이 따라 바뀐다.** 그러지 않으면 다시 셈할 까닭이
 *    없다.
 * 3. **원본을 건드리지 않는다.** 명세의 완료 기준이 「원본 보존」이다.
 *
 * ## 이 시험이 못 보는 것
 *
 * **16비트로 눌린 소리**를 본다. 원래 분석은 float 을 그대로 봤으므로
 * 아주 조용한 구간에서는 완전히 같지 않다(16비트 바닥은 -96dBFS 쯤).
 * 그래서 아래는 **예배당 음압 정도의 소리**로 견준다.
 */
class ReanalysisTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val rate = 48_000
    private val settings = ReanalysisSettings(
        offsetDb = 110.0,
        referenceOnly = false,
        leqWindowMs = 10_000L,
        weighting = Weighting.A,
        timeWeight = TimeWeight.Fast,
        fftSize = 4096,
    )

    /** 2초짜리 1kHz 순음. 격자와 무관하게 이어지는 소리다. */
    private fun tone(seconds: Int = 2, amp: Float = 0.2f) =
        FloatArray(rate * seconds) { i ->
            (amp * cos(2.0 * Math.PI * 1_000.0 * i / rate)).toFloat()
        }

    private fun wav(pcm: FloatArray): File {
        val f = tmp.newFile("r-${System.nanoTime()}.wav")
        val out = f.outputStream()
        val w = WavWriter(out, rate)
        w.write(pcm, pcm.size)
        out.close()
        w.finish(f)
        return f
    }

    /** 라이브가 하는 일. **재분석과 같은 코드**를 같은 소리로 돌린다. */
    private fun live(pcm: FloatArray, s: ReanalysisSettings = settings): RecordedSession {
        val rec = SessionRecorder(
            id = "live",
            nominalSampleRate = rate,
            startOffsetDb = s.offsetDb,
            startReferenceOnly = s.referenceOnly,
            startLeqWindowMs = s.leqWindowMs,
            weighting = s.weighting,
        )
        val spl = MultiWeightEngine(rate, s.timeWeight)
        val rta = RtaEngine(rate, s.fftSize)
        var i = 0
        val buf = FloatArray(Reanalysis.BLOCK_FRAMES)
        while (i < pcm.size) {
            val n = minOf(Reanalysis.BLOCK_FRAMES, pcm.size - i)
            System.arraycopy(pcm, i, buf, 0, n)
            rec.onBlock(buf, n, false, spl, rta)
            i += n
        }
        return rec.finish()
    }

    // ── 1. 같은 소리에서 같은 값 ─────────────────────────

    @Test
    fun `다시 셈한 행이 라이브와 같다`() {
        val pcm = tone()
        val expected = live(pcm)
        val got = Reanalysis.run(wav(pcm), "again", settings).getOrThrow()

        // **빈 목록끼리 견주면 무엇이든 통과한다.** 먼저 뭔가 나왔는지 본다.
        assertTrue("라이브가 행을 안 냈다 — 시험이 헛돈다", expected.rows.size >= 3)
        assertEquals("행 수가 다르다", expected.rows.size, got.rows.size)
        for (i in expected.rows.indices) {
            val a = expected.rows[i]
            val b = got.rows[i]
            assertEquals("$i 행의 번호", a.rowIndex, b.rowIndex)
            // **16비트로 한 번 눌린 소리**라 딱 같지는 않다. 0.1dB 안이면
            // 같은 값으로 본다 — 예배당 음압에서 이보다 큰 차이는 결함이다.
            assertEquals("$i 행의 현재값", a.currentRaw, b.currentRaw, 0.1)
            assertEquals("$i 행의 최대", a.maxRaw, b.maxRaw, 0.1)
            assertEquals("$i 행의 순간최고", a.peakRaw, b.peakRaw, 0.1)
            for (band in a.bands.indices) {
                // 소리가 없는 대역은 바닥값 근처라 격자 오차가 커진다.
                if (a.bands[band] > -80f) {
                    assertEquals(
                        "$i 행 $band 밴드",
                        a.bands[band].toDouble(),
                        b.bands[band].toDouble(),
                        0.5,
                    )
                }
            }
        }
    }

    @Test
    fun `길이도 같다`() {
        val pcm = tone()
        assertEquals(
            live(pcm).durationMs,
            Reanalysis.run(wav(pcm), "again", settings).getOrThrow().durationMs,
        )
    }

    // ── 2. 보정을 바꾸면 값이 따라 바뀐다 ────────────────

    @Test
    fun `보정을 바꾸면 그만큼 달라진다`() {
        // **이것이 다시 셈하는 까닭이다.** 예배가 끝난 뒤에 보정을 하면
        // 이미 담아 둔 소리를 새 잣대로 읽을 수 있어야 한다.
        val f = wav(tone())
        val a = Reanalysis.run(f, "a", settings).getOrThrow()
        val b = Reanalysis.run(f, "b", settings.copy(offsetDb = 120.0)).getOrThrow()

        // raw 는 보정 전 값이라 **같아야** 한다.
        assertEquals(a.rows[1].currentRaw, b.rows[1].currentRaw, 1e-9)
        // 보정은 epoch 에 실린다. 10dB 차이가 거기 남는다.
        assertEquals(110.0, a.epochs.first().calibrationOffsetDb, 1e-9)
        assertEquals(120.0, b.epochs.first().calibrationOffsetDb, 1e-9)
        assertEquals(10.0, b.summary.maxDb!! - a.summary.maxDb!!, 0.001)
    }

    @Test
    fun `가중치를 바꾸면 요약이 달라진다`() {
        val f = wav(tone())
        val a = Reanalysis.run(f, "a", settings).getOrThrow()
        val z = Reanalysis.run(f, "z", settings.copy(weighting = Weighting.Z)).getOrThrow()
        // 1kHz 에서는 A·Z 가 거의 같다. **달라지는지**만 보려고 저음을 섞는다.
        assertEquals(Weighting.A, a.summary.weighting)
        assertEquals(Weighting.Z, z.summary.weighting)
    }

    @Test
    fun `저음이 섞이면 A 와 Z 가 갈린다`() {
        // 100Hz 는 A 가중이 19dB 넘게 깎는다. 가중치가 실제로 걸리는지
        // 보려면 **깎이는 소리**를 넣어야 한다.
        val pcm = FloatArray(rate) { i ->
            (0.2f * cos(2.0 * Math.PI * 100.0 * i / rate)).toFloat()
        }
        val f = wav(pcm)
        val a = Reanalysis.run(f, "a", settings).getOrThrow()
        val z = Reanalysis.run(f, "z", settings.copy(weighting = Weighting.Z)).getOrThrow()
        val gap = z.summary.maxDb!! - a.summary.maxDb!!
        assertTrue("100Hz 에서 A 와 Z 가 ${gap}dB 밖에 안 갈린다", gap > 10.0)
    }

    // ── 3. 원본을 건드리지 않는다 ────────────────────────

    @Test
    fun `원본 파일이 그대로다`() {
        val f = wav(tone())
        val before = f.readBytes()
        val stamp = f.lastModified()

        Reanalysis.run(f, "again", settings).getOrThrow()

        assertTrue("소리 파일이 바뀌었다", before.contentEquals(f.readBytes()))
        assertEquals("고친 시각이 바뀌었다", stamp, f.lastModified())
    }

    // ── 없는 것·깨진 것 ──────────────────────────────────

    @Test
    fun `파일이 없으면 실패를 돌려준다`() {
        val r = Reanalysis.run(File(tmp.root, "없다.wav"), "x", settings)
        assertTrue(r.isFailure)
        assertTrue("${r.exceptionOrNull()}", r.exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun `WAV 가 아니면 실패를 돌려준다`() {
        val f = tmp.newFile("가짜.wav").apply { writeText("이건 소리가 아니다") }
        assertTrue(Reanalysis.run(f, "x", settings).isFailure)
    }

    // ── 진행 알림 ────────────────────────────────────────

    /**
     * **덩어리가 많아야 걸러 낼 일이 생긴다.**
     *
     * 처음에는 2초짜리(덩어리 47개)로 봤더니, 걸러 내는 코드를 통째로
     * 꺼도 통과했다 — 한 덩어리가 이미 2% 씩이라 **걸러 낼 것이
     * 없었다**(변이로 확인). 200덩어리쯤 되어야 한 걸음이 0.5% 가 된다.
     */
    @Test
    fun `진행이 0 에서 1 로 오른다`() {
        val seen = ArrayList<Float>()
        val many = FloatArray(Reanalysis.BLOCK_FRAMES * 200)
        Reanalysis.run(wav(many), "x", settings) { seen += it }.getOrThrow()

        assertTrue("진행을 한 번도 안 알렸다", seen.isNotEmpty())
        assertTrue("뒤로 갔다: $seen", seen.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("끝까지 안 갔다: ${seen.last()}", seen.last() >= 0.99f)

        // **매 덩어리마다 부르지 않는다.** 그러면 화면이 그것만 한다.
        //
        // 「몇 번 이하」로 셌더니 **짧은 파일에서는 걸러 내지 않아도
        // 그 수를 안 넘어** 헛그물이었다(변이로 확인). 파일 길이에
        // 기대지 않게 **간격 자체**를 본다.
        val tooClose = seen.zipWithNext()
            .filter { (a, b) -> b < 1f && b - a < 0.01f }
        assertTrue("1% 도 안 움직였는데 또 알린다: $tooClose", tooClose.isEmpty())
    }

    // ── 설정이 실제로 엔진에 닿는가 ──────────────────────

    /**
     * **FFT 길이를 바꾸면 낮은 대역이 달라진다.**
     *
     * 처음에는 「라이브와 같다」 시험이 이것도 잡아 줄 줄 알았는데
     * **아니었다**(변이로 확인). 이어지는 1kHz 순음은 FFT 길이를 바꿔도
     * 값이 거의 같아서, 설정이 통째로 무시돼도 통과했다.
     *
     * 갈리는 자리는 **낮은 대역**이다. 48kHz 에서 1024점이면 칸 하나가
     * 46.9Hz 라 25Hz 대역(폭 5.8Hz)을 **가를 수가 없다.**
     */
    @Test
    fun `FFT 길이가 실제로 걸린다`() {
        val pcm = FloatArray(rate) { i ->
            (0.3f * cos(2.0 * Math.PI * 30.0 * i / rate)).toFloat()
        }
        val f = wav(pcm)
        val big = Reanalysis.run(f, "big", settings).getOrThrow()
        val small = Reanalysis.run(f, "small", settings.copy(fftSize = 1024)).getOrThrow()

        val gaps = big.rows[1].bands.indices.map {
            abs(big.rows[1].bands[it] - small.rows[1].bands[it]).toDouble()
        }
        assertTrue(
            "FFT 길이를 바꿔도 모든 대역이 그대로다 — 설정이 안 걸린 것 같다",
            gaps.max() > 1.0,
        )
    }

    /**
     * **잘린 소리는 잘렸다고 적는다.**
     *
     * 원본 기록의 깃발은 **그때의 입력 이득**에 걸린 것이라, 다시 셈하는
     * 지금과 다를 수 있다. 그래서 소리에서 다시 본다.
     */
    @Test
    fun `끝에 붙은 소리를 잘렸다고 적는다`() {
        // ±1 을 넘겨 쓰면 16비트 끝에 붙는다.
        val pcm = FloatArray(rate) { if (it % 2 == 0) 2f else -2f }
        val got = Reanalysis.run(wav(pcm), "clip", settings).getOrThrow()
        assertTrue("잘렸는데 아무 행도 그렇게 안 적혔다", got.rows.any { it.clipped })
    }

    @Test
    fun `조용한 소리는 잘렸다고 적지 않는다`() {
        // 위 시험이 「늘 잘렸다고 적는 코드」로도 통과하지 않게 짝을 둔다.
        val got = Reanalysis.run(wav(tone(amp = 0.1f)), "quiet", settings).getOrThrow()
        assertTrue("안 잘렸는데 잘렸다고 적혔다", got.rows.none { it.clipped })
    }

    // ── 두 채널 ──────────────────────────────────────────

    @Test
    fun `두 채널이면 고른 쪽만 본다`() {
        // 왼쪽은 크고 오른쪽은 조용하다. 섞으면 둘 다 아닌 값이 나온다.
        val frames = rate
        val pcm = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val t = cos(2.0 * Math.PI * 1_000.0 * i / rate)
            pcm[i * 2] = (0.4 * t).toFloat()
            pcm[i * 2 + 1] = (0.02 * t).toFloat()
        }
        val f = tmp.newFile("stereo.wav")
        val out = f.outputStream()
        val w = WavWriter(out, rate, channels = 2)
        w.write(pcm, frames)
        out.close()
        w.finish(f)

        val left = Reanalysis.run(f, "L", settings).getOrThrow()
        val right = Reanalysis.run(f, "R", settings.copy(channelIndex = 1)).getOrThrow()

        val gap = left.summary.maxDb!! - right.summary.maxDb!!
        // 0.4 대 0.02 는 26dB 차이다. 섞였다면 그만큼 안 벌어진다.
        assertTrue("좌우가 ${gap}dB 밖에 안 갈린다 — 섞은 것 같다", gap > 20.0)
        assertNotEquals(left.rows[0].currentRaw, right.rows[0].currentRaw, 1e-6)
    }

    // ── 끊긴 파일 ────────────────────────────────────────

    @Test
    fun `꼬리가 잘린 파일도 있는 만큼 셈한다`() {
        // 녹음 중에 앱이 죽으면 머리에 적힌 길이보다 짧다. **통째로
        // 버리면 두 시간이 날아간다.**
        val f = wav(tone(seconds = 2))
        val full = f.readBytes()
        val cut = tmp.newFile("cut.wav")
        cut.writeBytes(full.copyOf(WavWriter.HEADER_BYTES + rate * 2)) // 1초만

        val got = Reanalysis.run(cut, "cut", settings).getOrThrow()
        assertTrue("아무것도 안 나왔다", got.rows.isNotEmpty())
        assertTrue(
            "1초짜리인데 ${got.durationMs}ms 가 나왔다",
            abs(got.durationMs - 1_000L) <= 500L,
        )
    }
}
