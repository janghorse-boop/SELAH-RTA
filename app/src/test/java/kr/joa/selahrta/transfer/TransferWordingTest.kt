package kr.joa.selahrta.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TF 간편 화면의 문구가 금지 표현을 쓰지 않는가(TF 설계 9·12장, 지시서 37장, CLAUDE.md 5~7장).
 *
 * 화면 문자열이 코드 안에 있으므로 원문을 읽는다. 「측정 신뢰도」는 30회차가 과장으로 짚어 없앴다.
 * 마이크 등급 표현(입문용·전문용 등)도 쓰지 않는다.
 */
class TransferWordingTest {

    private val files = listOf(
        "src/main/java/kr/joa/selahrta/ui/screens/transfer/SimpleTransferScreen.kt",
        "src/main/java/kr/joa/selahrta/transfer/TransferController.kt",
    )

    private val banned = listOf(
        "Smaart", "Class 1", "Class 2", "공인 측정", "자동 EQ 정답", "측정 신뢰도",
        "입문용", "전문용", "보급형", "고급형", "간편형", "정밀형",
    )

    /** 코드 안의 문자열 리터럴만 — KDoc·주석은 금지 표현을 「쓰지 않는다」고 설명하므로 뺀다. */
    private fun literals(src: String): List<String> =
        Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(src).map { it.groupValues[1] }.toList()

    @Test
    fun `화면과 조율부 문자열에 금지 표현이 없다`() {
        for (path in files) {
            val f = File(path)
            assertTrue("원문이 없다: ${f.absolutePath}", f.exists())
            val lits = literals(f.readText(Charsets.UTF_8))
            assertTrue("문자열을 하나도 못 찾았다 — 시험이 헛돈다: $path", lits.isNotEmpty())
            for (b in banned) {
                assertFalse("$path 의 문자열에 「$b」", lits.any { it.contains(b) })
            }
        }
    }

    @Test
    fun `실험용 띠는 시간축 미검증과 클럭 드리프트를 밝힌다`() {
        val src = File(files[0]).readText(Charsets.UTF_8)
        assertTrue(src.contains("실험용 진단 표시"))
        assertTrue(src.contains("시간축 미검증"))
        assertTrue(src.contains("클럭 드리프트 미확인·보정 안 함"))
        assertTrue(src.contains("상대 비교의 정확도도 보장하지 않습니다"))
    }
}
