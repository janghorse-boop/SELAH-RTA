package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.CalibrationOutcome
import kr.joa.selahrta.dsp.CalibrationSettings
import kr.joa.selahrta.dsp.Normalized
import kr.joa.selahrta.dsp.QualityVerdict
import kr.joa.selahrta.dsp.ResponseCurve

/**
 * 프로파일과 곡선을 **글자로 적고 되읽는다.**
 *
 * ## 왜 손으로 짠 형식인가
 *
 * 이 저장소에는 직렬화 라이브러리가 없고, [CurveStore] 는 이미 곡선을
 * 글 파일로 둔다. 기록 하나 때문에 틀을 들이는 것보다 그 결을 따르는 쪽이
 * 맞다.
 *
 * 대신 손으로 짠 해석기가 조용히 틀리는 자리를 막아 둔다:
 * - 값에 든 줄바꿈·역슬래시는 **적을 때 피해 쓴다**(`\n`, `\\`).
 * - `=` 는 **처음 하나만** 가른다. 값에 `=` 가 들어도 된다.
 * - 없는 열쇠는 `null` 이다. **빈 값(`key=`)과 다르다.**
 * - 되읽을 때 길이·판을 검사하고, 어긋나면 **까닭과 함께 실패**한다.
 *
 * ## 곡선은 축을 한 번만 적는다
 *
 * [kr.joa.selahrta.dsp.calibrateResponse] 는 다섯 곡선을 **같은 축** 위에
 * 만든다(`logAxis` 한 번). 축을 다섯 벌 적으면 파일만 커지는 게 아니라,
 * 서로 어긋난 축이 적힐 수 있게 된다. 한 번만 적고 되읽을 때 길이를
 * 맞춰 보면 그 어긋남이 **불가능해진다.**
 */

private const val PROFILE_HEADER = "# selah-rta profile"
private const val CURVES_HEADER = "# selah-rta curves"

// ----------------------------------------------------------------------
// 글자 다루기
// ----------------------------------------------------------------------

private fun esc(s: String): String =
    s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

private fun unesc(s: String): String {
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                // 모르는 피함꼴은 **그대로 둔다.** 조용히 지우면 값이 바뀐다.
                else -> { out.append(c); i++ }
            }
        } else {
            out.append(c); i++
        }
    }
    return out.toString()
}

/** 줄들을 열쇠-값으로. 주석(`#`)과 빈 줄은 건너뛴다. */
private fun parseLines(text: String): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    text.lineSequence().forEach { raw ->
        val line = raw.removeSuffix("\r")
        if (line.isBlank() || line.startsWith("#")) return@forEach
        val at = line.indexOf('=')
        if (at <= 0) return@forEach
        map[line.substring(0, at)] = unesc(line.substring(at + 1))
    }
    return map
}

private class Reader(private val map: Map<String, String>) {
    val missing = mutableListOf<String>()
    val malformed = mutableListOf<String>()

    fun str(key: String): String = map[key] ?: run { missing += key; "" }
    fun strOrNull(key: String): String? = map[key]

    fun int(key: String): Int = num(key) { it.toIntOrNull() } ?: 0
    fun intOrNull(key: String): Int? = map[key]?.let { v ->
        v.toIntOrNull() ?: run { malformed += key; null }
    }

    fun long(key: String): Long = num(key) { it.toLongOrNull() } ?: 0L

    /**
     * **`NaN`·`Infinity` 는 숫자로 받지 않는다**(독립 검증 CP03).
     *
     * `toDoubleOrNull` 은 그 둘을 **성공으로** 해석한다. 그대로 두면
     * 손상된 파일이 「길이가 맞는 정상 곡선」으로 읽혀 NaN 이 계산과
     * 그래프로 번진다 — 그래프는 조용히 비고, 보정값은 조용히 사라진다.
     */
    fun dbl(key: String): Double = num(key) { it.toFiniteOrNull() } ?: 0.0
    fun dblOrNull(key: String): Double? = map[key]?.let { v ->
        v.toFiniteOrNull() ?: run { malformed += key; null }
    }

    fun bool(key: String): Boolean = when (map[key]) {
        "true" -> true
        "false" -> false
        null -> { missing += key; false }
        else -> { malformed += key; false }
    }

    fun boolOrNull(key: String): Boolean? = when (map[key]) {
        "true" -> true
        "false" -> false
        null -> null
        else -> { malformed += key; null }
    }

    inline fun <reified E : Enum<E>> enum(key: String): E? {
        val v = map[key] ?: run { missing += key; return null }
        return enumValues<E>().firstOrNull { it.name == v } ?: run { malformed += key; null }
    }

    fun doubles(key: String): DoubleArray? {
        val v = map[key] ?: run { missing += key; return null }
        if (v.isEmpty()) return DoubleArray(0)
        val parts = v.split(',')
        val out = DoubleArray(parts.size)
        for (i in parts.indices) {
            val d = parts[i].toFiniteOrNull() ?: run { malformed += key; return null }
            out[i] = d
        }
        return out
    }

    /** 값이 조건을 어기면 깨진 것으로 적는다. 까닭을 열쇠 옆에 붙인다. */
    fun check(key: String, ok: Boolean, whyKo: String) {
        if (!ok) malformed += "$key($whyKo)"
    }

    /** `0`·`1` 만 들어 있는 줄. 참/거짓 배열이다. */
    fun flags(key: String): BooleanArray? {
        val v = map[key] ?: run { missing += key; return null }
        if (v.any { it != '0' && it != '1' }) { malformed += key; return null }
        return BooleanArray(v.length) { v[it] == '1' }
    }

    private inline fun <T> num(key: String, parse: (String) -> T?): T? {
        val v = map[key] ?: run { missing += key; return null }
        return parse(v) ?: run { malformed += key; null }
    }

    /** 모자라거나 깨진 것이 있으면 까닭을 한 줄로. 없으면 null. */
    fun problem(): String? = when {
        missing.isNotEmpty() && malformed.isNotEmpty() ->
            "빠진 항목: ${missing.joinToString()} / 깨진 항목: ${malformed.joinToString()}"
        missing.isNotEmpty() -> "빠진 항목: ${missing.joinToString()}"
        malformed.isNotEmpty() -> "깨진 항목: ${malformed.joinToString()}"
        else -> null
    }
}

/**
 * 유한한 수일 때만 돌려준다.
 *
 * `"NaN"`·`"Infinity"`·`"-Infinity"` 는 `toDoubleOrNull` 이 **성공으로**
 * 읽는다. 여기서 막지 않으면 그 값이 곡선이 되어 나간다(CP03).
 */
private fun String.toFiniteOrNull(): Double? = toDoubleOrNull()?.takeIf { it.isFinite() }

private fun StringBuilder.put(key: String, value: Any?) {
    if (value == null) return // 없는 것은 **적지 않는다**. 빈 값과 다르다.
    append(key).append('=').append(esc(value.toString())).append('\n')
}

// ----------------------------------------------------------------------
// 프로파일
// ----------------------------------------------------------------------

/** 프로파일 기록을 글자로. */
fun encodeProfile(p: MeasuredProfile): String = buildString {
    append(PROFILE_HEADER).append(" v").append(p.schemaVersion).append('\n')
    put("schemaVersion", p.schemaVersion)
    put("algorithmVersion", p.algorithmVersion)
    put("id", p.id)
    put("createdAtEpochMs", p.createdAtEpochMs)
    put("updatedAtEpochMs", p.updatedAtEpochMs)

    val e = p.environment
    put("env.deviceKey", e.deviceKey)
    put("env.deviceAddress", e.deviceAddress)
    put("env.micKind", e.micKind.name)
    put("env.audioSource", e.audioSource.name)
    put("env.sampleRate", e.sampleRate)
    put("env.channelCount", e.channelCount)
    put("env.channelIndex", e.channelIndex)
    put("env.manufacturer", e.manufacturer)
    put("env.model", e.model)
    put("env.osBuild", e.osBuild)

    put("separation", p.separation.name)

    put("ref.calFileName", p.reference.calFileName)
    put("ref.calSha256", p.reference.calSha256)
    put("ref.inputChannelIndex", p.reference.inputChannelIndex)
    put("ref.micName", p.reference.micName)

    put("quality.verdict", p.quality.verdict.name)
    put("quality.repeatSpreadDb", p.quality.repeatSpreadDb)
    put("quality.referenceDriftDb", p.quality.referenceDriftDb)
    put("quality.usableBandRatio", p.quality.usableBandRatio)
    put("quality.worstSnrDb", p.quality.worstSnrDb)
    put("quality.dspVerifiedBySignal", p.quality.dspVerifiedBySignal)

    put("levelOffsetDb", p.levelOffsetDb)
    put("normalizeBandLowHz", p.normalizeBandLowHz)
    put("normalizeBandHighHz", p.normalizeBandHighHz)
    put("smoothingFraction", p.smoothingFraction)
    put("maxCorrectionDb", p.maxCorrectionDb)
    put("validFromHz", p.validFromHz)
    put("validToHz", p.validToHz)
    put("curvesFileName", p.curvesFileName)
    put("caseRemoved", p.caseRemoved)
    put("enabled", p.enabled)
}

/**
 * 글자를 프로파일 기록으로.
 *
 * **모르는 판은 읽지 않는다.** 더 새 판에는 우리가 모르는 항목이 있고,
 * 그걸 조용히 버리면 프로파일의 뜻이 달라진 채로 걸린다.
 */
fun decodeProfile(text: String): Result<MeasuredProfile> {
    val r = Reader(parseLines(text))

    val schema = r.int("schemaVersion")
    if (r.missing.contains("schemaVersion")) {
        return Result.failure(IllegalArgumentException("프로파일 파일이 아닙니다(판 번호가 없습니다)."))
    }
    if (schema > PROFILE_SCHEMA_VERSION) {
        return Result.failure(
            IllegalArgumentException(
                "더 새 판(v$schema)의 프로파일입니다. 앱을 올린 뒤 다시 시도하십시오.",
            ),
        )
    }

    val micKind = r.enum<MicKind>("env.micKind")
    val source = r.enum<CaptureSource>("env.audioSource")
    val separation = r.enum<MicSeparation>("separation")
    val verdict = r.enum<QualityVerdict>("quality.verdict")

    val environment = ProfileEnvironment(
        deviceKey = r.str("env.deviceKey"),
        deviceAddress = r.strOrNull("env.deviceAddress") ?: "",
        micKind = micKind ?: MicKind.BuiltIn,
        audioSource = source ?: CaptureSource.Mic,
        sampleRate = r.int("env.sampleRate"),
        channelCount = r.int("env.channelCount"),
        channelIndex = r.int("env.channelIndex"),
        manufacturer = r.strOrNull("env.manufacturer") ?: "",
        model = r.strOrNull("env.model") ?: "",
        osBuild = r.strOrNull("env.osBuild") ?: "",
    )

    val profile = MeasuredProfile(
        id = r.str("id"),
        schemaVersion = schema,
        algorithmVersion = r.int("algorithmVersion"),
        createdAtEpochMs = r.long("createdAtEpochMs"),
        updatedAtEpochMs = r.long("updatedAtEpochMs"),
        environment = environment,
        separation = separation ?: MicSeparation.Indistinguishable,
        reference = ReferenceRecord(
            calFileName = r.strOrNull("ref.calFileName"),
            calSha256 = r.strOrNull("ref.calSha256"),
            inputChannelIndex = r.intOrNull("ref.inputChannelIndex"),
            micName = r.strOrNull("ref.micName") ?: "",
        ),
        quality = ProfileQuality(
            verdict = verdict ?: QualityVerdict.Fail,
            repeatSpreadDb = r.dbl("quality.repeatSpreadDb"),
            referenceDriftDb = r.dbl("quality.referenceDriftDb"),
            usableBandRatio = r.dbl("quality.usableBandRatio"),
            worstSnrDb = r.dblOrNull("quality.worstSnrDb"),
            dspVerifiedBySignal = r.bool("quality.dspVerifiedBySignal"),
        ),
        levelOffsetDb = r.dbl("levelOffsetDb"),
        normalizeBandLowHz = r.dbl("normalizeBandLowHz"),
        normalizeBandHighHz = r.dbl("normalizeBandHighHz"),
        smoothingFraction = r.dbl("smoothingFraction"),
        maxCorrectionDb = r.dbl("maxCorrectionDb"),
        validFromHz = r.dblOrNull("validFromHz"),
        validToHz = r.dblOrNull("validToHz"),
        curvesFileName = r.str("curvesFileName"),
        caseRemoved = r.boolOrNull("caseRemoved"),
        enabled = r.bool("enabled"),
    )

    // **길이와 꼴이 맞는 것과 뜻이 맞는 것은 다르다**(독립 검증 CP03).
    // 여기서 보지 않으면 sampleRate 0 이나 채널 번호가 채널 수보다 큰
    // 프로파일이 정상으로 읽혀, 경로 재대조가 엉뚱한 값을 견준다.
    r.check("env.sampleRate", environment.sampleRate > 0, "0 이하")
    r.check("env.channelCount", environment.channelCount >= 1, "1 미만")
    r.check(
        "env.channelIndex",
        environment.channelIndex in 0 until environment.channelCount.coerceAtLeast(1),
        "채널 수 밖",
    )
    r.check("algorithmVersion", profile.algorithmVersion >= 1, "1 미만")
    r.check("createdAtEpochMs", profile.createdAtEpochMs > 0, "0 이하")
    r.check("updatedAtEpochMs", profile.updatedAtEpochMs >= profile.createdAtEpochMs, "만든 때보다 이르다")
    r.check("id", profile.id.isNotBlank(), "비어 있음")
    r.check("curvesFileName", profile.curvesFileName.isNotBlank(), "비어 있음")
    r.check("quality.usableBandRatio", profile.quality.usableBandRatio in 0.0..1.0, "0~1 밖")
    r.check("normalizeBandHighHz", profile.normalizeBandHighHz > profile.normalizeBandLowHz, "낮은 쪽보다 작다")
    r.check("normalizeBandLowHz", profile.normalizeBandLowHz > 0.0, "0 이하")
    r.check("smoothingFraction", profile.smoothingFraction > 0.0, "0 이하")
    r.check("maxCorrectionDb", profile.maxCorrectionDb > 0.0, "0 이하")
    // 유효 범위는 **둘 다 있거나 둘 다 없어야** 한다. 한쪽만 있으면
    // 「어디부터 믿을 수 있는지」를 말할 수 없다.
    r.check(
        "validFromHz/validToHz",
        (profile.validFromHz == null) == (profile.validToHz == null),
        "한쪽만 있다",
    )
    if (profile.validFromHz != null && profile.validToHz != null) {
        r.check("validFromHz", profile.validFromHz > 0.0, "0 이하")
        r.check("validToHz", profile.validToHz > profile.validFromHz, "아래끝보다 작다")
    }

    // **판정을 기본값으로 메우지 않는다.** 빠진 것이 있으면 실패다 —
    // Fail 로 채워 두면 「품질 미달」로 보여 사람이 엉뚱한 곳을 고친다.
    r.problem()?.let { return Result.failure(IllegalArgumentException("프로파일을 읽지 못했습니다. $it")) }
    return Result.success(profile)
}

// ----------------------------------------------------------------------
// 곡선 넷
// ----------------------------------------------------------------------

private fun StringBuilder.putCurve(prefix: String, c: ResponseCurve) {
    put("$prefix.db", c.db.joinToString(","))
    put("$prefix.valid", c.valid.joinToString("") { if (it) "1" else "0" })
}

/**
 * 곡선 넷과 그 설정을 글자로.
 *
 * 축은 [CalibrationOutcome.reference] 의 것 하나만 적는다 — 다섯이 모두
 * 같은 축 위에 있다.
 */
fun encodeCurves(o: CalibrationOutcome): String = buildString {
    // **축이 같다는 것을 적기 전에 확인한다**(독립 검증 CP03).
    // calibrateResponse 는 늘 같은 축을 쓰지만 CalibrationOutcome 과
    // ResponseCurve 는 공개 타입이고 배열은 바뀔 수 있다. 길이만으로는
    // 모든 호출 경로를 보증하지 못한다 — 어긋난 채 적히면 되읽을 때
    // 길이 검사를 통과해 **없던 자리에 값이 놓인다.**
    val axis = o.reference.hz
    listOf(
        "internalRaw" to o.internalRaw,
        "internalNormalized" to o.internalNormalized.curve,
        "correction" to o.correction,
        "corrected" to o.corrected,
    ).forEach { (name, c) ->
        require(c.hz.contentEquals(axis)) { "$name 이 기준과 다른 축 위에 있다" }
    }

    append(CURVES_HEADER).append(" v").append(PROFILE_SCHEMA_VERSION).append('\n')
    put("schemaVersion", PROFILE_SCHEMA_VERSION)
    put("axis", o.reference.hz.joinToString(","))
    putCurve("reference", o.reference)
    putCurve("internalRaw", o.internalRaw)
    putCurve("internalNormalized", o.internalNormalized.curve)
    put("normalized.offsetDb", o.internalNormalized.offsetDb)
    put("normalized.bandLowHz", o.internalNormalized.bandLowHz)
    put("normalized.bandHighHz", o.internalNormalized.bandHighHz)
    put("normalized.pointsUsed", o.internalNormalized.pointsUsed)
    // **기준 쪽 오프셋도 남긴다**(지시서 4장: 「적용한 정규화 오프셋」).
    // 둘을 같이 봐야 레벨을 어떻게 맞췄는지 되짚을 수 있다. 대역은 두
    // 곡선이 같은 값을 쓰므로 위에 적은 것으로 갈음한다.
    put("refNormalized.offsetDb", o.referenceNormalized.offsetDb)
    put("refNormalized.pointsUsed", o.referenceNormalized.pointsUsed)
    putCurve("correction", o.correction)
    putCurve("corrected", o.corrected)
    put("settings.axisFromHz", o.settings.axisFromHz)
    put("settings.axisToHz", o.settings.axisToHz)
    put("settings.pointsPerOctave", o.settings.pointsPerOctave)
    put("settings.normalizeBandLowHz", o.settings.normalizeBandLowHz)
    put("settings.normalizeBandHighHz", o.settings.normalizeBandHighHz)
    put("settings.smoothingFraction", o.settings.smoothingFraction)
    put("settings.maxCorrectionDb", o.settings.maxCorrectionDb)
}

/**
 * 글자를 곡선 넷으로.
 *
 * **길이가 축과 다르면 실패한다.** 그대로 만들면 [ResponseCurve] 의
 * `init` 이 터지거나, 더 나쁘게는 짧은 쪽이 조용히 잘린 채 그려진다.
 */
fun decodeCurves(text: String): Result<CalibrationOutcome> {
    val r = Reader(parseLines(text))

    val schema = r.int("schemaVersion")
    if (r.missing.contains("schemaVersion")) {
        return Result.failure(IllegalArgumentException("곡선 파일이 아닙니다(판 번호가 없습니다)."))
    }
    if (schema > PROFILE_SCHEMA_VERSION) {
        return Result.failure(IllegalArgumentException("더 새 판(v$schema)의 곡선 파일입니다."))
    }

    val axis = r.doubles("axis")
    if (axis == null || axis.isEmpty()) {
        return Result.failure(IllegalArgumentException("곡선을 읽지 못했습니다. 주파수 축이 없습니다."))
    }
    // **축은 양수이고 엄격히 커져야 한다**(CP03). 보간과 평활이 그 전제
    // 위에 서 있다 — 뒤섞인 축으로도 길이 검사는 통과한다.
    if (axis[0] <= 0.0) {
        return Result.failure(IllegalArgumentException("곡선을 읽지 못했습니다. 주파수 축에 0 이하가 있습니다."))
    }
    for (i in 1 until axis.size) {
        if (axis[i] <= axis[i - 1]) {
            return Result.failure(
                IllegalArgumentException(
                    "곡선을 읽지 못했습니다. 주파수 축이 커지지 않습니다($i 번째: ${axis[i - 1]} → ${axis[i]}).",
                ),
            )
        }
    }

    fun curve(prefix: String): ResponseCurve? {
        val db = r.doubles("$prefix.db") ?: return null
        val valid = r.flags("$prefix.valid") ?: return null
        if (db.size != axis.size || valid.size != axis.size) {
            r.malformed += "$prefix(축 ${axis.size} · 값 ${db.size} · 유효 ${valid.size})"
            return null
        }
        return ResponseCurve(axis.copyOf(), db, valid)
    }

    val reference = curve("reference")
    val internalRaw = curve("internalRaw")
    val normalizedCurve = curve("internalNormalized")
    val correction = curve("correction")
    val corrected = curve("corrected")

    val settings = CalibrationSettings(
        axisFromHz = r.dbl("settings.axisFromHz"),
        axisToHz = r.dbl("settings.axisToHz"),
        pointsPerOctave = r.int("settings.pointsPerOctave"),
        normalizeBandLowHz = r.dbl("settings.normalizeBandLowHz"),
        normalizeBandHighHz = r.dbl("settings.normalizeBandHighHz"),
        smoothingFraction = r.dbl("settings.smoothingFraction"),
        maxCorrectionDb = r.dbl("settings.maxCorrectionDb"),
    )
    val offsetDb = r.dbl("normalized.offsetDb")
    val bandLow = r.dbl("normalized.bandLowHz")
    val bandHigh = r.dbl("normalized.bandHighHz")
    val pointsUsed = r.int("normalized.pointsUsed")
    val refOffsetDb = r.dbl("refNormalized.offsetDb")
    val refPointsUsed = r.int("refNormalized.pointsUsed")

    // 두 곡선은 **같은 자리**에서 맞춘다(독립 검증 RCP01). 다른 수가
    // 적혀 있으면 그 파일은 다른 규칙으로 만들어진 것이다.
    r.check(
        "refNormalized.pointsUsed",
        refPointsUsed == pointsUsed,
        "대상($pointsUsed)과 다르다",
    )

    r.problem()?.let { return Result.failure(IllegalArgumentException("곡선을 읽지 못했습니다. $it")) }

    return Result.success(
        CalibrationOutcome(
            reference = reference!!,
            internalRaw = internalRaw!!,
            internalNormalized = Normalized(
                curve = normalizedCurve!!,
                offsetDb = offsetDb,
                bandLowHz = bandLow,
                bandHighHz = bandHigh,
                pointsUsed = pointsUsed,
            ),
            referenceNormalized = Normalized(
                curve = reference,
                offsetDb = refOffsetDb,
                bandLowHz = bandLow,
                bandHighHz = bandHigh,
                pointsUsed = refPointsUsed,
            ),
            correction = correction!!,
            corrected = corrected!!,
            settings = settings,
        ),
    )
}
