package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Dayton EMM-6 파일 형식을 그대로 읽는가**
 * (USB 오디오 지시서 9.3·9.4).
 *
 * ## 실제 파일로 확인한 것 (2026-09-23)
 *
 * 사용자의 EMM-6(시리얼 17860) 개체 파일을 실제로 넣어 봤다. **그 파일은
 * 저장소에 올리지 않는다** — `inbox/` 는 건네받은 파일을 커밋하지 않는
 * 자리이고, 남의 기기 자료를 공개 저장소에 올릴 일도 아니다. 대신 여기
 * 둔 `SYNTH-emm6-format-sample.txt` 가 **형식을 그대로 본떴다.**
 *
 * 실제 파일의 생김새:
 *
 * ```
 * *1000Hz	-41.1        ← 1kHz 감도. 주석(`*`)이라 건너뛴다
 * 	                     ← 탭 하나뿐인 줄
 * 20.00	-2.0
 * 20.55	-2.0
 * …                      256점, 20Hz~20kHz, 탭 구분, CRLF
 * ```
 *
 * 우리 파서가 **256점을 하나도 빠뜨리지 않고** 읽었다(건너뛴 줄 0).
 *
 * ## 부호 — 막혀 있던 물음의 답
 *
 * 지시서 9.4 가 *「불명확하면 임의 부호로 측정값을 변경하지 않는다」* 고
 * 해서 어제까지 막아 두었던 자리다. 실제 파일의 값:
 *
 * | 주파수 | 값 |
 * |---|---|
 * | 20Hz | **−2.0 dB** |
 * | 1kHz | +0.06 (0 으로 정규화) |
 * | 8kHz | +2.4 |
 * | 20kHz | **+4.1 dB** |
 *
 * **저역이 처지고 고역이 오르는 모양**이다. 이것은 EMM-6 의 알려진
 * **응답**이다 — 소구경 일렉트릿 측정 마이크는 다이어프램 공진으로
 * 고역이 들린다. 이 파일이 `correction`(더할 값)이라면 「이 마이크는
 * 20kHz 에서 4dB **덜** 잡는다」는 뜻이 되는데, 그건 이 마이크의 성질과
 * 반대다.
 *
 * 그래서 **[CalibrationCurve] 의 지금 규약(빼기)이 맞다.**
 *
 * **다만 파일이 스스로 밝힌 것은 아니다.** 열 이름이 없어
 * [SignEvidence.Unknown] 이고, 판단의 근거는 ① 곡선 모양이 EMM-6 의
 * 알려진 응답과 맞는다 ② 이 계열 cal 파일을 쓰는 도구(REW·ARTA)의
 * 관례가 「응답을 빼는」 쪽이다 — 이 둘이다. 다른 마이크의 파일에는
 * 그대로 적용되지 않는다.
 */
class Emm6FormatTest {

    private val text: String
        get() = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("SYNTH-emm6-format-sample.txt"),
        ) { "표본이 없다" }.bufferedReader().use { it.readText() }

    @Test
    fun `감도 줄과 빈 줄을 건너뛰고 점만 읽는다`() {
        val parsed = CalibrationFile.parse(text)
        assertEquals("256점을 하나도 빠뜨리면 안 된다", 256, parsed.points.size)
        assertEquals("주석과 빈 줄은 세지 않는다", 0, parsed.skippedLines)
    }

    /**
     * **감도를 버리지 않는다.**
     *
     * `*1000Hz -41.1` 은 이 마이크의 1kHz 감도다. 곡선에는 안 들어가지만
     * 사람이 봐야 하는 값이라 머리글로 들고 와 화면에 보인다.
     */
    @Test
    fun `감도를 머리글로 들고 온다`() {
        val loaded = CalibrationFile.load(text).getOrThrow()
        assertTrue(
            "1kHz 감도가 머리글에 있어야 한다: ${loaded.headerLines}",
            loaded.headerLines.any { it.contains("1000Hz") && it.contains("-41.1") },
        )
    }

    /**
     * **열 이름이 없으므로 모른다고 한다.**
     *
     * 여기서 억지로 한쪽을 고르면, 다른 제조사의 `correction` 파일에도
     * 같은 판단을 내리게 된다.
     */
    @Test
    fun `열 이름이 없으면 부호를 단정하지 않는다`() {
        val loaded = CalibrationFile.load(text).getOrThrow()
        assertEquals(SignEvidence.Unknown, loaded.signEvidence)
        assertNull("근거 없이 경고를 띄우지 않는다", loaded.warningKo)
    }

    /**
     * **응답 곡선의 모양이다** — 저역 처짐, 고역 상승, 1kHz 에서 0.
     *
     * 이 모양이 부호를 가른 근거다(클래스 KDoc 참고).
     */
    @Test
    fun `저역은 처지고 고역은 오른다`() {
        val c = CalibrationFile.load(text).getOrThrow().curve
        val at20 = c.gainDbAt(20.0)
        val at1k = c.gainDbAt(1000.0)
        val at20k = c.gainDbAt(20000.0)

        assertTrue("20Hz 는 음수여야 한다: $at20", at20 < -1.0)
        assertEquals("1kHz 에서 0 으로 정규화돼 있다", 0.0, at1k, 0.15)
        assertTrue("20kHz 는 양수여야 한다: $at20k", at20k > 3.0)
        assertTrue("저역보다 고역이 높다", at20k > at20)
    }

    /**
     * **보정을 걸면 이 마이크의 기울기가 지워진다.**
     *
     * 응답을 **빼는** 규약이므로, 고역이 +4dB 로 들린 마이크의 측정값에서
     * 4dB 를 덜어낸다. 반대로 걸면 8dB 가 벌어진다.
     */
    @Test
    fun `응답을 빼는 쪽이 맞다`() {
        val c = CalibrationFile.load(text).getOrThrow().curve

        // 20kHz 에서 이 마이크는 4.1dB 더 잡는다. 칸 계수는 그만큼 **덜어내는**
        // 값이어야 한다 — 선형 전력 계수가 1보다 작다.
        val bins = c.binCorrectionLinear(4096, 48_000)
        val k20k = Math.round(20_000.0 / (48_000.0 / 4096)).toInt().coerceAtMost(bins.size - 1)
        assertTrue(
            "고역을 덜어내야 한다 (계수 ${bins[k20k]})",
            bins[k20k] < 1.0,
        )

        // 20Hz 에서는 2dB 덜 잡으므로 **보태는** 쪽이다.
        val k20 = Math.round(20.0 / (48_000.0 / 4096)).toInt().coerceAtLeast(1)
        assertTrue("저역은 보태야 한다 (계수 ${bins[k20]})", bins[k20] > 1.0)
    }
}
