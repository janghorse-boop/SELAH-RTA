package kr.joa.selahrta.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MicrophoneInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * **내장 마이크가 정말 갈라지는지 기기에 물어본다**
 * (S23 Ultra 개별 자동교정 지시서 2장·8.1).
 *
 * 지시서가 요구한 순서 그대로다:
 *
 * 1. `AudioManager.getMicrophones()` 로 마이크 메타데이터를 모은다.
 * 2. 내장 마이크 후보마다 **실제로 `AudioRecord` 를 열고 녹음을 시작한** 뒤
 *    `getRoutedDevice()` 와 `getActiveMicrophones()` 를 읽는다.
 * 3. 결과를 [judgeSeparationAcrossRepeats] 에 넘겨 세 상태 중 하나를 고른다.
 *
 * **목록에 보이는 것만으로 갈렸다고 하지 않는다.** 지금 이 앱은 S23 의
 * 내장 마이크를 주소(`bottom`·`back`)로 갈라 보고 있고 `setPreferredDevice`
 * 도 받아들여지지만, 그것은 증거가 아니다 — 실제로 어느 물리 마이크가
 * 소리를 받았는지는 녹음 중에만 알 수 있다.
 *
 * ## 안드로이드 판
 *
 * `getMicrophones`·`getActiveMicrophones` 는 **API 28 부터**다. 이 앱의
 * `minSdk` 는 26 이라 그 아래에서는 아무것도 못 묻는다 — 그때는 「구분
 * 불가」다. 없는 것을 있는 것처럼 적지 않는다.
 */
class MicrophoneProbe(private val context: Context) {

    private val scanner = InputDeviceScanner(context)

    /** 후보 하나를 이만큼 열어 둔다. 활성 마이크는 녹음이 시작돼야 나온다. */
    private val holdMs = 400L

    data class Report(
        /** `AudioManager.getMicrophones()` 가 알려 준 전부. 걸러내지 않는다. */
        val catalogue: List<ActiveMicInfo>,
        /** 회차마다의 후보별 결과. */
        val repeats: List<List<MicProbeRound>>,
        val verdict: MicSeparationResult,
        /** 왜 아무것도 못 했는지. 성공이면 null. */
        val blockedKo: String? = null,
    )

    /**
     * @param repeats 몇 번 되풀이할지. 지시서 2.4 의 재현성을 보려면 2 이상.
     */
    fun run(repeats: Int = 3): Report {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return blocked("마이크 권한이 없어 탐색할 수 없습니다.")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return blocked(
                "안드로이드 ${Build.VERSION.SDK_INT} 에서는 활성 마이크를 물어볼 수 " +
                    "없습니다(API 28부터). 물리 마이크가 갈리는지 확인할 방법이 없습니다.",
            )
        }

        val catalogue = catalogue()
        Log.i(TAG, "마이크 목록 ${catalogue.size}개: $catalogue")

        val builtIn = scanner.list().filter { it.kind == kr.joa.selahrta.domain.MicKind.BuiltIn }
        Log.i(TAG, "내장 후보 ${builtIn.size}개: ${builtIn.map { it.stableKey }}")

        val rounds = (1..repeats).map { n ->
            builtIn.map { probeOne(it) }.also { Log.i(TAG, "회차 $n: $it") }
        }
        val verdict = judgeSeparationAcrossRepeats(rounds)
        Log.i(TAG, "판정: ${verdict.state} — ${verdict.reasonKo}")
        return Report(catalogue, rounds, verdict)
    }

    private fun blocked(reasonKo: String): Report {
        Log.w(TAG, "탐색 불가: $reasonKo")
        return Report(
            catalogue = emptyList(),
            repeats = emptyList(),
            verdict = MicSeparationResult(MicSeparation.Indistinguishable, reasonKo),
            blockedKo = reasonKo,
        )
    }

    private fun catalogue(): List<ActiveMicInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return emptyList()
        return runCatching { am.microphones }.getOrNull().orEmpty().map { it.toInfo() }
    }

    /**
     * 후보 하나를 열고 **녹음을 시작한 뒤** 활성 마이크를 읽는다.
     *
     * 시작 전에는 규약상 `getRoutedDevice()` 가 null 이고 활성 마이크도
     * 비어 있다. 시작 전 값을 믿으면 확인하지 않은 것을 확인했다고 말하게
     * 된다(이 저장소가 R01 에서 이미 한 번 데인 자리다).
     */
    private fun probeOne(target: InputDeviceInfo): MicProbeRound {
        val minBytes = AudioRecord.getMinBufferSize(
            48_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) {
            return MicProbeRound(
                target.stableKey, target.displayName, null, emptyList(),
                "버퍼 크기를 얻지 못했습니다($minBytes).",
            )
        }

        var rec: AudioRecord? = null
        try {
            rec = @Suppress("MissingPermission") AudioRecord(
                CaptureSource.Unprocessed.androidValue,
                48_000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                minBytes * 2,
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                return MicProbeRound(
                    target.stableKey, target.displayName, null, emptyList(),
                    "열리지 않았습니다(state=${rec.state}).",
                )
            }

            val raw = scanner.findRaw(target.stableKey)
            val preferred = raw != null && rec.setPreferredDevice(raw)
            if (!preferred) Log.w(TAG, "선호 기기 지정 실패: ${target.stableKey}")

            rec.startRecording()
            // 실제로 소리를 읽어야 경로가 잡힌다. 읽지 않고 기다리기만 하면
            // 기기에 따라 계속 null 이다.
            val buf = FloatArray(1024)
            val until = System.nanoTime() + holdMs * 1_000_000
            while (System.nanoTime() < until) {
                rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            }

            val routed = rec.routedDevice?.let { scanner.infoOf(it) }
            val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { rec.activeMicrophones }.getOrNull().orEmpty().map { it.toInfo() }
            } else {
                emptyList()
            }

            return MicProbeRound(
                requestedKey = target.stableKey,
                requestedLabel = target.displayName,
                routedKey = routed?.stableKey,
                activeMics = active,
            )
        } catch (t: Throwable) {
            return MicProbeRound(
                target.stableKey, target.displayName, null, emptyList(),
                "여는 중 실패했습니다: $t",
            )
        } finally {
            runCatching { rec?.stop() }
            runCatching { rec?.release() }
        }
    }

    private companion object {
        const val TAG = "MicProbe"
    }
}

/**
 * `MicrophoneInfo` 는 **API 28 부터**다. 부르는 자리가 모두 판을 보고
 * 있지만, 확장 함수 안에서는 그 가드가 보이지 않는다 — 린트가
 * 옳게 짚었다. 여기서 계약을 밝힌다.
 */
@RequiresApi(Build.VERSION_CODES.P)
private fun MicrophoneInfo.toInfo(): ActiveMicInfo = ActiveMicInfo(
    id = id,
    address = address.orEmpty(),
    description = description.orEmpty(),
    location = location,
    directionality = directionality,
    group = group,
    indexInTheGroup = indexInTheGroup,
    channelMapping = runCatching {
        channelMapping.map { it.first to it.second }
    }.getOrDefault(emptyList()),
)
