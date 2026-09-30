package kr.joa.selahrta.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.joa.selahrta.audio.AudioInterruptions
import kr.joa.selahrta.audio.SerialCommands
import kr.joa.selahrta.audio.AudioBlock
import kr.joa.selahrta.audio.AudioSource
import kr.joa.selahrta.audio.ChoiceReason
import kr.joa.selahrta.audio.InputDeviceInfo
import kr.joa.selahrta.audio.InputDeviceScanner
import kr.joa.selahrta.audio.MicSource
import kr.joa.selahrta.audio.MicrophoneProbe
import kr.joa.selahrta.audio.chooseInput
import kr.joa.selahrta.audio.CaptureDiagnostics
import kr.joa.selahrta.audio.CaptureEnd
import kr.joa.selahrta.audio.CaptureGeneration
import kr.joa.selahrta.audio.CaptureService
import kr.joa.selahrta.audio.CaptureServiceBridge
import kr.joa.selahrta.audio.TestSignal
import kr.joa.selahrta.audio.DEFAULT_AMPLITUDE
import kr.joa.selahrta.audio.MAX_AMPLITUDE
import kr.joa.selahrta.audio.MAX_TONE_HZ
import kr.joa.selahrta.audio.MIN_AMPLITUDE
import kr.joa.selahrta.audio.MIN_TONE_HZ
import kr.joa.selahrta.audio.SignalChannels
import kr.joa.selahrta.audio.SignalRequest
import kr.joa.selahrta.audio.MEASURE_AMPLITUDE
import kr.joa.selahrta.audio.SignalPlayer
import kr.joa.selahrta.audio.OpenFailure
import kr.joa.selahrta.audio.OpenResult
import kr.joa.selahrta.audio.OpenedFormat
import kr.joa.selahrta.audio.RequestedFormat
import kr.joa.selahrta.calibration.ActiveCalibration
import kr.joa.selahrta.calibration.CalibrationGate
import kr.joa.selahrta.calibration.CalibrationKey
import kr.joa.selahrta.calibration.calibrationGate
import kr.joa.selahrta.calibration.ROUTE_UNCONFIRMED_KO
import kr.joa.selahrta.calibration.ActiveCurve
import kr.joa.selahrta.calibration.CalibrationStore
import kr.joa.selahrta.calibration.chooseCorrection
import kr.joa.selahrta.calibration.currentProfileEnvironment
import kr.joa.selahrta.calibration.CurveStore
import kr.joa.selahrta.calibration.GlobalCalibration
import kr.joa.selahrta.calibration.SaveResult
import kr.joa.selahrta.calibration.computeOffset
import kr.joa.selahrta.domain.FailureReason
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.domain.SegmentRange
import kr.joa.selahrta.dsp.SilenceWatch
import kr.joa.selahrta.dsp.BlockStats
import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.dsp.CalibrationFile
import kr.joa.selahrta.dsp.FeedbackCandidate
import kr.joa.selahrta.dsp.FeedbackDetector
import kr.joa.selahrta.dsp.FeedbackEvent
import kr.joa.selahrta.dsp.FeedbackState
import kr.joa.selahrta.dsp.BandAccumulator
import kr.joa.selahrta.dsp.ROOM_MIN_FRAMES
import kr.joa.selahrta.dsp.RoomResponse
import kr.joa.selahrta.dsp.computeRoomResponse
import kr.joa.selahrta.dsp.RtaEngine
import kr.joa.selahrta.dsp.SpectrumFrame
import kr.joa.selahrta.dsp.SpectrumSink
import kr.joa.selahrta.dsp.RtaFrame
import kr.joa.selahrta.dsp.LowEnergyHint
import kr.joa.selahrta.dsp.MultiWeightEngine
import kr.joa.selahrta.dsp.MultiWeightFrame
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import kr.joa.selahrta.settings.LeqWindow
import kr.joa.selahrta.settings.MeterSettings
import kr.joa.selahrta.settings.MeterSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * 화면에 띄울 레벨. 단위는 설정한 가중치에 따라 dBA/dBC/dB(Z) 가 된다.
 */
data class MeterReading(
    /** 지금 레벨(dB SPL). 보정 전이면 짐작한 눈금 위의 값이다. */
    val currentSpl: Double? = null,
    /** 짧은 구르는 Leq(10초). */
    val leqShort: Double? = null,
    /** 긴 구르는 Leq. 길이는 설정에 따른다. */
    val leqLong: Double? = null,
    /** 긴 Leq 의 창이 찼는가. 차기 전 값은 이름보다 짧은 구간의 평균이다. */
    val leqLongFull: Boolean = false,
    /** 측정을 시작한 뒤의 최대 레벨. */
    val maxSpl: Double? = null,
    /**
     * 측정을 시작한 뒤의 **최소** 레벨. 시간가중이 자리를 잡기 전에는 null.
     *
     * 자리를 잡기 전 값을 세면 MIN 이 늘 시작 구간으로 굳는다
     * ([kr.joa.selahrta.dsp.SplFrame.minDbfs]).
     */
    val minSpl: Double? = null,
    /** 파형 최대(순간). MAX 와 다른 지표다(명세 6장). */
    val peakSpl: Double? = null,
    /**
     * 그 피크가 풀스케일에 닿았는가.
     *
     * **닿았으면 그 숫자는 측정값이 아니라 하한이다.** 파형이 잘린 순간의
     * 실제 음압은 우리가 아는 값보다 높고, 얼마나 높은지는 알 길이 없다.
     * 박수 한 번이면 폰 마이크는 쉽게 잘린다. 그대로 숫자만 띄우면
     * 담당자는 그것을 잰 값으로 읽는다.
     */
    val peakClipped: Boolean = false,
    /** 측정 구간에 잘린 곳이 있었는가. 있으면 MAX 도 믿기 어렵다. */
    val anyClipping: Boolean = false,
    /** 보정에 쓰는 날 값. 화면의 SPL 과 달리 보정과 무관하다. */
    val currentDbfs: Double? = null,
    /**
     * **보정을 누르면 실제로 저장에 쓰일 값**(dBFS). 아직 못 미더우면 null.
     *
     * [currentDbfs] 와 다른 계산이다(독립 재검증 UISRF-01) — 이쪽은
     * 깨끗한 구간의 유한 창 평균이다. 카드가 이것을 적어야 사람이
     * 무엇에 맞추는지 안다.
     */
    val calibrationDbfs: Double? = null,
    /** 이어서 센 깨끗한 시간(ms). 화면이 「얼마나 더」를 적는다. */
    val calibrationCleanMs: Long = 0L,
    /**
     * C 가중과 A 가중의 차(dB). 저음이 얼마나 많은지를 말한다(명세 10장).
     *
     * 보정값은 두 쪽에 똑같이 더해지므로 **차이에는 영향이 없다** —
     * 미보정 상태에서도 이 값만은 믿을 수 있다.
     */
    val cMinusA: Double? = null,
    /** 그 차이가 뜻하는 바. 판정이 아니라 설명이다. */
    val lowEnergyHint: LowEnergyHint? = null,
    /**
     * 시간가중이 자리를 잡았는가(명세 6장).
     *
     * 시작 직후 첫 몇 백 ms 는 바늘이 0 에서 올라오는 중이라 실제보다
     * 낮다. 그 값을 측정값이라 부르면 안 되므로 화면이 알린다
     * (독립 검증 R07).
     */
    val settled: Boolean = false,
)

/**
 * FR 측정의 단계.
 *
 * **배경이 먼저다.** 어느 밴드를 믿어도 되는지는 배경보다 얼마나 큰가로
 * 가르므로, 순서가 바뀌면 판정할 근거가 없다.
 */
enum class ResponsePhase(val labelKo: String) {
    Idle("멈춤"),
    Quiet("배경 재는 중 — 조용히 해 주십시오"),
    Signal("응답 재는 중 — 소리를 그대로 두십시오"),
    Done("다 쟀습니다"),
}

/**
 * 사람의 확인을 기다리는 보정 한 건(독립 검토 UIS-02).
 *
 * **왜 세션 번호를 들고 있나**: 묻는 사이에 측정이 멈추거나 마이크가
 * 바뀔 수 있다. 그때 저장하면 묻던 것과 **다른 기기**의 보정값이 된다.
 */
data class PendingCalibration(
    val referenceDb: Double,
    val source: kr.joa.selahrta.calibration.CalibrationSource,
    /** 물을 때의 입력 세션. 답을 받을 때 달라졌으면 취소한다. */
    val session: Long,
    /** 순음을 왜 못 알아봤는지. 사람이 판단할 재료다. */
    val reasonKo: String?,
)

data class CaptureUiState(
    val measure: MeasureState = MeasureState.Idle,
    val opened: OpenedFormat? = null,
    val diagnostics: CaptureDiagnostics = CaptureDiagnostics(),
    /**
     * 지금 도는 분석 엔진의 FFT 길이. 측정 중이 아니면 null.
     *
     * **설정값이 아니라 엔진이 실제로 쓰는 값이다.** 설정은 다음
     * 측정부터 적용되므로, 설정값을 화면에 적으면 측정 중에 바꾼
     * 순간 화면이 거짓말을 한다.
     */
    val analysisFftSize: Int? = null,
    val meter: MeterReading = MeterReading(),
    val calibration: ActiveCalibration = ActiveCalibration.assumed,
    /** 31밴드 RTA. 아직 첫 FFT 가 안 찼으면 null. */
    val rta: RtaView? = null,
    /** 연속 스펙트럼. Spectrum 화면이 열려 있을 때만 채워진다. */
    val spectrum: SpectrumView? = null,
    /** 기록 중이면 그 이름. 아니면 null(Phase 10). */
    val recordingId: String? = null,
    /** 저장된 기록 목록. 화면이 열릴 때 읽는다. */
    val sessions: List<kr.joa.selahrta.recording.SessionMeta> = emptyList(),
    /** 읽지 못한 기록 수. **조용히 빼지 않는다.** */
    val brokenSessions: Int = 0,
    /** 지금 열어 본 기록. 목록만 볼 때는 null. */
    val openedSession: kr.joa.selahrta.recording.SessionMeta? = null,
    /**
     * 열어 본 기록의 행들. 아직 못 읽었으면 비어 있다.
     *
     * **열 때만 읽는다.** 목록을 그리려고 전부 읽으면 화면이 멈춘다 —
     * 2시간이면 1.4MB 다.
     */
    val openedRows: List<kr.joa.selahrta.recording.TimelineRow> = emptyList(),
    /** 내보내기·삭제 결과를 사람에게 한 줄로. */
    val historyNoticeKo: String? = null,
    /**
     * 다시 분석하는 중인 기록의 진행(0~1). 안 하는 중이면 null.
     *
     * **긴 녹음은 오래 걸린다.** 아무 표시가 없으면 사람은 앱이 멈춘
     * 줄 알고 나가거나 다시 누른다.
     */
    val reanalyzeProgress: Float? = null,
    /**
     * 공유 창을 띄울 파일들. 띄운 뒤 화면이 비운다.
     *
     * **CSV 와 소리를 함께 보낸다.** 따로 보내면 받는 쪽에서 짝이
     * 어긋난다 — 어느 소리가 어느 표의 것인지 알 수 없다.
     */
    val shareUris: List<android.net.Uri> = emptyList(),
    /**
     * **소리도 담을지 묻는 중인가.**
     *
     * 예배 소리를 담는 일은 dB 숫자를 남기는 것과 성격이 다르다 —
     * 설교와 성도들의 목소리가 그대로 들어간다. 그래서 기록을 시작할
     * 때마다 묻는다.
     */
    val askAudioBeforeRecording: Boolean = false,
    /** 소리를 담기로 했을 때 어느 꼴로 담을지. 설정에서 고른다. */
    val audioFormat: kr.joa.selahrta.recording.AudioFileFormat =
        kr.joa.selahrta.recording.AudioFileFormat.M4a,
    /** 하울링 후보(명세 9장). 센 것부터. 없으면 빈 목록이다. */
    val feedback: List<FeedbackCandidate> = emptyList(),
    /**
     * 이번 측정에서 「지속」까지 간 것들의 기록. 새것부터.
     *
     * 후보는 소리가 그치면 사라지지만 기록은 남는다 — 예배가 끝난 뒤
     * 「아까 그게 몇 Hz 였지」에 답할 수 있어야 한다.
     */
    val feedbackLog: List<FeedbackEvent> = emptyList(),
    /** 지금 적용 중인 주파수 보정 곡선. */
    val curve: ActiveCurve? = null,
    /** 곡선 가져오기 결과 안내. */
    val curveNoticeKo: String? = null,
    val meterSettings: MeterSettings = MeterSettings(),
    /**
     * 저장된 설정이 **도착했는가**.
     *
     * ## 왜 필요한가 (2026-09-27 담당자 보고)
     *
     * 「앱을 열면 첫 SPL 화면이 나오는데, 자세히 보면 원래 화면이 있고
     * 짧은 시간에 화면이 바뀐다」.
     *
     * 맞았습니다. 기기에서 재 보니 **50ms 동안 기본값으로 한 번 그리고**
     * 곧바로 저장된 값으로 갈아엎었다:
     *
     * | | 먼저 그린 것 | 곧바로 바뀐 것 |
     * |---|---|---|
     * | 구간 | 설교 | **찬양** |
     * | 권장 범위 | 68~75 dBA | **78~85 dBA** |
     * | Leq | (1분) | **(10초)** |
     *
     * [MeterSettings] 의 기본값은 **DataStore 를 읽기 전의 자리표시**인데
     * 화면이 그것을 그대로 그렸다. 짧아서 넘어갈 일이 아니다 — 잠깐이라도
     * **틀린 권장 범위**가 보이는 것은 이 앱이 하면 안 되는 일이고,
     * 화면이 저 혼자 뒤집히면 「내가 뭘 잘못 눌렀나」가 된다.
     *
     * 그래서 도착하기 전에는 그 값들을 **아예 그리지 않는다.** 없는 것을
     * 비워 두는 것과 틀린 것을 보여 주는 것은 다른 일이다.
     */
    val settingsLoaded: Boolean = false,
    /** 지금 쓸 수 있는 입력 기기들. 꽂고 빼면 바뀐다. */
    val inputs: List<InputDeviceInfo> = emptyList(),
    /** 기기 선택·전환에 관해 알릴 것. 사실을 숨기지 않는다. */
    val deviceNoticeKo: String? = null,
    val errorKo: String? = null,
    /** 보정 저장 결과 안내. 한 번 보여 주고 지운다. */
    val calibrationNoticeKo: String? = null,
    /**
     * **묻고 있는 보정.** 순음이 안 보이는데 교정기 단추를 눌렀을 때만 찬다.
     *
     * 사람이 「교정기를 물렸다」고 답하면 저장하고, 아니면 버린다
     * (독립 검토 UIS-02).
     */
    val pendingCalibration: PendingCalibration? = null,
    /**
     * 마지막으로 소리가 들어온 뒤 흐른 시간(ms). 측정 중이 아니거나 아직
     * 한 덩어리도 안 왔으면 null.
     *
     * **덩어리가 아니라 시계가 이 값을 키운다**(독립 재검증 UISR-03).
     * 콜백이 멈추면 진단도 함께 멈춰서, 누적값만 보던 경고는 **입력이
     * 끊겨도 아무 말도 하지 않았다.** 화면은 마지막 숫자를 들고 멀쩡히
     * 서 있었다.
     */
    val lastInputAgeMs: Double? = null,
    /**
     * 지금 입력 세션의 번호. 기기를 열 때마다 올라간다.
     *
     * 오디오 스레드가 낸 값에도 같은 번호가 붙는다. 번호가 다르면 **지난
     * 기기의 값**이라 버린다 — 기기를 바꾼 뒤 늦게 도착한 덩어리에 새
     * 기기의 보정값을 걸면, 화면은 멀쩡한데 다른 마이크의 숫자가 된다
     * (독립 검증 R03).
     */
    val session: Long = 0,
    /**
     * 마지막으로 썼던 입력. **지금 열려 있는 것이 아니다.**
     *
     * [opened] 를 멈춘 뒤에도 남겨 두면 설정 화면이 이미 닫힌 기기에
     * 「사용 중」을 붙이고 상단 배지도 켜진 색으로 남는다(독립 재검증
     * 추가 지적). 「지금 쓰는 것」과 「마지막에 쓴 것」을 갈라 둔다.
     */
    val lastInput: OpenedFormat? = null,
    /**
     * 곡선을 몇 번 갈아 끼웠는가. 엔진의 세대와 견주는 값이다.
     *
     * 화면의 「보정 적용됨」은 **그 곡선으로 실제 계산된 프레임**에만
     * 붙어야 한다. 곡선을 바꾼 직후에는 아직 옛 곡선의 프레임이 남아
     * 있는데, 예전에는 거기에 새 곡선의 이름표를 붙였다(독립 재검증 F06).
     */
    val curveGeneration: Long = 0,
    /** 지금 스피커로 내보내고 있는 시험 신호. 안 내보내면 null. */
    val playingSignal: TestSignal? = null,
    /** 내보내는 세기(진폭 0~1). 단계가 아니라 이어진 값이다. */
    val signalAmplitude: Double = DEFAULT_AMPLITUDE,
    /** [TestSignal.Custom] 으로 낼 주파수. */
    val signalToneHz: Double = 1_000.0,
    /** 어느 쪽 스피커로 낼 것인가. */
    val signalChannels: SignalChannels = SignalChannels.Both,
    /** 신호 발생기에 관해 알릴 것. */
    val signalNoticeKo: String? = null,
    /**
     * **소리가 실제로 어디로 나갔는가**(독립 검토 R5-04).
     *
     * `setPreferredDevice` 는 요청이라 거절될 수 있다. 거절되면 우회가
     * 안 걸린 것이고, 그때 USB 입력이 무음이 된다 — **그 사실이 화면에
     * 보여야** 사람이 알아챈다. 안 틀고 있으면 null.
     */
    val signalRouteKo: String? = null,

    // ── RTA 측정 저장 (지시서 §7) ───────────────────────
    /** 지금 재는 중이면 그 상태. 안 재면 null. */
    val rtaCapture: RtaCaptureUi? = null,
    /** 저장해 둔 측정 전부. */
    val savedRta: List<kr.joa.selahrta.data.rta.RtaMeasurement> = emptyList(),
    /** 비교 세트 목록. */
    val savedRtaSets: List<kr.joa.selahrta.data.rta.RtaComparisonSet> = emptyList(),
    /** 지금 그래프에 겹쳐 그릴 측정들. */
    val rtaOverlayIds: Set<String> = emptySet(),
    /** 실시간 곡선을 보일 것인가. 저장 곡선만 보고 싶을 때가 있다. */
    val rtaLiveVisible: Boolean = true,
    /** 저장에 관해 알릴 것(실패 까닭 등). */
    val rtaSaveNoticeKo: String? = null,
    /** 차례대로 재는 중이면 「2/3 · 오른쪽만」. 아니면 null. */
    val rtaSequenceKo: String? = null,
    /** FR — 지금 어느 단계인가. */
    val responsePhase: ResponsePhase = ResponsePhase.Idle,
    /** 그 단계가 얼마나 찼는가(0~1). */
    val responseProgress: Float = 0f,
    /** 마지막으로 잰 응답. 아직 없으면 null. */
    val responseResult: RoomResponse? = null,
    /** FR 에 관해 알릴 것. */
    val responseNoticeKo: String? = null,
    /** 재는 동안 이 폰이 핑크 잡음을 함께 낼 것인가. */
    val responsePlayHere: Boolean = true,
    /**
     * 배경을 재 두었는가.
     *
     * **밖에서 소리를 낼 때 필요하다.** PA 가 핑크 잡음을 계속 내고 있으면
     * 「배경 3초」가 그 소리를 배경으로 재어 SNR 이 0 이 된다. 그래서 밖에서
     * 낼 때는 사람이 소리를 끈 채로 배경을 먼저 재고, 켠 뒤에 응답을 잰다.
     */
    val responseQuietReady: Boolean = false,
    /**
     * 내장 마이크 탐색 결과. 아직 안 돌렸으면 null.
     *
     * **저장하지 않는다.** 앞을 다시 켰을 때 지난 판정을 그대로
     * 보이면, OS 가 올라가거나 기기 구성이 바뀜 상황에서도 옵날 말을
     * 한다(지시서 6장: 재시작 시 현재 상태와 다시 대조).
     */
    val micProbe: kr.joa.selahrta.audio.MicrophoneProbe.Report? = null,
) {
    /**
     * 지금 숫자를 그 기기의 측정값이라 불러도 되는가.
     *
     * 실제로 어느 마이크로 붙었는지 확인되기 전에는 보정값을 걸 근거가
     * 없다(독립 검증 R01). 확인될 때까지는 미보정으로 둔다.
     */
    val routeConfirmed: Boolean get() = opened?.routeConfirmed == true

    /** 화면에 기기 정보를 적을 때 쓸 값. 열려 있으면 그것, 아니면 마지막 것. */
    val inputForDisplay: OpenedFormat? get() = opened ?: lastInput
}

/**
 * 오디오 스레드가 내는 **측정 결과만** 담은 묶음.
 *
 * 설정·보정·기기 같은 주 스레드의 상태는 여기 없다. 캡처 스레드는 이것만
 * 쓰고, 화면 상태는 주 스레드에서 합친다.
 */
data class MeasurementSnapshot(
    /** 어느 입력 세션의 값인가. [CaptureUiState.session] 과 견준다. */
    val session: Long,
    val diagnostics: CaptureDiagnostics,
    /** A·C·Z 를 함께 담는다. 가중치 선택은 주 스레드의 설정이다. */
    val spl: MultiWeightFrame?,
    /**
     * 그때의 보정 근거. **화면의 계기와 다른 계산**이다(UISRF-01).
     *
     * 카드가 「저장에 쓸 값」을 적는 데 쓴다 — 화면의 현재값을 적어
     * 두면 사람이 그 값으로 맞춰진다고 읽는데, 실제로 저장되는 것은
     * 깨끗한 구간의 평균이다.
     */
    val calibration: kr.joa.selahrta.calibration.CalibrationEvidence? = null,
    val rta: RtaFrame?,
    /** 연속 스펙트럼. 화면이 꺼져 있으면 null 이다. */
    val spectrum: SpectrumFrame?,
    /**
     * 이 덩어리를 받은 **단조 시각**(ms).
     *
     * 스펙트로그램의 가로축이 이 값을 쓴다. 예전에는 화면이 그릴 때의
     * `System.currentTimeMillis()` 를 썼는데, 그것은 UI 가 밀린 만큼
     * 어긋나고 벽시계를 바꾸면 뛴다(독립 검토 UA-04).
     */
    val atMonotonicMs: Long,
    val anyClipping: Boolean,
    /**
     * 하울링 후보(명세 9장). 센 것부터.
     *
     * **기본값을 두지 않는다.** 두었더니 스냅샷을 만들 때 기록을 빠뜨려도
     * 컴파일이 통과했고, 화면만 조용히 비어 있었다. 새 필드를 더할 때
     * 채우는 것을 잊으면 컴파일이 막아 주어야 한다.
     */
    val feedback: List<FeedbackCandidate>,
    /** 이번 측정에서 「지속」까지 간 것들의 기록. 새것부터. */
    val feedbackLog: List<FeedbackEvent>,
)

/**
 * 화면에 그릴 연속 스펙트럼 한 장. 값은 [RtaView] 와 **같은 잣대**다.
 *
 * 같은 오프셋을 걸어야 두 화면이 같은 소리를 같은 숫자로 말한다. 엔진
 * 안에서 이미 마이크 곡선을 맞춰 두었고([RtaEngine.updateSpectrum]),
 * 여기서 절대 레벨만 옮긴다.
 */
class SpectrumView(
    /** 몇 번째 장인가. 같은 장이 두 번 쌓이는 것을 막는다. */
    val seq: Long,
    /** 이 장을 받은 단조 시각(ms). 스펙트로그램의 가로축이 쓴다. */
    val atMs: Long,
    val columnsSpl: DoubleArray,
    val holdSpl: DoubleArray,
    /** 칸 가운데 주파수. 엔진의 배열을 그대로 가리킨다 — 읽기만 한다. */
    val hz: DoubleArray,
    /** FFT 칸 폭(Hz). 화면이 분해능으로 적는다. */
    val binHz: Double,
    /** 가장 큰 봉우리. 아무 소리도 없으면 null. */
    val topHz: Double?,
    val topSpl: Double?,
) {
    // [RtaView] 와 같은 까닭 — 프레임마다 새 배열이라 값 비교가 무의미하다.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 화면에 그릴 RTA 한 프레임. 값은 보정을 거친 dB SPL 이다.
 */
data class RtaView(
    val bandsSpl: DoubleArray,
    val holdSpl: DoubleArray,
    val resolved: BooleanArray,
    /** 밴드마다 순음 에너지가 얼마나 새는가(dB). 얼마나 못 믿을지를 말한다. */
    val lossDb: DoubleArray,
    /** 주파수 보정이 걸렸는가. 걸렸으면 화면이 그 사실을 적는다. */
    val curveApplied: Boolean = false,
    /** 보정 곡선이 덮지 않아 끝점 값을 늘여 쓴 밴드. */
    val curveExtrapolated: BooleanArray? = null,
) {
    // DoubleArray 를 든 data class 는 equals 가 참조 비교라 Compose 가
    // 매번 다르다고 본다. 어차피 프레임마다 새 값이므로 그대로 두되,
    // 경고를 피하려고 명시해 둔다.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 한 번의 측정. **엔진·명령·통계가 모두 여기에 매여 있다.**
 *
 * 예전에는 엔진과 명령 큐가 ViewModel 의 필드였다. 그래서 멈출 때
 * `join(500)` 이 시간을 넘겨 옛 작업 스레드가 살아남으면, 그 스레드가
 * 깨어나 **새 측정의 엔진과 명령 큐**를 만질 수 있었다(독립 재검증 F02).
 * `@Volatile` 은 참조가 보이게만 할 뿐 두 스레드가 같은 객체를 동시에
 * 만지는 것을 막지 않는다.
 *
 * 이제 캡처 콜백은 맨 앞에서 「내가 지금 살아 있는 세션인가」를 보고,
 * 아니면 **자기 세션의 객체조차 건드리지 않고** 돌아간다. 옛 스레드가
 * 늦게 깨어나도 새 측정에 닿을 길이 없다.
 */
class CaptureSession(
    val id: Long,
    val source: AudioSource,
    val sampleRate: Int,
    settings: MeterSettings,
) {
    /** 설정이 바뀌면 갈아 끼운다. 갈아 끼우는 일도 오디오 스레드에서 한다. */
    @Volatile
    var engine: MultiWeightEngine = MultiWeightEngine(
        sampleRate = sampleRate,
        timeWeight = settings.timeWeight,
        // 창 길이 그대로다. 「전체」(누적)를 뺀 뒤로는 갈라 줄 것이 없다.
        // 아래 한 줄은 그 뒤에 남은 설명이라 지운다.
        // 들어가 측정이 통째로 망가진다.
        leqLongMs = settings.leqWindow.millis,
    )

    // **FFT 길이는 여기서 못박힌다.** 측정 중에는 바꾸지 않는다 —
    // 엔진을 새로 만들면 하울링 탐지와 FR 수집이 붙어 있던 자리가
    // 끊긴다. 설정은 다음 측정부터 적용된다.
    val rta = RtaEngine(sampleRate, fftSize = settings.fftSize)

    /**
     * 하울링 후보 탐지기(명세 9장).
     *
     * RTA 와 **같은 스펙트럼**을 본다 — 따로 FFT 를 돌리면 같은 일을 두 번
     * 하고, 두 결과의 시각이 어긋난다.
     */
    val feedback = FeedbackDetector(rta.fftSize, sampleRate)

    /** 주 스레드가 이 세션의 오디오 스레드에 시킬 일. */
    val commands = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    /**
     * 이 세션의 마지막 보정 근거(독립 재검증 UISRF-03).
     *
     * **세션이 들고 있어야 한다.** 컨트롤러 전역으로 두었더니 멈춘 뒤에도
     * 남아, 새 세션이 첫 덩어리를 받기 전에 옛 근거가 나갔다.
     */
    @Volatile
    var calibrationEvidence: kr.joa.selahrta.calibration.CalibrationEvidence? = null

    /**
     * 마지막으로 **실제 PCM 이 온** 때(단조 시계). 아직 없으면 null.
     *
     * 보정 근거와 따로 둔다(독립 재검증 UISRFF-02). 근거는 빈 덩어리
     * (읽기 오류)에도 갱신해야 한다 — 옛 값이 저장되는 것을 막아야
     * 하므로. 그런데 감시가 그 시각을 「소리가 온 때」로 다시 쓰면,
     * **읽기가 계속 실패하는 동안에도 나이가 0** 이 된다.
     *
     * 하나는 「저장해도 되는가」를, 다른 하나는 「소리가 오고 있는가」를
     * 말한다. 같은 값으로 둘 다 답할 수 없다.
     */
    @Volatile
    var lastValidInputNs: Long? = null

    /**
     * 기록을 남기는 중이면 그 기록기(Phase 10). 아니면 null.
     *
     * **세션이 소유한다.** 마이크가 다시 열리면 세션이 바뀌고, 그때
     * 기록도 거기서 끊긴다 — 다른 마이크의 값을 한 파일에 이어 붙이지
     * 않는다(녹음 설계 §6). 측정값을 이어 붙이지 않는 것과 같은 까닭이다.
     *
     * **오디오 스레드만 만진다.** 켜고 끄는 일도 명령 큐를 지나 그
     * 스레드에서 한다.
     */
    var recorder: kr.joa.selahrta.recording.SessionRecorder? = null

    /**
     * 소리를 파일로 담는 쪽. **담지 않기로 했으면 null** 이다.
     *
     * 캡처 스레드가 덩어리를 넘기기만 하고, 쓰는 일은 전용 스레드가
     * 한다. 기록기와 생명이 같다 — 함께 시작하고 함께 끝난다.
     */
    var audioFile: kr.joa.selahrta.recording.AudioFileRecorder? = null

    init {
        // FFT 한 장이 나올 때마다 탐지기에 넘긴다. 시각은 덩어리를 받은
        // 시각으로 쓴다 — 오디오 스레드에서만 건드리므로 안전하다.
        rta.addSpectrumSink { power -> feedback.process(power, spectrumMs) }
    }

    /** 지금 처리 중인 덩어리의 시각(ms). 탐지기에 넘길 값이다. */
    var spectrumMs = 0L

    // 캡처 스레드만 만지는 값들.
    var blocks = 0L
    var frames = 0L
    var readErrors = 0L
    var clippedBlocks = 0L

    /**
     * 입력이 **죽어 있는지** 지켜본다. 캐퍼 스레드만 만진다.
     *
     * 팬텀전원이 꺼져 있거나 케이블이 빠진 것과 **조용한 대목**을
     * 가른다. 가르지 못하면 설교 중 숫는 사이마다 경고가 뜨고,
     * 그러면 아무도 안 읽는다.
     */
    val silence = SilenceWatch()

    /** 최근 덩어리의 RMS. 진단 화면이 dBFS 로 적는다. */
    var lastRms = 0.0
    var lastEmitNs = 0L
    var startedNs = 0L

    /**
     * 지금까지 본 **가장 작은 지연**(ms). 시작 지연을 걷어내는 기준이다.
     *
     * ## 왜 필요한가 (실기기 확인 2026-09-29)
     *
     * `audioLagMs` 는 「흐른 시간 − 받은 소리 길이」다. 그런데 기기를
     * 열고 첫 덩어리가 오기까지 걸린 시간이 **거기 그대로 들어 있고,
     * 그 몫은 영영 따라잡히지 않는다.** UMC404HD 에서는 그 값이 1초쯤
     * 이었고, 90초를 재도 1초 그대로였다 — 소리를 잃는 중이 아니라
     * 처음부터 1초 늦게 출발한 것이다.
     *
     * 그 값으로 경고하면 **측정할 때마다 늘 떠 있는 경고**가 된다.
     * 그건 없느니만 못하다 — 사람이 무시하는 법을 배우고, 정작 진짜로
     * 잃을 때도 안 읽는다.
     *
     * 지연은 소리를 잃을 때만 **늘어난다**(줄지는 않는다). 그러니
     * 「가장 작았던 값」이 곧 출발선이고, 거기서 얼마나 늘었는지가
     * 잃은 양이다.
     */
    var minLagMs = Double.MAX_VALUE

    /**
     * 보정에 쓸 값을 따로 재는 창(독립 재검증 UISRF-01).
     *
     * 화면의 계기와 **다른 계산**이다 — 화면은 지수 시간가중이고 이쪽은
     * 깨끗한 구간의 유한 창 평균이다. 세션마다 새로 만든다.
     */
    val cleanWindow = kr.joa.selahrta.dsp.CleanWindow(sampleRate)

}

class CaptureViewModel(app: Application) : AndroidViewModel(app) {


    private val store = CalibrationStore(app)
    private val curveStore = CurveStore(app)
    private val settingsStore = MeterSettingsStore(app)

    /**
     * 측정 기록이 사는 곳(Phase 10).
     *
     * `filesDir` 안이라 **앱을 지우면 함께 사라진다.** 공용 저장소에 두면
     * 권한이 필요하고, 사람이 파일 탐색기에서 지워 목록에 유령이 뜬다.
     * 내보내기는 그때 SAF 로 따로 한다(명세 13장).
     */
    private val sessionStore = kr.joa.selahrta.recording.SessionStore(
        java.io.File(app.filesDir, "sessions"),
    )

    init {
        // **끝나지 않은 폴더를 치운다.** 재다가 앱이 죽으면 겉장 없는
        // 폴더가 남는다. 목록에는 안 뜨지만 자리를 차지한다.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { sessionStore.sweepUnfinished() }
        }
    }

    /**
     * **마이크 위치 안내 자료**를 한 번 읽는다(`assets/galaxy-mic-locations.json`).
     *
     * 자산이라 변하지 않으므로 한 번이면 된다. 읽기는 IO 스레드에서 한다 —
     * 89KB 를 주 스레드에서 열면 앱이 뜨는 동안 한 박자 멈춘다.
     *
     * **못 읽어도 앱은 그대로 돈다.** 이 자료는 안내일 뿐이고, 재는 일과
     * 아무 상관이 없다. 없으면 카드가 안 나올 뿐이다.
     */
    private val scanner = InputDeviceScanner(app)

    /**
     * 측정 세션의 수명주기. **여기서 나는 결함은 DSP 시험으로 안 잡힌다.**
     *
     * 안드로이드에 매인 넷만 여기서 넘겨 주고, 순서와 상태 전이는
     * [CaptureController] 가 갖는다 — 그래야 기기 없이 시험할 수 있다.
     */
    private val controller = CaptureController(
        listDevices = { scanner.list() },
        openSource = { target, hooks ->
            MicSource(
                context = app,
                target = target,
                onRoutingChanged = hooks.onRoutingChanged,
                onRouteConfirmed = hooks.onRouteConfirmed,
                onCaptureEnded = hooks.onCaptureEnded,
            )
        },
        post = { block -> onMainThread(block) },
        onRouteConfirmedHook = { fmt -> watchCalibration(fmt) },
        onStoppedHook = {
            calibrationJob?.cancel()
            calibrationJob = null
            curveJob?.cancel()
            curveJob = null
        },
        onRecordingFinished = { rec, audio -> writeRecording(rec, audio) },
        // **서비스는 여기서만 내린다.** `onStoppedHook` 에 넣으면 기기를
        // 갈아타는 중의 `stop` 에도 내려가고, 백그라운드에서는 다시 띄울 수
        // 없어 거기서 마이크가 끊긴다(독립 검증 FS01).
        onLifecycle = { life -> reconcileService(life) },
    )

    /**
     * 측정의 최종 상태에 **서비스를 맞춘다.**
     *
     * 서비스를 띄우는 자리는 둘이다 — 사람이 「측정 시작」을 누른 [start] 와
     * 여기. [start] 가 먼저인 까닭은 안드로이드 14부터 `microphone` 형은
     * **앱이 앞에 있을 때만** 띄울 수 있기 때문이고, 여기는 같은 것을 다시
     * 확인하는 자리다(이미 떠 있으면 값이 바뀌지 않아 불리지도 않는다).
     */
    private fun reconcileService(life: CaptureLifecycle) {
        when (life) {
            CaptureLifecycle.Active -> if (!CaptureService.start(getApplication())) {
                noteServiceUnavailable()
            }

            CaptureLifecycle.Finished -> CaptureService.stop(getApplication())
        }
    }

    /**
     * 붙들어 두지 못한다고 알린다. **측정은 그대로 둔다.**
     *
     * 검증자는 「실패 상태로 전달」을 권했지만, 화면을 보고 있는 동안의
     * 측정은 서비스 없이도 멀쩡하다. 재는 것까지 꺼 버리면 재려던 사람이
     * 잃는 것이 더 크다. 대신 **무엇을 잃는지**를 적는다 — 뒤로 가면
     * 끊길 수 있다.
     */
    private fun noteServiceUnavailable() {
        controller.update { st ->
            st.copy(
                deviceNoticeKo = "백그라운드 유지를 시작하지 못했습니다. " +
                    "화면을 보고 있는 동안은 그대로 재지만, 다른 앱으로 넘어가면 " +
                    "측정이 끊길 수 있습니다. 알림 권한이 허용되어 있는지 확인하십시오.",
            )
        }
    }

    /**
     * 화면이 보는 상태. **합치는 일은 주 스레드에서 한다.**
     *
     * 측정값에 보정과 설정을 입히는 자리가 하나뿐이라, 「어느 보정으로
     * 계산한 값인가」가 언제나 지금 상태와 같다.
     */
    val state: StateFlow<CaptureUiState> =
        combine(controller.baseState, controller.measurement) { base, m -> base.withMeasurement(m) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, CaptureUiState())

    /** 시험 신호를 스피커로 내보내는 쪽. 측정과는 따로 논다. */
    private val player = SignalPlayer(
        // **USB 로 재는 동안에는 소리를 폰 스피커로 돌린다**(2026-09-30).
        //
        // 안드로이드는 인터페이스를 꽂으면 출력도 그쪽으로 보내는데,
        // **같은 USB 카드로 동시에 넣고 빼면 입력이 완전한 디지털 무음**이
        // 되는 기기가 있다(실기기에서 쟀다). 그러면 마법사 2·4단계가
        // **아무것도 못 잰 채로** 넘어간다.
        openSink = {
            kr.joa.selahrta.audio.AudioTrackSink(
                preferredOutput = {
                    val st = controller.baseState.value
                    if (
                        kr.joa.selahrta.audio.SignalOutputChoice.preferBuiltInSpeaker(
                            st.meterSettings.signalOutput,
                            st.opened?.micKind,
                        )
                    ) {
                        builtInSpeaker()
                    } else {
                        null
                    }
                },
                // **요청이 그대로 됐는지 화면까지 올린다**(독립 검토 R5-04).
                onRoute = { note ->
                    onMainThread { controller.update { it.copy(signalRouteKo = note) } }
                },
            )
        },
        onEnded = { generation, reason ->
            // **생명주기 소식이라 버리면 안 된다**(독립 검토 SRLR-04).
            //
            // 예전에는 이것을 「바라는 상태」와 같은 줄에 넣었다. 그러면
            // 이 정리가 번호를 올려 **사람이 막 누른 새 시작을 지웠다** —
            // 두 번째 출력이 아예 안 열렸다.
            //
            // **재생의 주인은 명령 스레드다.** 세대를 보는 일도 거기서
            // 한다 — 주 스레드에서 보면 그 사이에 명령 스레드가 값을
            // 바꿔 둘이 어긋난다.
            signalCommands.postAlways {
                if (generation == playGeneration) {
                    val intent = activeSignalIntent
                    playGeneration = SignalPlayer.NONE
                    activeSignalRequest = null
                    // **스스로 끝난 길에서도 지킴이를 놓는다**(스윕이 다
                    // 훑었거나 오류로 끝났거나). 안 놓으면 방송 수신기가
                    // 남아, 다음에 이어폰을 뽑을 때 안 틀었는데
                    // 「멈췄습니다」가 뜬다.
                    interruptions.release()
                    publishSignal(intent, null, reason)
                }
            }
        },
    )

    /**
     * 지금 내보내고 있는 재생의 세대. 늦게 온 소식을 가린다.
     *
     * **명령 스레드만 읽고 쓴다**(독립 검토 SRLR-01·02). 주 스레드와
     * 나눠 가지면 「멈췄다」와 「막 시작했다」가 서로를 덮는다.
     */
    private var playGeneration = SignalPlayer.NONE

    /** 지금 도는 재생이 어느 의도로 시작됐나. 명령 스레드 전용. */
    private var activeSignalIntent = 0L

    /** 지금 도는 재생의 요청 전부. 주파수만 바뀌었는지 여기서 가린다. */
    private var activeSignalRequest: SignalRequest? = null

    /**
     * **사람이 바란 마지막 것의 번호**(독립 검토 SRLR-02).
     *
     * 재생의 세대(`playGeneration`)로는 모자란다 — 그것은 **소리가 실제로
     * 열린 뒤에야** 생긴다. 사람이 「멈춰」를 누른 시점은 그보다 앞일 수
     * 있고, 그때 늦게 끝난 시작이 화면을 되돌린다: **꺼진 신호가 계속
     * 재생 중으로 보인다**(실기기에서 `ui=Pink, player=null` 로 재현).
     *
     * 이 번호는 **주 스레드에서** 오른다. 사람의 뜻이 거기서 정해지기
     * 때문이다.
     */
    private val signalIntent = java.util.concurrent.atomic.AtomicLong()

    /**
     * **마지막으로 「멈춰」라고 한 뜻의 번호**(독립 검토 SRLRO-01).
     *
     * 줄에 선 정지는 더 새 명령에 **덮일 수 있다** — 그것이 「낡은 것은
     * 버린다」의 뜻이다. 그런데 그렇게 덮이고 나면 **「중간에 멈추라고
     * 했다」는 이력이 어디에도 안 남았다.**
     *
     * 그 틈으로 포커스를 잃은 재생이 되살아났다: 포커스를 잃어 정지가
     * 줄에 서고, 그것이 돌기 전에 같은 순음을 다시 누르면 — 세기·채널이
     * 같으므로 — **위상을 잇는 지름길로 빠져 `acquire()` 를 지나치지
     * 않는다.** 옛 소리가 포커스 없이 계속 나고 화면은 정상으로 돌아온다.
     *
     * 그래서 이 번호를 따로 남긴다. **이 뒤에 시작된 재생만** 지름길을
     * 쓸 수 있다.
     *
     * ## 이 번호를 올리는 길 — 그리고 안 올리는 길 하나
     *
     * 다섯 갈래가 모두 [stopSignal] 로 모이므로 같은 번호를 올린다:
     * **정지 단추 · 백그라운드**([onBackground]) **· 출력 변경 알림**
     * (이어폰 뽑힘) **· 포커스 상실**(LOSS · TRANSIENT · CAN_DUCK).
     *
     * **[onCleared] 는 다르다. 이 번호를 올리지 않는다.** 11회차 요청서에
     * 「거기도 같은 번호를 올린다」고 적었는데 **사실이 아니었다.** 거기서는
     * [signalClosed] 와 실행자 닫기가 막는다. 끝난 ViewModel 은 **다시 시작할
     * 일이 없으므로** 「이 뒤에 시작된 것인가」를 물을 자리가 아니다. 두
     * 그물의 역할이 다르다 — 맞추려고 여기에 한 줄 더 넣을 것이 아니다.
     *
     * ## 정지 길을 하나 더 만든다면
     *
     * `SrlStopBoundaryIndependentTest` 의 `Stop` 에도 함께 더한다. 그 시험이
     * **정지 원인 × 포커스 재획득(거절·허용)** 두 계약을 원인마다 건다.
     * 새 길만 내고 거기에 안 더하면 그 길로 들어온 정지는 **아무도 안 본다** —
     * 이 결함이 처음 난 까닭이 바로 「한 길만 보았다」였다.
     */
    private val lastSignalStopIntent = java.util.concurrent.atomic.AtomicLong()

    /** ViewModel 이 끝났는가. 끝난 뒤의 결과는 화면에 올리지 않는다. */
    @Volatile
    private var signalClosed = false

    /** 이 의도가 아직 사람이 바라는 것인가. */
    private fun signalIsCurrent(intent: Long): Boolean =
        !signalClosed && signalIntent.get() == intent

    /**
     * 결과를 화면에 올린다. **낡은 의도의 결과는 버린다.**
     *
     * 이것이 없으면 늦게 끝난 시작이 더 새로운 정지를 되돌린다(SRLR-02).
     */
    private fun publishSignal(intent: Long, signal: TestSignal?, notice: String?) {
        onMainThread {
            if (signalIsCurrent(intent)) {
                controller.update { it.copy(playingSignal = signal, signalNoticeKo = notice) }
            }
        }
    }

    /**
     * 소리를 멈추고 자원을 놓는다. **명령 스레드에서만 부른다.**
     *
     * **놓기는 반드시 한다** — `stop()` 이 터져도 지킴이는 놓아야 한다.
     * 안 놓으면 방송 수신기와 포커스가 남는다.
     */
    private fun stopSignalOnCommandThread() {
        playGeneration = SignalPlayer.NONE
        activeSignalRequest = null
        try {
            player.stop()
        } finally {
            interruptions.release()
        }
    }

    /**
     * **소리 명령을 한 줄로 세운다**(독립 검토 8회차 3장).
     *
     * ## 왜 주 스레드에서 빼는가 — 실기기에서 잰 값
     *
     * | 무엇 | 중앙 | 최대 |
     * |---|---:|---:|
     * | `stop()` | 24~38ms | **129ms** |
     * | 대역 슬라이더 한 번(stop→start) | **148ms** | **167ms** |
     * | 슬라이더 20번(손가락 한 번 끌기) | — | **합 2.5초** |
     *
     * 세기·좌우·대역 주파수 슬라이더는 모두 `playSignal` 을 다시 부른다.
     * 그것이 주 스레드에서 돌면 **손가락을 끄는 동안 화면이 통째로
     * 멎는다.** 감쇠를 기다리는 120ms 도 그 안에 있지만 더 큰 몫은
     * `AudioTrack` 을 새로 여는 일이다 — 둘 다 여기서 빠진다.
     *
     * ## 하나짜리 실행자인 까닭
     *
     * 명령마다 따로 스레드를 띄우면 `start` 와 `stop` 이 서로를 앞질러
     * 지금의 단일 제어 계약이 깨진다. 한 줄로 세우면 부른 차례가 그대로
     * 지켜진다.
     */
    private val signalCommands = SerialCommands("selah-signal-cmd")

    /** 소리 명령을 줄에 세운다. 뒤에 더 새 명령이 왔으면 **하지 않는다.** */
    private fun postSignalCommand(block: () -> Unit) = signalCommands.post(block)

    /**
     * 소리를 끊어야 할 바깥 사정 — 이어폰이 빠지거나 전화가 오거나
     * (지시서 §5 `Interrupted`).
     *
     * **소리를 내는 주인이 여기만은 아니다.** 교정 마법사와 FR 측정도
     * 제 손으로 핑크 잡음을 튼다(독립 검토 CA-05·UA-03). 그 둘은 짧고
     * 화면이 붙들려 있으며 제 손으로 끝을 맺는다 — 여기 지킴이는
     * **사람이 도구 화면에서 틀어 둔 신호**를 본다. 그것만이 화면을
     * 떠난 뒤에도 계속 나간다.
     */
    private val interruptions = AudioInterruptions(app) { reason ->
        onMainThread {
            // 이미 멎었으면 조용히 지나간다 — 안 틀었는데 「멈췄습니다」가
            // 뜨면 무슨 일이 난 줄 안다.
            if (controller.baseState.value.playingSignal == null) return@onMainThread
            stopSignal()
            controller.update { st -> st.copy(signalNoticeKo = reason) }
        }
    }

    /** 지금 도는 FR 측정. 겹쳐 돌지 않게 붙들어 둔다. */
    private var responseJob: Job? = null

    /**
     * 마지막으로 잰 배경. **어디서 잰 것인지 함께 붙들고 있다.**
     *
     * **상태에 담지 않는다.** 화면이 쓸 일이 없고, 31칸짜리 배열이 상태
     * 비교에 끼면 화면이 쓸데없이 다시 그려진다. 있는지 없는지만
     * [CaptureUiState.responseQuietReady] 로 알린다.
     */
    private var responseQuiet: QuietBackground? = null

    /**
     * 잰 배경 하나와 **어느 입력에서 쟀는지**.
     *
     * ## 왜 표를 붙이는가 (독립 검토 UA-02)
     *
     * 예전에는 배열 하나만 들고 있었다. 그래서 입력 A 로 배경을 재고,
     * 측정을 멈췄다가 입력 B 로 다시 시작해도 「2. 응답 재기」가 그대로
     * 눌렸다 — **B 의 소리를 A 의 배경과 견주었다.**
     *
     * 조용히 틀린다는 것이 나쁘다. 검토자가 실제 `computeRoomResponse` 로
     * 재 보니, 옛 배경(−70dBFS)을 쓰면 31밴드가 모두 「믿을 수 있음」으로
     * 나오고 지금 배경(−42dBFS)을 쓰면 아예 밴드가 모자라 실패했다.
     * 배경에 묻힌 대역을 멀쩡한 응답으로 승인하게 된다.
     *
     * 그래서 **쓰기 직전에 맞춰 본다.** 하나라도 다르면 배경을 버린다.
     */
    private data class QuietBackground(
        val meanDb: DoubleArray,
        /** 어느 캡처 세션에서 쟀는가. 멈췄다 다시 열면 번호가 바뀐다. */
        val session: Long,
        /** 어느 기기·채널인가. 마이크가 다르면 배경도 다르다. */
        val deviceKey: String,
        val channelIndex: Int,
        /** 셈의 눈금. 바뀌면 밴드 값의 잣대가 달라진다. */
        val sampleRate: Int,
        val fftSize: Int,
    ) {
        // DoubleArray 를 든 data class 는 equals 가 참조 비교다. 값 비교를
        // 할 일이 없으므로 명시해 경고를 없앤다.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * 지금 입력이 [responseQuiet] 를 잰 그 입력인가.
     *
     * 아니면 배경을 버린다 — 「남아 있는데 못 쓰는 값」은 다음 사람이
     * 왜 안 되는지 알 수 없다.
     */
    private fun usableQuiet(): QuietBackground? {
        val q = responseQuiet ?: return null
        if (!sameAsNow(q)) {
            responseQuiet = null
            controller.update { it.copy(responseQuietReady = false) }
            return null
        }
        return q
    }

    /** [q] 의 이름표가 **지금 열린 입력**과 같은가. */
    private fun sameAsNow(q: QuietBackground): Boolean {
        val st = controller.baseState.value
        val opened = st.opened ?: return false
        val spec = rtaSpec() ?: return false
        return q.session == st.session &&
            q.deviceKey == opened.deviceKey &&
            q.channelIndex == opened.channelIndex &&
            q.sampleRate == spec.second &&
            q.fftSize == spec.first
    }

    /**
     * 화면이 앞에 있는가.
     *
     * **소리를 내기 직전에 본다.** FR 은 스스로 두 단계를 이어 달리므로,
     * 배경을 재는 동안 앱을 나가면 **나간 뒤에** 핑크 잡음이 시작될 수
     * 있었다(독립 검토 UA-03). 작업을 끊는 것과 별개로, 내보내는 자리에서
     * 한 번 더 본다 — 끊기와 재생 사이에 틈이 있을 수 있다.
     */
    @Volatile
    private var inForeground: Boolean = true

    private companion object {
        /**
         * 소리 파이프의 버퍼 한 칸 크기(프레임).
         *
         * 48kHz 에서 한 덩어리가 1024 프레임 남짓이다. 넉넉히 잡아 두면
         * 큰 덩어리가 와도 잘리지 않는다 — 잘리면 그만큼 소리가 빠진다.
         */
        const val AUDIO_MAX_FRAMES = 8192

        /**
         * 입력이 살아 있는지 시계로 확인하는 간격(ms).
         *
         * 「멈췄다」를 1초 문턱으로 판정하므로 이 간격이면 늦어야 반
         * 박자다. 화면 갱신(66ms)보다 훨씬 성기게 두어 쓸데없이 깨우지
         * 않는다.
         */
        const val INPUT_AGE_TICK_MS = 400L

        /**
         * 배경을 몇 장 모을 것인가. 48kHz·FFT4096·50% 겹침이면 초당 23장쯤이라
         * 3초쯤이다.
         *
         * **짧게 잡는다.** 사람을 조용히 시켜 놓는 시간이라 길면 안 지켜진다.
         * 배경은 응답만큼 정밀할 필요도 없다 — 밴드를 가르는 문턱으로만 쓴다.
         */
        const val QUIET_FRAMES = 70

        /**
         * 응답을 몇 장 모을 것인가. 10초쯤이다.
         *
         * 핑크 잡음은 순간마다 출렁이므로 짧게 재면 그 출렁임이 방의
         * 응답처럼 보인다. 길수록 좋지만, 예배당에서 사람을 세워 두는
         * 시간이라 10초에서 끊는다.
         */
        const val SIGNAL_FRAMES = 230

        /** 진행을 얼마나 자주 볼 것인가(ms). */
        const val RESPONSE_TICK_MS = 100L

        /** RTA 측정 중 화면을 고쳐 그리는 간격. 남은 시간이 이만큼씩 준다. */
        const val RTA_TICK_MS = 100L

        /** 명령 스레드에 물어볼 때 기다리는 시간. 여는 데 걸리는 시간을 넣고 잡았다. */
        const val SIGNAL_ASK_TIMEOUT_MS = 3_000L
    }
    private var calibrationJob: Job? = null
    private var curveJob: Job? = null
    private var settingsJob: Job? = null
    init {
        // 알림의 「측정 종료」. 서비스는 화면을 알지 못하고 한 방향으로
        // 알리기만 한다([CaptureServiceBridge]).
        viewModelScope.launch {
            CaptureServiceBridge.stopRequests.collect { stop() }
        }

        // 붙들어 두기에 실패했다는 소식. **멈추지 않고 알리기만 한다.**
        viewModelScope.launch {
            CaptureServiceBridge.unavailable.collect { noteServiceUnavailable() }
        }

        // **콜백이 없어도 도는 점검**(독립 재검증 UISR-03).
        //
        // 진단은 덩어리가 와야 갱신된다. 그래서 입력이 아주 멈추면 진단도
        // 멈추고, 누적값만 보던 경고는 **아무 말도 하지 않았다** — 화면은
        // 마지막 숫자를 들고 멀쩡히 서 있었다. 시계로 나이를 재는 일은
        // 소리와 무관하게 돌아야 한다.
        //
        // 화면 갱신(66ms)보다 훨씬 성기게 돈다. 「멈췄다」를 1초 문턱으로
        // 판정하므로 이 간격이면 늦어야 반 박자다.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(INPUT_AGE_TICK_MS)
                val age = controller.inputWaitAgeMs()
                if (controller.baseState.value.lastInputAgeMs != age) {
                    controller.update { st -> st.copy(lastInputAgeMs = age) }
                }
            }
        }

        // 기기 목록은 늘 지켜본다. 측정 중이 아닐 때도 설정 화면이 최신
        // 목록을 보여야 하고, 측정 중이면 빠지는 것을 알아채야 한다.
        viewModelScope.launch {
            scanner.watch().collect { list ->
                val prev = controller.baseState.value.inputs
                controller.update { st -> st.copy(inputs = list) }
                // **본 기기는 기억한다.** 빼도 목록에 남아야 다시 꽂기
                // 전에도 고를 수 있고, 그 기기의 보정이 있다는 사실도
                // 보인다(2026-09-24 담당자 지시).
                list.forEach { d ->
                    settingsStore.rememberDevice(d.stableKey, d.displayName, d.kind)
                }
                if (controller.running) controller.onDeviceListChanged(prev, list)
            }
        }

        // 설정은 측정과 무관하게 늘 지켜본다. 바뀌면 엔진을 다시 만들어야
        // 하므로 돌아가는 중이면 다시 시작한다.
        settingsJob = viewModelScope.launch {
            settingsStore.settings.collect { s ->
                val old = controller.baseState.value.meterSettings
                // **도착했다고 함께 적는다.** 그 전까지 화면은 이 값들을
                // 그리지 않는다([CaptureUiState.settingsLoaded]).
                controller.update { st ->
                    st.copy(meterSettings = s, settingsLoaded = true, audioFormat = s.audioFormat)
                }
                // **구간 사건은 여기 한 곳에서만 남긴다**(독립 검토 UIS-04).
                //
                // 예전에는 `setSegment` 만 기록에 남겼다. 그런데 화면이 보는
                // 것은 `activeSegment` 라서, **구간을 지우기만 해도** 화면은
                // 다음 구간으로(마지막이면 구간 없음으로) 넘어갔다. 그 길에는
                // 사건이 없어 기록과 CSV 는 계속 지워진 구간으로 분류했다 —
                // 화면에서 견준 구간과 표에 적힌 구간이 달라진다.
                //
                // 단추마다 적지 않고 **설정을 받아들이는 경계**에서 앞뒤를
                // 견준다. 더하기·빼기·고르기가 모두 여기를 지나므로 새 길이
                // 생겨도 저절로 따라온다.
                if (old.activeSegment != s.activeSegment) {
                    noteSegmentToRecording(s.activeSegment, s)
                }
                controller.onSettingsChanged(old, s)
            }
        }
    }








    /** 오디오 스레드에서 온 일을 주 스레드로 넘긴다. 상태는 주 스레드만 쓴다. */
    private fun onMainThread(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) { block() }
    }

    /**
     * 교정 측정이 스펙트럼을 받아 갈 통로를 **오디오 스레드에서** 붙인다.
     *
     * 대입이 아니라 더하기라 하울링 탐지기가 밀려나지 않는다. 재는 중이
     * 아니면 아무 일도 일어나지 않는다 — 붙일 세션이 없다.
     */
    /**
     * 방·PA 의 크기 응답을 잰다(FR 화면).
     *
     * ## 배경을 먼저 재는 까닭
     *
     * 어느 밴드를 믿어도 되는지는 **배경보다 얼마나 큰가**로 가른다.
     * 배경 없이 곡선을 내놓으면 예배당 공조기 소리를 방의 저역 부스트로
     * 읽게 된다 — 그리고 그걸 보고 저역을 깎는다.
     *
     * ## 소리원을 누가 내는가
     *
     * [CaptureUiState.responsePlayHere] 가 true 면 이 폰이 핑크 잡음을
     * 함께 낸다. 그러면 **폰 스피커의 기울기가 결과에 섞인다** — 화면이
     * 그 사실을 적는다. PA 로 내면 PA 의 기울기가 섞이는데, 대개는 그쪽이
     * 알고 싶은 것이다.
     */
    fun measureResponse() = runResponse(quiet = true, signal = true)

    /**
     * 배경만 잰다 — **밖에서 소리를 낼 때** 쓴다.
     *
     * PA 나 다른 폰이 핑크 잡음을 계속 내고 있으면 한 번에 이어서 잴 수
     * 없다. 「배경 3초」가 그 소리를 배경으로 재어 버려 SNR 이 0 이 되고,
     * 모든 밴드가 「못 씀」이 된다. 사람이 소리를 끈 채로 이것부터 누른다.
     */
    fun measureResponseQuiet() = runResponse(quiet = true, signal = false)

    /** 응답만 잰다. 배경을 미리 재 두었어야 한다. */
    fun measureResponseSignal() = runResponse(quiet = false, signal = true)

    /**
     * 방·PA 의 크기 응답을 잰다(FR 화면).
     *
     * ## 배경을 먼저 재는 까닭
     *
     * 어느 밴드를 믿어도 되는지는 **배경보다 얼마나 큰가**로 가른다.
     * 배경 없이 곡선을 내놓으면 예배당 공조기 소리를 방의 저역 부스트로
     * 읽게 된다 — 그리고 그걸 보고 저역을 깎는다.
     *
     * ## 소리원을 누가 내는가
     *
     * [CaptureUiState.responsePlayHere] 가 true 면 이 폰이 핑크 잡음을
     * 함께 낸다. 그러면 **폰 스피커의 기울기가 결과에 섞인다** — 화면이
     * 그 사실을 적는다. PA 로 내면 PA 의 기울기가 섞이는데, 대개는 그쪽이
     * 알고 싶은 것이다.
     */
    private fun runResponse(quiet: Boolean, signal: Boolean) {
        if (controller.baseState.value.measure !is MeasureState.Running) {
            controller.update { st ->
                st.copy(responseNoticeKo = "먼저 「측정」에서 마이크를 여십시오.")
            }
            return
        }
        if (responseJob?.isActive == true) return
        if (signal && !quiet && usableQuiet() == null) {
            controller.update { st ->
                st.copy(responseNoticeKo = "배경을 먼저 재십시오.")
            }
            return
        }

        val spec = rtaSpec() ?: run {
            controller.update { st ->
                st.copy(responseNoticeKo = "분석기가 아직 돌지 않습니다. 잠시 뒤 다시 누르십시오.")
            }
            return
        }

        // **시작할 때의 신원을 붙들어 둔다**(독립 검토 CA-06).
        //
        // 예전에는 배경 장이 다 찬 **뒤에** 그때 열려 있는 경로를 읽어 표를
        // 붙였다. 장이 충분히 쌓인 뒤 완료 처리가 늦어지면 — 그 사이에
        // 멈추고 다른 입력을 열 수 있다 — **A 의 장에 B 의 이름표**가
        // 붙었다. 그러면 나중 검사도 그대로 통과한다.
        //
        // 잰 것과 이름표는 같은 순간에 정해져야 한다.
        val startedAt = controller.baseState.value
        val startedOpened = startedAt.opened
        if (startedOpened == null) {
            controller.update { st -> st.copy(responseNoticeKo = "입력이 열려 있지 않습니다.") }
            return
        }
        val startedStamp = QuietBackground(
            meanDb = DoubleArray(0),
            session = startedAt.session,
            deviceKey = startedOpened.deviceKey,
            channelIndex = startedOpened.channelIndex,
            sampleRate = spec.second,
            fftSize = spec.first,
        )

        responseJob = viewModelScope.launch {
            val tap = kr.joa.selahrta.dsp.MeasurementTap(spec.first, spec.second)
            installMeasurementTap(tap)
            try {
                if (quiet) {
                    // 1단계 — 배경. **소리를 내지 않는다.**
                    controller.update { st ->
                        st.copy(
                            responsePhase = ResponsePhase.Quiet,
                            responseProgress = 0f,
                            responseNoticeKo = null,
                            responseResult = if (signal) null else st.responseResult,
                        )
                    }
                    tap.startTarget()
                    val ok = collectFrames(tap, QUIET_FRAMES) { p ->
                        controller.update { st -> st.copy(responseProgress = p) }
                    }
                    tap.stop()
                    val frames = tap.drainTarget()
                    if (!ok || frames.size < ROOM_MIN_FRAMES) {
                        failResponse("배경을 재지 못했습니다. 마이크가 열려 있는지 확인하십시오.")
                        return@launch
                    }
                    val acc = BandAccumulator(frames.first().size)
                    frames.forEach { acc.add(it) }
                    val meanDb = acc.meanDb()
                    if (meanDb == null) {
                        failResponse("배경을 재지 못했습니다. 마이크가 열려 있는지 확인하십시오.")
                        return@launch
                    }
                    // **시작할 때의 이름표를 그대로 붙인다.** 지금 열린 경로를
                    // 읽으면 그 사이에 바뀐 입력의 이름이 붙는다(CA-06).
                    responseQuiet = startedStamp.copy(meanDb = meanDb)
                    // 그리고 그 이름표가 **지금도 맞는지** 곧바로 본다. 다르면
                    // 잰 것은 옛 입력의 것이라 쓸 수 없다.
                    if (usableQuiet() == null) {
                        failResponse("재는 동안 입력이 바뀌어 배경을 버렸습니다. 다시 재십시오.")
                        return@launch
                    }
                    controller.update { st -> st.copy(responseQuietReady = true) }
                }

                if (!signal) {
                    controller.update { st ->
                        st.copy(responsePhase = ResponsePhase.Idle, responseProgress = 0f)
                    }
                    return@launch
                }

                // 2단계 — 소리. 이 폰이 낼지는 사람이 정한다.
                //
                // **앞에 없으면 여기서 멈춘다**(독립 검토 UA-03). 배경을
                // 재는 3초 사이에 홈으로 나가면, 예전에는 살아 있는 이
                // 작업이 나간 뒤에 핑크 잡음을 틀었다 — 화면에 멈출 방법이
                // 없는 채로 예배당에서 소리가 난다.
                if (!inForeground) {
                    failResponse("화면을 나가서 멈췄습니다. 다시 「응답 재기」를 누르십시오.")
                    return@launch
                }
                // 소리를 내기 전에도 **시작할 때의 입력 그대로인지** 본다(CA-06).
                if (!sameAsNow(startedStamp)) {
                    failResponse("재는 동안 입력이 바뀌었습니다. 다시 재십시오.")
                    return@launch
                }
                val playHere = controller.baseState.value.responsePlayHere
                if (playHere) playSignal(TestSignal.Pink, MEASURE_AMPLITUDE)
                controller.update { st ->
                    st.copy(responsePhase = ResponsePhase.Signal, responseProgress = 0f)
                }
                tap.startTarget()
                val signalOk = collectFrames(tap, SIGNAL_FRAMES) { p ->
                    controller.update { st -> st.copy(responseProgress = p) }
                }
                tap.stop()
                if (playHere) stopSignal()
                val signalFrames = tap.drainTarget()
                if (!signalOk && signalFrames.size < ROOM_MIN_FRAMES) {
                    failResponse("소리를 트는 동안 장이 모자랐습니다. 다시 재 보십시오.")
                    return@launch
                }

                // **쓰기 직전에 한 번 더 맞춰 본다.** 배경을 잰 뒤 이 줄에
                // 닿기까지 10초가 흐르고, 그 사이에 기기가 바뀔 수 있다.
                val quietNow = usableQuiet()
                if (quietNow == null) {
                    failResponse("입력이 바뀌어 배경을 다시 재야 합니다.")
                    return@launch
                }
                computeRoomResponse(signalFrames, quietNow.meanDb).fold(
                    onSuccess = { res ->
                        controller.update { st ->
                            st.copy(
                                responsePhase = ResponsePhase.Done,
                                responseResult = res,
                                responseProgress = 1f,
                                responseNoticeKo = null,
                            )
                        }
                    },
                    onFailure = { e -> failResponse(e.message ?: "응답을 셈하지 못했습니다.") },
                )
            } finally {
                removeMeasurementTap(tap)
                // **어디서 빠져나오든 소리는 끈다.** 취소된 경우까지
                // 포함이다 — 예배당에서 핑크 잡음이 계속 나면 회중이 듣는다.
                if (controller.baseState.value.playingSignal != null) stopSignal()
            }
        }
    }

    /** 재는 도중에 그만둔다. 화면을 떠날 때도 부른다. */
    fun cancelResponse() {
        responseJob?.cancel()
        responseJob = null
        controller.update { st ->
            st.copy(
                responsePhase = if (st.responseResult != null) ResponsePhase.Done else ResponsePhase.Idle,
                responseProgress = 0f,
            )
        }
    }

    /** 이 폰이 핑크 잡음을 함께 낼 것인가. */
    fun setResponsePlayHere(on: Boolean) {
        controller.update { st -> st.copy(responsePlayHere = on) }
    }

    fun dismissResponseNotice() {
        controller.update { st -> st.copy(responseNoticeKo = null) }
    }

    private fun failResponse(reasonKo: String) {
        controller.update { st ->
            st.copy(
                responsePhase = ResponsePhase.Idle,
                responseProgress = 0f,
                responseNoticeKo = reasonKo,
            )
        }
    }

    /**
     * [target] 장이 모일 때까지 기다린다.
     *
     * **틱마다 진행을 알린다.** 10초를 아무 표시 없이 기다리게 하면
     * 멈춘 줄 알고 화면을 떠난다.
     */
    private suspend fun collectFrames(
        tap: kr.joa.selahrta.dsp.MeasurementTap,
        target: Int,
        onProgress: (Float) -> Unit,
    ): Boolean {
        var ticks = 0
        val maxTicks = target * 4 + 40
        while (tap.count < target && ticks < maxTicks) {
            kotlinx.coroutines.delay(RESPONSE_TICK_MS)
            onProgress((tap.count.toFloat() / target).coerceIn(0f, 1f))
            ticks++
        }
        onProgress(1f)
        return tap.count >= target
    }

    fun installMeasurementTap(tap: kr.joa.selahrta.dsp.MeasurementTap) {
        controller.postToCapture { session -> session.rta.addSpectrumSink(tap) }
    }

    fun removeMeasurementTap(tap: kr.joa.selahrta.dsp.MeasurementTap) {
        controller.postToCapture { session -> session.rta.removeSpectrumSink(tap) }
    }

    /** 지금 도는 분석기의 (FFT 길이, 샘플레이트). 안 돌면 null. */
    fun rtaSpec(): Pair<Int, Int>? = controller.rtaSpec()

    /** 목록에서 전에 쓴 기기를 지운다. */
    fun forgetDevice(key: String) {
        viewModelScope.launch { settingsStore.forgetDevice(key) }
    }

    fun setPreferredInput(key: String?) {
        viewModelScope.launch { settingsStore.setPreferredInput(key) }
    }

    /**
     * 그 기기로 재는 채널을 고른다. **기기마다 따로 기억한다.**
     *
     * 다음번에 열 때부터 적용된다 — 재는 도중에 바꾸면 그 앞뒤가
     * 서로 다른 마이크의 값인데 평균은 하나로 합쳐진다.
     */
    fun setInputChannel(deviceKey: String, index: Int) {
        viewModelScope.launch { settingsStore.setInputChannel(deviceKey, index) }
    }

    /**
     * 시험 신호를 스피커로 내보낸다(명세 16장).
     *
     * 폰 두 대가 있으면 한 대가 내보내고 한 대가 잰다. 한 대뿐이어도
     * 스피커에서 나온 소리가 제 마이크로 돌아오므로 하울링 탐지를 확인할
     * 수 있다.
     */
    fun playSignal(signal: TestSignal, amplitude: Double? = null) {
        if (signalClosed) return
        // **사람의 뜻은 여기서 정해진다.** 번호를 주 스레드에서 올려,
        // 늦게 끝난 옛 명령이 이 뜻을 되돌리지 못하게 한다(SRLR-02).
        val intent = signalIntent.incrementAndGet()
        val st0 = controller.baseState.value
        // 세기를 받으면 그것으로 튼다. 교정 측정은 사람이 고른 값이 아니라
        // 제 쓰임에 맞는 세기가 필요하다(독립 검토 뒤 실기기에서 조정).
        //
        // **지금 값을 여기서 뜬다.** 실행자 안에서 상태를 다시 읽으면,
        // 슬라이더가 그 사이에 더 움직였을 때 엉뚱한 값으로 튼다.
        val req = SignalRequest(
            signal = signal,
            amplitude = amplitude ?: st0.signalAmplitude,
            toneHz = st0.signalToneHz,
            channels = st0.signalChannels,
        )
        // **화면은 먼저 바꾼다.** 소리를 여는 데 실기기에서 150ms 가
        // 걸린다 — 그 동안 눌러도 아무 일이 없으면 사람은 한 번 더 누른다.
        controller.update { st -> st.copy(playingSignal = signal, signalNoticeKo = null) }
        postSignalCommand { startSignalOnCommandThread(intent, signal, req) }
    }

    /**
     * 실제로 소리를 여는 자리. **`signalCommands` 스레드에서만 부른다.**
     *
     * 여기서 걸리는 시간이 주 스레드에 닿지 않는 것이 요점이다.
     */
    private fun startSignalOnCommandThread(intent: Long, signal: TestSignal, req: SignalRequest) {
        if (!signalIsCurrent(intent)) return

        // **주파수만 바뀌었으면 다시 열지 않는다**(담당자 지시 2026-09-29:
        // 「아주 부드럽게」). 같은 AudioTrack 에서 값만 바꾸면 위상이 이어져
        // 미끄러지듯 따라온다.
        //
        // **그 판단을 여기서 한다**(독립 검토 SRLR-03). 예전에는 주
        // 스레드가 `retune` 을 바로 불렀는데, 그때 줄에는 **옛 주파수를
        // 담은 재시작**이 이미 서 있었다. 그것이 뒤에 돌아 3kHz 를 1kHz 로
        // 되돌렸다 — 화면은 3kHz 인데 귀로는 1kHz 를 들으며 공진을
        // 판단하게 된다. 실기기에서 1,007Hz 로 재현했다.
        val old = activeSignalRequest
        if (req.signal == TestSignal.Custom && old?.signal == TestSignal.Custom &&
            req.safeAmplitude == old.safeAmplitude && req.channels == old.channels &&
            // **멈추라는 말이 있었으면 지름길을 쓰지 않는다**(SRLRO-01).
            //
            // 그 정지가 더 새 명령에 덮여 실제로 안 돌았더라도, **뒤따르는
            // 시작은 정규 길을 지나야 한다** — 포커스를 다시 얻고 옛 재생을
            // 정리하는 그 길이다.
            activeSignalIntent > lastSignalStopIntent.get() &&
            player.retune(req.toneHz)
        ) {
            activeSignalRequest = req
            activeSignalIntent = intent
            publishSignal(intent, signal, null)
            return
        }

        // **포커스를 못 얻으면 틀지 않는다**(독립 검토 SRLR-06).
        //
        // 처음에는 「다른 앱 하나 때문에 예배 준비가 멈추면 안 된다」며
        // 거절을 무시했다. 그런데 포커스 없이 나가는 소리는 **끊김을
        // 알려 줄 길도 없는 소리**다 — 화면은 정상 재생으로 보이고,
        // 사람은 그 조건으로 잰 값을 믿는다. 못 틀었다고 말하고 다시
        // 누르게 하는 편이 낫다.
        if (!interruptions.acquire()) {
            stopSignalOnCommandThread()
            publishSignal(
                intent,
                null,
                "다른 앱이 소리를 쓰고 있어 테스트 신호를 시작하지 못했습니다. " +
                    "그 앱을 멈춘 뒤 다시 눌러 보십시오.",
            )
            return
        }
        // 지킴이를 건 뒤에 뜻이 바뀌었으면 **건 것을 도로 놓는다.**
        if (!signalIsCurrent(intent)) {
            stopSignalOnCommandThread()
            return
        }

        val gen = player.start(req)
        playGeneration = gen
        activeSignalRequest = if (gen != SignalPlayer.NONE) req else null
        activeSignalIntent = intent
        // 여는 동안 사람이 멈췄을 수 있다. 그러면 **연 것을 도로 닫는다.**
        if (!signalIsCurrent(intent)) {
            stopSignalOnCommandThread()
            return
        }
        val ok = gen != SignalPlayer.NONE
        // **못 열었으면 지킴이를 놓는다**(독립 검토 SRLR-05). 열기에
        // 실패하면 내보내는 쪽의 종료 소식도 오지 않으므로, 여기서 안
        // 놓으면 방송 수신기와 포커스가 영영 남는다.
        if (!ok) interruptions.release()
        publishSignal(intent, if (ok) signal else null, signalStartNoticeKo(ok))
    }

    /**
     * 못 튼 까닭.
     *
     * **막힌 까닭을 구분해 적는다**(독립 검증 답변 1번). 앞 재생이 아직
     * 끝나지 않아 막힌 것인데 「다른 앱이 스피커를 쓰는지 보라」고 하면
     * 엉뚱한 곳을 보게 된다.
     *
     * 내보내는 쪽의 값을 읽으므로 **명령 스레드에서 부른다.**
     */
    private fun signalStartNoticeKo(ok: Boolean): String? = when {
        ok -> null
        // 놓기에 **실패**한 것이 자리를 차지하고 있으면 기다려도 풀리지
        // 않는다. 그때 「잠시 뒤 다시」라고 하면 안 된다.
        player.failedReleaseCount > 0 ->
            "소리 장치를 정리하지 못했습니다. 기다려도 풀리지 않으니 앱을 모두 닫았다가 다시 여십시오."
        player.pendingCount >= SignalPlayer.MAX_STUCK_PLAYBACKS ->
            "앞서 내보내던 소리가 아직 끝나지 않았습니다. 잠시 뒤 다시 눌러 보십시오."
        else ->
            "소리를 내보내지 못했습니다. 다른 앱이 스피커를 쓰고 있는지 보십시오."
    }

    /**
     * 소리를 멈춘다.
     *
     * **화면은 곧바로 꺼지고 실제 멈춤은 명령 스레드에서 한다.** 실기기에서
     * `stop()` 하나가 최대 129ms 걸린다 — 그만큼 손가락이 멎으면 「눌렸나」
     * 싶어 한 번 더 누르게 된다.
     */
    fun stopSignal() {
        // **뜻을 먼저 올린다.** 이 뒤에 끝나는 옛 시작은 화면을 못 되돌린다.
        //
        // **멈추라고 한 그 번호를 따로 남긴다**(SRLRO-01). 이 정지가 뒤에
        // 온 시작에 덮이더라도, 그 시작은 지름길 대신 정규 길을 지나야 한다.
        lastSignalStopIntent.set(signalIntent.incrementAndGet())
        controller.update { st -> st.copy(playingSignal = null) }
        // **소리를 멈춘 뒤에 놓는다.** 먼저 놓으면 놓는 그 순간에 다른
        // 앱이 소리를 시작해 마지막 30ms 램프와 겹친다.
        postSignalCommand { stopSignalOnCommandThread() }
    }

    /**
     * 알림이 막혀 있어 **서랍에 「측정 종료」 버튼이 없다**고 알린다(FS02).
     *
     * 측정은 그대로 한다 — 알림은 편의이지 측정의 조건이 아니다. 대신
     * 끝내는 방법이 앱뿐이라는 것을 말한다. 화면을 못 보는 사람이 두 시간
     * 뒤에 「어떻게 끄지」로 막히는 것이 실제 피해다.
     *
     * **[CaptureController.start] 뒤에 부른다** — 시작하면서 기기 안내문을
     * 덮어쓰기 때문이다.
     */
    fun noteNotificationsBlocked() {
        controller.update { st ->
            st.copy(
                deviceNoticeKo = "알림이 꺼져 있어 알림 서랍에 「측정 종료」 버튼이 나오지 않습니다. " +
                    "측정은 그대로 이어지며, 끝낼 때는 앱으로 돌아와 「측정 종료」를 누르십시오. " +
                    "설정 > 알림에서 허용하면 서랍에서도 끌 수 있습니다.",
            )
        }
    }
    /**
     * 측정을 시작한다. 실제 일은 [CaptureController] 가 한다.
     *
     * **서비스는 여기서 띄우지 않는다.** 띄우고 내리는 자리를 모두
     * [reconcileService] 하나로 모았다. 예전에는 여기서 먼저 띄웠는데,
     * 마이크를 못 열면 컨트롤러는 「끝났다」를 내보내지 않았다(처음부터
     * 끝난 상태였으니 바뀐 것이 없다) — 그래서 **띄워 놓은 서비스가
     * 그대로 남았다.** 시작하는 자리와 끝내는 자리가 다른 것을 보고 있던
     * 셈이다(독립 검증 FS01).
     *
     * 안드로이드 14부터 `microphone` 형 포그라운드 서비스는 앱이 앞에
     * 있을 때만 띄울 수 있다. 여기서 [CaptureController.start] 는 사용자가
     * 「측정 시작」을 누른 그 순간 주 스레드에서 곧바로 돌고, 서비스는
     * 마이크가 열린 직후 같은 호출 안에서 뜬다 — 여전히 앞에 있다.
     */
    fun start() {
        controller.start()
    }

    /**
     * 측정을 멈춘다. 서비스는 [reconcileService] 가 내린다.
     *
     * 여기서 따로 내리지 않는다 — 내리는 자리가 둘이면 한쪽만 고치는 일이
     * 생긴다. 그게 FS01 에서 서비스가 남은 까닭이다.
     */
    fun stop() {
        controller.stop()
    }

    /**
     * 앱이 뒤로 갈 때 부른다. **소리만 멈춘다.**
     *
     * 예전에는 측정도 함께 멈췄다. 예배는 두 시간이고 그동안 담당자는
     * 다른 앱을 보는데, 그때마다 측정이 끊겼다. 이제 포그라운드 서비스가
     * 마이크를 붙들고 있어 **측정은 이어진다.**
     *
     * **소리는 여전히 멈춘다.** 예배당에서 4kHz 순음을 켜 놓고 앱을
     * 나가면 멈출 방법이 화면에 없다 — 앱을 다시 열거나 강제 종료해야
     * 했고, PA 에 물려 있으면 회중이 듣는다. 측정은 조용하지만 신호는
     * 그렇지 않다.
     */
    /**
     * 화면이 뒤로 갔다. **소리를 내는 모든 것을 멈춘다.**
     *
     * 예전에는 `stopSignal()` 뿐이었다. 그런데 FR 작업은
     * `viewModelScope` 에 있어 살아남고, 배경 70장이 모이면 **스스로**
     * 핑크 잡음을 틀었다(독립 검토 UA-03). 「백그라운드에서는 소리만
     * 멈춘다」는 이 앱의 약속이 거기서 깨졌다.
     *
     * 측정 자체는 멈추지 않는다 — 포그라운드 서비스가 마이크를 붙들고
     * 있고, 그것이 조용한 일이기 때문이다.
     *
     * **돌아와도 저절로 이어지지 않는다.** 소리를 내는 일은 사람이 다시
     * 눌러야 한다.
     */
    fun onBackground() {
        inForeground = false
        stopSignal()
        cancelResponse()
    }

    /** 화면이 앞으로 돌아왔다. 멈춘 것을 저절로 되살리지는 않는다. */
    fun onForeground() {
        inForeground = true
    }

    /** 세기를 바꾼다. 내보내는 중이면 그 자리에서 바꿔 끼운다. */
    fun setSignalLevel(amplitude: Double) {
        controller.update { st ->
            st.copy(signalAmplitude = amplitude.coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE))
        }
        controller.baseState.value.playingSignal?.let { playSignal(it) }
    }

    /**
     * 직접 고른 주파수를 바꾼다.
     *
     * **내보내는 중이면 바로 따라간다** — 슬라이더를 끌면서 어디가 하울링
     * 자리인지 귀로 찾는 쓰임이라, 멈췄다 다시 눌러야 하면 못 찾는다.
     * 다만 순음을 내고 있을 때만이다. 핑크 잡음 중에 주파수를 만졌다고
     * 순음으로 갈아 끼우면 놀란다.
     */
    fun setSignalToneHz(hz: Double) {
        controller.update { st ->
            st.copy(signalToneHz = hz.coerceIn(MIN_TONE_HZ, MAX_TONE_HZ))
        }
        // **바라는 주파수를 다른 값들과 함께 줄에 세운다**(독립 검토 SRLR-03).
        //
        // 예전에는 여기서 `retune` 을 바로 불렀다. 그러면 줄에 이미 서 있던
        // **옛 주파수를 담은 재시작**이 뒤에 돌아 값을 되돌린다 — 화면은
        // 3kHz 인데 귀로는 1kHz 를 듣는다. 자물쇠가 짧다는 것은 이 어긋남을
        // 풀어 주지 못한다.
        //
        // 이제 명령 스레드가 **다 모인 요청**을 보고 정한다: 주파수만
        // 바뀌었으면 위상을 이어 바꾸고, 아니면 다시 연다.
        when (val playing = controller.baseState.value.playingSignal) {
            TestSignal.Band, TestSignal.Custom -> playSignal(playing)
            else -> Unit
        }
    }

    /** 어느 쪽 스피커로 낼지 바꾼다. 내보내는 중이면 그 자리에서 바꿔 끼운다. */
    fun setSignalChannels(channels: SignalChannels) {
        controller.update { st -> st.copy(signalChannels = channels) }
        controller.baseState.value.playingSignal?.let { playSignal(it) }
    }

    /**
     * **어디로 내보낼지 사람이 고른다**(독립 검토 R5-04).
     *
     * 틀고 있는 중에 바꾸면 **다시 연다** — `setPreferredDevice` 는 열 때
     * 한 번 걸리는 것이라, 안 다시 열면 고른 것이 다음 재생부터 먹는다.
     * 화면에는 바뀐 것처럼 보이는데 소리는 그대로인 **조용한 어긋남**이다.
     */
    fun setSignalOutput(output: kr.joa.selahrta.audio.SignalOutput) {
        viewModelScope.launch { settingsStore.setSignalOutput(output) }
        controller.update { st -> st.copy(signalRouteKo = null) }
        controller.baseState.value.playingSignal?.let { playSignal(it) }
    }

    fun dismissSignalNotice() {
        controller.update { st -> st.copy(signalNoticeKo = null) }
    }

    // ── RTA 측정 저장 (지시서 §7) ────────────────────────

    /**
     * 저장한 측정을 담아 두는 곳.
     *
     * `filesDir` 안이라 **앱을 지우면 함께 사라진다.** 기록 탭의 세션과
     * 같은 자리다.
     */
    private val rtaStore = kr.joa.selahrta.data.rta.RtaMeasurementStore(
        java.io.File(app.filesDir, "rta-measurements"),
    )

    init {
        // 앱을 껐다 켜도 남아 있어야 한다 — 열자마자 한 번 읽어 둔다.
        reloadSavedRta()
    }

    private var rtaRun: kr.joa.selahrta.data.rta.RtaCaptureRun? = null
    private var rtaJob: Job? = null

    /** 저장된 것을 다시 읽어 화면에 올린다. */
    fun reloadSavedRta() {
        viewModelScope.launch {
            val listing = kotlinx.coroutines.withContext(Dispatchers.IO) { rtaStore.listing() }
            val sets = kotlinx.coroutines.withContext(Dispatchers.IO) { rtaStore.sets() }
            // **못 읽은 것이 있으면 말한다**(독립 검토 12회차 4장).
            //
            // 조용히 건너뛰면 목록이 아무 말 없이 짧아진다 — 사람은 저장이
            // 안 된 줄 알고 **다시 잰다.** 「읽을 수 없다」와 「없다」는
            // 다른 말이다.
            val notice = if (listing.unreadable > 0) {
                "기록 ${listing.unreadable}개를 읽지 못했습니다. " +
                    "더 새 판으로 저장된 것일 수 있습니다 — 앱을 올린 뒤 다시 보십시오."
            } else {
                null
            }
            controller.update { st ->
                st.copy(
                    savedRta = listing.items,
                    savedRtaSets = sets,
                    rtaSaveNoticeKo = notice ?: st.rtaSaveNoticeKo,
                )
            }
        }
    }

    /**
     * 한 채널을 재어 저장한다(담당자 지시 2026-09-29).
     *
     * **안정화 1.5초 뒤 10초를 잰다.** 장 수가 아니라 시간으로 센다 —
     * 까닭은 [kr.joa.selahrta.data.rta.RtaCaptureRun] 에 적었다.
     *
     * 재는 동안 **보정이 바뀌면 그만둔다.** 앞뒤가 다른 잣대로 잰 값을
     * 한 곡선에 담으면 그것을 나중에 가릴 길이 없다.
     */
    fun startRtaCapture(nameKo: String, setId: String?) {
        if (rtaJob?.isActive == true) return
        rtaJob = viewModelScope.launch { runOneRtaCapture(nameKo, setId) }
    }

    /**
     * 한 번 재고 저장한다. **끝날 때까지 기다린다.**
     *
     * 차례대로 재는 쪽([startRtaSequence])이 이것을 이어 부르므로, 한 번이
     * 끝난 것을 **기다릴 수 있어야** 한다. 예전에는 이 일이 `launch` 안에
     * 통째로 들어 있어 밖에서 끝을 볼 수 없었다.
     *
     * 저장까지 됐으면 true.
     */
    private suspend fun runOneRtaCapture(
        nameKo: String,
        setId: String?,
        /**
         * 이 측정이 **테스트 신호를 내며** 재는 것인가(차례 측정).
         *
         * 참이면 **출력이 실제로 열린 것을 확인한 뒤에** 재기 시작하고,
         * 재는 동안 그 출력이 살아 있는지 본다.
         */
        requireOutput: Boolean = false,
    ): Boolean {
        // **출력이 실제로 열렸는지 먼저 확인한다**(독립 검토 RMS-02).
        //
        // 화면의 `playingSignal` 은 **사람의 뜻**이지 출력이 아니다. 채널
        // 바꾸기를 줄에 넣자마자 안정화를 시작하면, 명령이 밀렸을 때
        // **소리가 나기도 전에 안정화가 끝난다** — 검토자가 출력이 한 번도
        // 안 열린 채 아홉 장을 쌓는 것을 보였다.
        val outputGeneration = if (requireOutput) {
            val gen = awaitSignalGeneration()
            if (gen == null || gen == SignalPlayer.NONE) {
                controller.update { st ->
                    st.copy(
                        rtaSaveNoticeKo = "테스트 신호가 실제로 나오지 않아 측정을 시작하지 않았습니다.",
                    )
                }
                return false
            }
            gen
        } else {
            null
        }

        // **여기서 조건을 붙박는다**(독립 검토 RMS-01). 저장할 때 화면을
        // 다시 읽으면 그사이 바뀐 값이 「그때의 조건」인 척 적힌다.
        val context = rtaContextNow()

        val run = kr.joa.selahrta.data.rta.RtaCaptureRun(
            settleMs = kr.joa.selahrta.data.rta.RtaCaptureRun.DEFAULT_SETTLE_MS,
            measureMs = kr.joa.selahrta.data.rta.RtaCaptureRun.DEFAULT_MEASURE_MS,
            maxGapMs = kr.joa.selahrta.data.rta.RtaCaptureRun.DEFAULT_MAX_GAP_MS,
            minFrameRatio = kr.joa.selahrta.data.rta.RtaCaptureRun.DEFAULT_MIN_FRAME_RATIO,
            startedAtMs = nowMs(),
        )
        rtaRun = run
        publishRtaCapture(run, nameKo)

        // **분석 스레드에서 직접 모으는 곳을 붙인다**(독립 검토 PND-03).
        //
        // **평균이 여기서 나온다**(단계 B). coverage 수치는 적어 두기만
        // 하고 **아직 판정에 쓰지 않는다**(단계 C 에서 분포를 모으는 중).
        //
        // 한 번에 다 바꾸면 「측정이 갑자기 안 된다」가 되고, 그것은 쓰는
        // 사람에게 고장이다(설계 3장).
        val sampleRate = context.sampleRate ?: 0
        val coverage = if (sampleRate > 0) {
            kr.joa.selahrta.data.rta.RtaCoverage(
                totalFrames = sampleRate.toLong() *
                    kr.joa.selahrta.data.rta.RtaCaptureRun.DEFAULT_MEASURE_MS / 1000L,
            ).also { controller.attachBandPowerSink(it) }
        } else {
            null
        }

        return kotlinx.coroutines.coroutineScope {
            // **장을 받는 쪽과 시간을 보는 쪽을 따로 둔다.** 장이 아예 안
            // 오는 것이 알아채야 할 일이므로, 시간은 장과 무관하게 흘러야
            // 한다.
            val frames = launch {
                // **같은 장을 두 번 세지 않는다**(독립 검토 RMS-05).
                //
                // 꾸러미의 시각은 **새 FFT 가 없어도 바뀐다** — 마이크에서
                // 0표본을 읽은 덩어리에도 들고 있던 그 장이 그대로 실린다.
                // 시각으로 가리면 **입력이 멈췄는데도 10초가 정상 완료된다.**
                // 장 번호가 같으면 같은 장이다.
                var lastSeq = -1L
                controller.measurement
                    .filterNotNull()
                    .collect { m ->
                        val rta = m.rta ?: return@collect
                        if (rta.seq == lastSeq) return@collect
                        lastSeq = rta.seq
                        run.onFrame(rta.bandsDbfs, nowMs())
                    }
            }
            try {
                while (run.running) {
                    kotlinx.coroutines.delay(RTA_TICK_MS)
                    run.tick(nowMs())
                    // **안정화가 끝나면 셈을 켠다**(PND-03). 안정화 구간의
                    // 소리는 평균에 안 들어가므로 coverage 에도 안 들어가야 한다.
                    //
                    // **「바뀌는 순간」을 잡으려다 한 번 헛돌았다.** 전환은
                    // 틱이 아니라 **장이 들어올 때** 일어난다(`onFrame` 이
                    // 직접 `tick` 을 부른다). 그래서 틱이 볼 때는 이미 끝난
                    // 뒤였고 셈이 영영 안 켜졌다 — 기기에서 `windows=0` 으로
                    // 드러났다.
                    //
                    // 지금은 **재는 중이면 그냥 켠다.** 두 번 켜도 같다.
                    //
                    // **켜는 일은 분석 스레드에서 한다**(독립 검토 R2-02).
                    // 원점을 거기서 박아야 **첫 창이 늦게 오는 만큼 앞이
                    // 비어 보인다.** 여기서 켜고 첫 창에서 원점을 잡으면
                    // 그 지연이 원점째로 밀려 사라진다.
                    if (run.phase is kr.joa.selahrta.data.rta.RtaCapturePhase.Measuring) {
                        coverage?.let { c ->
                            controller.postRtaFramePosition { c.armAt(it) }
                        }
                    }
                    // **조건이 바뀌면 그만둔다**(RMS-01). 앞뒤가 다른 잣대로
                    // 잰 값을 한 곡선에 담으면 나중에 가릴 길이 없다.
                    val changed = rtaContextChangeKo(context, rtaContextNow())
                    if (changed != null) {
                        run.cancel()
                        controller.update { st -> st.copy(rtaSaveNoticeKo = changed) }
                    } else if (outputGeneration != null &&
                        awaitSignalGeneration() != outputGeneration
                    ) {
                        // **출력이 끊기면 그만둔다**(RMS-02). 백그라운드로
                        // 가거나 포커스를 잃으면 소리가 멎는데, 그대로 두면
                        // **조용한 방을 재어 좌우 비교로 저장한다.**
                        run.cancel()
                        controller.update { st ->
                            st.copy(rtaSaveNoticeKo = "테스트 신호가 멈춰 측정을 중단했습니다.")
                        }
                    }
                    publishRtaCapture(run, nameKo)
                }
            } finally {
                frames.cancel()
                coverage?.let { controller.detachBandPowerSink(it) }
                // **그만둔 길에서도 화면을 치운다**(독립 검토 RMS-04).
                //
                // 코루틴이 취소되면 아래 `finishRtaCapture` 까지 못 간다.
                // 그러면 일은 끝났는데 화면에는 「안정화 중」이 남아, 저장
                // 단추로도 차례 시작으로도 돌아오지 못한다.
                //
                // **내 것일 때만 치운다** — 늦은 정리가 새 측정을 지우면
                // 안 된다.
                if (rtaRun === run) {
                    rtaRun = null
                    controller.update { st -> st.copy(rtaCapture = null) }
                }
            }

            // **저장을 승인하기 직전에 한 번 더 본다**(독립 검토 PND-01).
            //
            // 틱마다 보는 것으로는 모자랐다. `tick` 이 먼저 `Done` 으로
            // 바꾸고 나면 **`cancel()` 은 아무 일도 하지 않는다** — 그
            // 함수가 「도는 중일 때만」 멈추기 때문이다. 그래서 **완료
            // 경계에서 바뀐 조건**은 걸리지 않고 그대로 저장됐다.
            //
            // 여기서는 멈추라고 부탁하지 않는다. **저장 함수를 아예
            // 부르지 않는다** — 끝났다는 사실보다 **조건이 성립하지
            // 않는다는 사실이 앞선다.**
            //
            // 출력 확인은 기다리는 일이므로 **그 뒤에** 다시 본다. 기다리는
            // 동안에도 사람이 채널을 바꿀 수 있다.
            val outputAtCommit = if (outputGeneration != null) awaitSignalGeneration() else null
            val invalid = rtaContextChangeKo(context, rtaContextNow())
                ?: "테스트 신호가 멈춰 측정을 저장하지 않았습니다."
                    .takeIf { outputGeneration != null && outputAtCommit != outputGeneration }
            if (invalid != null) {
                if (rtaRun === run) rtaRun = null
                controller.update { st -> st.copy(rtaCapture = null, rtaSaveNoticeKo = invalid) }
                return@coroutineScope false
            }

            finishRtaCapture(run, nameKo, setId, context, coverage)
        }
    }

    /**
     * **명령 스레드에 물어 지금 나가는 재생의 세대를 받는다**(RMS-02).
     *
     * 줄 맨 뒤에 서서 묻기 때문에, **바로 앞에 넣은 채널 바꾸기가 실제로
     * 돈 뒤**의 값이 온다. 못 열었으면 [SignalPlayer.NONE] 이고, 대답이
     * 없으면 null 이다.
     */
    private suspend fun awaitSignalGeneration(): Long? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            signalCommands.ask(SIGNAL_ASK_TIMEOUT_MS) { playGeneration }
        }

    /**
     * **한 측정 구간 내내 붙박여 있어야 하는 조건**(독립 검토 RMS-01).
     *
     * 재는 도중에 이 가운데 하나라도 바뀌면 **그 앞뒤는 같은 곡선에 담을 수
     * 없다.** 담아 놓으면 어느 부분이 어떤 조건이었는지 **저장한 뒤에는
     * 되살릴 길이 없다.**
     *
     * 저장할 때 쓰는 값도 여기서 나온다 — 끝나고 화면을 다시 읽으면 그사이
     * 바뀐 값이 「그때의 조건」인 척 적힌다.
     */
    private data class RtaCaptureContext(
        val inputKey: String?,
        val inputSource: String?,
        val inputChannel: Int?,
        val calibrationState: String,
        val calibrationSource: String,
        val offsetDb: Double,
        val curveName: String,
        val curveHash: String,
        val analysisWeighting: String,
        val fftSize: Int?,
        val sampleRate: Int?,
        val signal: String,
        /** 어떤 소리였는가 — 주파수·대역 모양까지(독립 검토 PND-02). */
        val signalSpec: String,
        val channel: String,
        val outputDbfs: Double,
    )

    private fun rtaContextNow(): RtaCaptureContext {
        val st = controller.baseState.value
        val spec = rtaSpec()
        val curve = st.curve?.takeIf { it.enabled }
        return RtaCaptureContext(
            inputKey = st.opened?.deviceKey,
            inputSource = st.opened?.audioSource?.name,
            inputChannel = st.opened?.channelIndex,
            calibrationState = st.calibration.state.name,
            // **「보정이 없다」와 「모른다」를 가른다**(담당자 지시 기준 5).
            calibrationSource = st.calibration.saved?.source?.name ?: "",
            offsetDb = st.calibration.offset.db,
            // **「안 걸렸다」와 「모른다」를 가른다.**
            curveName = curve?.fileName ?: "",
            curveHash = curveHashOf(curve),
            analysisWeighting = st.meterSettings.analysisWeighting.name,
            fftSize = spec?.first,
            sampleRate = spec?.second,
            signal = st.playingSignal?.name ?: "",
            signalSpec = signalSpecOf(st.playingSignal, st.signalToneHz),
            channel = st.signalChannels.name,
            outputDbfs = 20.0 * kotlin.math.log10(st.signalAmplitude.coerceAtLeast(1e-6)),
        )
    }

    /**
     * **무슨 소리를 넣었는가** — 이름만으로 모자란 몫을 적는다(PND-02).
     *
     * 「주파수 지정」은 1kHz 일 수도 2kHz 일 수도 있고, 「1/3 옥타브 대역」은
     * 중심이 어디냐에 따라 전혀 다른 소리다. 그것이 안 적히면 **다른
     * 주파수로 잰 두 곡선이 같은 조건**이 되고, 그 차이가 방의 차이로 읽힌다.
     *
     * **대역의 폭은 사람이 고르는 값이 아니라 규칙**이라, 숫자 대신 그
     * 규칙의 이름을 적는다 — 없는 사용자 설정을 있는 것처럼 만들지 않는다.
     */
    private fun signalSpecOf(signal: TestSignal?, toneHz: Double): String = when {
        signal == null -> "none"
        signal == TestSignal.Band ->
            "band:$toneHz:${kr.joa.selahrta.dsp.BandNoiseFilter.SPEC_VERSION}"
        signal.usesPickedHz -> "hz:$toneHz"
        // 핑크·화이트·정해진 순음은 **이름이 곧 조건**이다.
        else -> "fixed"
    }

    /**
     * **곡선 내용의 지문**(독립 검토 RMS-03).
     *
     * 파일 이름만으로는 모자라다 — 같은 이름으로 **다른 곡선**을 가져올 수
     * 있고, 그러면 곡선이 바뀐 줄 모르고 견준다.
     *
     * 곡선이 안 걸렸으면 빈 글자다. 「모른다」가 아니라 「없다」이다.
     */
    private fun curveHashOf(curve: kr.joa.selahrta.calibration.ActiveCurve?): String {
        val points = curve?.curve?.points ?: return ""
        val text = points.joinToString(";") { p -> "${p.hz}:${p.gainDb}" }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.take(8).joinToString("") { b -> "%02x".format(b) }
    }

    /**
     * 무엇이 바뀌었는지 **사람 말로** 돌려준다. 안 바뀌었으면 null.
     *
     * 「측정을 멈췄습니다」만 적으면 사람이 무엇을 되돌려야 할지 모른다.
     */
    private fun rtaContextChangeKo(
        fixed: RtaCaptureContext,
        now: RtaCaptureContext,
    ): String? {
        if (fixed == now) return null
        val what = when {
            fixed.offsetDb != now.offsetDb || fixed.calibrationState != now.calibrationState ||
                fixed.calibrationSource != now.calibrationSource -> "보정"
            fixed.curveName != now.curveName || fixed.curveHash != now.curveHash -> "보정 곡선"
            fixed.analysisWeighting != now.analysisWeighting -> "분석 가중"
            fixed.channel != now.channel -> "출력 채널"
            fixed.signal != now.signal -> "테스트 신호"
            // 이름은 그대로인데 **속이 바뀐** 경우다 — 순음 주파수,
            // 대역 중심. 「측정 조건」이라고만 적으면 무엇을 되돌려야 할지 모른다.
            fixed.signalSpec != now.signalSpec -> "신호 주파수"
            fixed.outputDbfs != now.outputDbfs -> "출력 세기"
            fixed.inputKey != now.inputKey || fixed.inputSource != now.inputSource ||
                fixed.inputChannel != now.inputChannel -> "입력 마이크"
            else -> "측정 조건"
        }
        return "재는 도중 ${what}이(가) 바뀌어 측정을 멈췄습니다."
    }

    /**
     * **L → R → L+R 을 이어서 잰다**(지시서 §7 후속).
     *
     * 좌우를 견주려면 세 번을 **같은 자리에서, 같은 조건으로** 재야 한다.
     * 손으로 하면 그 사이에 채널을 잘못 고르거나 이름을 다르게 적게 되고,
     * 그러면 잰 차이가 **좌우의 차이가 아니게 된다.**
     *
     * **소리가 나고 있어야 한다.** 안 틀고 재면 조용한 방을 세 번 재어
     * 저장하게 된다 — 그것을 좌우 비교라고 읽으면 안 된다.
     *
     * 한 걸음이 실패하면 **거기서 멈춘다.** 그때까지 저장한 것은 그대로
     * 둔다 — 한 채널만 있어도 쓸모가 있다(담당자 지시 2항).
     */
    fun startRtaSequence(baseNameKo: String) {
        if (rtaJob?.isActive == true) return
        if (controller.baseState.value.playingSignal == null) {
            controller.update { st ->
                st.copy(
                    rtaSaveNoticeKo = "테스트 신호를 먼저 트십시오. " +
                        "소리가 없으면 조용한 방을 세 번 재게 됩니다.",
                )
            }
            return
        }

        val seq = kr.joa.selahrta.data.rta.RtaSequence(
            baseNameKo = baseNameKo,
            setId = kr.joa.selahrta.data.rta.RtaMeasurementStore.newId(),
        )
        rtaSequence = seq
        rtaJob = viewModelScope.launch {
            try {
                while (!seq.done) {
                    val channel = seq.currentChannel() ?: break
                    // 채널을 바꾸면 소리를 다시 연다. **안정화 구간이
                    // 그것을 받아 준다** — 앞 채널의 소리가 안 섞인다.
                    setSignalChannels(channel)
                    publishRtaSequence(seq)
                    // **출력이 실제로 열렸는지 확인한 뒤에 재게 한다**(RMS-02).
                    val ok = runOneRtaCapture(
                        seq.currentNameKo(),
                        seq.setId,
                        requireOutput = true,
                    )
                    if (!ok) break
                    seq.advance()
                }
            } finally {
                rtaSequence = null
                controller.update { st -> st.copy(rtaSequenceKo = null) }
            }
        }
    }

    private fun publishRtaSequence(seq: kr.joa.selahrta.data.rta.RtaSequence) {
        controller.update { st -> st.copy(rtaSequenceKo = seq.progressKo()) }
    }

    /** 지금 도는 차례. 한 번만 재는 경우에는 null 이다. */
    private var rtaSequence: kr.joa.selahrta.data.rta.RtaSequence? = null

    /**
     * 사람이 그만둔다. **저장하지 않는다.**
     *
     * 차례대로 재는 중이면 **그 차례도 함께 그만둔다** — 한 걸음만
     * 멈추고 다음으로 넘어가면 사람이 멈춘 뜻과 다르다.
     */
    fun cancelRtaCapture() {
        rtaJob?.cancel()
        rtaRun?.cancel()
    }

    private fun publishRtaCapture(
        run: kr.joa.selahrta.data.rta.RtaCaptureRun,
        nameKo: String,
    ) {
        val ui = if (run.running) {
            RtaCaptureUi(
                settling = run.phase is kr.joa.selahrta.data.rta.RtaCapturePhase.Settling,
                remainingMs = run.remainingMs(nowMs()),
                frames = run.average.frames,
                nameKo = nameKo,
            )
        } else {
            null
        }
        controller.update { st -> st.copy(rtaCapture = ui) }
    }

    /**
     * 다 잰 뒤. **정상으로 끝났을 때만 저장한다**(담당자 지시 기준 1).
     *
     * 끝난 평균과 **그때의 조건을 한 벌로** 담는다(기준 6) — 나중에 상태를
     * 다시 읽어 채우면 그 사이에 바뀐 값이 섞인다.
     */
    private suspend fun finishRtaCapture(
        run: kr.joa.selahrta.data.rta.RtaCaptureRun,
        nameKo: String,
        setId: String?,
        /** 잴 때 붙박아 둔 조건. **끝나고 화면을 다시 읽지 않는다**(RMS-01). */
        context: RtaCaptureContext,
        /**
         * 분석 스레드에서 모은 것. **평균이 여기서 나온다**(PND-03 단계 B).
         *
         * coverage 수치 자체는 **아직 판정에 쓰지 않는다** — 적어 두고
         * 분포를 모으는 중이다(단계 C).
         */
        coverage: kr.joa.selahrta.data.rta.RtaCoverage?,
    ): Boolean {
        if (rtaRun === run) {
            rtaRun = null
            controller.update { st -> st.copy(rtaCapture = null) }
        }

        when (val p = run.phase) {
            is kr.joa.selahrta.data.rta.RtaCapturePhase.Failed -> {
                controller.update { st -> st.copy(rtaSaveNoticeKo = p.reasonKo) }
                return false
            }
            is kr.joa.selahrta.data.rta.RtaCapturePhase.Cancelled -> return false
            else -> Unit
        }

        // **평균은 분석 스레드에서 모은 것을 쓴다**(독립 검토 PND-03, 단계 B).
        //
        // 예전에는 화면으로 나온 값을 다시 평균했다. 그 값은 **평활을 거쳤고
        // 66ms 에 한 번만** 나오므로, 분석한 장의 3분의 1 이상이 아예 안
        // 들어왔다 — 실기기에서 초당 23.5장 중 15장 남짓만 받았다.
        //
        // **짧고 큰 소리가 그 틈으로 사라진다.** 예배당에서 그 소리가 바로
        // 우리가 보려는 것이다.
        //
        // **못 받았으면 저장하지 않는다.** 옛 길로 슬그머니 돌아가면 같은
        // 이름(`averageVersion`)을 단 두 가지 값이 섞이고, 나중에 어느
        // 쪽이었는지 가릴 길이 없다.
        // **여기서 닫고 한 번에 떠 온다**(독립 검토 R2-02·R2-03).
        //
        // 예전에는 평균·창 수·coverage 를 **따로** 읽었다. 그 사이에 분석
        // 스레드가 한 장을 더하면 **서로 다른 시점의 값**이 한 기록에
        // 적힌다. 떼어 내는 명령은 줄에 들어갈 뿐 곧바로 듣지 않으므로,
        // 「끝났다」와 「더는 안 들어온다」는 같은 순간이 아니다.
        val closed = coverage?.close(context.offsetDb)
        val mean = closed?.meanDb ?: run {
            controller.update { st ->
                st.copy(
                    rtaSaveNoticeKo = if (coverage == null) {
                        "입력 설정을 읽지 못해 저장하지 않았습니다."
                    } else {
                        "분석한 값을 받지 못해 저장하지 않았습니다."
                    },
                )
            }
            return false
        }

        // **끝나고 화면을 다시 읽지 않는다**(독립 검토 RMS-01).
        //
        // 예전에는 여기서 `baseState` 를 다시 읽어 채널·신호·보정을 적었다.
        // 그러면 **재는 도중에 바뀐 값이 「그때의 조건」인 척** 적힌다 —
        // 검토자가 왼쪽으로 시작해 6초에 오른쪽으로 바꾼 공에서, **섞인
        // 곡선이 `Right` 로 저장되는 것**을 보였다.
        val snapshot = kr.joa.selahrta.data.rta.RtaMeasurement(
            id = kr.joa.selahrta.data.rta.RtaMeasurementStore.newId(),
            setId = setId ?: kr.joa.selahrta.data.rta.RtaMeasurementStore.newId(),
            nameKo = nameKo,
            method = "rta",
            bandsSpl = mean,
            signal = context.signal,
            channel = context.channel,
            outputDbfs = context.outputDbfs,
            // **이 값은 평균과 상관이 없다**(독립 검토 R2-03).
            //
            // 단계 B 뒤로 평균은 분석 스레드에서 나온다. 이 칸은 **화면에
            // 올라온 장 수**이고, `averageVersion` 이 `analysis-tap-v1` 인
            // 기록에서는 **평균에 들어간 창 수가 아니다.** 그 수는
            // 아래 `windows` 다. 남겨 두는 까닭은 **둘의 차이가 곧 화면
            // 발행이 얼마나 성글었나**이기 때문이다 — 옛 평균이 무엇을
            // 놓쳤는지 그 기록 안에서 셈할 수 있다.
            averagedFrames = run.average.frames,
            // **평균과 같은 순간에 뜬 값이다.** 따로 읽지 않는다(R2-03).
            coverage = closed.coverage,
            windows = closed.windows,
            hopFrames = controller.rtaHopFrames(),
            conditions = kr.joa.selahrta.data.rta.RtaConditions(
                inputKey = context.inputKey,
                calibrationState = context.calibrationState,
                // **「보정이 없다」와 「모른다」를 가른다**(담당자 지시 기준 5).
                // null 을 그대로 두면 겉장에 그 줄이 아예 안 쓰이고, 다시
                // 읽을 때 「미확인」이 된다 — 보정이 없다는 것은 아는
                // 사실이므로 빈 글자로 적어 둔다(실기기 겉장에서 확인).
                calibrationSource = context.calibrationSource,
                // **「안 걸렸다」와 「모른다」를 가른다.** 곡선이 꺼져 있으면
                // 빈 글자이고, 그것은 「모른다」가 아니다.
                curveName = context.curveName,
                fftSize = context.fftSize,
                sampleRate = context.sampleRate,
                // **이 다섯이 없어서 가중·보정 수치가 다른 것끼리 「같은
                // 조건」이 되었다**(독립 검토 RMS-03). 100Hz 에서 19dB 이,
                // 보정 6dB 이 좌우 차이로 읽혔다.
                analysisWeighting = context.analysisWeighting,
                offsetDb = context.offsetDb,
                curveHash = context.curveHash,
                inputSource = context.inputSource,
                inputChannel = context.inputChannel,
                signalSpec = context.signalSpec,
                // **이름을 올렸다**(단계 B). 평균을 분석 스레드의 생값으로
                // 모으면서 값이 달라졌다 — 이름을 그대로 두면 **정의가 다른
                // 두 곡선이 같은 조건으로** 셀해진다.
                averageVersion = "analysis-tap-v1",
            ),
            measuredAtEpochMs = System.currentTimeMillis(),
        )

        val saved = kotlinx.coroutines.withContext(Dispatchers.IO) { rtaStore.save(snapshot) }
        if (saved.isFailure) {
            controller.update { st2 ->
                st2.copy(rtaSaveNoticeKo = "저장하지 못했습니다: ${saved.exceptionOrNull()?.message}")
            }
            return false
        }
        reloadSavedRta()
        // 막 저장한 것은 바로 보여 준다 — 재고 나서 안 보이면 저장이
        // 됐는지 알 수 없다.
        controller.update { st2 ->
            st2.copy(rtaOverlayIds = st2.rtaOverlayIds + snapshot.id, rtaSaveNoticeKo = null)
        }
        return true
    }

    fun setRtaOverlayShown(id: String, shown: Boolean) {
        controller.update { st ->
            st.copy(rtaOverlayIds = if (shown) st.rtaOverlayIds + id else st.rtaOverlayIds - id)
        }
    }

    fun setRtaLiveVisible(visible: Boolean) {
        controller.update { st -> st.copy(rtaLiveVisible = visible) }
    }

    fun renameRtaSet(setId: String, nameKo: String) {
        viewModelScope.launch {
            val existing = kotlinx.coroutines.withContext(Dispatchers.IO) { rtaStore.sets() }
                .firstOrNull { it.id == setId }
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                rtaStore.putSet(
                    kr.joa.selahrta.data.rta.RtaComparisonSet(
                        id = setId,
                        nameKo = nameKo,
                        createdAtEpochMs = existing?.createdAtEpochMs ?: System.currentTimeMillis(),
                    ),
                )
            }
            reloadSavedRta()
        }
    }

    fun deleteRtaMeasurement(id: String) {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) { rtaStore.delete(id) }
            controller.update { st -> st.copy(rtaOverlayIds = st.rtaOverlayIds - id) }
            reloadSavedRta()
        }
    }

    fun deleteRtaSet(setId: String) {
        viewModelScope.launch {
            val gone = controller.baseState.value.savedRta
                .filter { it.setId == setId }.map { it.id }.toSet()
            kotlinx.coroutines.withContext(Dispatchers.IO) { rtaStore.deleteSet(setId) }
            controller.update { st -> st.copy(rtaOverlayIds = st.rtaOverlayIds - gone) }
            reloadSavedRta()
        }
    }

    fun dismissRtaSaveNotice() {
        controller.update { st -> st.copy(rtaSaveNoticeKo = null) }
    }

    /** 단조 시계. 벽시계를 쓰면 시간이 바뀔 때 남은 시간이 튄다. */
    private fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

    fun dismissDeviceNotice() {
        controller.update { st -> st.copy(deviceNoticeKo = null) }
    }

    /** 구간을 더한다(최대 5). 더 자리가 없으면 아무 일도 없다. */
    fun addSegment(s: ChurchSegment) {
        viewModelScope.launch { settingsStore.addSegment(s) }
    }

    /** 구간을 뺀다. 설교·찬양은 저장소가 막는다. */
    fun removeSegment(s: ChurchSegment) {
        viewModelScope.launch { settingsStore.removeSegment(s) }
    }

    /**
     * 구간을 고른다.
     *
     * **여기서 기록에 남기지 않는다**(독립 검토 UIS-04). 예전에는 이
     * 함수만 사건을 남겼는데, 그러면 더하기·빼기로 바뀌는 구간이 빠진다.
     * 설정을 받아들이는 경계 한 곳에서 앞뒤를 견주므로 여기는 저장만 한다.
     */
    fun setSegment(s: ChurchSegment) {
        viewModelScope.launch { settingsStore.setSegment(s) }
    }

    /** 구간 범위를 고친다. 말이 안 되는 값은 저장하지 않고 그 사실을 알린다. */
    fun setRange(s: ChurchSegment, r: SegmentRange) {
        viewModelScope.launch {
            val ok = settingsStore.setRange(s, r)
            if (!ok) {
                controller.update { st -> st.copy(
                    calibrationNoticeKo = "값이 서로 맞지 않습니다. " +
                        "평균 아래값 < 평균 위값 이어야 하고, 피크 위값이 평균 위값보다 커야 합니다.",
                ) }
            }
        }
    }

    fun resetRange(s: ChurchSegment) {
        viewModelScope.launch { settingsStore.resetRange(s) }
    }

    /**
     * 구간 이름을 고친다. 빈 이름이면 기본값으로 되돌린다.
     *
     * **이름은 화면에 적히는 글자일 뿐이다.** 어느 구간의 범위인지는
     * [ChurchSegment] 가 그대로 쥐고 있어, 이름을 바꿔도 저장된 범위를
     * 잃지 않는다.
     */
    fun setSegmentName(s: ChurchSegment, name: String) {
        viewModelScope.launch { settingsStore.setSegmentName(s, name) }
    }

    fun setSplWeighting(w: Weighting) {
        viewModelScope.launch { settingsStore.setSplWeighting(w) }
    }

    fun setPeakWeighting(w: Weighting) {
        viewModelScope.launch { settingsStore.setPeakWeighting(w) }
    }

    /**
     * 분석 가중을 바꾼다. **엔진에도 바로 흘린다** — 설정만 바꾸고
     * 엔진에 안 흘리면 화면 글자만 바뀌는 바로 그 상태가 된다(지시서 §18).
     */
    fun setAnalysisWeighting(w: Weighting) {
        viewModelScope.launch { settingsStore.setAnalysisWeighting(w) }
    }

    fun resetSplWeighting() { viewModelScope.launch { settingsStore.resetSplWeighting() } }
    fun resetPeakWeighting() { viewModelScope.launch { settingsStore.resetPeakWeighting() } }
    fun resetAnalysisWeighting() {
        viewModelScope.launch { settingsStore.resetAnalysisWeighting() }
    }
    fun setTimeWeight(t: TimeWeight) { viewModelScope.launch { settingsStore.setTimeWeight(t) } }
    fun setLeqWindow(w: LeqWindow) { viewModelScope.launch { settingsStore.setLeqWindow(w) } }

    /** 화면 밝기 한 벌. 재는 것과 무관하므로 엔진을 건드리지 않는다. */
    fun setThemeMode(m: kr.joa.selahrta.ui.theme.ThemeMode) {
        viewModelScope.launch { settingsStore.setThemeMode(m) }
    }

    /**
     * FFT 길이를 고른다. **다음 측정부터 적용된다.**
     *
     * 측정 중에 엔진을 새로 만들면 하울링 탐지와 FR 수집이 붙어 있던
     * 자리가 끊긴다 — FR 을 재는 도중이면 그 회차를 잃는다.
     */
    fun setFftSize(n: Int) { viewModelScope.launch { settingsStore.setFftSize(n) } }



    /**
     * 이 입력 조합의 보정값을 지켜본다.
     *
     * 열린 기기가 바뀌면 열쇠도 바뀌므로 이전 구독을 끊는다 — 안 끊으면
     * USB 를 꽂았을 때 내장 마이크의 보정값이 덮어쓴다.
     */
    /** 이 경로에 걸 수 있는 프로파일과 그 곡선. 경로가 바뀔 때 다시 읽는다. */
    private var applicableProfile: kr.joa.selahrta.calibration.MeasuredProfile? = null
    private var applicableCurve: kr.joa.selahrta.dsp.ResponseCurve? = null

    private val profileStore = kr.joa.selahrta.calibration.ProfileStore.forApp(app)
    private val deviceBuild = kr.joa.selahrta.calibration.DeviceBuildInfo.current()

    /**
     * 이 경로에 걸 프로파일을 찾아 둔다.
     *
     * **곡선은 걸 것이 정해진 뒤에만 읽는다.** 목록에 있는 모두의 곡선을
     * 미리 읽으면 경로가 바뀔 때마다 수백 점을 여러 벌 읽게 된다.
     */
    private suspend fun loadApplicableProfile(format: OpenedFormat) {
        val now = currentProfileEnvironment(format, controller.baseState.value.inputs, deviceBuild)
        val listed = profileStore.list()
            .filterIsInstance<kr.joa.selahrta.calibration.StoredProfile.Ok>()
            .map { it.profile }
        val picked = kr.joa.selahrta.calibration.pickApplicable(listed, now)
        applicableProfile = picked
        applicableCurve = picked?.let { p ->
            profileStore.loadCurves(p).getOrNull()?.correction
        }
        // 곡선을 못 읽었으면 프로파일도 없는 셈이다 — 반쪽으로 두면
        // 「걸렸다」고 적히면서 아무것도 안 걸린다.
        if (applicableCurve == null) applicableProfile = null
    }

    private fun watchCalibration(format: OpenedFormat) {
        calibrationJob?.cancel()
        val key = CalibrationKey.of(format)
        // **이 경로에 맞는 기종 기본값**을 한 번 찾아 둔다(개발지시서 17장).
        //
        // 경로가 바뀌면 이 함수가 다시 불리므로 여기서 굳혀도 된다 —
        // 표는 앱 안에 있어 도중에 변하지 않는다.
        val factory = kr.joa.selahrta.calibration.findFactoryCalibration(
            build = deviceBuild,
            micKind = format.micKind,
            source = format.audioSource,
            routedAddress = format.routedAddress,
            routeConfirmed = format.routeConfirmed,
        )
        calibrationJob = viewModelScope.launch {
            store.watch(key).collect { saved ->
                // **자리를 견주고 건다**(독립 재검토 CAR-03). 예전에는
                // 그대로 걸었다 — 저장 열쇠에 자리가 없어서 `bottom` 에서
                // 잰 감도를 `back` 에서도 「보정 완료」로 썼다.
                //
                // 지금 열린 경로의 자리를 쓴다. `format` 은 이 구독을 연
                // 그 경로이므로, 늦게 온 값이 다른 경로에 걸릴 길이 없다.
                controller.update { st ->
                    st.copy(
                        calibration = ActiveCalibration.from(
                            saved,
                            nowRoute = format.routedAddress,
                            routeConfirmed = format.routeConfirmed,
                            factory = factory,
                        ),
                    )
                }
            }
        }
        curveJob?.cancel()
        curveJob = viewModelScope.launch {
            loadApplicableProfile(format)
            curveStore.watch(key).collect { c ->
                // 보정은 **엔진 안에서 FFT 칸마다** 걸린다. 칸 계수는 곡선이
                // 바뀔 때 한 번만 계산한다 — 초당 15번 2049개 칸을 보간하면
                // 그것만으로 폰이 더워진다.
                // **꺼 두면 걸지 않는다.** 파일은 그대로 있고 화면에도 남지만,
                // 엔진에는 넘기지 않는다 — 그래야 보정 전·후를 견준다.
                //
                // **거는 자리는 여기 하나뿐이다.** 곡선의 출처가 둘(가져온
                // 파일 · 잰 프로파일)이 되었으므로, 어느 것을 걸지는
                // chooseCorrection 이 한 번만 정한다 — 각자 걸면 이중
                // 보정이 되고, 그건 화면에서 안 보인다(지시서 4.6).
                val chosen = chooseCorrection(
                    imported = c,
                    profile = applicableProfile,
                    profileCurve = applicableCurve,
                    now = currentProfileEnvironment(
                        format, controller.baseState.value.inputs, deviceBuild,
                    ),
                )
                controller.postToCapture { session -> session.rta.setCurve(chosen.curveOrNull) }
                controller.update { st -> st.copy(
                    curve = c,
                    curveGeneration = controller.baseState.value.curveGeneration + 1,
                    // 이전 판의 이름으로 저장된 곡선이 남아 있으면 알린다.
                    // 이름 규칙이 바뀌어 더는 찾지 못하는데, 조용히 두면
                    // 보정이 걸린 줄 알고 재게 된다(독립 재검증 추가 지적).
                    curveNoticeKo = if (c == null && curveStore.hasLegacyFile(key)) {
                        "이전 판에서 저장한 주파수 보정 파일이 남아 있지만 지금 " +
                            "판에서는 쓰지 않습니다. 파일을 다시 가져오십시오 — 옛 " +
                            "이름은 서로 다른 기기가 같은 파일을 가리킬 수 있어, " +
                            "어느 기기의 것인지 우리가 정할 수 없습니다."
                    } else {
                        controller.baseState.value.curveNoticeKo
                    },
                ) }
            }
        }
    }

    /**
     * 고른 파일을 읽어 보정으로 삼는다.
     *
     * 읽기는 IO 스레드에서 한다 — 클라우드 제공자를 거치면 네트워크를 타서
     * 주 스레드에서 하면 화면이 멈춘다.
     */
    fun importCurveFrom(uri: android.net.Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val read = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val name = app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                    } ?: uri.lastPathSegment ?: "보정 파일"
                    val text = app.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: error("파일을 열 수 없습니다.")
                    name to text
                }
            }
            read.fold(
                onSuccess = { (name, text) -> importCurve(name, text) },
                onFailure = {
                    controller.update { st -> st.copy(
                        curveNoticeKo = "파일을 읽지 못했습니다: ${it.message}",
                    ) }
                },
            )
        }
    }

    /** 주파수 보정 파일을 가져온다(명세 8장). */
    fun importCurve(fileName: String, text: String) {
        val format = controller.confirmedFormat()
        if (format == null) {
            controller.update { st -> st.copy(
                curveNoticeKo = if (controller.baseState.value.opened == null) {
                    "측정을 한 번 시작해야 어느 기기의 보정인지 정해집니다."
                } else {
                    "어느 마이크로 열렸는지 아직 확인되지 않았습니다. " +
                        "확인된 뒤에 가져오십시오 — 지금 저장하면 다른 기기의 " +
                        "보정으로 남을 수 있습니다."
                },
            ) }
            return
        }
        viewModelScope.launch {
            val r = curveStore.save(CalibrationKey.of(format), fileName, text)
            controller.update { st -> st.copy(
                curveNoticeKo = r.fold(
                    onSuccess = { c ->
                        // 파일이 수상해도 거부하지 않는다. 다만 무엇이 수상한지
                        // 함께 적어 사람이 판단하게 한다.
                        val loaded = CalibrationFile.load(text).getOrNull()
                        val warn = loaded?.warningKo
                        // **머리글을 그대로 남긴다.** 보정 부호가 응답인지
                        // 보정값인지는 이 줄들로만 가릴 수 있는데(9.4), 화면에
                        // 다 띄우기에는 길다. 처음 보는 마이크를 물릴 때
                        // 이 줄이 없으면 짐작밖에 할 수 없다.
                        android.util.Log.i(
                            "CurveImport",
                            "$fileName header=${loaded?.headerLines} " +
                                "sign=${loaded?.signEvidence} points=${c.pointCount}",
                        )
                        // **셋을 가려서 말한다**(실기기 확인 2026-09-27).
                        // 여기서 문구를 만들다가, 고를 수 없는 파일에
                        // 「고르십시오」라고 말하고 있었다.
                        kr.joa.selahrta.calibration.curveImportNoticeKo(
                            fileName = c.fileName,
                            pointCount = c.pointCount,
                            enabled = c.enabled,
                            unsupportedKo = c.readingUnsupportedKo,
                            warnKo = warn,
                        )
                    },
                    onFailure = { it.message ?: "보정 파일을 읽지 못했습니다." },
                ),
            ) }
        }
    }

    /**
     * 내장 마이크가 **정말 갈라지는지** 기기에 물어본다
     * (S23 개별 자동교정 지시서 2장).
     *
     * **재는 중에는 돌리지 않는다.** 후보마다 마이크를 열었다
     * 닫으므로, 돌고 있는 측정을 가로채거나 끊어 버린다.
     */
    fun probeMicrophones() {
        if (controller.running) {
            controller.update { st -> st.copy(
                deviceNoticeKo = "측정을 멈춘 뒤에 탐색하십시오. 탐색은 마이크를 " +
                    "여러 번 열었다 닫아 재는 중이면 측정이 끊깁니다.",
            ) }
            return
        }
        viewModelScope.launch {
            val report = kotlinx.coroutines.withContext(Dispatchers.IO) {
                MicrophoneProbe(getApplication()).run()
            }
            controller.update { st -> st.copy(micProbe = report) }
        }
    }

    /**
     * 이 보정이 어느 마이크의 것인지 적어 둔다. **측정에는 영향이 없다.**
     *
     * 오디오 인터페이스는 채널마다 다른 마이크가 꽂힐 수 있는데, 무엇이
     * 꽂혀 있는지는 꽂은 사람만 안다(USB 오디오 지시서 9.2).
     */
    fun setCurveMicName(name: String) {
        val format = controller.confirmedFormat() ?: return
        viewModelScope.launch { curveStore.setMicName(CalibrationKey.of(format), name) }
    }

    /**
     * 주파수 보정을 켜거나 끈다. **파일은 지우지 않는다.**
     *
     * 지우는 것과 가른다 — 보정 전·후를 견주려면 껐다 켰다 해야 하는데,
     * 그때마다 파일을 다시 가져오게 하면 아무도 견주지 않는다.
     */
    fun setCurveEnabled(on: Boolean) {
        val format = controller.confirmedFormat() ?: return
        viewModelScope.launch {
            // **막히면 그 까닭을 말한다**(독립 재검토 CFRF-01). 조용히
            // 안 켜지면 사람은 스위치가 고장 난 줄 안다.
            curveStore.setEnabled(CalibrationKey.of(format), on)?.let { why ->
                controller.update { st -> st.copy(curveNoticeKo = why) }
            }
        }
    }

    /**
     * 가져온 곡선의 **읽는 법을 사람이 정해 준다**(독립 재검토 CFRF-01).
     *
     * 켜기 스위치와 가른다. 스위치는 「쓸 것인가」만 받고, 이것은
     * 「둘째 열이 응답인가 보정값인가」를 받는다 — 서로 다른 물음이라
     * 한 단추로 묶으면 묻지 않은 것에 답한 것이 된다.
     */
    fun confirmCurveReading(
        token: kr.joa.selahrta.calibration.CurveConfirmationToken,
        reading: kr.joa.selahrta.dsp.CurveReading,
    ) {
        val format = controller.confirmedFormat() ?: return
        // **입력이 바뀌었으면 옛 화면의 선택을 받지 않는다**(CFRC-01).
        // 저장소도 같은 것을 보지만, 여기서 먼저 걸러야 사람에게 무엇이
        // 어긋났는지 말할 수 있다.
        if (CalibrationKey.of(format) != token.key) {
            controller.update { st ->
                st.copy(
                    curveNoticeKo = "보고 계시던 입력이 그 사이에 바뀌었습니다. " +
                        "지금 입력의 파일을 보고 다시 골라 주십시오.",
                )
            }
            return
        }
        viewModelScope.launch {
            val why = curveStore.confirmReading(token, reading)
            controller.update { st ->
                st.copy(
                    curveNoticeKo = why
                        ?: "이 파일을 「${reading.labelKo}」으로 읽기로 했습니다. 보정을 겁니다.",
                )
            }
        }
    }

    fun clearCurve() {
        val format = controller.confirmedFormat() ?: return
        viewModelScope.launch {
            curveStore.clear(CalibrationKey.of(format))
            controller.update { st -> st.copy(curveNoticeKo = "주파수 보정을 지웠습니다.") }
        }
    }

    fun dismissCurveNotice() {
        controller.update { st -> st.copy(curveNoticeKo = null) }
    }

    /**
     * 간편 보정: 기준 소음계 값을 받아 보정값을 계산해 저장한다.
     *
     * 지금 읽고 있는 dBFS 를 기준으로 삼는다. 소리가 안정된 상태에서
     * 눌러야 맞는 값이 나오며, 그렇지 않으면 저장소가 거부한다.
     *
     * ## 교정기 단추는 순음을 확인하고 누른다 (독립 검토 UIS-02)
     *
     * 2026-09-28 에 「간편교정은 쉬워야 한다」는 지시로 단추를 늘 누를 수
     * 있게 열었다. 그때 **틀리게 맞추면 화면이 이상해질 것**이라고 적어
     * 두었는데, **그 말이 틀렸다.**
     *
     * 오프셋은 `기준값 − 지금 읽는 값`이다. 그러니 무엇에 대고 맞추든
     * **맞춘 직후 화면은 반드시 기준값(94)을 가리킨다.** 교정기를 안
     * 끼우고 주변 소리에 맞춰도 화면은 94 다 — 틀릴수록 멀쩡해 보인다.
     * 검토자가 실제로 넣어 본 값이 `offset=144.0 dB` 였고 저장됐다.
     *
     * 그래서 **막지는 않되 묻는다.** 순음이 보이면 예전처럼 곧바로
     * 저장한다(쉬움은 그대로다). 안 보이면 「정말 교정기를 물렸습니까」를
     * 한 번 묻고, 사람이 그렇다고 하면 저장한다. 회색으로 잠가 두던 옛
     * 방식으로 돌아가지 않는다 — 그때는 아예 보정을 할 수 없었다.
     *
     * ## 판정한 값으로 저장한다 (독립 재검증 UISR-01)
     *
     * 판정과 저장이 **각자 값을 읽으면** 그 사이에 다른 값이 들어온다.
     * 그래서 근거([CalibrationEvidence])를 한 번 집어 그것으로 판정하고,
     * **그 근거가 들고 있는 값으로** 저장한다.
     */
    fun saveSimpleCalibration(
        referenceDb: Double,
        source: kr.joa.selahrta.calibration.CalibrationSource =
            kr.joa.selahrta.calibration.CalibrationSource.Meter,
    ) {
        // **한 번만 집는다.** 아래 판정과 저장이 같은 근거를 본다.
        val evidence = controller.calibrationEvidence()
        when (val gate = gateFor(source, evidence)) {
            is CalibrationGate.Reject ->
                controller.update { st -> st.copy(calibrationNoticeKo = gate.reasonKo) }

            CalibrationGate.AskConfirmation ->
                controller.update { st -> st.copy(
                    pendingCalibration = PendingCalibration(
                        referenceDb = referenceDb,
                        source = source,
                        session = st.session,
                        reasonKo = currentToneCheck()?.reasonKo,
                    ),
                ) }

            CalibrationGate.Save ->
                performCalibrationSave(referenceDb, source, requireNotNull(evidence))
        }
    }

    /**
     * 「교정기를 물렸다」는 사람의 확인을 받고 저장한다.
     *
     * **묻는 사이에 바뀌었으면 취소한다.** 대화상자가 떠 있는 동안 측정이
     * 멈추거나 마이크가 바뀔 수 있는데, 그때 저장하면 **묻던 것과 다른
     * 기기**의 보정값이 된다.
     */
    fun confirmPendingCalibration() {
        val pending = state.value.pendingCalibration ?: return
        controller.update { st -> st.copy(pendingCalibration = null) }

        // **근거를 새로 집는다**(독립 재검증 UISR-01). 묻는 동안 소리가
        // 끊겼거나 잘리기 시작했을 수 있다. 순음은 이미 사람이 답했으므로
        // 여기서 또 묻지 않는다 — 나머지 조건만 다시 본다.
        val evidence = controller.calibrationEvidence()
        val gate = gateFor(pending.source, evidence)
        if (gate is CalibrationGate.Reject) {
            controller.update { st -> st.copy(calibrationNoticeKo = gate.reasonKo) }
            return
        }
        // **묻기 전과 같은 세션인가.** 근거 쪽 세션도 함께 본다 — 화면
        // 상태만 보면 오디오 쪽이 이미 갈렸는데도 통과할 수 있다.
        if (state.value.session != pending.session ||
            evidence?.session != pending.session
        ) {
            controller.update { st -> st.copy(
                calibrationNoticeKo = "묻는 사이에 측정이 다시 시작됐습니다. " +
                    "보정하지 않았습니다 — 다시 누르십시오.",
            ) }
            return
        }
        performCalibrationSave(pending.referenceDb, pending.source, requireNotNull(evidence))
    }

    fun dismissPendingCalibration() {
        controller.update { st -> st.copy(pendingCalibration = null) }
    }

    /** 지금 들어오는 소리가 1kHz 순음인가. 첫 FFT 전이면 null. */
    private fun currentToneCheck(): kr.joa.selahrta.dsp.CalibratorToneCheck? =
        state.value.rta?.let { kr.joa.selahrta.dsp.checkCalibratorTone(it.bandsSpl) }

    /**
     * 건네받은 근거로 판단한다. 규칙 자체는 [calibrationGate] 에 있다 —
     * 안드로이드 없이 시험할 수 있어야 하기 때문이다.
     *
     * **근거를 밖에서 받는다**(독립 재검증 UISR-01). 여기서 직접 읽으면
     * 판정과 저장이 서로 다른 값을 볼 수 있다.
     */
    private fun gateFor(
        source: kr.joa.selahrta.calibration.CalibrationSource,
        evidence: kr.joa.selahrta.calibration.CalibrationEvidence?,
    ): CalibrationGate {
        // **직접 저장에도 세션 검사를 둔다**(독립 재검증 UISRF-03).
        // 예전에는 확인 대화상자 쪽에만 있었다.
        if (evidence != null && evidence.session != controller.baseState.value.session) {
            return CalibrationGate.Reject("측정 세션이 바뀌었습니다. 새 입력을 받은 뒤 다시 보정하십시오.")
        }
        return calibrationGate(
        opened = controller.baseState.value.opened != null,
        routeConfirmed = controller.confirmedFormat() != null,
        evidence = evidence,
        nowNs = controller.monotonicNs(),
        source = source,
        toneOk = currentToneCheck()?.ok,
        )
    }

    /**
     * **판정에 쓴 근거의 값으로 저장한다**(독립 재검증 UISR-01).
     * 여기서 다시 읽으면 그 사이에 들어온 다른 값이 저장된다.
     */
    private fun performCalibrationSave(
        referenceDb: Double,
        source: kr.joa.selahrta.calibration.CalibrationSource,
        evidence: kr.joa.selahrta.calibration.CalibrationEvidence,
    ) {
        val format = controller.confirmedFormat() ?: return
        val measured = evidence.measuredDbfs(state.value.meterSettings.splWeighting) ?: return
        val cal = GlobalCalibration(
            offsetDb = computeOffset(referenceDb, measured),
            savedAtEpochMs = System.currentTimeMillis(),
            referenceDb = referenceDb,
            measuredDbfs = measured,
            source = source,
            // **어느 자리에서 쟀는지 적는다**(독립 재검토 CAR-03). 열쇠에는
            // 자리가 없어서, 안 적으면 다른 자리에서도 그대로 걸린다.
            routeAddress = format.routedAddress.ifEmpty { null },
        )
        viewModelScope.launch {
            val notice = when (val r = store.save(CalibrationKey.of(format), cal)) {
                is SaveResult.Saved ->
                    "보정값 ${"%+.1f".format(cal.offsetDb)} dB 을 저장했습니다."
                is SaveResult.Rejected -> r.reasonKo
            }
            // MAX·PEAK 는 보정 이전 눈금으로 쌓인 값이라 더는 뜻이 없다. 비운다.
            controller.postToCapture { session -> session.engine.resetPeaks() }
            controller.update { st -> st.copy(calibrationNoticeKo = notice) }
        }
    }

    /**
     * 보정값을 **그대로** 저장한다. 기준에서 옮겨 온 값에 쓴다.
     *
     * [saveSimpleCalibration] 은 「지금 읽는 값」에서 오프셋을 셈하는데,
     * 옮겨 온 값은 이미 완성된 오프셋이라 다시 셈하면 안 된다. 그리고
     * 옮길 때는 소리가 나고 있지 않아도 된다 — 견준 것은 아까 잰 두
     * 측정이지 지금 들어오는 소리가 아니다.
     */
    fun saveOffsetDirect(
        offsetDb: Double,
        source: kr.joa.selahrta.calibration.CalibrationSource,
        /**
         * 이 값이 **어느 경로의 것인가.** null 이면 검사하지 않는다.
         *
         * 옮겨 온 보정값은 대상의 것인데, 저장은 「지금 열린 경로」로
         * 기록된다. 교정 마법사는 마지막에 **기준** 마이크가 열려 있어,
         * 검사가 없으면 대상의 오프셋이 기준의 것을 덮어쓴다(CA-01).
         *
         * **기기 열쇠가 아니라 경로 전체다**(독립 재검토 CA-R01). 저장소는
         * 기기·source·채널을 모두 가르는데 검사만 기기를 봤더니, 같은
         * 인터페이스의 다른 채널에 그대로 덮어쓸 수 있었다.
         */
        expectedKey: kr.joa.selahrta.calibration.CalibrationKey? = null,
    ) {
        val format = controller.confirmedFormat()
        if (expectedKey != null) {
            val now = format?.let { kr.joa.selahrta.calibration.CalibrationKey.of(it) }
            if (now != expectedKey) {
                controller.update { st ->
                    st.copy(
                        calibrationNoticeKo = "지금 열린 입력이 이 보정값의 대상 경로가 아닙니다. " +
                            "저장하지 않았습니다 — ${expectedKey.storageKey()} 로 되돌린 뒤 다시 하십시오.",
                    )
                }
                return
            }
        }
        if (format == null) {
            controller.update { st ->
                st.copy(
                    calibrationNoticeKo = "어느 마이크로 열렸는지 아직 확인되지 않았습니다. " +
                        "확인된 뒤에 보정하십시오 — 지금 저장하면 다른 기기의 " +
                        "보정값으로 남을 수 있습니다.",
                )
            }
            return
        }
        val measured = state.value.meter.currentDbfs
        val cal = GlobalCalibration(
            offsetDb = offsetDb,
            savedAtEpochMs = System.currentTimeMillis(),
            // 옮겨 온 값에는 「기준 소음계가 가리킨 값」이 없다. 지금
            // 읽는 값이 있으면 그것으로 되짚을 수 있게 남겨 둔다.
            referenceDb = measured?.let { it + offsetDb } ?: Double.NaN,
            measuredDbfs = measured ?: Double.NaN,
            source = source,
            // 옮겨 온 값도 **이 자리에서** 잰 대상의 것이다(CAR-03).
            routeAddress = format.routedAddress.ifEmpty { null },
        )
        viewModelScope.launch {
            val notice = when (val r = store.save(CalibrationKey.of(format), cal)) {
                is SaveResult.Saved ->
                    "기준 마이크에서 옮긴 보정값 ${"%+.1f".format(offsetDb)} dB 을 저장했습니다."
                is SaveResult.Rejected -> r.reasonKo
            }
            controller.postToCapture { session -> session.engine.resetPeaks() }
            controller.update { st -> st.copy(calibrationNoticeKo = notice) }
        }
    }

    /**
     * 「이 자리에서 잰 것이 맞다」고 **사람이** 확인해 준다(CAR-03).
     *
     * 옛 기록에는 잰 자리가 없다. 앱이 지금 자리로 채우면 확인하지 않은
     * 것을 확인했다고 적는 꼴이므로, 이 단추가 있어야 채워진다.
     */
    fun confirmCalibrationRoute() {
        val format = controller.confirmedFormat()
        if (format == null || format.routedAddress.isEmpty()) {
            controller.update { st ->
                st.copy(calibrationNoticeKo = ROUTE_UNCONFIRMED_KO)
            }
            return
        }
        viewModelScope.launch {
            val notice = when (
                val r = store.confirmRoute(CalibrationKey.of(format), format.routedAddress)
            ) {
                is SaveResult.Saved ->
                    "이 자리(${format.routedAddress})에서 잰 것으로 확인했습니다. 보정을 다시 겁니다."
                is SaveResult.Rejected -> r.reasonKo
            }
            controller.update { st -> st.copy(calibrationNoticeKo = notice) }
        }
    }

    /**
     * 사람이 잰 보정값을 지운다.
     *
     * **기종 기본값은 지우지 않는다** — 지울 수 없다. 그것은 앱 안에
     * 있고 [CalibrationStore] 에 없다. 그래서 여기서 지우고 나면 값이
     * 없어지는 것이 아니라 **기본값으로 되돌아간다.** 화면이 그 말을
     * 해야 사람이 「분명히 지웠는데 왜 숫자가 그대로지」를 겪지 않는다.
     */
    fun clearCalibration() {
        val format = controller.confirmedFormat() ?: return
        val factory = kr.joa.selahrta.calibration.findFactoryCalibration(
            build = deviceBuild,
            micKind = format.micKind,
            source = format.audioSource,
            routedAddress = format.routedAddress,
            routeConfirmed = format.routeConfirmed,
        )
        viewModelScope.launch {
            store.clear(CalibrationKey.of(format))
            controller.postToCapture { session -> session.engine.resetPeaks() }
            val notice = if (factory != null) {
                "잰 보정값을 지웠습니다. 이 기종의 기본값" +
                    "(${"%+.1f".format(factory.offsetDb)} dB)으로 돌아갑니다."
            } else {
                "보정값을 지웠습니다."
            }
            controller.update { st -> st.copy(calibrationNoticeKo = notice) }
        }
    }

    fun dismissCalibrationNotice() {
        controller.update { st -> st.copy(calibrationNoticeKo = null) }
    }

    /**
     * Spectrum 화면이 열렸는가를 엔진에 알린다.
     *
     * 켜져 있을 때만 엔진이 칸 2049개를 곱하고 줄인다. RTA 만 보는 동안
     * 그 일을 할 까닭이 없다.
     */
    fun setSpectrumEnabled(on: Boolean) {
        controller.spectrumEnabled = on
        // 끌 때는 화면 상태에서도 지운다. 남겨 두면 다시 열었을 때 몇 분 전
        // 그림이 한 장 스쳐 보이고, 그것을 지금 소리로 읽는다.
        if (!on) controller.update { st -> st.copy(spectrum = null) }
    }

    // ------------------------------------------------------------------
    // 측정 기록 (Phase 10)
    // ------------------------------------------------------------------

    /** 기록을 시작한다. 측정 중에만 된다. */
    /**
     * 기록 단추를 눌렀다. **먼저 소리를 담을지 묻는다.**
     *
     * 담는 것은 기본이 아니다. 실수로 켜진 채 다음 예배까지 담기는
     * 일이 없도록, 켜고 끄는 스위치가 아니라 **매번 묻는** 쪽으로
     * 했다(담당자 결정 2026-09-27).
     */
    fun askBeforeRecording() {
        val st = controller.baseState.value
        if (st.measure !is MeasureState.Running) {
            controller.update { it.copy(errorKo = "먼저 측정을 시작하십시오.") }
            return
        }
        controller.update { it.copy(askAudioBeforeRecording = true) }
    }

    fun dismissAudioAsk() {
        controller.update { it.copy(askAudioBeforeRecording = false) }
    }

    /** 소리를 담을 꼴을 고른다. 설정에 남는다. */
    fun setAudioFormat(f: kr.joa.selahrta.recording.AudioFileFormat) {
        controller.update { it.copy(audioFormat = f) }
        viewModelScope.launch { settingsStore.setAudioFormat(f) }
    }

    fun startRecording(withAudio: Boolean) {
        val st = controller.baseState.value
        controller.update { it.copy(askAudioBeforeRecording = false) }
        if (st.measure !is MeasureState.Running) {
            controller.update { it.copy(errorKo = "먼저 측정을 시작하십시오.") }
            return
        }
        val opened = st.opened ?: return
        val startedAt = System.currentTimeMillis()
        val id = kr.joa.selahrta.recording.SessionStore.newId(
            startedAt,
            // 같은 초에 두 번 시작해도 겹치지 않게. 없다고 **가정**해
            // 덮어쓰는 것보다 낫다.
            java.util.UUID.randomUUID().toString().take(4),
        )
        val cal = st.calibration
        // 기록 겉장에는 **표시값**(millis)을 적는다 — Session 이면 -1 이고,
        // 리포트가 그것을 「전체」로 읽는다.
        val window = st.meterSettings.leqWindow.millis
        val weighting = st.meterSettings.splWeighting
        val fmt = st.audioFormat

        // 자리를 먼저 만든다. 겉장은 끝낼 때 쓴다 — 그것이 「온전하다」는
        // 표시다.
        viewModelScope.launch(Dispatchers.IO) {
            val made = sessionStore.create(id)
            onMainThread {
                if (made.isFailure) {
                    controller.update { it.copy(errorKo = "기록할 자리를 만들지 못했습니다.") }
                    return@onMainThread
                }
                // **여기서 다시 읽는다**(독립 재검증 UISR-04).
                //
                // 위의 `st` 는 이 함수에 **들어올 때** 잡은 상태다. 그 뒤로
                // 폴더를 만드는 IO 가 끼어 있어, 그 사이에 사람이 구간을
                // 바꿀 수 있다. 옛 `st` 로 첫 사건을 적으면 **화면은 찬양인데
                // 기록은 설교**가 된다(검토자가 그 순서를 재현했다).
                val initial = controller.baseState.value.meterSettings
                controller.startRecording(
                    make = { session ->
                        kr.joa.selahrta.recording.SessionRecorder(
                            id = id,
                            nominalSampleRate = session.sampleRate,
                            startOffsetDb = cal.offset.db,
                            startReferenceOnly = cal.isReferenceOnly,
                            startLeqWindowMs = window,
                            weighting = weighting,
                        ).also { recorder ->
                            // **만들면서 곧바로 적는다.** 따로
                            // `postToCapture` 로 보내면 그 명령이 닿기 전에
                            // 첫 PCM 이 들어와, 사건 없는 앞구간이 생긴다.
                            // 여기서 적으면 반드시 0ms 다.
                            val segment = initial.activeSegment
                            recorder.note(
                                kr.joa.selahrta.recording.SessionEventKind.SegmentChange,
                                segment?.let { initial.nameFor(it) } ?: "구간 없음",
                                segment,
                            )
                        }
                    },
                    makeAudio = { session ->
                        if (!withAudio) {
                            null
                        } else {
                            // **기록 폴더 안에 둔다.** 지울 때 함께 사라져야
                            // 한다 — 따로 두면 기록을 지워도 소리가 남는다.
                            kr.joa.selahrta.recording.AudioFileRecorder.create(
                                format = fmt,
                                file = java.io.File(
                                    sessionStore.dirOf(id),
                                    kr.joa.selahrta.recording.audioFileName(fmt, startedAt),
                                ),
                                sampleRate = session.sampleRate,
                                maxFramesPerBlock = AUDIO_MAX_FRAMES,
                            )
                        }
                    },
                )
                recordingStartedAt = startedAt
                recordingOpened = opened
                controller.update { it.copy(recordingId = id) }
            }
        }
    }

    /** 기록만 끝낸다. 측정은 이어 간다. */
    fun stopRecording() {
        controller.stopRecording()
        controller.update { it.copy(recordingId = null) }
    }

    /** 구간(설교/찬양)이 바뀌었다고 기록에 적는다. */
    /**
     * 구간이 바뀐 순간을 기록에 남긴다. 구간은 행에 넣지 않고 사건으로 적는다.
     *
     * **「구간 없음」도 사건이다**(독립 검토 UIS-04). 마지막 구간을 지우면
     * 화면은 구간 없이 돌아가는데, 그때 사건을 안 남기면 표는 **지워진
     * 구간으로 끝까지 분류한다.** 비어 있는 것과 틀린 것은 다른 일이다.
     *
     * 이름은 **그때 화면에 적혀 있던 이름**으로 박아 둔다. 나중에 이름을
     * 바꿔도 옛 기록의 글자가 따라 바뀌지 않는다.
     */
    private fun noteSegmentToRecording(
        segment: ChurchSegment?,
        settings: kr.joa.selahrta.settings.MeterSettings,
    ) {
        controller.postToCapture { s ->
            s.recorder?.note(
                kr.joa.selahrta.recording.SessionEventKind.SegmentChange,
                segment?.let { settings.nameFor(it) } ?: "구간 없음",
                segment,
            )
        }
    }

    /** 기록을 시작할 때의 벽시계·입력. 겉장에 적는다. */
    private var recordingStartedAt: Long = 0L
    private var recordingOpened: OpenedFormat? = null

    /**
     * 기록을 파일로 쓴다. **주 스레드가 아니다.**
     *
     * 겉장을 **마지막에** 쓴다 — 그것이 「이 기록은 온전하다」는 표시이고,
     * 목록은 겉장 없는 폴더를 건너뛴다.
     *
     * **신원은 결과가 지니고 온다**([RecordedSession.id]). 화면 상태에서
     * 읽었더니 늘 비어 있었다 — [stopRecording] 이 곧바로 지우는데 이
     * 콜백은 캡처 스레드를 거쳐 그 뒤에 온다.
     */
    private fun writeRecording(
        rec: kr.joa.selahrta.recording.RecordedSession,
        audio: kr.joa.selahrta.recording.AudioFileRecorder?,
    ) {
        val opened = recordingOpened
        val startedAt = recordingStartedAt
        val st = controller.baseState.value
        val id = rec.id
        recordingOpened = null

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val spec = rtaSpec()
                sessionStore.timelineFile(id).outputStream().buffered().use { out ->
                    // 머리는 생성자가 쓴다.
                    val w = kr.joa.selahrta.recording.TimelineWriter(
                        out,
                        kr.joa.selahrta.recording.TimelineHeader(
                            nominalSampleRate = spec?.second ?: opened?.sampleRate ?: 48_000,
                            rowMillis = kr.joa.selahrta.recording.TimelineFormat.ROW_MILLIS,
                        ),
                    )
                    rec.rows.forEach { w.write(it) }
                }
                sessionStore.writeMeta(
                    kr.joa.selahrta.recording.SessionMeta(
                        id = id,
                        startedAtEpochMs = startedAt,
                        endedAtEpochMs = startedAt + rec.durationMs,
                        durationMs = rec.durationMs,
                        deviceKey = opened?.deviceKey.orEmpty(),
                        deviceLabel = opened?.deviceLabel.orEmpty(),
                        micKind = opened?.micKind ?: MicKind.BuiltIn,
                        sampleRate = opened?.sampleRate ?: 0,
                        encoding = opened?.encoding?.name.orEmpty(),
                        channelCount = opened?.channelCount ?: 0,
                        channelIndex = opened?.channelIndex ?: 0,
                        calibrationOffsetDb = st.calibration.offset.db,
                        referenceOnly = st.calibration.isReferenceOnly,
                        curveApplied = st.curve?.enabled == true,
                        curveLabel = st.curve?.fileName.orEmpty(),
                        // **요약은 기록기가 센 것을 쓴다.** 화면의 계기는
                        // 측정 전체를 재고 있어, 기록 시작 전의 소리까지
                        // 겉장에 섞여 들어갔다(2026-09-26 기기에서 확인).
                        weighting = rec.summary.weighting,
                        // **PEAK 에는 가중이 안 걸린다**(독립 검토 R3-04 를
                        // 따라가다 라이브에서도 찾았다).
                        //
                        // 예전에는 **화면 설정값**(기본 C)을 그대로 적었다.
                        // 그런데 기록에 남는 peak 는 `blockPeakDbfs` 이고
                        // 그 값은 **A·C·Z 가 모두 같다** — 재서 확인했고,
                        // `SplEngine` 도 「Peak 는 가중 전에 잰다」고 적어
                        // 두었다. 설정값을 적으면 **걸지 않은 가중을
                        // 걸었다고 말하는 것**이다.
                        //
                        // 화면의 PEAK 는 `weightedPeakDbfs` 라 설정을
                        // 따른다 — **화면과 기록이 다른 값**이라는 뜻이고,
                        // 그쪽은 따로 볼 일이다(요청서에 적었다).
                        peakWeighting = kr.joa.selahrta.dsp.Weighting.Z,
                        analysisWeighting = st.meterSettings.analysisWeighting,
                        timeWeight = st.meterSettings.timeWeight,
                        leqWindowMs = st.meterSettings.leqWindow.millis,
                        leqDb = rec.summary.leqDb ?: Double.NaN,
                        minDb = rec.summary.minDb ?: Double.NaN,
                        maxDb = rec.summary.maxDb ?: Double.NaN,
                        peakDb = rec.summary.peakDb ?: Double.NaN,
                        events = rec.events,
                        droppedPackets = rec.droppedPackets,
                        // **찌그러짐은 행에 있다.** 여기서 한 번 세어 두지
                        // 않으면 목록과 리포트가 타임라인을 열어야 안다.
                        clippedRows = rec.rows.count { it.clipped },
                        // **소리 파일을 마무리하고 그 결과를 적는다.**
                        // 실패했거나 빈 파일이면 null 이 오고, 그때 파일은
                        // 스스로 지워진다 — 반쯤 쓴 파일을 남기면 사람이
                        // 「담겼다」고 여긴다.
                        audio = audio?.finish()?.let { a ->
                            kr.joa.selahrta.recording.RecordedAudio(
                                format = a.format,
                                fileName = a.fileName,
                                bytes = a.bytes,
                                droppedBlocks = a.droppedBlocks,
                            )
                        },
                        // **보정이 바뀐 자리를 남긴다.** 행은 epoch id 만
                        // 지니므로, 이 표가 없으면 다시 열었을 때 그 id 를
                        // 풀 길이 없다 — 한 가지 값으로 뭉뚱그려진다.
                        epochs = rec.epochs,
                        // **잰 조건을 함께 남긴다**(담당자 지시 2026-09-27).
                        //
                        // 숫자만 남기면 나중에 그 숫자를 해석할 수 없다.
                        // 이 폰만 해도 UNPROCESSED 를 못 열어 음성인식
                        // 경로로 재고 활성 마이크가 하나다(S23 Ultra 실측).
                        conditions = kr.joa.selahrta.recording.MeasurementConditions(
                            audioSource = opened?.audioSource,
                            unprocessedSupported = opened?.unprocessedSupported,
                            agcDisabled = opened?.effects?.agc?.disabled,
                            nsDisabled = opened?.effects?.ns?.disabled,
                            aecDisabled = opened?.effects?.aec?.disabled,
                            routedAddress = opened?.routedAddress.orEmpty(),
                            activeMicCombo = kr.joa.selahrta.audio.activeMicComboKey(
                                opened?.activeMics.orEmpty(),
                            ),
                            calibrationState = st.calibration.state,
                            // **걸린 값의 출처만 적는다.** 자리를 확인하지
                            // 못해 보류한 값은 이 측정에 걸리지 않았다.
                            calibrationSource = st.calibration.saved
                                ?.source
                                ?.takeIf { !st.calibration.isReferenceOnly },
                            // **걸린 곡선만 적는다.** 꺼 두었거나 확인
                            // 전이면 이 측정에 안 들어갔다.
                            curveReading = st.curve?.takeIf { it.enabled }?.reading,
                            curveReadingConfirmed = st.curve?.readingConfirmed == true,
                        ),
                    ),
                ).getOrThrow()
            }.onFailure { e ->
                onMainThread {
                    controller.update { it.copy(errorKo = "기록을 저장하지 못했습니다: ${e.message}") }
                }
                runCatching { sessionStore.delete(id) }
            }
            onMainThread { controller.update { it.copy(recordingId = null) } }
        }
    }

    // ── 기록(컨셉 화면 7번) ────────────────────────────────

    /**
     * 저장된 기록을 다시 읽는다. 화면이 열릴 때와 지운 뒤에 부른다.
     *
     * **겉장만 읽는다.** 타임라인은 내보낼 때만 연다 — 2시간이면
     * 1.4MB 라, 목록을 그리려고 전부 읽으면 화면이 멈춘다.
     */
    fun refreshSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching { sessionStore.list() }.getOrNull() ?: return@launch
            onMainThread {
                controller.update {
                    it.copy(sessions = list.sessions, brokenSessions = list.broken)
                }
            }
        }
    }

    /**
     * 기록 하나를 연다.
     *
     * **목록이 준 겉장을 그대로 쓰지 않는다**(독립 검토 R4-02). 그 겉장은
     * 목록을 그릴 때의 것이라, 그 사이에 복구가 일어났으면 **행과 다른
     * 판**이 된다. 겉장과 행을 **한 번에** 다시 읽는다.
     */
    fun openSession(meta: kr.joa.selahrta.recording.SessionMeta) {
        // 먼저 목록의 것으로 화면을 연다 — 읽는 동안 빈 화면을 보이지
        // 않으려는 것이다. 행은 아직 비워 둔다.
        controller.update { it.copy(openedSession = meta, openedRows = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = sessionStore.readSnapshot(meta.id)
            onMainThread {
                // 그 사이에 다른 기록을 열었으면 버린다.
                controller.update { st ->
                    if (st.openedSession?.id != meta.id) {
                        st
                    } else {
                        snapshot.fold(
                            onSuccess = { s ->
                                st.copy(openedSession = s.meta, openedRows = s.rows)
                            },
                            onFailure = { e ->
                                // **못 읽으면 말한다.** 옛 겉장으로 그린
                                // 화면을 그대로 두면 「읽혔다」로 보인다.
                                st.copy(
                                    openedRows = emptyList(),
                                    historyNoticeKo = "이 기록을 읽지 못했습니다: ${e.message}",
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    /** 폰 내장 스피커. 못 찾으면 null — 그때는 안드로이드가 고른다. */
    private fun builtInSpeaker(): android.media.AudioDeviceInfo? = runCatching {
        val am = getApplication<Application>()
            .getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }.getOrNull()

    fun closeSession() {
        controller.update {
            it.copy(openedSession = null, openedRows = emptyList(), historyNoticeKo = null)
        }
    }

    fun dismissHistoryNotice() {
        controller.update { it.copy(historyNoticeKo = null) }
    }

    /**
     * 기록 하나를 CSV 로 내보내 **나눠 보낸다.**
     *
     * 파일은 캐시에 쓴다. 앱이 지워도 함께 사라지고, 받는 쪽은 이미
     * 제 앱으로 옮겨 간 뒤다.
     */
    /**
     * 기록 하나를 **CSV 와 소리로 내보내 나눠 보낸다.**
     *
     * 둘을 **한 번에** 보낸다. 따로 보내면 받는 쪽에서 어느 소리가 어느
     * 표의 것인지 알 수 없다.
     *
     * CSV 는 캐시에 새로 쓴다. 소리는 이미 기록 폴더에 있으므로 **옮겨
     * 담지 않고 그 자리에서 건넨다** — 100MB 를 복사할 까닭이 없다.
     */
    fun exportSession(meta: kr.joa.selahrta.recording.SessionMeta, withAudio: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val r = runCatching {
                val uris = ArrayList<android.net.Uri>(2)
                val dir = java.io.File(app.cacheDir, "export").apply { mkdirs() }
                val csv = java.io.File(dir, kr.joa.selahrta.recording.SessionExport.fileName(meta))
                // **겉장과 행을 한 판으로 읽는다**(독립 검토 R4-02).
                // 목록이 준 겉장에 나중에 읽은 행을 붙이면 **서로 다른
                // 판**이 섞여, 새 행을 옛 보정으로 내보낸다.
                //
                // **직렬화가 끝날 때까지 잠금을 쥔다**(독립 검토 R5-01).
                // 겉장만 잠금 안에서 읽고 행을 밖에서 흘려 보내면, 쓰는
                // 도중에 게시가 일어나 **표 한 장 안에서 판이 갈린다.**
                // 행을 통째로 메모리에 올리지 않으려고 스트림은 그대로 둔다.
                sessionStore.withSession(meta.id) {
                    sessionStore.recover(meta.id)
                    val fresh = sessionStore.readMeta(meta.id).getOrThrow()
                    sessionStore.timelineFile(meta.id).inputStream().buffered().use { input ->
                        // **UTF-8 로 못박는다.** 기본값이 UTF-8 이지만,
                        // 여기서 인코딩이 달라지면 BOM 만 맞고 본문이 깨진다.
                        csv.bufferedWriter(Charsets.UTF_8).use { out ->
                            kr.joa.selahrta.recording.SessionExport.writeCsv(fresh, input, out)
                        }
                    }
                }
                uris += uriFor(csv)

                if (withAudio) {
                    val audio = audioFileOf(meta)
                    if (audio != null && audio.isFile && audio.length() > 0L) uris += uriFor(audio)
                }
                uris
            }
            onMainThread {
                r.fold(
                    onSuccess = { uris -> controller.update { it.copy(shareUris = uris) } },
                    onFailure = { e ->
                        controller.update {
                            it.copy(historyNoticeKo = "내보내지 못했습니다: ${e.message}")
                        }
                    },
                )
            }
        }
    }

    /** 소리 파일만 보낸다. 표 없이 들어 보라고 건넬 때. */
    fun shareAudioOnly(meta: kr.joa.selahrta.recording.SessionMeta) {
        val f = audioFileOf(meta)
        if (f == null || !f.isFile || f.length() <= 0L) {
            controller.update { it.copy(historyNoticeKo = "소리 파일을 찾지 못했습니다.") }
            return
        }
        controller.update { it.copy(shareUris = listOf(uriFor(f))) }
    }

    /** 그 기록의 소리 파일. 담지 않았으면 null. */
    fun audioFileOf(meta: kr.joa.selahrta.recording.SessionMeta): java.io.File? {
        val a = meta.audio ?: return null
        return java.io.File(sessionStore.dirOf(meta.id), a.fileName)
    }

    /**
     * 기록 하나를 **읽을 수 있는 한 장(PDF)으로** 내보낸다.
     *
     * **CSV 와 쓰임이 다르다.** CSV 는 표 계산기로 여는 것이고, 이것은
     * 목사님·장로님께 그대로 건네거나 인쇄하는 것이다. 그래서 **따로
     * 보낸다** — 한 장만 필요한 자리에 표와 소리까지 딸려 가면 받는 쪽이
     * 무엇을 봐야 하는지 흐려진다.
     *
     * 글은 화면 리포트와 **같은 문장**이고, 믿음에 관한 경고가 숫자보다
     * 먼저 온다([kr.joa.selahrta.recording.ReportPdf]).
     */
    fun exportReportPdf(meta: kr.joa.selahrta.recording.SessionMeta) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val r = runCatching {
                val dir = java.io.File(app.cacheDir, "export").apply { mkdirs() }
                val pdf = java.io.File(dir, kr.joa.selahrta.recording.ReportPdf.fileName(meta))
                pdf.outputStream().buffered().use { out ->
                    kr.joa.selahrta.recording.ReportPdf.write(meta, out)
                }
                uriFor(pdf)
            }
            onMainThread {
                r.fold(
                    onSuccess = { uri -> controller.update { it.copy(shareUris = listOf(uri)) } },
                    onFailure = { e ->
                        controller.update {
                            it.copy(historyNoticeKo = "리포트를 만들지 못했습니다: ${e.message}")
                        }
                    },
                )
            }
        }
    }

    private fun uriFor(f: java.io.File): android.net.Uri {
        val app = getApplication<Application>()
        return androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", f)
    }

    /** 공유 창을 띄운 뒤 비운다. 같은 파일을 두 번 띄우지 않는다. */
    fun clearShareUri() {
        controller.update { it.copy(shareUris = emptyList()) }
    }

    /**
     * 기록을 지운다. **폴더째** 지운다.
     *
     * 되돌릴 수 없으므로 묻는 일은 화면이 한다.
     */
    /**
     * 기록에 메모를 적는다(명세 12장).
     *
     * **열어 둔 기록도 함께 갱신한다.** 목록만 새로 읽으면 지금 보고
     * 있는 화면은 옛 메모를 그대로 들고 있어, 적었는데 안 적힌 것처럼
     * 보인다.
     */
    fun setSessionMemo(id: String, memo: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val r = sessionStore.setMemo(id, memo)
            val updated = r.map { sessionStore.readMeta(id).getOrNull() }.getOrNull()
            onMainThread {
                controller.update {
                    it.copy(
                        // 열어 둔 것이 이 기록이면 갈아 끼운다.
                        openedSession = if (it.openedSession?.id == id && updated != null) {
                            updated
                        } else {
                            it.openedSession
                        },
                        historyNoticeKo = if (r.isSuccess) null else "메모를 저장하지 못했습니다.",
                    )
                }
            }
            refreshSessions()
        }
    }

    /**
     * 기록 하나를 **지금 설정으로 다시 분석한다**(명세 Recording-E).
     *
     * ## 왜 「지금 설정」인가
     *
     * 다시 셈하는 까닭이 대개 **보정을 새로 했기 때문**이다. 그러니
     * 지금 걸려 있는 보정·곡선·가중치를 그대로 쓴다.
     *
     * **소리는 그대로**이고 **처음 잰 타임라인도 남는다** —
     * [kr.joa.selahrta.recording.SessionReanalyzer] 가 지킨다.
     */
    fun reanalyzeSession(meta: kr.joa.selahrta.recording.SessionMeta) {
        val audio = audioFileOf(meta)
        if (audio == null || !audio.isFile) {
            controller.update {
                it.copy(historyNoticeKo = "이 기록에는 다시 분석할 소리가 없습니다.")
            }
            return
        }
        // **두 번 누르는 것을 막는다.** 같은 파일에 둘이 쓰면 결과가 섞인다.
        if (controller.baseState.value.reanalyzeProgress != null) return

        val st = controller.baseState.value

        // **이 보정이 이 기록의 것인가**(독립 검토 R3-03).
        //
        // 지금 걸린 보정은 **지금 열린 입력**의 것이다. 내장 마이크로 담은
        // 기록에 USB 마이크의 감도·곡선을 걸면 과거 기록이 **다른 마이크의
        // 잣대**로 바뀌고, 그것이 「보정 완료된 수치」처럼 보인다.
        // 오프셋은 숫자 하나라 **값만 봐서는 알 길이 없다.**
        kr.joa.selahrta.recording.ReanalysisIdentity.blockedReasonKo(
            meta = meta,
            openedDeviceKey = st.opened?.deviceKey,
            openedMicKind = st.opened?.micKind,
            openedChannelIndex = st.opened?.channelIndex,
            routeConfirmed = st.routeConfirmed,
            openedAudioSource = st.opened?.audioSource,
        )?.let { why ->
            controller.update { it.copy(historyNoticeKo = why) }
            return
        }
        val settings = kr.joa.selahrta.recording.ReanalysisSettings(
            offsetDb = st.calibration.offset.db,
            referenceOnly = st.calibration.isReferenceOnly,
            leqWindowMs = st.meterSettings.leqWindow.millis,
            weighting = st.meterSettings.splWeighting,
            analysisWeighting = st.meterSettings.analysisWeighting,
            timeWeight = st.meterSettings.timeWeight,
            fftSize = st.meterSettings.fftSize,
            curve = st.curve?.takeIf { it.enabled }?.curve,
            channelIndex = meta.channelIndex,
            // **곡선의 신원도 함께 넘긴다**(독립 검토 R4-04). 값만 넘기면
            // 겉장에 옛 이름과 옛 확인 근거가 그대로 남는다.
            curveLabel = st.curve?.takeIf { it.enabled }?.fileName.orEmpty(),
            curveReading = st.curve?.takeIf { it.enabled }?.reading,
            curveReadingConfirmed = st.curve?.readingConfirmed == true,
            // **걸린 값의 출처만 적는다** — 라이브와 같은 규칙이다.
            // 미보정이면 그 값은 이 측정에 안 걸렸다.
            calibrationSource = st.calibration.saved?.source
                ?.takeIf { !st.calibration.isReferenceOnly },
        )
        controller.update { it.copy(reanalyzeProgress = 0f, historyNoticeKo = null) }

        viewModelScope.launch(Dispatchers.IO) {
            val r = kr.joa.selahrta.recording.SessionReanalyzer(sessionStore).run(
                id = meta.id,
                audioFile = audio,
                settings = settings,
                nowMs = System.currentTimeMillis(),
            ) { p -> onMainThread { controller.update { it.copy(reanalyzeProgress = p) } } }

            onMainThread {
                controller.update {
                    it.copy(
                        reanalyzeProgress = null,
                        historyNoticeKo = if (r.isSuccess) {
                            "다시 분석했습니다. 처음 잰 값도 그대로 남아 있습니다."
                        } else {
                            "다시 분석하지 못했습니다: ${r.exceptionOrNull()?.message}"
                        },
                    )
                }
            }
            // **행까지 다시 읽어야 화면이 새 값을 본다.** 겉장만 갈아
            // 끼우면 그래프와 「듣는 자리의 값」은 옛 행 그대로다.
            r.getOrNull()?.let { onMainThread { openSession(it) } }
            refreshSessions()
        }
    }

    /**
     * **처음 잰 값으로 되돌린다**(명세 Recording-E 「원본 보존」).
     *
     * 원본을 남겨 두고도 **꺼낼 길이 없으면** 「그대로 남습니다」는
     * 확인할 수 없는 말이다. 되돌린 뒤에 다시 분석할 수도 있다 —
     * 원본은 그대로 남는다.
     *
     * **보정 소유권을 묻지 않는다.** 되돌리기는 새 잣대를 거는 일이
     * 아니라 **그때 잰 것을 그대로 꺼내는 일**이라, 지금 어느 입력이
     * 열려 있든 상관이 없다.
     */
    fun restoreOriginalAnalysis(meta: kr.joa.selahrta.recording.SessionMeta) {
        if (controller.baseState.value.reanalyzeProgress != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val r = kr.joa.selahrta.recording.SessionReanalyzer(sessionStore)
                .restoreOriginal(meta.id)
            onMainThread {
                controller.update {
                    it.copy(
                        historyNoticeKo = if (r.isSuccess) {
                            "처음 잰 값으로 되돌렸습니다."
                        } else {
                            "되돌리지 못했습니다: ${r.exceptionOrNull()?.message}"
                        },
                    )
                }
            }
            r.getOrNull()?.let { onMainThread { openSession(it) } }
            refreshSessions()
        }
    }

    fun deleteSession(meta: kr.joa.selahrta.recording.SessionMeta) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = sessionStore.delete(meta.id).isSuccess
            onMainThread {
                controller.update {
                    it.copy(
                        openedSession = null,
                        historyNoticeKo = if (ok) "기록을 지웠습니다." else "지우지 못했습니다.",
                    )
                }
            }
            refreshSessions()
        }
    }

    /** RTA 의 Peak Hold 를 다시 센다. */
    fun resetRtaHold() {
        controller.postToCapture { session -> session.rta.resetHold() }
    }

    /**
     * MAX·PEAK 를 다시 센다. Leq 는 그대로 둔다.
     *
     * 화면의 값은 엔진이 다음 덩어리를 내놓을 때 따라온다(약 66ms).
     * 여기서 미리 지우면, 아직 예전 값을 담고 있는 다음 프레임이 도착해
     * 도로 올라온 것처럼 보인다.
     */
    fun resetMax() {
        controller.postToCapture { session -> session.engine.resetPeaks() }
    }


    /**
     * 앱이 완전히 사라질 때(최근 앱에서 밀어내기 등).
     *
     * **서비스도 함께 내린다.** 안 내리면 아무도 보고 있지 않은 마이크가
     * 켜진 채로 알림만 남는다.
     */
    override fun onCleared() {
        signalClosed = true
        signalIntent.incrementAndGet()
        // **주 스레드에서 멈추려 들지 않는다**(독립 검토 SRLR-01, High).
        //
        // 예전에는 `shutdownNow()` 로 끊고 여기서 `player.stop()` 을
        // 불렀다. 그런데 `shutdownNow()` 는 **끼어들기를 시도할 뿐**
        // 끝나기를 기다리지 않는다. 끼어들기를 무시하는 `open()` 이
        // 뒤늦게 돌아오면 — 그때 `current` 는 아직 비어 있어 주 스레드의
        // stop 은 이미 끝났다 — **주인이 사라진 자리에서 소리가 시작되고
        // 치울 사람이 없다.** 화면의 정지 단추로도 못 끈다.
        //
        // 정리를 **같은 실행자**에 맡긴다. 돌던 일이 끝난 뒤에 돈다.
        signalCommands.close { stopSignalOnCommandThread() }
        controller.stop()
        CaptureService.stop(getApplication())
        super.onCleared()
    }
}

internal fun OpenFailure.toDomain(): FailureReason = when (this) {
    OpenFailure.PermissionDenied -> FailureReason.PermissionDenied
    OpenFailure.NoDevice -> FailureReason.NoInputDevice
    OpenFailure.Busy -> FailureReason.Preempted
    OpenFailure.Unsupported -> FailureReason.Unknown
    OpenFailure.Unknown -> FailureReason.Unknown
}


/**
 * 잰 것에 보정과 설정을 입혀 화면 상태를 만든다. **주 스레드에서만 부른다.**
 *
 * 여기가 측정값과 보정이 만나는 유일한 자리다. 그래서 화면에 뜬 숫자는
 * 언제나 「지금 상태의 보정으로 계산한 값」이다.
 */
internal fun CaptureUiState.withMeasurement(m: MeasurementSnapshot?): CaptureUiState {
    // 지난 세션의 값은 버린다(독립 검증 R03).
    if (m == null || m.session != session) return this

    val offset = calibration.offset
    return copy(
        diagnostics = m.diagnostics,
        meter = m.spl?.let { w ->
            val f = w.of(meterSettings.splWeighting)
            // **두 번 집는다.** 음압과 PEAK 의 잣대가 다를 수 있다 —
            // 킥·스네어의 저역이 A 가중에 깎여 순간 음압을 놓치는 일을
            // 막자고 갈라 둔 것이다(지시서 §16).
            val pf = w.of(meterSettings.peakWeighting)
            MeterReading(
                currentSpl = f.currentDbfs.toSpl(offset).value,
                leqShort = f.leqShortDbfs?.toSpl(offset)?.value,
                leqLong = f.leqLongDbfs?.toSpl(offset)?.value,
                leqLongFull = f.leqLongFull,
                maxSpl = f.maxDbfs.toSpl(offset).value,
                minSpl = f.minDbfs?.toSpl(offset)?.value,
                // **가중 뒤 값이다.** 클리핑 판정(peakClipped)은 가중 전
                // 값으로 내려야 ADC 포화를 놓치지 않는다.
                peakSpl = pf.weightedPeakDbfs.toSpl(offset).value,
                peakClipped = pf.peakClipped,
                anyClipping = m.anyClipping,
                currentDbfs = f.currentDbfs.value,
                calibrationDbfs = m.calibration?.measuredDbfs(meterSettings.splWeighting),
                calibrationCleanMs = m.calibration?.cleanMs ?: 0L,
                // 차이는 보정과 무관하다 — 두 쪽에 같은 값이 더해진다.
                cMinusA = w.cMinusALeq ?: w.cMinusA,
                lowEnergyHint = LowEnergyHint.of(w.cMinusALeq ?: w.cMinusA),
                settled = f.settled,
            )
        } ?: meter,
        // 프레임이 **그 곡선으로 계산된 것일 때만** 보정 적용이라고 적는다.
        feedback = m.feedback,
        feedbackLog = m.feedbackLog,
        spectrum = m.spectrum?.toView(offset.db, m.atMonotonicMs) ?: spectrum,
        rta = m.rta?.toView(
            offsetDb = offset.db,
            // 꺼 둔 곱선은 그리지도 않는다 — 엔진이 안 걸고 있는데 그리면
            // 「걸려 있다」고 읽힌다.
            curve = curve?.curve
                ?.takeIf { curve.enabled && m.rta.curveGeneration == curveGeneration },
        ) ?: rta,
    )
}

/**
 * dBFS 밴드를 dB SPL 로 옮긴다.
 *
 * **주파수 보정은 여기서 걸지 않는다.** 밴드로 묶기 전에 FFT 칸마다
 * 이미 걸렸다([RtaEngine.setCurve]). 묶은 뒤에 밴드 하나를 숫자 하나로
 * 보정하면 밴드 안에서 응답이 변하는 구간에서 틀린 값을 뺀다
 * (독립 검증 R05).
 */
/**
 * dBFS 칸을 dB SPL 로 옮긴다. [RtaFrame.toView] 와 **같은 오프셋**이다.
 *
 * 마이크 곡선은 여기서 걸지 않는다 — 엔진이 칸마다 이미 걸었다. 두 번
 * 걸면 고역이 곡선만큼 더 깎인다.
 */
private fun SpectrumFrame.toView(offsetDb: Double, atMs: Long) = SpectrumView(
    seq = seq,
    atMs = atMs,
    columnsSpl = DoubleArray(columnsDbfs.size) { columnsDbfs[it] + offsetDb },
    holdSpl = DoubleArray(holdDbfs.size) { holdDbfs[it] + offsetDb },
    hz = hz,
    binHz = binHz,
    topHz = top?.hz,
    topSpl = top?.dbfs?.plus(offsetDb),
)

private fun RtaFrame.toView(
    offsetDb: Double,
    curve: CalibrationCurve?,
) = RtaView(
    bandsSpl = DoubleArray(bandsDbfs.size) { bandsDbfs[it] + offsetDb },
    holdSpl = DoubleArray(holdDbfs.size) { holdDbfs[it] + offsetDb },
    resolved = resolved,
    lossDb = lossDb,
    curveApplied = curve != null,
    curveExtrapolated = curve?.bandCovered()?.let { covered ->
        BooleanArray(covered.size) { !covered[it] }
    },
)
