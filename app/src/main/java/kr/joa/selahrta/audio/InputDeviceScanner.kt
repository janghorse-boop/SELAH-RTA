package kr.joa.selahrta.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "InputDeviceScanner"

/**
 * 지금 쓸 수 있는 입력 기기를 찾고, 꽂히고 빠지는 것을 지켜본다.
 *
 * 안드로이드 자료형은 여기서만 만진다 — 바깥은 [InputDeviceInfo] 만 본다.
 * 그래야 고르는 규칙을 기기 없이 시험할 수 있다.
 */
class InputDeviceScanner(context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 지금 목록. */
    fun list(): List<InputDeviceInfo> {
        val raw = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        // 무엇이 보이는지 그대로 남긴다(명세 16장 진단). 기기마다 목록이
        // 크게 달라서, 문제가 생겼을 때 이 줄이 없으면 짐작밖에 할 수 없다.
        Log.i(TAG, "입력 기기 ${raw.size}개: " + raw.joinToString(" | ") {
            "id=${it.id} type=${it.type} name='${it.productName}' addr='${it.addressOrEmpty()}'"
        })
        return raw.mapNotNull { it.toInfo() }.dedupeByKey()
    }

    /**
     * 열쇠가 완전히 같은 기기만 합친다.
     *
     * 갤럭시 S23 은 내장 마이크를 둘 노출한다(실측: 하단·후면). 둘은
     * 물리적으로 다른 마이크라 **합치지 않는다** — 합치면 보정값이 섞인다.
     * 주소까지 같은 것만 중복으로 보는데, 그런 경우는 같은 기기가 두 번
     * 잡힌 것이다.
     */
    private fun List<InputDeviceInfo>.dedupeByKey(): List<InputDeviceInfo> =
        distinctBy { it.stableKey }

    /**
     * 목록이 바뀔 때마다 새 목록을 흘린다.
     *
     * 처음에 지금 목록을 한 번 내보낸다 — 구독한 쪽이 「아직 모름」 상태로
     * 시작하면 화면이 잠깐 「기기 없음」을 띄우게 된다.
     */
    fun watch(): Flow<List<InputDeviceInfo>> = callbackFlow {
        trySend(list())
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
                Log.i(TAG, "기기 추가: ${added?.joinToString { it.productName.toString() }}")
                trySend(list())
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
                Log.i(TAG, "기기 제거: ${removed?.joinToString { it.productName.toString() }}")
                trySend(list())
            }
        }
        audioManager.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        awaitClose { audioManager.unregisterAudioDeviceCallback(cb) }
    }

    /** 열쇠로 실제 안드로이드 기기를 찾는다. AudioRecord 에 넘길 때 쓴다. */
    fun findRaw(key: String): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.toInfo()?.stableKey == key }

    /**
     * 안드로이드가 준 기기를 우리 자료형으로 옮긴다.
     *
     * `AudioRecord.getRoutedDevice()` 가 돌려준 기기의 신원을 확인할 때 쓴다.
     * **목록에서 id 로 되찾지 않는다** — 목록에 없는 기기로 열릴 수도 있고,
     * 열쇠를 만드는 방식이 한 군데여야 어긋나지 않는다.
     */
    fun infoOf(raw: AudioDeviceInfo): InputDeviceInfo? = raw.toInfo()
}

/**
 * 주소를 읽는다. **API 28 부터 있는 값이다.**
 *
 * 그 아래(안드로이드 8)에서는 빈 문자열이 되고, 내장 마이크가 여럿이면
 * 서로 구별되지 않아 하나로 합쳐진다. 기능이 줄 뿐 죽지는 않는다 —
 * 가드 없이 부르면 NoSuchMethodError 로 앱이 통째로 멈춘다.
 */
private fun AudioDeviceInfo.addressOrEmpty(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) address?.trim().orEmpty() else ""

private fun AudioDeviceInfo.toInfo(): InputDeviceInfo? {
    val (kind, typeKo) = classifyInput(type) ?: return null
    val addr = addressOrEmpty()
    val name = productName?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: typeKo
    return InputDeviceInfo(
        id = id,
        // 이름이 비어 있는 기기가 있다. 빈 문자열을 그대로 쓰면 화면에
        // 아무것도 안 보이고 열쇠도 종류만 남아 서로 구별되지 않는다.
        productName = name,
        kind = kind,
        // 내장 마이크가 여럿이면 어느 것인지 적는다. 「SM-S918N」이 두 줄
        // 뜨면 담당자는 무엇을 고르는지 알 수 없다.
        typeKo = micPositionKo(addr)?.let { "$typeKo ($it)" } ?: typeKo,
        sampleRates = sampleRates?.toList() ?: emptyList(),
        channelCounts = channelCounts?.toList()?.filter { it > 0 }?.sorted() ?: emptyList(),
        address = addr,
    )
}
