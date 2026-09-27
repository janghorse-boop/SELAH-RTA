package kr.joa.selahrta.recording

/**
 * **기록할 때 소리를 어떤 꼴로 담을 것인가**(담당자 지시 2026-09-27).
 *
 * ## 왜 고르게 두는가
 *
 * 둘은 쓰임이 다르다. 예배를 다시 들어 보려는 것이면 [M4a] 가 맞고,
 * 나중에 그 소리로 **다시 분석**할 것이면 [Wav] 라야 한다. 손실 압축을
 * 거친 소리로 잰 값은 원본으로 잰 값과 같다고 말할 수 없다.
 *
 * ## 크기를 숨기지 않는다
 *
 * 2시간 예배면 m4a 가 100MB 대, WAV 는 700MB 에 가깝다. 고르는 자리에
 * 그 숫자를 함께 적는다 — 다 찬 뒤에 알면 늦다.
 */
enum class AudioFileFormat(
    val labelKo: String,
    val extension: String,
    val mimeType: String,
    /** 1분에 몇 MB 쯤인가. 어림이고, 화면이 그렇게 적는다. */
    val megabytesPerMinute: Double,
    val noteKo: String,
) {
    /**
     * AAC 압축(.m4a). 들어 보는 용도.
     *
     * 48kHz 모노 128kbps 면 1분에 1MB 쯤이다.
     */
    M4a(
        "m4a (압축)",
        "m4a",
        "audio/mp4",
        0.96,
        "들어 보기에 맞습니다. 손실 압축이라 이 파일로 다시 잰 값은 " +
            "원본으로 잰 값과 같다고 할 수 없습니다.",
    ),

    /**
     * 무압축 PCM(.wav). 다시 분석할 용도.
     *
     * **16비트로 담는다.** 마이크는 32비트 float 로 들어오지만, 그대로
     * 담으면 두 배가 되고 얻는 것은 거의 없다 — 들어오는 소리의
     * 다이내믹 레인지가 16비트를 넘지 않는다.
     */
    Wav(
        "WAV (무압축)",
        "wav",
        "audio/wav",
        5.76,
        "원본에 가깝습니다. 나중에 이 파일로 다시 분석할 수 있지만 " +
            "자리를 많이 차지합니다.",
    ),
    ;

    /** 이만큼 재면 몇 MB 인가. 화면이 「2시간이면 약 115MB」로 적는다. */
    fun estimateMegabytes(minutes: Double): Double = megabytesPerMinute * minutes

    /** 2시간 예배 어림. 고르는 자리에 그대로 적는다. */
    fun twoHourEstimateKo(): String = "2시간이면 약 ${estimateMegabytes(120.0).toInt()}MB"
}

/**
 * 소리 파일 이름. 겉장·타임라인과 같은 폴더에 둔다.
 *
 * **표와 같은 몸통을 쓴다**(`selah-rta-<시각>`). 소리는 자리를 많이
 * 차지해서 보낼 때 복사하지 않고 **그 자리에서 건넨다** — 그래서 폴더
 * 안의 이름이 그대로 받는 쪽에 뜬다. `audio.m4a` 로 두면 표 여러 장
 * 옆에서 어느 기록의 소리인지 알 수 없다.
 *
 * 옛 기록은 `audio.m4a` 로 남아 있지만 겉장이 제 이름을 적어 두므로
 * ([RecordedAudio.fileName]) 찾는 데는 지장이 없다.
 */
fun audioFileName(format: AudioFileFormat, startedAtEpochMs: Long): String =
    "${SessionExport.stem(startedAtEpochMs)}.${format.extension}"
