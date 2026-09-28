package kr.joa.selahrta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * **매니페스트가 공개 문서의 약속을 깨지 않는가.**
 *
 * 홈페이지의 개인정보처리방침(<https://joaworks.com/privacy/>)과 앱의
 * 녹음 안내 문구는 **매니페스트가 어떻게 생겼는지에 기대어** 쓰여 있다.
 * 매니페스트를 고치면서 그 문서를 같이 고치지 않으면, 공개된 방침이
 * 조용히 거짓이 된다 — Google Play 정책 위반이다.
 *
 * 그래서 사람의 기억 대신 여기서 본다.
 *
 * ## 실제로 그런 일이 있었다 (2026-09-28)
 *
 * `allowBackup` 이 기본값 `true` 였다. 안드로이드 자동 백업이 앱 전용
 * 저장소를 통째로 구글 드라이브로 올린다 — **예배 녹음까지.** 그런데
 * 앱은 소리를 담을 때 「폰 안에만 두고, 기록을 지우면 함께 사라집니다」
 * 라고 말하고 있었고, 홈페이지는 「클라우드 저장·백업 없음」이라고
 * 적어 두었다. 둘 다 거짓이었다.
 *
 * ## 문자열로 찾지 않는다 (독립 검토 UIS-07, 2026-09-28)
 *
 * 처음 쓴 판은 `manifest.contains("...")` 였다. 그래서 **서비스를 밖으로
 * 열어도 시험이 통과했다** — `CaptureService` 라는 글자와
 * `android:exported="false"` 라는 글자가 **문서 어딘가에 각각** 있으면
 * 참이었고, 그 `false` 는 FileProvider 의 것이었다. 검토자가 그 변이를
 * 실제로 주입해 통과시켰다.
 *
 * 주석도 구별하지 못했다. 설명문에 적어 둔 `allowBackup="false"` 한 줄이
 * 진짜 속성 노릇을 할 수 있었다.
 *
 * 그래서 **요소를 찾고 그 요소의 속성을 읽는다.** 무엇을 보는지가
 * 분명해야 다음 사람이 믿을 수 있다.
 */
class ManifestPromisesTest {

    private val manifest: Document by lazy { parse("src/main/AndroidManifest.xml") }

    /** `<application>` 요소 하나. 여기 붙은 속성만 진짜 선언이다. */
    private val application: Element by lazy {
        val els = manifest.getElementsByTagName("application")
        assertEquals("application 요소가 하나가 아니다", 1, els.length)
        els.item(0) as Element
    }

    @Test
    fun `백업이 꺼져 있다`() {
        assertEquals(
            "allowBackup 이 꺼져 있지 않다. 켜면 예배 녹음이 구글 드라이브로 " +
                "백업되는데, 앱은 「폰 안에만 둔다」고 말하고 있다.",
            "false",
            application.androidAttr("allowBackup"),
        )
    }

    /**
     * **끄는 것만으로는 폰을 바꿀 때 따라간다.**
     *
     * targetSdk 31 이상에서 `allowBackup` 은 클라우드 백업만 끈다. 새 폰을
     * 살 때 제조사 마법사가 하는 **기기 간 이전(D2D)** 은 그 속성이 막지
     * 않는다 — 이전된 사본은 원래 폰에서 기록을 지워도 따라 지워지지
     * 않는다. 그래서 제외 규칙까지 함께 건다.
     *
     * **아홉 영역을 다 센다.** 한 줄이 빠지면 그 영역만 조용히 딸려 간다.
     */
    @Test
    fun `기기 간 이전에서도 앱 데이터가 빠져 있다`() {
        val res = application.androidAttr("dataExtractionRules")
        assertEquals(
            "dataExtractionRules 가 걸려 있지 않다 — allowBackup=false 만으로는 " +
                "Android 12 이상에서 기기 간 이전이 막히지 않는다.",
            "@xml/data_extraction_rules",
            res,
        )

        val rules = parse("src/main/res/xml/data_extraction_rules.xml")
        val domains = setOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
        )
        for (section in listOf("cloud-backup", "device-transfer")) {
            val els = rules.getElementsByTagName(section)
            assertEquals("<$section> 이 하나가 아니다", 1, els.length)
            val excluded = (els.item(0) as Element).childElements("exclude")
                .filter { it.getAttribute("path") == "." }
                .map { it.getAttribute("domain") }
                .toSet()
            assertEquals(
                "<$section> 에서 빠지지 않은 저장 영역이 있다 — 그 영역만 " +
                    "조용히 폰 밖으로 나간다.",
                domains,
                excluded,
            )
        }
    }

    /**
     * **인터넷 권한이 없다는 것이 방침의 뼈대다.**
     *
     * 개인정보처리방침이 「JOAWORKS 서버로 자동 전송하지 않는다」고
     * 적은 근거다. 생기면 방침을 먼저 고쳐야 한다.
     *
     * `uses-permission` 요소만 센다 — 주석에 적힌 이름은 권한이 아니다.
     */
    @Test
    fun `앱이 선언한 권한이 늘지 않았다`() {
        val declared = manifest.getElementsByTagName("uses-permission")
            .let { list -> (0 until list.length).map { list.item(it) as Element } }
            .mapNotNull { it.androidAttr("name") }
            .map { it.removePrefix("android.permission.") }
            .toSortedSet()

        assertEquals(
            "앱이 선언한 권한이 달라졌다. 개인정보처리방침의 권한 표와 " +
                "Play Data Safety 를 함께 고쳐라. INTERNET 이 늘었다면 " +
                "「서버로 보내지 않는다」는 방침부터 고쳐야 한다.",
            sortedSetOf(
                // 사람에게 묻는 것은 이 둘뿐이다.
                "POST_NOTIFICATIONS",
                "RECORD_AUDIO",
                // 나머지 둘은 묻지 않는 보통 권한이다. **「사람이 허락하는
                // 네 권한」이라고 적지 않는다**(독립 검토 2026-09-28).
                "FOREGROUND_SERVICE",
                "FOREGROUND_SERVICE_MICROPHONE",
            ),
            declared,
        )
    }

    /**
     * 전경 서비스는 마이크 용도이고 밖에서 부를 수 없어야 한다.
     *
     * **그 서비스 요소를 찾아서 그 요소의 속성을 본다.** 문서 어딘가의
     * `exported="false"` 로는 통과하지 못한다(UIS-07).
     */
    @Test
    fun `캡처 서비스가 마이크 전경 서비스이고 밖으로 열려 있지 않다`() {
        val service = application.childElements("service")
            .firstOrNull { it.androidAttr("name")?.endsWith(".audio.CaptureService") == true }
        assertNotNull("CaptureService 선언을 못 찾았다", service)
        requireNotNull(service)

        assertEquals(
            "CaptureService 의 foregroundServiceType 이 microphone 이 아니다",
            "microphone",
            service.androidAttr("foregroundServiceType"),
        )
        assertEquals(
            "CaptureService 가 밖으로 열려 있다 — 다른 앱이 마이크 서비스를 " +
                "시작시킬 수 있다.",
            "false",
            service.androidAttr("exported"),
        )
    }

    /** 파일이 있어야 시험이 뜻을 갖는다. 없으면 통과가 아니라 실패다. */
    private fun parse(path: String): Document {
        val f = File(path)
        assertTrue("파일을 못 찾았다: ${f.absolutePath}", f.isFile)
        // **이름공간을 인식해 읽는다.** 그래야 `android:exported` 와
        // 접두사만 같은 남의 속성이 섞이지 않는다.
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(f)
    }

    private fun Element.androidAttr(name: String): String? =
        getAttributeNodeNS(ANDROID_NS, name)?.value

    /** **바로 아래 자식만** 본다. 손자까지 세면 요소별 검사가 아니게 된다. */
    private fun Element.childElements(tag: String): List<Element> {
        val out = mutableListOf<Element>()
        val kids = childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n is Element && n.tagName == tag) out += n
        }
        return out
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
