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
    fun dbl(key: String): Double = num(key) { it.toDoubleOrNull() } ?: 0.0
    fun dblOrNull(key: String): Double? = map[key]?.let { v ->
        v.toDoubleOrNull() ?: run { malformed += key; null }
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
            val d = parts[i].toDoubleOrNull() ?: run { malformed += key; return null }
            out[i] = d
        }
        return out
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
            correction = correction!!,
            corrected = corrected!!,
            settings = settings,
        ),
    )
}
