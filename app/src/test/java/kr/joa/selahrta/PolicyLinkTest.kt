package kr.joa.selahrta

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **앱 안에서 방침에 닿을 수 있는가.**
 *
 * Play 정책은 스토어 목록뿐 아니라 **앱 안에서도** 개인정보처리방침에
 * 닿을 것을 요구한다. 마이크를 쓰고 소리를 파일로 남기는 앱이라 더
 * 그렇다 — 무엇이 담기고 어디에 남는지를 **담기 전에** 읽을 수 있어야
 * 한다.
 *
 * 2026-09-29 까지 **코드 어디에도 그 주소가 없었다.** 홈페이지에 방침을
 * 올리고 문서까지 갖춰 두고도, 앱에서 가는 길만 빠져 있었다.
 *
 * ## 화면을 띄우지 않고 무엇을 지키나
 *
 * 이 시험은 Compose 를 돌리지 않는다. **주소가 소스에 있는지**와 그
 * 주소가 **공개 방침의 것과 같은지**만 본다 — 실제 탭 동작은 기기에서
 * 봐야 한다.
 *
 * 그래도 값어치가 있다. 주소는 조용히 어긋나기 쉬운 값이다: 홈페이지
 * 경로를 바꾸거나 오타가 나도 **빌드는 통과하고 화면도 멀쩡하며**,
 * 누르는 사람만 404 를 본다.
 */
class PolicyLinkTest {

    private val source: String by lazy {
        val f = File("src/main/java/kr/joa/selahrta/ui/screens/HistorySettingsScreens.kt")
        assertTrue("설정 화면 소스를 못 찾았다: ${f.absolutePath}", f.isFile)
        f.readText()
    }

    @Test
    fun `설정 화면이 개인정보처리방침 주소를 들고 있다`() {
        assertTrue(
            "개인정보처리방침 주소가 없다. 앱 안에서 방침에 닿을 수 없으면 " +
                "Play 정책 위반이다.",
            source.contains(PRIVACY),
        )
    }

    @Test
    fun `설정 화면이 이용약관 주소를 들고 있다`() {
        assertTrue("이용약관 주소가 없다", source.contains(TERMS))
    }

    /**
     * **인터넷 권한을 끌어들이지 않는다.**
     *
     * 여는 것은 브라우저이지 이 앱이 아니다. `ACTION_VIEW` 는 권한 없이
     * 쓸 수 있고, 그래서 「서버로 보내지 않는다」는 방침이 그대로
     * 유지된다. 권한 자체는 `ManifestPromisesTest` 가 못박는다 —
     * 여기서는 **여는 방식**이 그 길인지만 본다.
     */
    @Test
    fun `브라우저로 여는 길을 쓴다`() {
        assertTrue(
            "ACTION_VIEW 가 아니다. 앱이 직접 받아 오는 길로 바꾸면 " +
                "INTERNET 권한이 필요해지고, 방침의 뼈대가 무너진다.",
            source.contains("Intent.ACTION_VIEW"),
        )
    }

    /**
     * **열리지 않을 때 조용하지 않아야 한다.**
     *
     * 브라우저가 없거나 막힌 기기에서는 `ActivityNotFoundException` 이
     * 난다. 그때 아무 일도 안 일어나면 사람은 앱이 고장 난 줄 안다.
     */
    @Test
    fun `열리지 않으면 주소를 보여 준다`() {
        assertTrue(
            "ActivityNotFoundException 을 잡지 않는다 — 조용히 실패한다",
            source.contains("ActivityNotFoundException"),
        )
    }

    /**
     * **홈페이지에 실제로 그 문서가 있는가.**
     *
     * 앱의 주소와 홈페이지 저장소가 따로 놀면, 경로를 바꾼 날 앱만
     * 404 로 남는다. 저장소가 옆에 있을 때만 본다 — 없으면 건너뛴다
     * (CI 에서는 이 저장소만 받을 수 있다).
     */
    @Test
    fun `홈페이지 저장소가 옆에 있으면 그 경로가 실재한다`() {
        val site = File("../../joaworks-website")
        if (!site.isDirectory) return
        assertTrue(
            "홈페이지에 privacy/index.html 이 없다 — 앱의 링크가 404 가 된다",
            File(site, "privacy/index.html").isFile,
        )
        assertTrue(
            "홈페이지에 terms/index.html 이 없다",
            File(site, "terms/index.html").isFile,
        )
    }

    private companion object {
        /** 공개 방침 주소. 바꾸면 홈페이지 저장소도 함께 본다. */
        const val PRIVACY = "https://joaworks.com/privacy/"
        const val TERMS = "https://joaworks.com/terms/"
    }
}
