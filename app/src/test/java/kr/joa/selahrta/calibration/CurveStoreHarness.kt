package kr.joa.selahrta.calibration

import android.app.Application
import java.io.File

/**
 * **진짜 [CurveStore] 를 JVM 에서 돌린다**(독립 재검토 CFRC-01).
 *
 * ## 왜 만들었나
 *
 * CFRF-01 요청서에 「`Context` 가 없으면 한 줄도 못 돌린다」고 적었다.
 * **틀린 말이었다.** 검토자는 `filesDir` 와 `applicationContext` 둘만
 * 대신해 주고 **원본 `CurveStore` 와 실제 DataStore** 를 돌려 반례를
 * 만들어 냈다. 그 자리에서 나온 것이 CFRF-01 이고 CFRC-01 이다.
 *
 * 순수 함수 시험만으로는 **흐름이 잘못 조합되는 자리**가 잡히지 않는다.
 * 이 저장소가 같은 이유로 네 번 데였다(CF2-01 · CFR-01 · CFRF-01 ·
 * CFRC-01). 그래서 그 경계를 정규 시험으로 들여놓는다.
 *
 * ## 무엇을 대신하나
 *
 * 둘뿐이다 — 파일을 둘 자리와 「나 자신」. 나머지는 손대지 않았다.
 * `DataStore`·`AtomicWrite`·파서·판정은 전부 앱에 실리는 그 코드다.
 *
 * 안드로이드 화면·`AudioRecord`·실제 기기 저장 경로는 **여전히 못 본다.**
 * 여기서 통과했다고 기기에서 된다는 뜻이 아니다.
 */
class TestApplication(private val dir: File) : Application() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir.apply { mkdirs() }
}
