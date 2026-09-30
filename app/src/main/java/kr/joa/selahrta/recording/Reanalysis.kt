package kr.joa.selahrta.recording

import kr.joa.selahrta.dsp.MultiWeightEngine
import kr.joa.selahrta.dsp.RtaEngine
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import java.io.File
import java.io.IOException

/**
 * **저장한 WAV 를 다시 분석한다**(명세 Recording-E).
 *
 * 명세의 완료 기준은 「**원본 보존** + 새 분석 결과 생성」이다. 여기서는
 * 원본을 **읽기만** 한다 — 쓰는 곳이 하나도 없다.
 *
 * ## 왜 필요한가
 *
 * 예배가 끝난 뒤에 보정을 한다. 그러면 **이미 담아 둔 소리**를 새 보정으로
 * 다시 셈할 수 있어야 한다. 그러지 못하면 보정 전의 기록은 영영
 * 「참고용」으로 남는다 — 그 예배는 다시 오지 않는다.
 *
 * ## 라이브와 **같은 코드**를 쓴다
 *
 * [SessionRecorder] 를 그대로 돌린다. 재분석 전용 경로를 따로 지으면
 * **두 길이 조용히 갈린다** — 같은 소리에서 다른 숫자가 나오고, 어느
 * 쪽이 맞는지 가릴 길이 없다. 마이크 대신 [WavReader] 를 물릴 뿐이다.
 *
 * 그래서 이 파일에는 안드로이드가 하나도 없다. 전부 시험으로 확인한다.
 *
 * ## 무엇이 달라질 수 있나
 *
 * 다시 셈해서 **값이 달라지는** 까닭은 [ReanalysisSettings] 에 담긴
 * 것뿐이다 — 보정값·곡선·가중치·시간가중·FFT 길이. 소리 자체는 그대로다.
 *
 * ## 여기서 **못 되살리는 것**
 *
 * WAV 는 **16비트로 눌린** 소리다. 원래 분석은 float 을 그대로 봤다.
 * 그래서 아주 조용한 구간에서는 **원본과 완전히 같은 값이 나오지
 * 않는다**(16비트의 바닥은 -96dBFS 쯤이다). 예배당 음압에서는 문제가
 * 되지 않지만, 「같아야 한다」고 적지 않으려고 여기 남긴다.
 *
 * **끊겼던 자리도 되살아나지 않는다.** 녹음이 놓친 조각은 파일에
 * 없으므로, 다시 분석해도 그 구간은 그냥 짧을 뿐이다 — 원본 기록의
 * `missing` 행과 **다르게** 나온다.
 */
object Reanalysis {

    /** 한 번에 넣는 프레임 수. 라이브 덩어리(60ms 남짓)와 비슷하게 잡는다. */
    const val BLOCK_FRAMES = 2048

    /**
     * [file] 을 다시 분석한다.
     *
     * @param id 결과에 붙일 이름. **원본과 같아도 되고 달라도 된다** —
     *   어디에 저장할지는 부르는 쪽이 정한다.
     * @param onProgress 0~1. 긴 파일에서 화면이 멈춘 것처럼 보이지
     *   않게 한다. 길이를 모르면 부르지 않는다.
     */
    fun run(
        file: File,
        id: String,
        settings: ReanalysisSettings,
        onProgress: ((Float) -> Unit)? = null,
    ): Result<RecordedSession> = runCatching {
        if (!file.isFile || file.length() <= 0L) {
            throw IOException("소리 파일이 없습니다: ${file.name}")
        }
        file.inputStream().buffered().use { input ->
            val reader = WavReader(input)
            val fmt = reader.format

            // **소리에 적힌 샘플레이트를 쓴다.** 기록 겉장의 값이 아니다 —
            // 둘이 다르면 시간축이 통째로 어긋난다.
            val recorder = SessionRecorder(
                id = id,
                nominalSampleRate = fmt.sampleRate,
                startOffsetDb = settings.offsetDb,
                startReferenceOnly = settings.referenceOnly,
                startLeqWindowMs = settings.leqWindowMs,
                weighting = settings.weighting,
            )
            val spl = MultiWeightEngine(fmt.sampleRate, settings.timeWeight)
            val rta = RtaEngine(fmt.sampleRate, settings.fftSize)
            // **분석 가중을 실제로 건다**(독립 검토 R3-04). 예전에는 겉장에
            // 옛 값을 남기면서 엔진은 기본값(Z)으로 돌았다 — **적힌 것과
            // 셈한 것이 달랐다.**
            rta.setAnalysisWeighting(settings.analysisWeighting)
            rta.setCurve(settings.curve)

            val buf = FloatArray(BLOCK_FRAMES * fmt.channels)
            // 여러 채널이면 **한 채널만** 본다. 섞으면 좌우가 다른 소리일 때
            // 뜻이 무너진다.
            val mono = if (fmt.channels == 1) buf else FloatArray(BLOCK_FRAMES)
            val totalFrames = totalFramesOf(file, fmt)
            var done = 0L
            var lastReported = -1f

            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                if (fmt.channels > 1) {
                    val ch = settings.channelIndex.coerceIn(0, fmt.channels - 1)
                    for (i in 0 until n) mono[i] = buf[i * fmt.channels + ch]
                }
                // **잘림 깃발을 따로 세지 않는다.** [SessionRecorder] 가
                // `clipped || 엔진이 본 것` 으로 합치는데, 엔진은 지금 넣는
                // 바로 그 표본을 본다 — 여기서 한 번 더 세어 봐야 답이
                // 같다. 변이로 지워도 아무 시험이 안 잡혀 알았다.
                recorder.onBlock(mono, n, false, spl, rta)
                done += n
                if (totalFrames > 0 && onProgress != null) {
                    val p = (done.toFloat() / totalFrames).coerceIn(0f, 1f)
                    // 1% 마다만 알린다. 매 덩어리마다 부르면 화면이 그것만 한다.
                    if (p - lastReported >= 0.01f || p >= 1f) {
                        lastReported = p
                        onProgress(p)
                    }
                }
            }
            recorder.finish()
        }
    }

    /** 머리를 뺀 소리 길이에서 프레임 수를 어림한다. 모르면 0. */
    private fun totalFramesOf(file: File, fmt: WavFormat): Long {
        val body = file.length() - WavWriter.HEADER_BYTES
        if (body <= 0L) return 0L
        return body / (fmt.channels * 2L)
    }
}

/**
 * 다시 셈할 때 **무엇을 적용할 것인가.**
 *
 * 소리는 그대로다. 값이 달라지는 까닭은 전부 여기 있다.
 */
data class ReanalysisSettings(
    /** dBFS 에 더해 음압으로 옮기는 값. **보정을 새로 한 뒤 다시 셈하는 까닭**이다. */
    val offsetDb: Double,
    /** 그 보정이 짐작한 눈금인가. 미보정 구간의 숫자를 「음압」이라 부르지 않으려고 따라다닌다. */
    val referenceOnly: Boolean,
    val leqWindowMs: Long,
    /** 음압·Leq·MIN·MAX 에 걸 가중. **PEAK 에는 안 걸린다**(가중 전에 잰다). */
    val weighting: Weighting,
    val timeWeight: TimeWeight,
    val fftSize: Int,
    /** 마이크 보정 곡선. 없으면 null — 그때는 곡선이 안 걸린다. */
    val curve: kr.joa.selahrta.dsp.CalibrationCurve? = null,
    /** 여러 채널이면 어느 쪽을 볼 것인가. */
    val channelIndex: Int = 0,
    /**
     * RTA 대역에 걸 가중. 안 주면 **Z**(안 걸림).
     *
     * **맨 뒤에 둔다.** 가운데 넣었더니 위치 인자로 부르던 시험이
     * 깨졌다 — 이 저장소에서 두 번째로 같은 자리다.
     */
    val analysisWeighting: Weighting = Weighting.Z,
    /**
     * **이 곡선이 무엇인가**(독립 검토 R4-04).
     *
     * 값만 넘기면 겉장에 **옛 곡선 이름**이 그대로 남는다 — 새 곡선으로
     * 셈해 놓고 보고서는 옛 마이크 이름을 적는다. 사람이 「어느 곡선으로
     * 낸 값인가」를 되짚을 근거가 사라진다.
     */
    val curveLabel: String = "",
    /** 둘째 열을 무엇으로 읽었나. 곡선이 없으면 null. */
    val curveReading: kr.joa.selahrta.dsp.CurveReading? = null,
    /** 사람이 그 읽는 법을 확인했는가. */
    val curveReadingConfirmed: Boolean = false,
    /** 지금 보정이 **무엇에 맞춘** 것인가(교정기·소음계). */
    val calibrationSource: kr.joa.selahrta.calibration.CalibrationSource? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReanalysisSettings) return false
        return offsetDb == other.offsetDb &&
            referenceOnly == other.referenceOnly &&
            leqWindowMs == other.leqWindowMs &&
            weighting == other.weighting &&
            analysisWeighting == other.analysisWeighting &&
            curveLabel == other.curveLabel &&
            curveReading == other.curveReading &&
            curveReadingConfirmed == other.curveReadingConfirmed &&
            calibrationSource == other.calibrationSource &&
            timeWeight == other.timeWeight &&
            fftSize == other.fftSize &&
            channelIndex == other.channelIndex &&
            curve == other.curve
    }

    override fun hashCode(): Int {
        var h = offsetDb.hashCode()
        h = 31 * h + weighting.hashCode()
        h = 31 * h + fftSize
        h = 31 * h + (curve?.hashCode() ?: 0)
        return h
    }
}
