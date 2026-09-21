package kr.joa.selahrta.audio

import kr.joa.selahrta.dsp.BlockStats
import kr.joa.selahrta.dsp.blockStats
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 읽기 루프가 바깥에 기대는 것 전부. **시험에서는 가짜로 바꿔 끼운다.**
 *
 * `AudioRecord` 를 직접 부르면 이 루프를 기기 없이 시험할 수 없다. 그런데
 * 여기서 틀리는 것들 — 멈춘 뒤에 덩어리를 한 번 더 흘리거나, 읽기 오류로
 * 죽고도 아무에게 안 알리거나 — 은 전부 **순서**의 문제라 기기가 있어야
 * 드러나는 것이 아니다. 오히려 기기로는 재현하기 어렵다(독립 재검증
 * F02·L01).
 */
interface CaptureRecorder {
    /**
     * PCM 을 읽어 [into] 에 −1..1 로 채운다.
     *
     * 돌려주는 값은 읽은 프레임 수다. 0 은 「멈추는 중」, 음수는 오류이며
     * `AudioRecord` 의 오류 코드를 그대로 쓴다.
     *
     * **데이터가 찰 때까지 기다린다**(READ_BLOCKING). 기다리는 동안 바깥이
     * 멈출 수 있다는 것이 이 루프의 어려운 점이다.
     */
    fun read(into: FloatArray, frames: Int): Int

    /**
     * 지금 실제로 붙어 있는 기기. 아직 모르면 null.
     *
     * 녹음을 시작한 뒤에야 답이 나오는 기기가 있어, 첫 덩어리를 받은 뒤
     * 한 번 더 물어본다(독립 검증 R01).
     */
    fun routedDevice(): InputDeviceInfo?
}

/** 루프가 바깥에 알리는 일들. */
interface CaptureLoopCallbacks {
    fun onBlock(block: AudioBlock, stats: BlockStats)

    /** 경로가 확인됐다. 루프가 늦게 확인에 성공한 경우에만 온다. */
    fun onRouteConfirmed(device: InputDeviceInfo)

    /** 캡처가 **스스로** 끝났다. 사람이 멈춘 경우는 오지 않는다. */
    fun onEnded(end: CaptureEnd)
}

/**
 * 캡처 읽기 루프(명세 17장).
 *
 * `MicSource` 에서 꺼내 온 것이라 안드로이드에 기대지 않는다. 지켜야 할
 * 순서가 셋 있고, 셋 다 예전에 틀렸던 자리다:
 *
 * 1. **읽고 나서 `running` 을 다시 본다.** `read()` 가 기다리는 동안 누가
 *    멈췄을 수 있다. 안 보면 이미 끝난 캡처가 덩어리를 한 번 더 흘려보내고,
 *    그 덩어리는 **다음 측정의 엔진**으로 들어간다(F02).
 * 2. **읽기 오류로 끝날 때 반드시 알린다.** 조용히 빠져나가면 화면은
 *    「측정 중」인 채로 마지막 숫자가 굳는다(L01).
 * 3. **경로 확인을 한 번 더 시도한다.** 녹음을 시작한 직후에는 아직 답을
 *    못 주는 기기가 있다(R01).
 *
 * @param running 바깥이 내리는 멈춤 신호. 루프는 읽기 전후로 본다.
 * @param routeAlreadyConfirmed 이미 경로가 확인됐는가. 확인됐으면 다시 묻지 않는다.
 */
fun runCaptureLoop(
    recorder: CaptureRecorder,
    sampleRate: Int,
    running: AtomicBoolean,
    callbacks: CaptureLoopCallbacks,
    routeAlreadyConfirmed: () -> Boolean,
    nowNs: () -> Long = System::nanoTime,
    /** 한 덩어리는 약 21ms(1024 프레임 @48kHz). FFT 4096 을 채우기에 알맞다. */
    framesPerBlock: Int = 1024,
) {
    // **버퍼를 한 번만 만든다.** 덩어리마다 새로 만들면 초당 50번 쓰레기가
    // 생겨 장시간 예배에서 GC 가 캡처를 멈춘다.
    val floats = FloatArray(framesPerBlock)

    // startRecording 직후에도 경로가 아직 안 잡히는 기기가 있다.
    // 소리가 실제로 들어온 뒤 한 번 더 물어본다.
    var retriedConfirm = false

    while (running.get()) {
        val read = recorder.read(floats, framesPerBlock)

        // 이 덩어리의 자료가 손에 들어온 시각. 나중에 녹음과 그래프를 맞출 때
        // 기준이 되는 값이라 단조 시계를 쓴다(벽시계는 뒤로 갈 수 있다).
        val ready = nowNs()

        // 읽고 나서 다시 본다 — 위 1번.
        if (!running.get()) return

        if (read <= 0) {
            // 음수는 오류, 0 은 멈추는 중이다. 둘 다 이 덩어리는 버린다.
            callbacks.onBlock(
                AudioBlock(floats, 0, sampleRate, ready),
                BlockStats(0.0, 0.0, 0),
            )
            if (read < 0) {
                // 위 2번.
                running.set(false)
                callbacks.onEnded(CaptureEnd.of(read))
                return
            }
            continue
        }

        // 위 3번.
        if (!retriedConfirm && !routeAlreadyConfirmed()) {
            retriedConfirm = true
            recorder.routedDevice()?.let { callbacks.onRouteConfirmed(it) }
        }

        callbacks.onBlock(AudioBlock(floats, read, sampleRate, ready), blockStats(floats, read))
    }
}
