package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **같은 것을 네 가지로 부르지 않는다**(담당자 지시 2026-09-27).
 *
 * 「Z 가중」·「무가중」·「가중 없음」·「Flat」이 섞여 쓰이던 탓에
 * 「고정된 Z 와 무가중이 다른 것인가」라는 물음이 실제로 나왔다.
 * 이름은 하나이고, 평탄하다는 사실은 설명 줄이 말한다.
 */
class WeightingLabelTest {

    @Test
    fun `이름이 -weighting 꼴이다`() {
        assertEquals("A-weighting", Weighting.A.labelKo)
        assertEquals("C-weighting", Weighting.C.labelKo)
        assertEquals("Z-weighting", Weighting.Z.labelKo)
    }

    /** dBA 만 괄호가 없던 어긋남이 이 시험이 있는 까닭이다. */
    @Test
    fun `단위가 모두 같은 꼴이다`() {
        assertEquals("dB(A)", Weighting.A.unitSuffix)
        assertEquals("dB(C)", Weighting.C.unitSuffix)
        assertEquals("dB(Z)", Weighting.Z.unitSuffix)
    }

    @Test
    fun `모든 가중의 단위가 dB 괄호 한 글자 꼴이다`() {
        Weighting.entries.forEach { w ->
            assertTrue(
                "${w.name} 의 단위가 꼴에 맞지 않는다: ${w.unitSuffix}",
                Regex("""^dB\([ACZ]\)$""").matches(w.unitSuffix),
            )
        }
    }

    /** 이름 자리에 설명을 끼워 넣으면 다시 두 가지가 된다. */
    @Test
    fun `이름에 무가중 같은 옛말이 섞이지 않는다`() {
        val banned = listOf("무가중", "가중 없", "가중없", "Flat", "flat")
        Weighting.entries.forEach { w ->
            banned.forEach { b ->
                assertTrue(
                    "${w.name} 의 이름에 「$b」이 들어 있다: ${w.labelKo}",
                    !w.labelKo.contains(b),
                )
            }
        }
    }

    // ── L 기호 ──────────────────────────────────────────

    /**
     * **계산에 쓴 가중에서 글자가 나온다**(지시서 §10).
     *
     * 화면이 손으로 "LAeq" 를 적으면 가중을 C 로 바꾼 뒤에도 A 라고
     * 적혀 있게 된다 — 표기와 실제가 어긋나는 바로 그 사고다.
     */
    @Test
    fun `Leq 이름이 가중 글자를 따른다`() {
        assertEquals("LAeq 1분", Weighting.A.leqLabel("1분"))
        assertEquals("LCeq 1분", Weighting.C.leqLabel("1분"))
        assertEquals("LZeq 전체", Weighting.Z.leqLabel("전체"))
    }

    @Test
    fun `PEAK 이름이 가중 글자를 따른다`() {
        assertEquals("LApeak", Weighting.A.peakLabel())
        assertEquals("LCpeak", Weighting.C.peakLabel())
        assertEquals("LZpeak", Weighting.Z.peakLabel())
    }
}
