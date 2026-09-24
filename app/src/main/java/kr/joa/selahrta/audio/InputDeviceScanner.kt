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
import kr.joa.selahrta.domain.MicKind

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
        //
        // **채널 수와 샘플레이트도 적는다.** 오디오 인터페이스를 처음
        // 꽂을 때 **안드로이드가 무엇을 알려 주는지**가 가장 먼저 알아야 할
        // 일인데, 그걸 볼 자리가 여기뿐이다. 4채널을 알리는지 2채널만
        // 알리는지에 따라 채널 선택의 설계가 갈린다(USB 오디오 지시서 5장).
        Log.i(TAG, "입력 기기 ${raw.size}개: " + raw.joinToString(" | ") {
            "id=${it.id} type=${it.type} name='${it.productName}'" +
                " addr='${it.addressOrEmpty()}'" +
                " ch=${it.channelCounts?.joinToString(",") ?: "?"}" +
                " fs=${it.sampleRates?.joinToString(",") ?: "?"}"
        })
        return listAll().foldBuiltInIntoOne()
    }

    /**
     * **접지 않은** 목록. 내장 마이크가 여럿이면 여럿 그대로 나온다.
     *
     * [MicrophoneProbe] 가 「이 폰이 물리 마이크를 갈라 주는가」를 물을 때
     * 쓴다. 사람에게 보이는 목록([list])은 접혀 있어서 여기를 쓸 수 없다 —
     * 접힌 목록으로 물으면 후보가 하나뿐이라 **언제나 「모른다」**가 되어,
     * 갈라 주는 폰이 나와도 알아채지 못한다.
     */
    fun listAll(): List<InputDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .mapNotNull { it.toInfo() }
            .distinctBy { it.id }

    /**
     * 내장 마이크를 **한 줄로 접는다**(2026-09-23 담당자 지시).
     *
     * ## 왜 접는가 — 갈라 놓을 수가 없다
     *
     * 예전에는 하단·상단(안드로이드 표기 `bottom`·`back`)을 따로 띄우고
     * 각각 보정을 두려 했다. 지시서 2.5 가 그것을 요구했기 때문이다.
     *
     * **실측이 그 전제를 무너뜨렸다**(2026-09-23, S23 Ultra):
     *
     * | 고른 것 | 실제로 켜진 마이크 |
     * |---|---|
     * | 하단 | `[22]` |
     * | 상단 | `[22, 24]` |
     *
     * 「상단」을 골라도 **하단이 함께 켜진다.** 빔포밍이 두 마이크를 섞어
     * 쓰기 때문이고, 우리가 받는 것은 그 섞인 결과다. 그 소리를 「상단
     * 마이크의 응답」이라고 부를 수 없다 — 그렇게 잰 보정 곡선은 두
     * 마이크와 빔포밍 알고리즘이 섞인 무언가다.
     *
     * 게다가 주소가 위치와도 맞지 않았다. 안드로이드는 `back` 이라 하는데
     * 실제 위치는 **상단**이다(담당자 확인). 그러니 화면에 「후면」이라고
     * 적던 것은 **틀린 안내**였다.
     *
     * 고를 수 없고, 이름도 못 믿는 구분은 사람에게 보여 봐야 **잘못
     * 고르게 할 뿐**이다. 그래서 기기명 한 줄로 접는다.
     *
     * ## 접어도 잃지 않는 것
     *
     * 갈라지는지 묻는 일([listAll])은 그대로 남는다. 다른 폰이 정말
     * 갈라 준다면 탐색이 그렇게 말할 것이고, 그때 이 결정을 다시 보면 된다.
     *
     * ## 어느 것이 남는가
     *
     * **안드로이드가 먼저 알리는 것**을 남긴다. S23 에서는 하단(id 22)이고,
     * 그것이 빔포밍 없이 홀로 켜지는 유일한 마이크이자 측정에 쓸 것이다.
     * 주소는 지운다 — 접은 뒤에도 주소가 남아 있으면 열쇠와 이름에 다시
     * 새어 나와, 폰을 재부팅해 순서가 바뀌면 **같은 마이크가 다른 기기로**
     * 보인다(보정값이 통째로 떨어져 나간다).
     */
    private fun List<InputDeviceInfo>.foldBuiltInIntoOne(): List<InputDeviceInfo> {
        val folded = ArrayList<InputDeviceInfo>(size)
        var builtInTaken = false
        for (d in this) {
            if (d.kind != MicKind.BuiltIn) {
                folded += d
                continue
            }
            if (builtInTaken) continue
            builtInTaken = true
            folded += d.copy(address = "", typeKo = "내장 마이크")
        }
        return folded.distinctBy { it.stableKey }
    }

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

    /**
     * **id 로** 실제 안드로이드 기기를 집는다. 탐색이 후보 하나하나를
     * 열어 볼 때 쓴다 — 내장 마이크는 열쇠가 하나로 묶여 있어
     * [findRaw] 로는 둘을 가를 수 없다.
     *
     * id 는 꽂았다 빼면 바뀌므로 **기억해 두는 용도로는 쓰지 않는다.**
     * 목록을 받은 그 자리에서만 쓴다.
     */
    fun findRawById(id: Int): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id == id }

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
