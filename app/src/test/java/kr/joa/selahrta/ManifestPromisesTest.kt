package kr.joa.selahrta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
 */
class ManifestPromisesTest {

    private val manifest: String by lazy {
        val f = File("src/main/AndroidManifest.xml")
        assertTrue("매니페스트를 못 찾았다: ${f.absolutePath}", f.isFile)
        f.readText()
    }

    /**
     * **이것이 이 파일의 한가운데다.**
     *
     * 켜면 예배 녹음이 사용자의 구글 드라이브로 자동 백업된다.
     * 켜야 할 까닭이 생겼다면 **앱의 녹음 안내 문구와 홈페이지
     * 개인정보처리방침을 먼저 고쳐라.**
     */
    @Test
    fun `백업이 꺼져 있다`() {
        assertTrue(
            "매니페스트에 allowBackup 선언이 없다 — 기본값은 true 다",
            manifest.contains("android:allowBackup"),
        )
        assertTrue(
            "allowBackup 이 꺼져 있지 않다. 켜면 예배 녹음이 구글 드라이브로 " +
                "백업되는데, 앱은 「폰 안에만 둔다」고 말하고 있다.",
            manifest.contains("android:allowBackup=\"false\""),
        )
    }

    /**
     * **인터넷 권한이 없다는 것이 방침의 뼈대다.**
     *
     * 개인정보처리방침이 「JOAWORKS 서버로 자동 전송하지 않는다」고
     * 적은 근거다. 생기면 방침을 먼저 고쳐야 한다.
     */
    @Test
    fun `인터넷 권한이 없다`() {
        assertFalse(
            "INTERNET 권한이 생겼다. 개인정보처리방침과 Play Data Safety 를 " +
                "먼저 고쳐라.",
            manifest.contains("android.permission.INTERNET"),
        )
    }

    /**
     * 사람이 허락하는 권한은 이 넷뿐이다.
     *
     * **「전부」라고 세지 않는다.** release 병합본에는 AndroidX 가 넣는
     * `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 이 하나 더 있다 —
     * 서명 수준의 자기 권한이라 사람에게 묻지 않지만, 「네 개가 전부」는
     * 틀린 말이 된다. 방침에도 그렇게 적지 않는다.
     */
    @Test
    fun `앱이 직접 선언한 권한이 늘지 않았다`() {
        val declared = Regex("""android\.permission\.([A-Z_]+)""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSortedSet()
        assertEquals(
            "앱이 선언한 권한이 달라졌다. 개인정보처리방침의 권한 표와 " +
                "Play Data Safety 를 함께 고쳐라.",
            sortedSetOf(
                "FOREGROUND_SERVICE",
                "FOREGROUND_SERVICE_MICROPHONE",
                "POST_NOTIFICATIONS",
                "RECORD_AUDIO",
            ),
            declared,
        )
    }

    /** 전경 서비스는 마이크 용도이고 밖에서 부를 수 없어야 한다. */
    @Test
    fun `캡처 서비스가 마이크 전경 서비스이고 밖으로 열려 있지 않다`() {
        assertTrue(
            "foregroundServiceType 이 microphone 이 아니다",
            manifest.contains("""android:foregroundServiceType="microphone""""),
        )
        assertTrue(
            "CaptureService 가 exported 되어 있다",
            manifest.contains(""".audio.CaptureService""") &&
                manifest.contains("""android:exported="false""""),
        )
    }
}
