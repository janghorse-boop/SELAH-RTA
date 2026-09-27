package kr.joa.selahrta.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **옛말이 화면에 다시 섞여 들지 않게 막는다**(담당자 지시 2026-09-27).
 *
 * 「Z 가중」·「무가중」·「가중 없음」·「Flat」이 같은 것을 가리키며 섞여
 * 쓰이던 탓에 「고정된 Z 와 무가중이 다른 것인가」라는 물음이 실제로
 * 나왔다. 사람의 기억으로는 다시 섞이므로 기계가 본다.
 *
 * ## 주석은 보지 않는다
 *
 * 설명하는 글에서는 「가중을 걸지 않는다」가 자연스럽고, 그것을 막으면
 * 코드를 설명할 말이 없어진다. 막으려는 것은 **화면에 뜨는 글자**다.
 *
 * ## 왜 화면 파일만 보는가
 *
 * `ui/` 아래만 훑는다. DSP 의 시험 이름이나 주석은 화면에 뜨지 않는다.
 */
class WeightingWordingTest {

    /** 화면 문구에 쓰면 안 되는 말. 셋 다 `Z-weighting` 하나로 부른다. */
    private val banned = listOf("무가중", "가중 없음", "가중없음")

    /**
     * 괄호 없는 단위. `dB(A)` 꼴로만 적는다(담당자 지시 2026-09-27).
     *
     * **처음 지적받은 것이 바로 이것이다** — `dBA`·`dBC` 만 괄호가 없고
     * `dB(Z)` 만 있었다. enum 을 고쳐도 화면 문구에 손으로 적어 둔 곳이
     * 남아 있었고(권장 범위·주의사항 등), 그 자리는 기계가 아니면 다시
     * 찾지 못한다.
     */
    private val bareUnit = Regex("""dB[ACZ](?!\))""")

    /**
     * 화면 문구만 고른다.
     *
     * 줄이 주석으로 시작하면 건너뛰고, 따옴표가 없으면 문자열이 아니므로
     * 건너뛴다. 정밀한 파서는 아니지만 **놓치는 쪽이 아니라 더 잡는
     * 쪽으로** 틀린다 — 헛걸림은 사람이 보면 알고, 놓치면 모른다.
     */
    private fun uiStringLines(f: File): List<Pair<Int, String>> =
        f.readLines().mapIndexedNotNull { i, raw ->
            val line = raw.trim()
            val isComment = line.startsWith("//") ||
                line.startsWith("*") ||
                line.startsWith("/*")
            if (isComment || !line.contains('"')) null else i + 1 to line
        }

    @Test
    fun `화면 문구에 옛말이 없다`() {
        val root = File("src/main/java/kr/joa/selahrta/ui")
        assertTrue(
            "화면 소스 폴더를 못 찾았다: ${root.absolutePath}",
            root.isDirectory,
        )

        val hits = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            uiStringLines(f).forEach { (no, line) ->
                banned.forEach { b ->
                    if (line.contains(b)) hits += "${f.name}:$no  $line"
                }
            }
        }

        assertTrue(
            "화면 문구에 옛말이 남아 있다. 「Z-weighting」으로 바꾸십시오:\n" +
                hits.joinToString(System.lineSeparator()),
            hits.isEmpty(),
        )
    }

    /**
     * **괄호 없는 `dBA` 가 화면에 남지 않았는가.**
     *
     * enum 하나를 고쳐도 화면 문구에 손으로 적어 둔 자리는 따라오지
     * 않는다 — 실제로 권장 범위·주의사항·보정 안내에 다섯 군데가
     * 남아 있었고, 기기에 올려 보고서야 찾았다.
     *
     * **화면 파일만으로는 모자란다.** 권장 범위의 문구는 `domain` 에,
     * 보정 안내는 `calibration` 에 있다. 화면에 뜨는 글자가 어느 꾸러미에
     * 있든 잡아야 하므로 앱 소스 전체를 본다.
     */
    @Test
    fun `화면 문구의 단위에 괄호가 빠지지 않았다`() {
        val root = File("src/main/java/kr/joa/selahrta")
        assertTrue("앱 소스 폴더를 못 찾았다: ${root.absolutePath}", root.isDirectory)

        val hits = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            uiStringLines(f).forEach { (no, line) ->
                if (bareUnit.containsMatchIn(line)) hits += "${f.name}:$no  $line"
            }
        }

        assertTrue(
            "화면 문구에 괄호 없는 단위가 있다. dB(A)·dB(C)·dB(Z) 로 적으십시오:\n" +
                hits.joinToString(System.lineSeparator()),
            hits.isEmpty(),
        )
    }

    /**
     * **이 시험이 정말 보고 있는지 확인한다.**
     *
     * 폴더를 못 찾거나 확장자가 안 맞으면 훑을 파일이 0개인 채로 통과한다 —
     * 「아무것도 안 잡혔다」와 「아무것도 안 봤다」는 다르다.
     */
    @Test
    fun `훑을 화면 파일이 실제로 있다`() {
        val root = File("src/main/java/kr/joa/selahrta/ui")
        val count = root.walkTopDown().count { it.extension == "kt" }
        assertTrue("화면 파일을 하나도 못 찾았다", count > 10)
    }
}
