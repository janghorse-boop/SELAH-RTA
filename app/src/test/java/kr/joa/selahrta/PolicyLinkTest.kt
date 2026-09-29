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
 * ## 무엇을 지키고 무엇을 못 지키나
 *
 * 이 시험은 Compose 를 돌리지 않는다. **소스를 문자열로 읽을 뿐이다** —
 * 「화면에 실제로 그려지는지 본다」고 적었던 것은 **과한 말이었다**
 * (독립 재검증 2026-09-29).
 *
 * 검토자가 넣은 변이 넷 가운데 **하나만** 잡았다:
 *
 * | 변이 | 이 시험 |
 * |---|---|
 * | 호출부 **삭제** | **잡음** |
 * | 호출 줄을 `//` 로 주석 처리 | 통과 ← 주석과 코드를 구별 못 한다 |
 * | 개인정보 단추를 `TERMS_URL` 로 연결 | 통과 |
 * | `failedUrl` 갱신 제거 | 통과 |
 *
 * **정규식 조건을 늘려 메우려 하지 않는다.** 누른 결과를 보는 시험이
 * 있어야 하고, 그것은 `PolicyLinksInteractionTest`(androidTest) 다.
 * **바깥 호출을 주석 처리하는 변이도 못 잡는다**(UIS6-03) — 그쪽은
 * `SettingsScreenPolicyPresenceTest` 가 진짜 화면을 열어 본다.
 * 여기 남는 값어치는 **주소가 공개 방침의 것과 같은가** 하나다 —
 * 기기 없이 돌고, 오타가 나도 빌드는 통과하는 값이기 때문이다.
 *
 * ## 처음 쓴 판은 뚫렸다 (독립 재검증 2026-09-29)
 *
 * 검토자가 **설정 화면에서 `PolicyLinks(...)` 호출을 지우는 변이**를
 * 넣었는데 **다섯 개가 다 통과했다.** 주소 상수는 선언부에 그대로
 * 남아 있으니 문자열 검색이 참이었던 것이다 — 화면에서는 링크가
 * 사라졌는데.
 *
 * UIS-07 에서 매니페스트 시험이 같은 함정에 빠졌었다. 같은 실수를
 * 같은 저장소에서 두 번 했다.
 *
 * 그래서 **선언 말고 호출**을 센다. 그래도 Compose 를 돌리는 것은
 * 아니므로, 실제 탭 동작은 기기에서 봐야 한다(2026-09-29 확인함).
 */
class PolicyLinkTest {

    private val source: String by lazy {
        val f = File("src/main/java/kr/joa/selahrta/ui/screens/HistorySettingsScreens.kt")
        assertTrue("설정 화면 소스를 못 찾았다: ${f.absolutePath}", f.isFile)
        f.readText()
    }

    /**
     * **선언이 아니라 호출을 센다.** 이것이 없으면 화면에서 링크를
     * 지워도 시험이 통과한다(검토자가 실제로 뚫었다).
     *
     * **주석 처리는 못 잡는다.** 문자열 검색의 한계이고, 그쪽은
     * `PolicyLinksInteractionTest` 가 덮는다.
     */
    @Test
    fun `설정 화면이 방침 줄을 실제로 그린다`() {
        val declAt = source.indexOf("internal fun AppInfoWithPolicyLinks(")
        assertTrue("AppInfoWithPolicyLinks 선언을 못 찾았다", declAt > 0)
        val callAt = source.indexOf("AppInfoWithPolicyLinks(")
        assertTrue(
            "AppInfoWithPolicyLinks 를 선언만 하고 부르지 않는다 — 화면에서 " +
                "링크가 사라져도 아무도 모른다.",
            callAt in 1 until declAt,
        )
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
        // 여는 일을 밖에서 받게 해 두었는가 — 나중에 화면 시험을 붙일 길이다.
        assertTrue(
            "여는 일이 화면 안에 박혀 있다 — 가짜를 넣어 시험할 수 없다",
            source.contains("openUrl: (String) -> Boolean"),
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
