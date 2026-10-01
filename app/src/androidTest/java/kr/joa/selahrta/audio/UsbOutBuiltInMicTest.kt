package kr.joa.selahrta.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **내장 마이크로 재면서 USB-C 로 소리를 내보낼 수 있는가**
 * (2026-10-01 검증지시서).
 *
 * ## 무엇을 가리는 시험인가
 *
 * 간편 Transfer Function 모드는 이 구조를 전제한다:
 *
 * ```
 * 앱의 신호 → USB-C 출력 → 믹서·스피커 → 방 → 폰 내장 마이크
 * ```
 *
 * 그런데 안드로이드는 USB 오디오 기기를 꽂으면 **입력까지 그쪽으로**
 * 끌고 가는 일이 있다. 그러면 「방을 잰다」가 아니라 「USB 를 잰다」가
 * 되는데 — **숫자는 멀쩡히 나오므로 조용히 틀린다.**
 *
 * 그래서 기능을 만들기 전에 **경로가 유지되는지부터** 잰다(지시서 16장).
 *
 * ## 여기서 하지 않는 것
 *
 * Transfer Function 은 구현하지 않는다(지시서 14장). 이 파일은 **재서
 * 적을 뿐**이고, 판정은 그 값으로 한다.
 *
 * ## 기기가 없으면 스스로 건너뛴다
 *
 * **건너뛴 것을 통과로 세지 마십시오.** 로그에 어느 쪽인지 남긴다.
 */
class UsbOutBuiltInMicTest {

    private val tag = "USBOUT"

    private fun kindOf(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "내장마이크"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "폰스피커"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "수화부"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB기기"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB헤드셋"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB액세서리"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "유선헤드셋"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "유선헤드폰"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "블루투스A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "블루투스SCO"
        AudioDeviceInfo.TYPE_TELEPHONY -> "통화"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "원격서브믹스"
        else -> "종류$t"
    }

    private fun line(d: AudioDeviceInfo): String = buildString {
        append("id=${d.id} ${kindOf(d.type)}")
        append(" 이름=${d.productName}")
        append(" 주소='${d.address}'")
        append(" 표본율=${d.sampleRates.joinToString("/").ifEmpty { "미지정" }}")
        append(" 채널=${d.channelCounts.joinToString("/").ifEmpty { "미지정" }}")
        append(" 인코딩=${d.encodings.joinToString("/").ifEmpty { "미지정" }}")
    }

    private fun audioManager() =
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager

    /** 쓰는 내장 마이크는 **하단**이다 — 후면을 고르면 둘이 함께 켜진다(실측). */
    private fun bottomMic() = InputDeviceScanner(
        InstrumentationRegistry.getInstrumentation().targetContext,
    ).listAll().firstOrNull { it.kind == kr.joa.selahrta.domain.MicKind.BuiltIn }

    private fun wiredOut(): AudioDeviceInfo? =
        audioManager().getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { SignalOutputChoice.matches(OutputKind.Wired, it.type) }

    private fun dbfs(rms: Double) = if (rms > 0) 20 * log10(rms) else -240.0

    // ── 진단 (지시서 6·7장) ─────────────────────────────

    /**
     * **지금 폰이 보는 입출력 기기를 전부 적는다.**
     *
     * 판정의 바탕이다 — USB-C 오디오 출력이 **목록에 있는지**, 그리고
     * **USB 입력이 생겼는지**부터 봐야 그 다음이 뜻을 갖는다.
     */
    @Test
    fun 진단_입출력_기기를_모두_적는다() {
        val am = audioManager()
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS)

        Log.i(tag, "=== 출력 기기 ${outs.size}개 ===")
        outs.forEach { Log.i(tag, "  OUT ${line(it)}") }
        Log.i(tag, "=== 입력 기기 ${ins.size}개 ===")
        ins.forEach { Log.i(tag, "  IN  ${line(it)}") }

        val usbIn = ins.count {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        Log.i(tag, "판정근거 유선출력=${outs.count { SignalOutputChoice.matches(OutputKind.Wired, it.type) }}개 USB입력=${usbIn}개")
    }

    // ── 사례 1: 입력 기준선 (소리 없음) ─────────────────

    /**
     * **소리를 내지 않고** 내장 마이크의 상태를 적는다(지시서 6장).
     *
     * 고른 기기 · 실제 경로 · 주소 · 입력 경로 · 표본율 · 채널 · 인코딩,
     * 그리고 AGC/NS/AEC 를 센다.
     *
     * **견줄 바탕**이다 — 「USB 를 꽂았더니 바뀌었다」를 말하려면 바뀌기
     * 전 값이 있어야 한다.
     */
    @Test
    fun 사례1_내장마이크_기준선() {
        val mic = bottomMic()
        Log.i(tag, "고른 입력=${mic?.displayName} 주소='${mic?.address}' 열쇠=${mic?.stableKey}")
        assumeTrue("내장 마이크를 못 찾았다", mic != null)

        val confirmed = AtomicReference<OpenedFormat?>(null)
        val src = MicSource(
            InstrumentationRegistry.getInstrumentation().targetContext,
            mic,
            onRouteConfirmed = { confirmed.set(it) },
        )
        val r = src.open(RequestedFormat(sampleRate = 48_000))
        assumeTrue("입력을 못 열었다", r is OpenResult.Opened)
        val fmt = (r as OpenResult.Opened).format
        Log.i(
            tag,
            "열림 종류=${fmt.micKind} 경로=${fmt.audioSource} 표본율=${fmt.sampleRate} " +
                "채널=${fmt.channelCount}/${fmt.channelIndex} 인코딩=${fmt.encoding} " +
                "기기=${fmt.deviceLabel} 무가공지원=${fmt.unprocessedSupported}",
        )
        Log.i(
            tag,
            "효과 AGC끔=${fmt.effects.agc.disabled} NS끔=${fmt.effects.ns.disabled} " +
                "AEC끔=${fmt.effects.aec.disabled} 남은것=${fmt.effects.stillOn}",
        )

        val levels = ConcurrentLinkedQueue<Double>()
        src.start { _, st -> levels += st.rms }
        Thread.sleep(5_000)
        val after = confirmed.get()
        src.close()

        val db = levels.map { dbfs(it) }.sorted()
        Log.i(
            tag,
            "기준선 장=%d RMS 중앙=%.1f 최소=%.1f 최대=%.1f dBFS · 확인된경로=%s 주소='%s'".format(
                levels.size,
                db.getOrElse(db.size / 2) { -240.0 },
                db.firstOrNull() ?: -240.0,
                db.lastOrNull() ?: -240.0,
                after?.deviceLabel ?: "확인 안 됨",
                after?.routedAddress ?: "",
            ),
        )
        assertTrue("장이 하나도 안 들어왔다", levels.isNotEmpty())
    }

    // ── 사례 2: USB-C 를 꽂아도 입력이 안 끌려가는가 ────

    /**
     * **USB-C 출력이 꽂혀도 입력은 내장 마이크인가**(지시서 사례 2).
     *
     * 이 검증의 핵심 질문이다. 소리는 내지 않는다 — **꽂혀 있기만 해도**
     * 끌려가는지가 질문이기 때문이다.
     */
    @Test
    fun 사례2_USB출력이_꽂혀도_입력은_내장마이크다() {
        val out = wiredOut()
        Log.i(tag, "유선 출력=${out?.let { line(it) } ?: "없음"}")
        assumeTrue("USB-C 오디오 출력이 꽂혀 있어야 한다", out != null)

        val mic = bottomMic()
        assumeTrue("내장 마이크를 못 찾았다", mic != null)

        val confirmed = AtomicReference<OpenedFormat?>(null)
        val src = MicSource(
            InstrumentationRegistry.getInstrumentation().targetContext,
            mic,
            onRouteConfirmed = { confirmed.set(it) },
        )
        val r = src.open(RequestedFormat(sampleRate = 48_000))
        assumeTrue("입력을 못 열었다", r is OpenResult.Opened)
        val opened = (r as OpenResult.Opened).format

        val blocks = AtomicInteger()
        src.start { _, _ -> blocks.incrementAndGet() }
        Thread.sleep(2_000)
        val fmt = confirmed.get() ?: opened
        src.close()

        Log.i(
            tag,
            "USB 꽂힌 채: 종류=${fmt.micKind} 기기=${fmt.deviceLabel} 주소='${fmt.routedAddress}' " +
                "경로=${fmt.audioSource} 표본율=${fmt.sampleRate} 확인됨=${fmt.routeConfirmed} " +
                "장=${blocks.get()}",
        )
        assertEquals(
            "USB 를 꽂았더니 입력이 끌려갔다",
            kr.joa.selahrta.domain.MicKind.BuiltIn,
            fmt.micKind,
        )
        assertTrue("장이 하나도 안 들어왔다", blocks.get() > 0)
    }

    // ── 사례 3: 같이 틀어 놓고 버티는가 ─────────────────

    /**
     * **USB-C 로 내보내면서 내장 마이크로 재기**(지시서 사례 3).
     *
     * 길이는 `-e minutes N` 으로 준다(기본 5분).
     *
     * **세기는 작게 잡았다**(0.03 ≈ -30dBFS). 이 시험이 보는 것은 **경로가
     * 버티는가**이지 소리가 큰가가 아니다 — 담당자가 근무 중이기도 하다.
     */
    @Test
    fun 사례3_USB로_내보내며_내장마이크로_잰다() {
        val minutes = InstrumentationRegistry.getArguments()
            .getString("minutes")?.toDoubleOrNull() ?: 5.0
        val out = wiredOut()
        assumeTrue("USB-C 오디오 출력이 꽂혀 있어야 한다", out != null)
        val mic = bottomMic()
        assumeTrue("내장 마이크를 못 찾았다", mic != null)

        val routeChanges = AtomicInteger()
        val captureEnds = AtomicInteger()
        val confirmed = AtomicReference<OpenedFormat?>(null)
        val src = MicSource(
            InstrumentationRegistry.getInstrumentation().targetContext,
            mic,
            onRoutingChanged = {
                routeChanges.incrementAndGet()
                Log.w(tag, "입력 경로가 바뀌었다 → ${it?.displayName}")
            },
            onRouteConfirmed = { confirmed.set(it) },
            onCaptureEnded = {
                captureEnds.incrementAndGet()
                Log.w(tag, "캡처가 끝났다: $it")
            },
        )
        val r = src.open(RequestedFormat(sampleRate = 48_000))
        assumeTrue("입력을 못 열었다", r is OpenResult.Opened)
        val opened = (r as OpenResult.Opened).format

        val routeNote = AtomicReference("모름")
        val player = SignalPlayer(
            openSink = {
                AudioTrackSink(
                    preferredOutput = { out },
                    onRoute = {
                        routeNote.set(it)
                        Log.i(tag, "실제 출력: $it")
                    },
                )
            },
        )

        val blocks = AtomicLong()
        val silent = AtomicLong()
        val levels = ConcurrentLinkedQueue<Double>()
        src.start { _, st ->
            blocks.incrementAndGet()
            if (st.rms <= 0.0) silent.incrementAndGet()
            levels += st.rms
        }
        Thread.sleep(3_000)
        val beforeDb = levels.toList().map { dbfs(it) }.sorted()
            .let { it.getOrElse(it.size / 2) { -240.0 } }

        val id = player.start(
            SignalRequest(TestSignal.Pink, amplitude = 0.03, channels = SignalChannels.Both),
        )
        Log.i(tag, "재생 시작 id=$id (0 이면 못 열었다) · 틀기 전 RMS=%.1fdBFS".format(beforeDb))
        assertTrue("소리를 못 열었다", id != SignalPlayer.NONE)

        val endAt = System.currentTimeMillis() + (minutes * 60_000).toLong()
        var tick = 0
        while (System.currentTimeMillis() < endAt) {
            Thread.sleep(15_000)
            tick++
            val recent = levels.toList().takeLast(60).map { dbfs(it) }.sorted()
            Log.i(
                tag,
                "%d분%02d초 장=%d 무음장=%d RMS=%.1fdBFS 경로변경=%d 캡처끝=%d 출력=%s".format(
                    tick * 15 / 60,
                    tick * 15 % 60,
                    blocks.get(),
                    silent.get(),
                    recent.getOrElse(recent.size / 2) { -240.0 },
                    routeChanges.get(),
                    captureEnds.get(),
                    routeNote.get(),
                ),
            )
        }
        player.stop()
        Thread.sleep(500)
        val afterDb = levels.toList().takeLast(60).map { dbfs(it) }.sorted()
            .let { it.getOrElse(it.size / 2) { -240.0 } }
        val fmt = confirmed.get() ?: opened
        src.close()

        Log.i(
            tag,
            "끝 장=${blocks.get()} 무음장=${silent.get()} 경로변경=${routeChanges.get()} " +
                "캡처끝=${captureEnds.get()} 입력=${fmt.deviceLabel}/${fmt.sampleRate}/" +
                "${fmt.audioSource} 마지막RMS=%.1fdBFS".format(afterDb),
        )

        assertEquals("도는 중에 입력 경로가 바뀌었다", 0, routeChanges.get())
        assertEquals("도는 중에 캡처가 끊겼다", 0, captureEnds.get())
        assertEquals(
            "입력이 내장 마이크가 아니게 됐다",
            kr.joa.selahrta.domain.MicKind.BuiltIn,
            fmt.micKind,
        )
        assertEquals("입력 표본율이 바뀌었다", opened.sampleRate, fmt.sampleRate)
        assertTrue("장이 하나도 안 들어왔다", blocks.get() > 0)

        // **무음 장 하나로 실패시키지 않는다.**
        //
        // 기준선을 재 보니 **첫 장이 비어서** 들어온다(최소 -240dBFS). 거기에
        // 「하나라도 있으면 실패」를 걸면 **헛된 실패**가 난다.
        //
        // 우리가 막으려는 것은 그런 한 장이 아니라 **입력이 통째로 죽는
        // 것**이다 — UMC404HD 에서 겪은 것이 그랬다(266,240 표본 전부 0).
        // 그래서 **비율**과 **중앙값**으로 본다.
        val silentShare = silent.get().toDouble() / blocks.get()
        assertTrue(
            "무음 장이 %.1f%% 다 — 입력이 끊긴다".format(silentShare * 100),
            silentShare < 0.01,
        )
        assertTrue(
            "재는 내내 바닥값이다(%.1fdBFS) — 입력이 죽었다".format(afterDb),
            afterDb > -120.0,
        )
    }
}
