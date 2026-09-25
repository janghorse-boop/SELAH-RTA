package kr.joa.selahrta.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

private const val TAG = "AudioEffects"

/** 신호를 건드리는 효과 하나의 상태. */
data class EffectState(
    val nameKo: String,
    /** 이 기기에 그 효과가 있는가. */
    val available: Boolean,
    /** 우리가 연 세션에 실제로 붙어 있었는가. */
    val wasEnabled: Boolean,
    /** 끄는 데 성공했는가. 없거나 이미 꺼져 있었으면 true. */
    val disabled: Boolean,
) {
    /** 화면에 적을 한 마디. 「없음」과 「껐음」과 「켜진 채」는 다른 사실이다. */
    val statusKo: String
        get() = when {
            !available -> "기기에 없음"
            !disabled -> "켜진 채 — 못 끔"
            wasEnabled -> "껐음"
            else -> "꺼짐"
        }
}

/**
 * 우리 세션에 붙은 신호 가공을 끈 결과.
 *
 * **이것이 이 앱의 정확도를 가르는 자리다.** 자동 게인(AGC)이 살아 있으면
 * 큰 소리가 들어올 때 시스템이 몰래 입력을 줄인다 — 음압을 재는 도구가
 * 「음압이 안 올라가는」 도구가 된다. 잡음 억제(NS)는 조용한 대역을 깎아
 * 주파수 균형을 통째로 바꾼다.
 *
 * UNPROCESSED 입력을 못 여는 기기(갤럭시 S23 이 그렇다)에서는 이 API 로
 * 끄는 것이 유일한 방어다. 끄지 못했으면 **끄지 못했다고 화면에 적는다.**
 */
data class EffectsReport(
    val agc: EffectState,
    val ns: EffectState,
    val aec: EffectState,
) {
    /** 세 가지가 모두 꺼져 있는가. 하나라도 살아 있으면 절대값을 믿기 어렵다. */
    val allClear: Boolean get() = agc.disabled && ns.disabled && aec.disabled

    /** 우리가 실제로 꺼서 상태를 바꾼 것들. */
    val turnedOff: List<String>
        get() = listOf(agc, ns, aec).filter { it.wasEnabled && it.disabled }.map { it.nameKo }

    /** 살아 있는 채로 남은 것들. 화면에 경고로 띄운다. */
    val stillOn: List<String>
        get() = listOf(agc, ns, aec).filter { !it.disabled }.map { it.nameKo }
}

/**
 * 열린 오디오 세션에 붙은 가공 효과를 끈다.
 *
 * 효과 객체는 `AudioRecord` 가 살아 있는 동안 함께 살아 있어야 한다 —
 * release 하면 설정이 되돌아갈 수 있으므로 [handles] 에 담아 두고
 * 캡처를 닫을 때 같이 놓는다.
 */
class AudioEffectsController {

    private val handles = mutableListOf<AudioEffect>()

    fun disableProcessing(sessionId: Int): EffectsReport {
        val agc = turnOff(
            "자동 게인(AGC)",
            AutomaticGainControl.isAvailable(),
        ) { AutomaticGainControl.create(sessionId) }

        val ns = turnOff(
            "잡음 억제(NS)",
            NoiseSuppressor.isAvailable(),
        ) { NoiseSuppressor.create(sessionId) }

        val aec = turnOff(
            "반향 제거(AEC)",
            AcousticEchoCanceler.isAvailable(),
        ) { AcousticEchoCanceler.create(sessionId) }

        Log.i(TAG, "가공 끄기: agc=$agc ns=$ns aec=$aec")
        return EffectsReport(agc, ns, aec)
    }

    private fun turnOff(
        nameKo: String,
        available: Boolean,
        create: () -> AudioEffect?,
    ): EffectState {
        if (!available) {
            // 기기에 그 효과 자체가 없다. 걱정할 것이 없으므로 「꺼짐」으로 본다.
            return EffectState(nameKo, available = false, wasEnabled = false, disabled = true)
        }
        val fx = runCatching { create() }.getOrNull()
            ?: return EffectState(nameKo, available = true, wasEnabled = false, disabled = false)

        handles += fx
        val was = runCatching { fx.enabled }.getOrDefault(false)
        val ok = runCatching { fx.setEnabled(false) }.getOrDefault(AudioEffect.ERROR) ==
            AudioEffect.SUCCESS
        // setEnabled 가 성공을 돌려줘도 실제로 꺼졌는지 다시 읽어 확인한다.
        // 「요청했다」와 「그렇게 됐다」는 다르다.
        val nowEnabled = runCatching { fx.enabled }.getOrDefault(true)
        return EffectState(
            nameKo = nameKo,
            available = true,
            wasEnabled = was,
            disabled = ok && !nowEnabled,
        )
    }

    /** 캡처를 닫을 때 함께 놓는다. */
    fun release() {
        handles.forEach { runCatching { it.release() } }
        handles.clear()
    }
}
