package kr.joa.selahrta.ui

import kr.joa.selahrta.audio.AudioBlock
import kr.joa.selahrta.audio.AudioSource
import kr.joa.selahrta.audio.CaptureEnd
import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.audio.EffectState
import kr.joa.selahrta.audio.EffectsReport
import kr.joa.selahrta.audio.InputDeviceInfo
import kr.joa.selahrta.audio.OpenFailure
import kr.joa.selahrta.audio.OpenResult
import kr.joa.selahrta.audio.OpenedFormat
import kr.joa.selahrta.audio.PcmEncoding
import kr.joa.selahrta.audio.RequestedFormat
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.BlockStats
import kr.joa.selahrta.dsp.blockStats

/**
 * 시험이 쥐고 있는 시계.
 *
 * **실제 시계를 쓰면 시험이 불안정해진다.** 화면 갱신이 66ms 에 한 번으로
 * 묶여 있어서, 덩어리를 빨리 넣으면 첫 장만 화면에 반영되고 나머지는
 * 버려진다 — 그 경계가 기계 속도에 좌우된다. 실제로 그 때문에 같은
 * 시험이 한 번은 통과하고 한 번은 실패했다.
 */
class FakeClock(var ns: Long = 0L) {
    fun advanceMs(ms: Long) {
        ns += ms * 1_000_000L
    }
}

/**
 * 시험용 가짜 입력. **덩어리를 언제 흘릴지 시험이 정한다.**
 *
 * 실제 마이크는 제 스레드에서 알아서 흘리므로 순서를 만들 수 없다.
 * 여기서는 [deliver] 를 부를 때만 덩어리가 나가고, [hooks] 로 경로 확인·
 * 오류를 원하는 시점에 낼 수 있다.
 */
class FakeSource(
    val device: InputDeviceInfo?,
    val hooks: SourceHooks,
    private val sampleRate: Int = 48_000,
    /** 열 때 실패시킬 것인가. */
    private val failToOpen: OpenFailure? = null,
    /** 열자마자 경로를 확인해 줄 것인가. 실제 마이크는 대개 그렇다. */
    private val confirmOnStart: Boolean = true,
    /** 덩어리를 흘릴 때마다 그 길이만큼 흐른다. */
    private val clock: FakeClock = FakeClock(),
) : AudioSource {

    override val labelKo: String = device?.productName ?: "가짜 입력"

    var closed = false
        private set
    var started = false
        private set

    private var onBlock: ((AudioBlock, BlockStats) -> Unit)? = null
    private var frames = 0L

    val openedFormat: OpenedFormat = OpenedFormat(
        micKind = device?.kind ?: MicKind.BuiltIn,
        sampleRate = sampleRate,
        encoding = PcmEncoding.Float,
        audioSource = CaptureSource.Unprocessed,
        bufferSizeBytes = 4096,
        deviceLabel = device?.displayName ?: "가짜 입력",
        deviceKey = device?.stableKey ?: "fake",
        unprocessedSupported = true,
        effects = EffectsReport(
            EffectState("AGC", available = false, wasEnabled = false, disabled = true),
            EffectState("NS", available = false, wasEnabled = false, disabled = true),
            EffectState("AEC", available = false, wasEnabled = false, disabled = true),
        ),
        requestedDeviceLabel = device?.productName,
        routedAsRequested = true,
        routeConfirmed = false,
    )

    override fun open(requested: RequestedFormat): OpenResult =
        failToOpen?.let { OpenResult.Failed(it, "시험") } ?: OpenResult.Opened(openedFormat)

    override fun start(onBlock: (AudioBlock, BlockStats) -> Unit) {
        started = true
        this.onBlock = onBlock
        if (confirmOnStart) confirmRoute()
    }

    override fun close() {
        closed = true
    }

    /** 경로가 확인됐다고 알린다. 시험이 시점을 정한다. */
    fun confirmRoute(device: InputDeviceInfo? = this.device) {
        hooks.onRouteConfirmed(
            openedFormat.copy(
                routeConfirmed = true,
                deviceKey = device?.stableKey ?: "fake",
                deviceLabel = device?.displayName ?: "가짜 입력",
                micKind = device?.kind ?: MicKind.BuiltIn,
            ),
        )
    }

    /** 덩어리 하나를 흘린다. */
    fun deliver(amplitude: Float = 0.2f, count: Int = 1024) =
        deliverRaw(FloatArray(count) { amplitude })

    /** 만들어 둔 PCM 을 그대로 흘린다. 시계도 그만큼 흐른다. */
    fun deliverRaw(buf: FloatArray) {
        frames += buf.size
        clock.ns = frames * 1_000_000_000L / sampleRate
        onBlock?.invoke(
            // 시각은 표본 수에서 낸다 — 실제 시간과 같은 속도로 흐르게 해야
            // 하울링의 「몇 초 이어졌는가」가 뜻을 갖는다.
            AudioBlock(buf, buf.size, sampleRate, frames * 1_000_000_000L / sampleRate),
            blockStats(buf, buf.size),
        )
    }

    /** 읽기가 오류로 끝났다고 알린다. */
    fun endWith(end: CaptureEnd) = hooks.onCaptureEnded(end)

    /** 경로가 다른 기기로 바뀌었다고 알린다. */
    fun rerouteTo(to: InputDeviceInfo?) = hooks.onRoutingChanged(to)
}

/** 시험에서 기기 목록을 손으로 쥐고 흔든다. */
class FakeDevices(vararg initial: InputDeviceInfo) {
    var list: List<InputDeviceInfo> = initial.toList()

    fun builtIn(addr: String = "bottom") =
        InputDeviceInfo(1, "SM-S918N", MicKind.BuiltIn, "내장 마이크", listOf(48_000), addr)
}

fun builtInMic(id: Int = 1, addr: String = "bottom") =
    InputDeviceInfo(id, "SM-S918N", MicKind.BuiltIn, "내장 마이크", listOf(48_000), addr)

fun usbMic(id: Int = 2, name: String = "iMM-6C") =
    InputDeviceInfo(id, name, MicKind.Usb, "USB 오디오 기기", listOf(48_000))
