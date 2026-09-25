# SELAH RTA — UA 수정분 및 교정 마법사 독립 재검토

검토일: 2026-09-25 · 검토자: Codex

**판정: 승인 보류. Critical 0건, High 6건, Medium 4건.**

고정 커밋: `e031ece01a62c9a284cd9c54bfd32d72c715585e`.
검토 시작·종료 시 작업 트리는 깨끗했고 production 코드는 수정하지 않았다.

범위는 요청서의 UA 수정분 `23369aa..e031ece` 및 처음 제출된 A 구간
`9cf5b1b..f6ce966^`다. 후자는 83파일 +7,090/−883이다. 이전 보고서,
이번 합동 요청서, Master Spec v1.2, USB 입력·S23 교정 지시서와 관련 ADR을
대조했다. 모든 파일을 동일한 깊이로 검증했다는 뜻은 아니다. DSP 계산,
교정의 수집→판정→저장→적용 연결, 라우팅 및 비동기 작업 경계를 우선했다.
구현자의 실기기 확인 주장은 이번 독립 실행 결과와 구별했다.

## 이전 지적의 상태

| 이전 ID | 판정 | 이번 근거 |
|---|---|---|
| UA-01 | 해결 확인 | 이전 독립 startup-drift 시험 통과. 2·3Hz 비브라토, 간헐음, 실제 무음 뒤 재등장 대조군도 아래 범위에서 통과 |
| UA-02 | 미해결 경로 있음 | 완료 시점의 새 입력에 옛 배경을 귀속시키는 경합: CA-06 |
| UA-03 | FR 경로 수정 확인 | FR 취소·재생 전 확인은 추가됨. 별도 교정 마법사에는 동일한 백그라운드 재생 문제가 남음: CA-05 |
| UA-04 | 부분 해결 | 부분 충전 시간 좌표 계산은 수정됨. 세션 번호 충돌과 가득 찬 뒤 눈금 상태 갱신 문제가 추가됨: CA-07·08 |
| UA-05 | 범위 계약 해결, 봉우리 선택은 미완료 | 이전 독립 시험 통과. 제외한 봉우리의 경사면을 다음 봉우리로 보고함: CA-09 |

## CA-01 — High: 대상의 절대 보정값을 기준 마이크에 저장할 수 있다

**위치:** [SelahApp.kt:491](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/SelahApp.kt:491),
[CaptureViewModel.kt:1408](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:1408),
[CalibrationWizardScreen.kt:1000](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/screens/CalibrationWizardScreen.kt:1000).

**근거·재현:** 정상 순서는 기준→대상→기준 재측정이다. 완료 직후 열린 입력은
기준이다. 저장 화면의 `이 값으로 보정하기`는 대상 식별자 없이 Double 하나를
`saveOffsetDirect`로 넘기고, 이 함수는 **현재** `confirmedFormat()`의 키에 저장한다.
곡선 저장의 `wizard.save()`에는 대상 검사(447행)가 있지만 이 단추는 그 경로를
지나지 않는다. 실제 `computeLevelTransfer`에 기준 −40dBFS/offset 120dB,
대상 −30dBFS를 주면 대상 offset 110dB가 나온다. 이 상태에서 단추를 누르면
기준의 120dB를 110dB로 덮는 호출 연결이다. DataStore 쓰기 자체는 이번에 실행하지 않았다.

또한 이 단추에는 품질 승인 검사도 없다. `reviewGate`는 결과 존재만 검사하여
Save 화면 진입을 허용하고, Save 화면의 차단 안내와 별개로 이 단추는 활성화된다.
클리핑 등으로 최종 판정이 Fail인 결과라도 transfer 계산이 성공하면 이 경로가 열린다.

**영향:** 기준 경로의 SPL·Leq·MAX·Peak 및 그 기준으로 이어지는 교정이 함께
틀어진다. 예시에서는 −10dB다. 단순 화면 표시 문제가 아니라 지속 저장되는 오염이다.

**권장 수정:** 대상의 전체 CalibrationKey와 측정 세션, 최종 판정을 transfer와
함께 보존하고 저장 함수에서 검증한다. 대상과 현재 경로가 다르면 차단하고 안내한다.
주파수 프로파일 저장 단추에만 있던 검사를 이 경로에도 적용한다. 보정값 하나만
받는 외부 진입점으로 승인·대상 검사를 우회하지 않도록 한다.

**회귀 시험:** 기준을 연 채 적용, 다른 USB 채널에서 적용, 대상에서 적용,
Fail 결과 적용을 fake store로 실행한다. 앞의 거절 경우 write 0회와 기존 기준
offset 불변, 올바른 대상에서만 정확한 키로 110dB 저장을 단언한다.

## CA-02 — High: 무효 보정 마스크가 적용 직전에 사라진다

**위치:** [ActiveCorrection.kt:81](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/calibration/ActiveCorrection.kt:81),
[CalibrationCurve.kt:78](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationCurve.kt:78),
[RtaEngine.kt:191](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/RtaEngine.kt:191).

**근거·재현:** `correctionAsCurve`가 valid=false 점을 삭제하고 일반
`CalibrationCurve`로 변환한다. 이 곡선은 남은 점 사이를 보간하고 양 끝을
외삽하므로 버렸던 구간이 다시 보정된다. `ResponseCalibration`의 무효 구간을
가로질러 보간하지 않는 계약 및 교정 지시서 4.5와 반대다.

독립 fixture는 1kHz 0dB, 2kHz 무효, 4kHz +6dB 및 8kHz 무효다.
실제 production 변환 함수와 엔진이 쓰는 1kHz 기준 계수 계산 결과:

| 위치 | 기대 | 실제 적용 |
|---|---|---|
| 2,003.906Hz — 내부 무효 구간 | 원시 전력 유지, 미지원 표시 | +3.008445dB |
| 8,003.906Hz — 유효 범위 밖 | 원시 전력 유지, 미지원 표시 | +6dB |
| 46.875Hz — 유효 범위 아래 | 원시 전력 유지, 미지원 표시 | +6dB |

**영향:** 품질 화면에서 못 믿는다고 버린 대역을 RTA·Spectrum·Spectrogram이
실제로 보정한다. `covers()`도 내부 구멍을 유효 범위로 보고한다.

**권장 수정:** 측정 프로파일의 valid 마스크를 bin 계수와 coverage 계산까지
보존한다. 무효 구간을 가로질러 보간하거나 끝점 보정을 늘이지 않는다. 미지원
bin은 계산상 unity로 두되 UI에는 미지원으로 표시한다. 제조사에서 가져온
곡선의 외삽 정책과 측정 프로파일의 유효성 계약을 구별한다.

**회귀 시험:** 내부 구멍·양 끝 무효 구간에서 bin 계수=1 및 coverage=false를
단언하고, 유효 구간의 보정 부호·1kHz 기준은 유지되는지 확인한다. 현재 독립
JUnit 두 건이 실패한다. 기존 `믿을 수 없는 점은 빼고 옮긴다` 시험은 최저·최고
주파수만 확인하여 실제 적용 계수를 검증하지 못한다.

## CA-03 — High: 기준·대상 마이크의 배경과 DSP 증거를 하나로 재사용한다

**위치:** [CalibrationWizardViewModel.kt:185](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:185),
[CalibrationWizardViewModel.kt:353](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:353).

**근거·재현:** InputCheck는 그때 열린 입력의 noiseFloorDb와 DSP 판정을 한 벌만
저장한다. `finishMeasurement`는 같은 배열을 `noiseDb`와 `referenceNoiseDb`
양쪽에 넘기며 DSP 검사 결과 역시 대상 경로와 대조하지 않는다. 기준과 대상은
의도적으로 다른 입력인데, 각 ADC의 dBFS 잡음 바닥은 서로 대체할 수 없다.

실제 CAL 적용 함수로 기준 증거를 만들고 단계마다 120장을 기록한 독립 probe에서,
기준 SNR 40dB/대상 SNR 2dB인 자료를 넣었다. 현재 호출처럼 기준 배경을 양쪽에
쓰면 **31/31 usable, Pass**가 나온다. 실제 대상 배경을 넘기면 **0/31 usable**이고
곡선 생성이 거절된다. DSP 함수의 구별 기능은 있는데 ViewModel 연결이 없앤 것이다.

**영향:** 잡음에 묻힌 대상 또는 DSP 우회가 확인되지 않은 대상이 검증된
교정으로 저장·자동 적용될 수 있다.

**권장 수정:** 배경·DSP·클리핑 증거를 입력 경로, 채널, sample rate, 세션에
묶어 기준/대상별로 보관한다. 대상 전환 시 그 입력을 직접 검사하며, 기준 배경은
기준 신호와 같은 CAL 좌표로 비교한다. 빠진 증거를 다른 입력의 값으로 채우지 않는다.

**회귀 시험:** 기준만 조용한 경우와 대상만 조용한 경우를 서로 뒤집어 검사한다.
기준 검사 후 대상 전환 시 대상 증거는 unknown이어야 한다. 신호 검사를 기준만
통과한 경우 대상 자동 적용을 허용하지 않는 마법사 통합 시험이 필요하다.

## CA-04 — High: 내장 입력 묶음 키가 라우팅 변화와 보정 경계를 지운다

**위치:** [InputDevices.kt:56](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/audio/InputDevices.kt:56),
[MicSource.kt:280](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/audio/MicSource.kt:280),
[MicSource.kt:319](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/audio/MicSource.kt:319),
[CurrentEnvironment.kt:45](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/calibration/CurrentEnvironment.kt:45).

**근거·재현:** 내장 입력의 stableKey에서 주소를 삭제한 뒤 그 키를 선호 경로
확인, 라우팅 변경 검출, CalibrationKey에 모두 사용한다. 목록도 address=""로
접힌다. 따라서 실제 ID 22/bottom에서 ID 24/back으로 바뀌어도 같은 입력이다.
독립 probe에서 실제 production 자료형과 환경·적용 판정 함수를 실행한 결과:

`sameStableKey=true, envEqual=true, routingListenerWouldNotify=false, mayAutoApply=true`

ADR 자체도 bottom은 단독, back은 복수 활성 마이크였다고 기록한다. 이는 같은
주파수 응답이라는 근거가 아니다. 화면에서 기기 한 줄로 표시하는 선택을
되돌릴 필요는 없지만, 보정의 신원까지 합쳐서는 안 된다.

**영향:** 다른 내장 경로 또는 혼합 경로에 기존 보정이 계속 걸리고, 경로 변경
콜백 자체가 발생하지 않는다. USB와 내장이 다른 것은 여전히 구별하지만 내장
내부의 검증 경계가 사라졌다.

**권장 수정:** 화면의 그룹 키와 실제 측정·보정 경로 키를 분리한다. 실제
routed device 및 확인 가능한 활성 마이크/채널 증거가 바뀌거나 불명확해지면
기존 프로파일을 잠정 해제하고 재검증한다. 불확실성을 키 삭제로 해결하지 않는다.

**회귀 시험:** 표시 목록은 한 줄인 상태에서 bottom→back, 단일→복수 활성
입력으로 바꿔 route 변경 통지와 프로파일 적용 차단을 확인한다. 기존
`CurrentEnvironmentTest`의 수동 문자열 `BuiltIn|SM-S918N|back`은 현재 생성되는
키와 다르므로 실제 `InputDeviceInfo.stableKey`를 통해 fixture를 만들어야 한다.

## CA-05 — High: 교정 마법사는 홈으로 나간 뒤 시험음을 새로 시작한다

**위치:** [CalibrationWizardViewModel.kt:171](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:171),
[WizardRunner.kt:137](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/calibration/WizardRunner.kt:137),
[SelahApp.kt:204](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/SelahApp.kt:204).

**논리적 재현:** 마법사의 입력 점검을 시작하고 조용한 배경 40장을 모으는 동안
홈으로 나간다. ON_STOP은 CaptureViewModel의 FR 작업만 취소한다. 별도
CalibrationWizardViewModel의 작업은 살아 있고, 서비스의 캡처도 이어진다.
40장이 차면 `checkDsp`가 Pink를 재생한다. `WizardCaptureBridge → playSignal`
호출에는 foreground 검사가 없다. 마법사 `닫기`도 UI Boolean만 바꾸므로
작업의 취소가 아니다. 기기에서 소리를 내는 재현은 이번에 하지 않았다.

**영향:** UA-03과 같은 예기치 않은 소리가 다른 기능에서 재발한다. 돌아와도
자동 재개하지 않는다는 앱의 명시한 정책과 어긋난다.

**권장 수정:** 마법사가 실행 Job을 소유하고 닫기·백그라운드·캡처 종료에서
취소하도록 한다. 출력 직전 공통 재생 허용 정책도 검사하여 FR만 보호하지 않도록
한다. 취소 시 tap 제거와 소리 종료를 finally로 보장한다.

**회귀 시험:** 잡음 단계의 마지막 장 직전에 barrier를 걸고 ON_STOP/닫기를
전달한 후 나머지 장을 공급한다. fake player.start 0회, tap 제거, busy 해제,
앞으로 돌아와도 자동 재생 0회를 확인한다. 실제 VM 연결도 포함해야 한다.

## CA-06 — High: FR 배경을 수집한 입력 대신 완료 시점의 입력으로 표시한다

**위치:** [CaptureViewModel.kt:768](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:768),
[CaptureViewModel.kt:885](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:885).

**논리적 재현 순서:** A의 tap에 배경 70장이 찬 뒤 기다리던 코루틴의 재개를
보류한다. A를 멈추고 동일 sample rate/FFT의 B를 열어 라우팅을 확정한다.
FR 코루틴을 재개하면 A의 tap 배열을 평균 내면서 `openedNow`와 session은
현재 B에서 가져온다. 이후 `usableQuiet()`는 B로 잘못 붙인 표식을 검사하므로
통과한다. 조용한 배경만 재는 경로로 완료한 뒤 B의 신호를 별도로 재면 이 값이
소비된다. 수집 중 언제 끊어도 일어난다는 주장이 아니라, **충분한 옛 장이
쌓인 후 완료 처리가 늦어진 순서**가 반례다.

`stop()`은 controller.stop만 부르고, onStoppedHook도 FR 작업을 취소하지 않는다.
tap이 새 엔진에 재설치되는 문제는 아니다. 옛 tap의 자료에 새 신원을 붙이는 문제다.

**영향:** 입력 간 배경이 섞여 SNR·FR의 신뢰 판정이 틀린다. UA-02는 종료할 수 없다.

**권장 수정:** 작업 시작 시 전체 입력 신원·세션·분석 설정을 캡처하고 결과에
그 값을 붙인다. 수집 완료와 소비 시점에 둘 다 같은 세션인지 확인한다. 세션 종료는
작업 취소와 결과 폐기로 연결한다. 완료 시점의 신원을 원본의 신원으로 쓰지 않는다.

**회귀 시험:** 위 순서를 main dispatcher/barrier로 고정한다. B에서 ready=false,
옛 quiet 폐기, signal 시작 차단을 단언한다. A→A 재시작(기기는 같고 세션만
다름), A→B, channel·rate·FFT 변경을 나눈다. 이번 근거는 호출·상태 추적이며
AndroidViewModel 전체를 실행한 barrier 시험은 아니다.

## CA-07 — Medium: 새 캡처의 프레임 번호를 과거 번호로 판단해 화면이 멈춘다

**위치:** [AnalyzeScreens.kt:379](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/screens/AnalyzeScreens.kt:379),
[RtaEngine.kt:104](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/RtaEngine.kt:104).

**근거·재현:** lastSeq는 화면의 remember에 유지되지만 spectrumSeq는 새
RtaEngine마다 0에서 시작한다. 스펙트로그램을 유지한 채 엔진이 다시 열리면
새 seq가 과거 최대값을 넘을 때까지 `seq > lastSeq`가 거짓이다. 실제 엔진
두 개로 이전 seq=99, 새 seq=1..9를 만들면 승인되는 새 장은 0개다.
Compose 화면 자체를 실행한 시험은 아니며, 생산 seq와 실제 비교 조건을 검증했다.

**영향:** 오디오 측정은 도는데 화면은 장시간 과거 그림을 유지한다. 오래
실행한 세션일수록 기다리는 시간도 길어진다.

**권장 수정:** `(capture.session, seq)`를 프레임 신원으로 쓰고 세션 전환 시
lastSeq 및 과거 그림을 명시적으로 초기화하거나 새 세션 경계를 표시한다.

**회귀 시험:** 화면을 유지한 채 seq 10,000 이후 새 session/seq 1을 보내
즉시 새 장이 들어오는지 확인한다. 같은 세션·같은 seq의 중복만 거절해야 한다.

## CA-08 — Medium: 720장이 차면 시간 눈금의 상태 구독이 갱신을 놓친다

**위치:** [SpectrogramChart.kt:138](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/components/SpectrogramChart.kt:138),
[SpectrogramChart.kt:338](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/components/SpectrogramChart.kt:338).

**근거·재현:** 눈금 Canvas가 호출하는 agoMsAt는 frames만 Compose 상태로
읽는다. 가득 차면 frames=720이 고정이고, 바뀌는 head는 그림 Canvas만 읽는다.
timeline은 일반 가변 객체다. 실제 Compose 1.7.6 Snapshot으로 이 읽기·게시
경계를 실행하면 100ms 간격 720장 뒤 늦은 새 장을 넣었을 때 눈금 값은
71,900→79,900ms로 바뀌지만 **읽은 상태와 바뀐 상태의 교집합은 0개**다.

이는 Android 화면 렌더링 시험은 아니다. 하지만 draw의 상태 읽기를 국소적으로
추적하는 규칙상 눈금에 자체 갱신 원인이 없으며, 다른 재구성·재그리기가 우연히
발생해야 반영된다. [Android Compose 단계 문서](https://developer.android.com/develop/ui/compose/phases).

**영향:** 그림은 이동하는데 부하·일시 정지 등으로 달라진 시간 간격은 눈금에
제때 반영되지 않는다. 부분 충전 계산 시험만으로는 이 경계를 확인할 수 없다.

**권장 수정:** 눈금 읽기에 head 또는 매 push마다 증가하는 관측 가능한 revision을
포함한다. 가변 timeline만 바꾸고 frames 동일값을 게시하는 것으로 끝내지 않는다.

**회귀 시험:** 가득 채운 뒤 입력 주기를 바꾸고 눈금 draw scope의 invalidation과
새 텍스트를 검사한다. Snapshot 경계 시험과 Compose 렌더링 시험을 구별한다.

## CA-09 — Medium: 제외한 초저역 봉우리의 경사면을 ‘다음 봉우리’로 보고한다

**위치:** [SpectrumDisplay.kt:213](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/SpectrumDisplay.kt:213).

**근거·재현:** 후보 bin이 국소 최대인지 검사하지 않고 모든 bin을 보간한다.
19Hz 순음에 진폭 0.01의 1kHz 순음을 더해 20~20kHz에서 찾으면:

| sample rate | 보고 주파수 | 선택된 bin 전력의 좌/중/우 |
|---|---:|---|
| 48kHz | 29.296875Hz | 0.2795 / 0.01854 / 0.0002158 |
| 44.1kHz | 26.916504Hz | 0.3100 / 0.03639 / 0.0001937 |

좌→우로 계속 내려가는 경사면이다. 요청 범위에는 들어왔지만 봉우리는 아니다.
20Hz+1kHz에서도 같은 현상을 확인했다. 독립 JUnit은 1kHz 대신 29.296875Hz로 실패한다.

**영향:** 화면의 ‘가장 큰 봉우리’ 숫자가 존재하지 않는 주파수를 가리킨다.
범위 밖 신호를 범위 안 숫자로 둔갑시키지 않겠다는 수정 목적이 달성되지 않는다.

**권장 수정:** 국소 최대 후보를 먼저 고른 뒤 보간·범위 정책을 적용한다.
경계 봉우리를 제외했다면 다음 실제 국소 최대를 선택하고, 없으면 결과 없음으로
처리한다. 단순 clamp나 다음으로 큰 bin 선택으로 대체하지 않는다.

**회귀 시험:** 순수 17/19/20Hz와 약한 1kHz를 더한 경우를 함께 검사한다.
‘범위 안인가’뿐 아니라 실제 후보의 국소 최대성 및 다음 1kHz 선택도 단언한다.

## CA-10 — Medium: CAL의 측정 레벨 설명만으로 부호를 확정한다

**위치:** [CalibrationSign.kt:40](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:40),
[CalibrationSign.kt:169](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:169),
[WizardFlow.kt:145](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/calibration/WizardFlow.kt:145).

**근거·재현:** 모든 머리글에 대한 부분 문자열 검색에서 `spl` 또는 `measured`
등이 하나라도 있으면 LooksLikeResponse이고, 기준 CAL에서도 자동 Settled가 된다.
독립 fixture `# Measured at 94 dB SPL` + 주파수/숫자 세 줄은
`Settled(Response)`가 나온다. 이 주석은 측정 조건만 설명하며 두 번째 열이
응답인지 correction인지 말하지 않는다. 사람의 선택이 없어도 마법사 관문이 열린다.

**영향:** 모호한 파일을 임의로 응답 부호로 사용하여 뒤집힌 교정을 만들 수 있다.
이 fixture가 특정 Dayton 원본이라고 주장하는 것은 아니다. 일반 파일 입력
경계에서 ‘불명확하면 확인’ 계약을 검증하는 반례다.

**권장 수정:** 단순 설명문 단서는 사용자 확인용으로만 쓰고 부호 확정 권한을
주지 않는다. 알려진 형식의 명시적 열 의미 또는 사용자의 명시적 선택으로만
기준 CAL을 확정한다. 부호 선택의 출처도 보존한다.

**회귀 시험:** 실제 열 제목, 단순 SPL 감도/측정 조건 주석, response/correction
혼합 설명, 주석 없는 파일을 구별한다. 모호한 기준 파일은 선택 전 적용·측정
시작 0회여야 한다. 현재 독립 JUnit 한 건이 실패한다.

## 요청서의 질문에 대한 답

1. **600ms와 느린 비브라토:** 2Hz/3Hz, ±60cent, 1kHz 중심, 10초 연속 신호에서
   48k/44.1kHz 모두 Persistent 프레임 0개였다. 600ms는 이 두 주파수의 한 주기
   이상이므로 ‘두 주기 미만이라 실패한다’고 할 근거는 없다. 연속성은 별개의
   조건이지 흔들림 판정을 대신하는 근거가 아니다. 실제 보컬·악기 전체의
   오탐률을 보장하지는 않는다.
2. **stableSinceMs:** 창의 앞부분부터 관측된 안정성이 있다는 뜻으로 해석할
   수 있다. 초기 장 하나를 영구 안정으로 선언하는 것은 아니고 1.5초 조건을
   채우는 동안 창이 계속 재평가된다. 기존 startup 회귀 두 건 통과. 추가로
   200ms on/off에서는 지속 0회, 2초 tone→1초 무음→3초 tone에서는 무음 중 None,
   재등장 Persistent 및 사건 2개를 두 sample rate에서 확인했다.
3. **FR tap 전환:** 충분한 옛 장이 쌓인 뒤 완료 처리가 늦어지면 섞인다.
   CA-06처럼 수집 시작 신원을 보존해야 한다. 기기·채널 키만으로 사용자가
   돌린 하드웨어 gain 변화를 검출할 수 없다는 한계도 여전히 있다.
4. **ViewModel 시험 경계:** Application 전체를 가짜로 만드는 대신 FR/마법사의
   작업 조정 객체에 CapturePort(세션·경로 및 tap lease), SignalPort,
   CoroutineScope/dispatcher, tick을 주입한다. AndroidViewModel은 Android I/O와
   lifecycle 이벤트를 그 객체로 전달한다. 기존 WizardRunner 추출만으로는
   소유 Job 취소나 최종 store 키를 검사할 수 없다. 소규모 VM 연결 시험도 남겨
   ON_STOP→cancel과 최종 저장 호출이 실제로 연결되는지 검증한다.
5. **스펙트로그램:** 칸 인덱스 축에 실제 시각 눈금을 붙이는 방식 자체는 가능하나
   ‘균일한 시간축’처럼 지속시간을 면적·폭으로 읽게 해서는 안 된다. 누락 구간을
   표시하거나 시간 기준으로 재표본화해야 한다. CA-07·08은 그 정책과 별개인
   결함이다. read 완료 시각의 오차가 항상 hop 42.7ms 이하라는 주장은 성립하지
   않는다. 버퍼·스케줄링 지연은 별도이며, FFT 프레임 시각은 sample-frame 좌표로
   정의해야 한다. 이번에 실제 기기의 지연 상한을 측정하지 않았다.
6. **엄격한 20Hz 경계:** 정책으로 선택할 수 있다. 다만 단일 순음의 주파수
   추정과 두 근접 순음의 분해능은 다른 개념이다. bin 폭을 근거로 20Hz 숫자를
   전부 무의미하다고 단정할 필요는 없다. 경계를 제외하는 정책이어도 CA-09의
   경사면을 봉우리로 보고해서는 안 된다.
7. **CAL 부호:** Response일 때 전력에 `10^(-response/10)`, Correction일 때
   `10^(correction/10)`을 곱하는 산술 방향과 measured correction 변환 방향은
   맞다. 그렇다고 파일의 의미를 자동 판별한 근거가 맞다는 뜻은 아니다(CA-10).
   ADR에 적힌 EMM-6 파일 모양은 제조사 규약의 독립 확인이 아니다. 이번에 그
   원본 파일과 제조사의 해당 파일 규약을 직접 대조하지 않았으므로 확정하지 않는다.

ON_START/ON_STOP을 ‘보이는 동안 허용’의 기준으로 쓰는 것은 가능한 정책이다.
Paused 상태가 여전히 보이는 multi-window 경우가 있으므로 ON_PAUSE에 무조건
연결해야 하는 결함은 아니다. 다만 CA-05처럼 별도 작업 소유자를 빠뜨리면 그
정책도 지킬 수 없다. [Android Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle).

## 독립 실행과 검증 한계

고정 커밋의 app/dsp archive를 별도 폴더에 펼쳐 Kotlin 2.2.20/JBR 21.0.10(JVM target 17) 및
캐시된 JUnit으로 실행했다. 원본 저장소의 build 출력·소스는 바꾸지 않았다.

| 실행 | 결과 |
|---|---|
| 기존 DSP 전체 | 532건 통과 |
| 선택한 앱 교정 시험 10개 클래스 | 176건 통과 |
| 이전 검토자의 독립 회귀 시험 원본 | 2건 통과 |
| 새 독립 회귀 시험 | 4건 실행, 4건 실패 — CA-02 2건, CA-09 1건, CA-10 1건 |
| 추가 수치·경계 probe | 비브라토/간헐음/무음, SNR 교차 사용, offset 산술, seq 재시작, 내장 경로 충돌, Compose 상태 구독 결과 기록 |

앱 176건은 ActiveCorrection, WizardFlow, WizardRunner, ProfileBuilder,
ProfileStore, MeasuredProfile, ProfileCodec, GainDrift, CurrentEnvironment,
CalibrationKeyChannel 시험이다. DataStore의 ActiveCurve 자료형만 원문에서
그대로 분리하여 컴파일했다. ActiveCorrection의 production 함수는 그대로
컴파일해 독립 JUnit에서 호출했다. 별도의 combined probe는 그 함수 본문을
그대로 추출한 버전도 사용한다. 추출 파일·실행 스크립트를 함께 남긴다.

**Android 전체 Gradle build/lint, APK, 실제 Compose 화면, AudioRecord·USB 장치,
DataStore 최종 저장 및 소리 재생을 이번 실행으로 검증했다고 주장하지 않는다.**
CA-01·05·06의 비동기/저장 연결은 소스의 구체적 호출 순서 근거다. 나머지
probe도 자신의 검증 층을 넘어 실기기 증명으로 확대하지 않는다.

기존 시험 통과는 계산·모델 수준의 좋은 근거지만 승인에 충분하지 않다.
특히 기기별 증거가 ViewModel에서 하나로 합쳐지거나, 결과 저장/적용에서
대상·마스크가 소실되는 결함은 DSP 단위 시험만으로 잡히지 않는다.

## 첨부

다음 폴더에 고정 소스 archive, 스크립트, 독립 시험 원본, 실행 로그를 보존했다.

[독립 검증 작업 폴더](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/combined-20260925)

- [새 실패 회귀 시험](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/combined-20260925/IndependentCombinedRegressionTest.kt)
- [실패 로그](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/combined-20260925/independent-combined-regression-output.txt)
- [DSP 532건 로그](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/combined-20260925/dsp-test-output.txt)
- [앱 교정 176건 로그](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/combined-20260925/app-calibration-test-output.txt)
- [수치 probe](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/combined-20260925/IndependentCombinedProbe.kt)
- [입력 경로 probe](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/combined-20260925/IndependentRouteProbe.kt)
- [Compose 상태 구독 probe](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/combined-20260925/IndependentAxisProbe.kt)
- [간헐음·무음 probe](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/combined-20260925/IndependentFeedbackProbe.kt)
