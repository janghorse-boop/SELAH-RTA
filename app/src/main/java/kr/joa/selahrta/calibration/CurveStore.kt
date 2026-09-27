package kr.joa.selahrta.calibration

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kr.joa.selahrta.dsp.CURVE_READING_RULES_VERSION
import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.dsp.CalibrationFile
import kr.joa.selahrta.dsp.CurveReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private val Context.curveDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "curves")

/**
 * 지금 적용 중인 주파수 보정.
 *
 * **이 보정은 주파수를 나눠 보는 화면에만 걸린다.** 아래 [FREQUENCY_SCOPE_NOTE] 참고.
 */
data class ActiveCurve(
    val curve: CalibrationCurve,
    /** 가져온 파일 이름. 어느 파일인지 화면에 적는다. */
    val fileName: String,
    /**
     * 이 보정이 **어느 마이크의 것인지.** 사람이 적는다. 비어 있을 수 있다.
     *
     * **짓어내지 않는다.** 파일 머리글에 적혀 있기도 하지만, 거기서
     * 이름을 뽑아내 「이 마이크다」라고 말하면 틀렸을 때 더 나쁘다.
     * 오디오 인터페이스는 채널마다 다른 마이크가 꽂힐 수 있어, 어느 것이
     * 뭐인지는 꽂은 사람만 안다(USB 오디오 지시서 9.2).
     */
    val micName: String = "",
    val pointCount: Int,
    /**
     * 파일 앞의 머리글. **보정 부호를 가를 단서이고, 마이크가
     * 무엇인지도 보통 여기 적혀 있다.**
     *
     * 화면에 그대로 보인다 — 우리가 해석해 「이 마이크다」라고 말하지
     * 않는다. 짓어낸 이름이 틀리면 그것이 더 나쁘다.
     */
    val headerLines: List<String> = emptyList(),
    val importedAtEpochMs: Long,
    /**
     * 지금 **걸려 있는가.** 꺼도 파일은 그대로 둔다.
     *
     * 보정 전·후를 견주려면 꺼봤다 켜봐야 하는데, 그때마다 파일을
     * 다시 가져오게 하면 아무도 견주지 않는다(USB 오디오 지시서 11장).
     *
     * **켜 두었다는 것과 걸려 있다는 것은 다르다**(독립 재검토 CFRF-01).
     * 읽는 법이 안 정해진 파일은 사람이 켜 두었어도 여기가 거짓이다.
     */
    val enabled: Boolean = true,
    /**
     * **읽는 법을 사람이 정해 줘야 걸린다**(독립 재검토 CFRF-01).
     *
     * 참이면 [enabled] 는 반드시 거짓이고, 곡선은 **미리보기**다 —
     * 화면에 그리기만 하고 측정 보정에는 넣지 않는다.
     *
     * 계산해서 얻는 값이 아니라 [CurveStore] 가 읽으면서 **판정한 그대로**
     * 실어 보낸다. 화면이 따로 다시 판정하면 두 답이 갈릴 수 있고, 그러면
     * 「적용됨」이라고 적힌 채 안 걸리는 상태가 생긴다.
     */
    val readingConfirmationNeeded: Boolean = false,
    /** 왜 확인이 필요한지. 그대로 화면에 적는다. */
    val readingWhyKo: String? = null,
    /** 앱이 보기에 이쪽일 것 같다는 제안. 확인 단추의 기본값이다. */
    val suggestedReading: CurveReading = CurveReading.Response,
    /**
     * 지금 이 곡선을 어느 규약으로 읽고 있는가.
     *
     * 사람이 확인해 준 것이면 그 값, 아니면 판정이 정한 값이다. 확인이
     * 필요한 동안의 미리보기는 관례(응답)로 그린다.
     */
    val reading: CurveReading = CurveReading.Response,
    /** 자동 판정이 아니라 **사람이** 확인해 준 규약인가. */
    val readingConfirmed: Boolean = false,
    /**
     * **화면이 보여 준 바로 이 내용**을 가리키는 표(독립 재검토 CFRC-01).
     *
     * 확인 단추가 이것을 들고 돌아간다. 저장소는 이 표가 가리키는 내용과
     * 지금 디스크에 있는 내용이 같을 때만 확인을 발급한다 — 그러지 않으면
     * 사람이 본 적 없는 파일에 그 선택이 붙는다.
     */
    val confirmationToken: CurveConfirmationToken? = null,
    /**
     * **부호를 골라도 풀리지 않는** 까닭. 풀 수 있으면 null.
     *
     * 이것이 있으면 화면은 확인 단추를 띄우지 않는다 — 띄우면 고르면 될
     * 일이라고 믿게 만든다.
     */
    val readingUnsupportedKo: String? = null,
)

/**
 * 주파수 보정이 어디까지 걸리는지.
 *
 * **주파수를 나눠 보는 화면(RTA·Spectrum·Spectrogram)에 건다. 큰 음압
 * 숫자(dBA·Leq·MAX·PEAK)에는 걸지 않는다.**
 *
 * 2026-09-25 에 Spectrum·Spectrogram 이 생기면서 걸리는 곳이 늘었다. 셋은
 * 같은 FFT 한 장에서 나오고 보정도 같은 자리에서 한 번 걸리므로
 * ([RtaEngine]), 셋이 어긋날 수가 없다.
 *
 * 왜 그런가 — 주파수별 보정을 광대역 음압에 제대로 걸려면 시간 영역에서
 * 역응답 필터(FIR/IIR)를 통과시켜야 한다. 「밴드마다 고쳐서 다시 합치는」
 * 손쉬운 방법은 쓸 수 없다: 규격이 정의한 Fast/Slow 는 시간 영역 신호의
 * 지수 가중이지 밴드 합이 아니고, 파형 PEAK 은 아예 밴드로 되살릴 수 없다.
 *
 * **남는 오차를 작다고 말하지 않는다.** 예전에 여기에 「±2dB 마이크면
 * 광대역 영향이 0.5dB 아래이고 전대역 보정이 흡수한다」고 적었는데, 그것은
 * 일반적으로 틀리다(독립 검증 R04). 남는 오차는 **보정을 맞춘 주파수의
 * 응답과 실제 소리가 놓인 주파수의 응답 차이**다:
 *
 * - 1kHz 에서 맞추고 그 마이크의 125Hz 응답이 +2dB 이면, 저역 위주 소리에서
 *   광대역 값이 +2dB 만큼 높게 나온다.
 * - 보정 기준 대역이 −2dB, 측정 대역이 +2dB 이면 차이는 4dB 까지 벌어진다.
 *
 * C−A 도 마찬가지다. 전대역 보정값은 양쪽에서 상쇄되지만 마이크 응답의
 * 기울기는 상쇄되지 않는다.
 *
 * 그래서 지금은 걸지 않고, **그 사실과 남는 오차의 크기를 화면에 적는다.**
 * 나중에 역응답 필터를 넣으면 그때 광대역에도 건다.
 */
const val FREQUENCY_SCOPE_NOTE: String =
    "주파수 보정은 RTA·Spectrum·Spectrogram 에 적용됩니다. " +
        "큰 음압 숫자(dBA·Leq·MAX·PEAK)는 " +
        "전대역 보정값만 씁니다 — 마이크 응답이 고르지 않으면, 보정을 맞춘 " +
        "주파수와 실제 소리가 놓인 주파수의 응답 차이만큼 오차가 남습니다."

/**
 * 기기별 주파수 보정 곡선을 저장한다.
 *
 * 파일 내용은 앱 전용 폴더에 그대로 둔다 — 점이 수천 개인 파일도 있어서
 * 설정 저장소에 문자열로 넣기에는 크고, 원본을 남겨 두면 나중에 다시
 * 해석하거나 내보낼 수 있다.
 */
/**
 * @param store 설정을 담는 곳. 기본은 앱이 쓰는 그 하나다.
 *
 * **시험이 제 것을 넣을 수 있게 열어 두었다**(독립 재검토 CFRC-R02).
 * 위의 `by preferencesDataStore` 는 **한 JVM 안에서 같은 인스턴스를
 * 돌려준다** — `Context` 만 바꿔서는 새 DataStore 가 되지 않는다.
 * 검토자가 그것을 재 보였다(`sharedAcrossContexts=true`).
 *
 * 그래서 「저장소를 새로 만들었으니 앱을 다시 켠 것과 같다」던 내 시험은
 * **그 말을 증명하지 못하고 있었다.** 디스크에서 다시 읽는지를 보려면
 * DataStore 자체가 새것이어야 한다.
 */
class CurveStore(
    private val context: Context,
    private val store: DataStore<Preferences> = context.curveDataStore,
) {

    private fun nameKey(k: CalibrationKey) = stringPreferencesKey("${k.storageKey()}|curveFile")
    private fun countKey(k: CalibrationKey) = intPreferencesKey("${k.storageKey()}|curvePoints")
    private fun atKey(k: CalibrationKey) = longPreferencesKey("${k.storageKey()}|curveAt")

    /**
     * 걸어 둔 것을 사람이 꺼 두었는가.
     *
     * **없으면 켜진 것으로 본다.** 지금까지 저장된 곱선은 이 값이 없는데,
     * 그걸 「꺼짐」으로 읽으면 앱을 올리는 순간 보정이 조용히 풀린다.
     */
    private fun onKey(k: CalibrationKey) = stringPreferencesKey("${k.storageKey()}|curveOn")

    /** 사람이 적은 마이크 이름. */
    private fun micNameKey(k: CalibrationKey) = stringPreferencesKey("${k.storageKey()}|micName")

    // ── 읽는 법 확인 기록 (독립 재검토 CFRF-01) ───────────────────────
    //
    // **파일 이름이나 Boolean 하나로 확인을 인정하지 않는다.** 이름이
    // 같고 내용이 다른 파일을 다시 넣으면 옛 확인이 새 파일에 붙는다.
    // 그래서 **내용의 해시**에 매단다 — 내용이 한 글자만 달라도 확인은
    // 무효가 되고 다시 묻는다.

    /** 확인해 준 그 내용의 SHA-256(앱이 붙인 주석은 뺀 원본). */
    private fun readShaKey(k: CalibrationKey) = stringPreferencesKey("${k.storageKey()}|curveReadSha")

    /** 사람이 고른 규약([CurveReading] 의 이름). */
    private fun readAsKey(k: CalibrationKey) = stringPreferencesKey("${k.storageKey()}|curveReadAs")

    /** 그때의 규칙 판. 규칙이 바뀌면 옛 확인을 인정하지 않는다. */
    private fun readRulesKey(k: CalibrationKey) = intPreferencesKey("${k.storageKey()}|curveReadRules")

    private fun curveDir(): File = File(context.filesDir, "curves").apply { mkdirs() }

    /**
     * 열쇠를 파일 이름으로 옮긴다. **해시를 쓴다.**
     *
     * 예전에는 쓸 수 없는 글자를 전부 `_` 로 바꿨는데, 그러면 서로 다른
     * 기기가 같은 파일을 쓴다 — `Mic A` 와 `Mic_A` 가 둘 다
     * `cal_Usb_Mic_A_...cal` 이 된다(독립 검증 R09). 한쪽 곡선을 저장하면
     * 다른 쪽이 덮이고, 한쪽을 지우면 다른 쪽 파일이 사라진다. 설정 저장소의
     * 열쇠는 서로 달라서 화면은 곡선이 있다고 말하는데 파일 내용은 남의
     * 것이다.
     *
     * SHA-256 은 서로 다른 열쇠가 같은 이름이 될 걱정을 없애 준다. 대신
     * 폴더만 봐서는 어느 기기 것인지 알 수 없으므로, 원래 열쇠를 파일
     * 첫 줄에 주석으로 남긴다([KEY_COMMENT_PREFIX]).
     */
    private fun fileFor(k: CalibrationKey): File =
        File(curveDir(), sha256Hex(k.storageKey()) + ".cal")

    private fun sha256Hex(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 이전 판이 쓰던 파일 이름. **읽기만 한다.**
     *
     * 이름 규칙이 바뀌었으므로(R09) 예전에 저장한 곡선은 새 이름으로는
     * 찾을 수 없다. 조용히 사라지면 담당자는 보정이 걸린 줄 알고 재게
     * 된다.
     *
     * 그렇다고 옛 파일을 새 이름으로 옮기지도 않는다 — 옛 이름은 서로 다른
     * 기기가 같은 파일을 가리킬 수 있었으므로, 옮기는 순간 **어느 기기의
     * 것인지 정하는 셈**이 된다. 그 판단은 우리가 할 수 없다(독립 재검증
     * 추가 지적). 있다는 사실만 알리고 다시 가져오게 한다.
     */
    private fun legacyFileFor(k: CalibrationKey): File =
        File(curveDir(), k.storageKey().replace(Regex("[^A-Za-z0-9._-]"), "_") + ".cal")

    /** 이전 판의 곡선 파일이 남아 있는가. 화면이 「다시 가져오십시오」를 띄운다. */
    fun hasLegacyFile(key: CalibrationKey): Boolean =
        !fileFor(key).exists() && legacyFileFor(key).exists()

    /** 저장된 파일에서 앱이 붙인 주석을 뗀 **원본 CAL 내용**. 까닭은 [curveSourceText]. */
    private fun sourceTextOf(f: File): String? =
        runCatching { f.readText() }.getOrNull()?.let(::curveSourceText)

    /** 설정 저장소에 남은 확인 기록. 세 값이 다 있어야 기록으로 친다. */
    private fun recordOf(prefs: Preferences, key: CalibrationKey): ReadingConfirmationRecord? {
        val sha = prefs[readShaKey(key)] ?: return null
        val name = prefs[readAsKey(key)] ?: return null
        val rules = prefs[readRulesKey(key)] ?: return null
        return ReadingConfirmationRecord(sha, name, rules)
    }

    /** 이 파일을 지금 어떻게 읽을 것인가. 판단은 [resolveCurveReading] 이 한다. */
    private fun resolve(
        headerLines: List<String>,
        source: String,
        prefs: Preferences,
        key: CalibrationKey,
    ) = resolveCurveReading(headerLines, sha256Hex(source), recordOf(prefs, key))

    fun watch(key: CalibrationKey): Flow<ActiveCurve?> =
        store.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { prefs ->
                val name = prefs[nameKey(key)] ?: return@map null
                val f = fileFor(key)
                if (!f.exists()) return@map null
                val source = sourceTextOf(f) ?: return@map null
                val loaded = runCatching { CalibrationFile.load(source) }
                    .getOrNull()?.getOrNull() ?: return@map null

                // **읽을 때 다시 판정한다**(독립 재검토 CFRF-01).
                //
                // 예전에는 켜짐 표시 하나만 봤다. 그래서 (1) 일반 스위치를
                // 켜면 부호 확인을 건너뛰었고, (2) 옛 판에서 켜진 채로 남은
                // 모순 파일이 앱을 올린 뒤에도 그대로 걸렸다. 검토자가
                // 실제 DataStore 로 둘 다 재현했다:
                //
                //     TOGGLE_WITHOUT_READING enabled=true correction=FromFile
                //     REOPEN                 enabled=true correction=FromFile
                //
                // 막는 자리를 **읽기 경계 하나로** 모은다. 스위치만 막으면
                // 다른 부르는 곳이 생길 때 또 새어 나간다.
                val r = resolve(loaded.headerLines, source, prefs, key)

                // 확인된 규약이 관례와 다르면 **원본에서 다시 만든다.**
                // 이미 뒤집힌 곡선을 또 뒤집지 않도록 점부터 다시 읽는다.
                val curve = if (r.reading != null && r.reading != CurveReading.Response) {
                    runCatching { CalibrationFile.load(source, r.reading) }
                        .getOrNull()?.getOrNull()?.curve ?: loaded.curve
                } else {
                    loaded.curve
                }

                ActiveCurve(
                    curve = curve,
                    fileName = name,
                    pointCount = prefs[countKey(key)] ?: loaded.pointCount,
                    headerLines = loaded.headerLines,
                    importedAtEpochMs = prefs[atKey(key)] ?: 0L,
                    // **켜 두었고 또 읽는 법이 정해졌을 때만** 걸린다.
                    enabled = r.enabledWith(prefs[onKey(key)] != "false"),
                    micName = prefs[micNameKey(key)].orEmpty(),
                    readingConfirmationNeeded = r.needsPerson,
                    readingWhyKo = r.decision.whyKo.takeIf { r.needsPerson },
                    suggestedReading = r.decision.reading,
                    reading = r.reading ?: CurveReading.Response,
                    readingConfirmed = r.confirmed,
                    confirmationToken = CurveConfirmationToken(
                        key = key,
                        sourceSha = sha256Hex(source),
                        rulesVersion = CURVE_READING_RULES_VERSION,
                    ),
                    readingUnsupportedKo = r.rejectionKo,
                )
            }

    /**
     * 파일 내용을 저장한다. **먼저 해석해 보고, 안 되면 저장하지 않는다.**
     *
     * 읽히지 않는 파일을 저장해 두면 다음에 앱을 열 때 조용히 무시되고,
     * 담당자는 보정이 걸린 줄 안다.
     */
    suspend fun save(key: CalibrationKey, fileName: String, text: String): Result<ActiveCurve> =
        withContext(Dispatchers.IO) {
            val loaded = CalibrationFile.load(text).getOrElse { return@withContext Result.failure(it) }
            // **읽을 때와 똑같이 판정한다.** 여기만 열 선언을 빼면 저장할
            // 때와 다시 열 때의 답이 갈린다(독립 재검토 CFRF-01).
            val r = resolveCurveReading(
                loaded.headerLines,
                sha256Hex(text),
                // 새로 가져오는 참이다 — 확인 기록이 있을 수 없다.
                record = null,
            )
            // **걸 수 있는가**는 판정이 아니라 결론으로 묻는다. 판정만 보면
            // 지원하지 않는 형식이 「사람에게 묻는 중」으로 읽힌다.
            val settled = !r.needsPerson
            runCatching {
                // 원래 열쇠를 첫 줄 주석으로 남긴다. 해석기가 건너뛰는 줄이라
                // 곡선에는 영향이 없고, 폴더만 봐도 어느 기기 것인지 알 수 있다.
                val body = KEY_COMMENT_PREFIX + key.storageKey() + "\n" + text
                writeAtomically(fileFor(key), body)
                store.edit { p ->
                    p[nameKey(key)] = fileName
                    p[countKey(key)] = loaded.pointCount
                    p[atKey(key)] = System.currentTimeMillis()
                    // 새로 가져오면 켜진다. 꺼 두고 가져왔는데 그대로 꺼져
                    // 있으면 「가져왔는데 아무 일도 안 일어난다」가 된다.
                    //
                    // **다만 읽는 법이 안 정해졌으면 켜지 않는다**(독립 검토
                    // R04). 머리글이 우리 가정과 반대를 가리키는 파일을 그대로
                    // 걸면 보정이 반대로 두 배 걸리고, 그 차이는 ±30dB 경고로
                    // 잡히지 않는다 — 측정 마이크 파일은 대개 ±5dB 안쪽이다.
                    if (settled) p.remove(onKey(key)) else p[onKey(key)] = "false"
                    // **새 파일에는 옛 확인이 따라오지 않는다.** 해시가
                    // 달라 어차피 인정되지 않지만, 남겨 두면 같은 내용을
                    // 다시 넣었을 때 묻지 않고 지나간다 — 그 사이에 사람이
                    // 마음을 바꿨을 수도 있다.
                    p.remove(readShaKey(key))
                    p.remove(readAsKey(key))
                    p.remove(readRulesKey(key))
                }
                ActiveCurve(
                    curve = loaded.curve,
                    fileName = fileName,
                    // 읽는 법이 안 정해졌으면 꺼진 채로 들어온다(R04).
                    enabled = settled,
                    pointCount = loaded.pointCount,
                    headerLines = loaded.headerLines,
                    importedAtEpochMs = System.currentTimeMillis(),
                    readingConfirmationNeeded = !settled,
                    readingWhyKo = r.decision.whyKo.takeIf { !settled },
                    suggestedReading = r.decision.reading,
                    reading = r.reading ?: CurveReading.Response,
                    confirmationToken = CurveConfirmationToken(
                        key = key,
                        // **원본 내용의 해시다.** 앱이 앞에 붙이는 주석은
                        // 넣지 않는다 — 그러면 저장 열쇠가 바뀔 때 같은
                        // 파일의 해시가 달라진다.
                        sourceSha = sha256Hex(text),
                        rulesVersion = CURVE_READING_RULES_VERSION,
                    ),
                    readingUnsupportedKo = r.rejectionKo,
                )
            }.recoverCatching {
                throw IOException("보정 파일을 저장하지 못했습니다: ${it.message}")
            }
        }

    /**
     * 걸기를 켜거나 끔다. **파일은 그대로 둔다.**
     *
     * 지우는 것과 다르다 — 지우면 다시 가져와야 하고, 그러면 보정
     * 전·후를 견주지 못한다.
     *
     * **켜는 것은 읽는 법의 확인이 아니다**(독립 재검토 CFRF-01).
     *
     * 이 스위치가 받는 말은 「쓸 것인가」뿐이고, 「응답인가 보정값인가」는
     * 묻지도 저장하지도 않는다. 그런데 예전에는 이것을 켜면 부호 확인이
     * 통째로 건너뛰어졌다 — 검토자가 실제 DataStore 로 재현했다:
     *
     *     TOGGLE_WITHOUT_READING enabled=true correction=FromFile
     *
     * 그래서 켤 때는 [watch] 와 **같은 판정**을 한 번 더 한다. 화면에서
     * 스위치를 못 누르게 막는 것만으로는 모자라다 — 다른 부르는 곳이
     * 생기면 또 새어 나간다.
     *
     * **끄는 것은 언제나 된다.** 거는 것을 막는 규칙이지 떼는 것을 막을
     * 까닭은 없다.
     *
     * @return 막혔으면 그 까닭, 됐으면 null.
     */
    suspend fun setEnabled(key: CalibrationKey, on: Boolean): String? = withContext(Dispatchers.IO) {
        if (on) {
            val source = sourceTextOf(fileFor(key))
                ?: return@withContext "보정 파일을 찾지 못했습니다."
            val loaded = CalibrationFile.load(source).getOrNull()
                ?: return@withContext "보정 파일을 읽지 못했습니다."
            val prefs = store.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .first()
            curveEnableRefusalKo(resolve(loaded.headerLines, source, prefs, key))
                ?.let { return@withContext it }
        }
        runCatching {
            store.edit { p -> p[onKey(key)] = on.toString() }
        }.exceptionOrNull()?.let { "켜기를 저장하지 못했습니다: ${it.message}" }
    }

    /**
     * 사람이 **읽는 법을 정해 준다**(독립 재검토 CFRF-01 · 보고서 4장).
     *
     * 확인은 **그 내용**에 매단다. 파일 이름이나 전역 참·거짓에 매달면,
     * 이름이 같고 내용이 다른 파일을 넣었을 때 하지도 않은 확인이
     * 따라붙는다.
     *
     * 확인과 동시에 켠다 — 「읽는 법을 정했는데 왜 안 걸리지」를 겪게
     * 할 까닭이 없다.
     *
     * @return 막혔으면 그 까닭, 됐으면 null.
     */
    suspend fun confirmReading(token: CurveConfirmationToken, reading: CurveReading): String? =
        withContext(Dispatchers.IO) {
            val key = token.key
            // **규칙이 바뀌었으면 옛 화면의 선택을 받지 않는다.** 그 화면은
            // 지금과 다른 말을 물어 놓고 답을 받아 온 것이다.
            if (token.rulesVersion != CURVE_READING_RULES_VERSION) {
                return@withContext "읽기 규칙이 바뀌었습니다. 파일을 다시 보고 골라 주십시오."
            }
            val source = sourceTextOf(fileFor(key))
                ?: return@withContext "보정 파일을 찾지 못했습니다."
            // **화면이 보여 준 그 내용인가**(독립 재검토 CFRC-01).
            //
            // 이 검사가 없으면 저장소는 「지금 디스크에 있는 파일」에 확인을
            // 붙인다. 그 사이에 다른 파일이 들어왔으면 **사람이 본 적 없는
            // 파일에 그 선택이 붙고**, 그 뒤의 해시 검사는 전부 통과한다.
            if (sha256Hex(source) != token.sourceSha) {
                return@withContext "보고 계시던 파일이 그 사이에 바뀌었습니다. " +
                    "새 파일을 보고 다시 골라 주십시오."
            }
            // **부호로 풀 수 없는 형식은 확인으로도 안 풀린다**(CFRC-02).
            resolveCurveReading(
                CalibrationFile.parse(source).headerLines,
                token.sourceSha,
                record = null,
            ).rejectionKo?.let { return@withContext it }
            // **고른 규약으로 실제로 만들어 본다.** 만들어지지 않는 파일을
            // 확인만 받아 두면, 다음에 열 때 조용히 곡선이 없다.
            CalibrationFile.load(source, reading).getOrElse {
                return@withContext "이 파일을 「${reading.labelKo}」 로 읽지 못했습니다: ${it.message}"
            }
            runCatching {
                store.edit { p ->
                    p[readShaKey(key)] = sha256Hex(source)
                    p[readAsKey(key)] = reading.name
                    p[readRulesKey(key)] = CURVE_READING_RULES_VERSION
                    // 확인했으면 건다. 꺼 두고 싶으면 스위치로 끈다.
                    p.remove(onKey(key))
                }
            }.exceptionOrNull()?.let { "확인을 저장하지 못했습니다: ${it.message}" }
        }

    /**
     * 어느 마이크의 보정인지 적어 둔다. 빈 문자열이면 지운다.
     *
     * 측정에는 아무 영향이 없다 — 사람이 뒤에 보고 알아보려고 두는 것이다.
     */
    suspend fun setMicName(key: CalibrationKey, name: String) = withContext(Dispatchers.IO) {
        runCatching {
            store.edit { p -> p[micNameKey(key)] = name.trim() }
        }
        Unit
    }

    suspend fun clear(key: CalibrationKey) = withContext(Dispatchers.IO) {
        runCatching {
            fileFor(key).delete()
            store.edit { p ->
                p.remove(nameKey(key))
                p.remove(countKey(key))
                p.remove(atKey(key))
                p.remove(onKey(key))
                p.remove(micNameKey(key))
                // 확인 기록도 함께 지운다. 남겨 두면 같은 내용을 다시
                // 넣었을 때 묻지 않고 지나간다.
                p.remove(readShaKey(key))
                p.remove(readAsKey(key))
                p.remove(readRulesKey(key))
            }
        }
        Unit
    }
}
