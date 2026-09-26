# SELAH RTA CA-01~10 독립 재검토

검토일: 2026-09-26 · 검토자: Codex

**판정: 승인 보류. Critical 0건, High 4건, Medium 1건.**

CA-02·06·07·08·09의 기존 결함은 아래 검증 범위에서 해결됐다.
CA-01·03·04·10은 같은 부류의 미해결 경로가 남았고, CA-05는 배경 재생을
막는 대신 정상적인 마법사 재진입을 막는 회귀가 생겼다.
시험 부재만으로 보류한 것이 아니라 실제 코드 실행 및 호출 경로로 확인한 결함이다.

## 1. 고정 범위와 검증 수준

- 요청서: `docs/review/2026-09-25-ca-rereview-request.md`.
- 이전 회신: `docs/review/2026-09-25-combined-independent-review.md`.
- 고정 범위: **`e031ece..0e72cc086b8ee762c94d22ff59c6a9fc36826107`**.
  구현 `33bda12`, 요청서 `0e72cc0`; 17파일 +1126/-58(검토 문서 포함).
- 검토 중 원본 HEAD가 `6bbe5dd01d0971ae07cda423c2095765214905cd`로 이동했다.
  그 사이 추가된 merge 및 기록/CSV 작업은 이 보고서의 시험 범위가 아니다.
  아래 지적 대상 production 파일들은 해당 추가 변경 목록에 없다.
- production 파일을 수정하지 않았다. 시험은 `0e72cc0`의 별도 복사본에서 했다.
  원본 저장소는 마지막 확인 시 clean이었다.
- 첨부 요청서의 완료 주장과 제안은 검증 대상으로 읽었다. 그 문구 자체를
  검증 결과나 구현 변경 지시로 취급하지 않았다.

| 독립 실행 | 결과 | 범위/한계 |
|---|---|---|
| DSP JUnit 전체 | **535건 통과** | 고정 소스를 Kotlin 2.2.20/JBR 21, JVM target 17로 컴파일 |
| 앱 교정 관련 선택 JUnit | **181건 통과** | 11개 교정 시험 클래스; Android 앱 전체 시험 수가 아님 |
| 이전 독립 회귀 JUnit | **4건 통과** | 무효 내부/범위 밖 보정, 지역 봉우리, 기존 CAL 머리글 fixture |
| 실제 CalibrationWizardViewModel 실행 | 재진입 실패·증거 재사용·채널 guard 누락 확인 | production VM/Runner 그대로; 가짜 Application/filesDir, WizardCapture, 시험 Main dispatcher |
| FR 완료 경합 | 수정본 12조건 통과, 수정 전 코드에서 결함 재현 | 실제 메서드 본문을 추출한 호스트 + 실제 MeasurementTap/DSP + 가상 시간. 전체 CaptureViewModel은 아님 |
| 스펙트로그램 seq | 재시작·중복·멈춤 해제 확인 | 실제 LaunchedEffect 본문을 추출한 상태 전이 시험. Compose 화면 실행은 아님 |
| 스펙트로그램 눈금 | 포화 후 변경 구독 확인 | 실제 agoMsAt 본문 + Compose Snapshot runtime. Bitmap/Canvas 렌더링은 아님 |
| 내장 경로 | 자동 적용 허용 재현 | 실제 키/환경 생성/판정 함수. 실제 기기의 라우팅을 유발한 시험은 아님 |

세 시험 묶음의 숫자는 합산하여 고유 시험 수로 주장하지 않는다. 이관된
회귀 시험과 별도 독립 실행에 중복이 있다. 이번에는 Gradle 전체 assemble/lint,
APK 설치, 실기기 소리 재생, USB 연결 시험을 하지 않았다.

## 2. CA-R01 — High: 저장 대상이 같아도 채널·입력 소스가 다를 수 있다

관련: CA-01. 기기 간 잘못된 저장과 Fail 판정 저장은 개선됐지만 종료할 수 없다.

**위치**

- [CalibrationWizardViewModel.kt:394](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:394): 대상은 `targetDeviceKey`만 보존한다.
- [CalibrationWizardViewModel.kt:528](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:528): `transferBlockedKo`의 대상 검사.
- [CaptureViewModel.kt:1453](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:1453): `expectedDeviceKey`만 비교한 뒤 현재 `CalibrationKey.of(format)`으로 저장.
- [CalibrationWizardViewModel.kt:573](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:573): 곡선 저장도 같은 기기 검사만 한다.
- [CalibrationProfile.kt:16](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/calibration/CalibrationProfile.kt:16): 실제 저장 키는 기기·source·다중 입력 channel을 구분한다.

**재현/근거**

동일 USB 인터페이스의 ch0에서 유효한 교정을 얻은 뒤, ch1을 열고 보정값을
옮긴다. 저장 키는 아래처럼 다르지만 새 guard가 받는 문자열은 동일하다.
실제 VM에 정상 Pass 품질/결과를 넣고 guard를 호출한 결과다.

```text
measured=cal|Usb|target|a|Unprocessed|ch0
current =cal|Usb|target|a|Unprocessed|ch1
blocked=null
differentDeviceBlocked=true failedQualityBlocked=true
```

파일에 실제 오프셋을 쓴 시험은 아니다. **실제 VM의 허용 판정**과
`SelahApp → saveOffsetDirect → store.save(CalibrationKey.of(format), …)`의
production 호출 경로를 함께 확인했다. 동일 source/ch0 대조군으로 만든
정상 품질 fixture를 사용했으므로 CA-R02의 잘못된 Pass에 의존하지 않는다.

**사용자 영향:** Input 1의 감도로 Input 2의 교정을 덮어쓰거나, 다른
AudioSource 경로의 오프셋으로 저장해 SPL이 조용히 틀릴 수 있다. 곡선
프로파일도 현재 열린 환경으로 잘못 귀속될 수 있다.

**권장 수정:** 수집 시작 때 대상의 전체 `CalibrationKey`와 경로 증거를
보존한다. UI 허용 판정과 실제 저장 함수 모두 그 키를 비교한다. 대상 신원이
없으면 거절한다. reference도 전후 측정의 전체 입력 신원을 대조한다.

**회귀 시험:** ch0→ch1, Unprocessed→다른 source, 다른 device는 저장 횟수 0;
같은 전체 키의 정상 Pass는 지정 키에 1회; Fail은 같은 키여도 0회.
전대역 오프셋과 곡선 저장 양쪽을 검사한다.

## 3. CA-R02 — High: 다른 채널의 잡음/DSP 증거와 실패 전의 성공이 재사용된다

관련: CA-03. 기준과 대상이 다른 deviceKey일 때 분리하는 방향은 맞다.

**위치**

- [WizardFlow.kt:220](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/calibration/WizardFlow.kt:220): 두 증거 map의 키는 device 문자열이다.
- [CalibrationWizardViewModel.kt:227](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:227): 잡음 측정 실패는 옛 map 항목을 지우지 않고 반환한다.
- [CalibrationWizardViewModel.kt:374](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:374): 측정 중 가청 여부에는 여전히 공통 `st.noiseFloorDb`를 넘긴다.
- [CalibrationWizardViewModel.kt:427](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:427): 최종 판정에서 해당 map을 신뢰한다.

**재현/근거**

실제 VM/Runner로 target ch0의 입력 점검을 성공시킨 뒤 같은 device의 ch1로
전환했다. 잡음 측정을 실패시켜도 ch0의 잡음과 `verifiedBySignal=true`가
남았다. 실제 VM의 CalibrationSession에 일정한 reference/target 장을 넣고
실제 `finishMeasurement()`를 실행한 결과:

```text
CHANNEL_SWITCH currentChannel=1 reusedDsp=true reusedNoise=true
NOISE_CHECK_FAILURE oldDsp=true oldNoise=true
STALE_CHANNEL_VERDICT verdict=Pass maySave=true staleUsable=31 actualUsable=0
```

ch0에서 얻은 SNR 40dB 상당의 배경을 사용하면 Pass/31밴드다. 같은 target
신호에 ch1의 실제 배경(SNR 2dB)을 사용하면 0밴드다. 수집 자료 주입에만
reflection을 썼고, 최종 판정 메서드나 Runner는 대체하지 않았다.

**사용자 영향:** 검사하지 않은 채널/입력 경로 또는 새 잡음 검사가 실패한
상태에서도 검증된 교정으로 승인될 수 있다. 마지막으로 검사한 다른 기기의
공통 noiseFloorDb는 측정 시작 때 가청 판정을 잘못 허용하거나 거절할 수도 있다.

**권장 수정:** 증거의 신원을 deviceKey보다 넓혀 source·channel·rate·분석
격자·수집 세대/시간을 포함한다. 입력 점검 시작/실패/취소 때 해당 시도의
증거 유효성을 명시적으로 갱신한다. 단계 시작과 완료 때 신원을 비교하고,
가청 검사도 그 단계의 증거를 선택한다. 오래된 증거를 다시 쓸 정책이 있다면
허용 조건을 명시해야 하며, 현재 입력의 증거처럼 취급해서는 안 된다.

**회귀 시험:** 다른 device뿐 아니라 같은 device의 채널/source/rate 변경,
잡음 단계 실패, DSP 단계 실패, 입력 전환 중 완료를 각각 고정한다. 실제
finishMeasurement에서 SNR 2dB 표본의 maySave=false를 단언한다.

## 4. CA-R03 — High: CA-04 주소 검사는 재개방에서도 필요한 주소를 받지 못한다

관련: CA-04. 요청서의 “재개방은 잡고 세션 도중만 남았다”는 설명은 성립하지 않는다.

**위치**

- [InputDevices.kt:56](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/audio/InputDevices.kt:56): 내장 stableKey는 주소를 제외한다.
- [InputDeviceScanner.kt:111](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/audio/InputDeviceScanner.kt:111): 표시 목록은 `address=""`로 합친다.
- [CurrentEnvironment.kt:53](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/calibration/CurrentEnvironment.kt:53): 보정 환경이 그 표시 목록에서 주소를 가져온다.
- [MeasuredProfile.kt:275](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/calibration/MeasuredProfile.kt:275): 양쪽 주소가 비어 있지 않을 때만 새 검사가 작동한다.
- [MicSource.kt:319](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/audio/MicSource.kt:319): callback도 합쳐진 stableKey가 달라야 상위에 통지한다.

**재현/근거**

bottom(id22) → back(id24)의 서로 다른 원본 주소와 실제 표시 목록 형태를
넣고 production `currentProfileEnvironment`/`judgeProfileApply`를 실행했다.

```text
sameStableKey=true envEqual=true
routingListenerWouldNotify=false mayAutoApply=true
```

새 주소 조건 자체는 두 개의 정확한 주소를 직접 주면 동작한다. 문제는 앱의
입력 경로에서 그 정보를 잃는 것이다. 목록에서 찾지 못했을 때 key의 마지막
조각을 주소로 읽는 fallback도 내장 키에서는 제품명이 된다.
`ApplyWithWarning.mayAutoApply=false`는 이번에 새로 추가된 정책이 아니다.

**사용자 영향:** 재개방과 세션 내 전환 모두 다른 라우팅의 보정이 계속 자동
적용될 수 있다. 이것이 별도 물리 마이크임을 확정한다는 뜻은 아니다. 실제
경로가 같다는 증거도 없으므로 이전 교정과 동등하다고 승인할 근거가 없다.

**권장 수정:** 표시 그룹과 실제 route 증거를 분리한다. startRecording 뒤
확인한 원본 정보를 OpenedFormat/환경으로 보존하고, 기존 routing listener에서
그 증거의 변화를 감지한다. null/불명 상태도 상위로 전달해 이전 적용을
중지/재평가한다. 상세 마이그레이션과 callback 경계는 8절 답변 참조.

**회귀 시험:** 원본 라우팅→접힌 UI 목록→확정 입력→환경→실제 자동 적용의
연결 시험을 세운다. bottom/back 재개방·세션 내 전환, 주소 소실, USB 두
기기와 같은 제품명, 옛 빈 주소 프로파일에서 자동 적용이 허용되지 않아야 한다.

## 5. CA-R04 — High: 탭 이동/닫기 뒤 마법사를 다시 열어도 재생 허용이 복구되지 않는다

관련: CA-05 수정에서 생긴 회귀. 기존의 화면 이탈 후 자동 재생은 차단됨을 확인했다.

**위치**

- [CalibrationWizardViewModel.kt:98](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:98): `stopWork()`가 inForeground=false로 설정한다.
- [SelahApp.kt:308](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/SelahApp.kt:308): 하단 탭 변경은 항상 stopWork를 부른다.
- [SelahApp.kt:388](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/SelahApp.kt:388): 열기는 wizardOpen=true만 한다.
- [SelahApp.kt:211](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/SelahApp.kt:211): 복구는 Activity ON_START에만 연결돼 있다.

**재현/근거**

측정→설정 탭 이동, 또는 마법사 닫기→다시 열기를 Activity가 앞에 있는 동안
수행한다. 이때 ON_START는 새로 오지 않는다. UI와 같은 호출 순서를 실제 VM에
넣고 충분한 잡음 장을 공급해도 재생 0회, DSP 결과 null이다.

```text
WIZARD_REOPEN starts=0 dsp=null
notice=화면을 나가서 소리를 내지 않았습니다. 다시 시작하십시오.
WIZARD_FOREGROUND starts=1 verified=true
```

`onForeground()`를 명시적으로 호출한 대조군은 성공한다. 기존 결함의 회귀
조건도 별도로 실행했다: 잡음 39장 뒤 stopWork를 부르면 재생 0회, tap 제거,
busy=null이었다. **취소는 고쳤지만 정상적인 다음 실행을 함께 막았다.**

**사용자 영향:** 보통의 탭 이동으로 마법사에 들어가는 경로부터 입력 검사와
측정이 진행되지 않는다. “다시 시작”을 눌러도 flag는 복구되지 않는다.

**권장 수정:** Activity 전경 상태와 마법사 작업 취소/가시성을 구분한다.
진입/퇴장의 lifecycle 소유권을 명확히 연결하되, 재진입으로 이전 작업을 자동
재개하지 않는다. 취소된 job의 finally가 새 작업을 정리하지 않도록 소유권도
회귀 시험에 포함한다.

**회귀 시험:** 탭 이동→열기, 닫기→열기에서 새 사용자 실행은 1회 시작;
ON_STOP/닫기 직전 barrier에서는 0회; 재진입만 하고 시작을 누르지 않으면 0회.

## 6. CA-R05 — Medium: 측정 조건 머리글이 여전히 CAL 부호를 자동 확정한다

관련: CA-10. 원래 주어진 한 문자열은 고쳤지만 열 의미를 확인하는 판정은 아니다.

**위치:** [CalibrationSign.kt:47](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:47),
[CalibrationSign.kt:66](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:66),
[CalibrationSign.kt:185](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:185).

**재현:** 실제 signEvidenceOf→decideReading(ReferenceForCalibration) 결과.

| 머리글 | settled |
|---|---|
| `# Measured at 94 dB SPL` | false — 기존 fixture 해결 |
| `# Reference SPL: 94 dB` | **true** |
| `# Measured at 94 dB(SPL)` | **true** |
| `# For frequency response measurements` | **true** |

숫자+단위 토막 하나를 지운 뒤 남은 모든 설명문에서 spl/response 등을 찾는다.
그러므로 둘째 열이 무엇인지 선언하지 않은 파일이 Response로 확정된다.

**사용자 영향:** 실제로 correction 열인 모호한 파일을 반대 부호로 사용할
수 있다. 기준 마이크에 잘못 걸면 그 기준으로 만든 다른 교정에도 전파된다.

**권장 수정:** 알려진 형식의 열 선언처럼 열 의미를 연결할 수 있는 증거만
자동 확정에 쓴다. 나머지는 Unknown/사용자 선택을 유지한다. 조건 문자열
제거 정규식에 예외를 계속 더하는 방식으로 해결하지 않는다.

**회귀 시험:** 위 조건/설명문은 settled=false, 명시적인 주파수·응답 열
선언은 의도한 값, 상충하는 선언은 미확정. 파일 parser부터 wizard 관문까지 연결한다.

## 7. 해결 확인한 항목

| 기존 ID | 재검토 결론 | 확인한 근거 |
|---|---|---|
| CA-01 | 부분 개선, High 유지 | 다른 기기/Fail 차단 확인. 같은 기기 다른 channel/source는 CA-R01 |
| CA-02 | 해결 | 이전 두 회귀 통과. 구멍과 지원 범위 밖 계수 1.0; 제조사 null support 규약 유지 |
| CA-03 | 부분 개선, High 유지 | device별 분리됨. 채널/실패 증거 재사용은 CA-R02 |
| CA-04 | 미해결, High 유지 | 실제 환경이 빈 주소를 받아 새 검사 우회, CA-R03 |
| CA-05 | 원 결함 해결, 새 High 회귀 | 취소 39장 barrier 통과. 정상 재진입 차단은 CA-R04 |
| CA-06 | 원 경합 해결 | 시작 신원 유지, 완료/재생 전 거절을 수정 전 코드와 비교 |
| CA-07 | 해결 | old seq10000→새 session seq1 즉시 반영; 중복 거절; frozen 해제 확인 |
| CA-08 | 해결 | 720칸 포화 후 head 변경이 눈금 구독을 무효화 |
| CA-09 | 해결 | 이전 지역 최대 회귀 통과; DSP 전체 시험 통과 |
| CA-10 | 특정 fixture만 해결, Medium 유지 | 부호 자동 확정의 같은 부류 반례 3개, CA-R05 |

FR은 마지막 quiet 장을 넣은 뒤 메인 continuation이 진행하기 전에 신원을
바꿨다. quiet-only/quiet+signal 각각에서 변경 없음, session, device, channel,
sampleRate, FFT의 6조건을 실행했다. 변경 시 모두 ready=false·재생 0회였다.
같은 호스트에 e031ece의 메서드 본문을 넣으면 session+device 변경에도
ready=true·재생 1회가 나왔다. **전체 CaptureViewModel/Android 연결 시험이라는
뜻은 아니다.** 실제 MicSource가 알리지 않는 라우팅은 CA-R03의 별도 문제다.

Snapshot 시험은 720칸에서 실제 눈금이 71900→79900ms로 변할 때,
읽은 상태 2개 중 변경된 상태 1개가 교차함을 확인했다. 이전 함수는 교차 0이었다.
시간 비례가 아닌 열 인덱스 폭의 한계까지 해결됐다는 뜻은 아니다.

## 8. 요청서의 다섯 질문에 대한 답

**1. 표시용 키와 보정용 키 / 기존 자료 보존**

표시용 그룹과 보정 유효성의 신원은 분리해야 한다. 다만 기존 storageKey를
당장 전부 바꾸거나 파일을 삭제할 필요는 없다. 보존과 자동 적용은 별도다.

기존 프로파일을 그대로 읽고 목록/내보내기에 유지하면서, 버전 있는 route
증거와 검증 상태를 추가한다. 정확한 과거 경로가 남은 항목만 그 증거로
대응시킨다. 빈 주소/합쳐진 키처럼 과거 신원을 복원할 수 없는 항목은
“경로 재확인 필요”로 남기고 자동 적용을 중지한다. 현재 열린 주소를 과거
측정 주소로 채우거나 모든 후보 경로로 복제하지 않는다. 재검증 후 명시적으로
새 경로에 연결하고 원본은 보존한다. 마이그레이션은 반복 실행·중단 복구·
구버전 읽기를 시험한다. 이 보고서에서 사용자 자료를 옮기거나 삭제하지 않았다.

**2. 세션 중 경로 변경은 어디서 잡는가**

현재 MicSource.start의 `AudioRouting.OnRoutingChangedListener`가 출발점이다.
OS callback이 전혀 없는 구조가 아니라, 합친 stableKey 비교에서 변화를 버리는
경로가 있다. Android는 이 listener와 실제 routedDevice 조회를 제공하며,
선호 기기는 실제 사용 기기와 같다고 보장하지 않는다.
[Android AudioRouting 공식 문서](https://developer.android.com/reference/android/media/AudioRouting).

캡처 시작 뒤 확인한 원본 route와 callback의 원본 route를 비교하고, 확인 실패도
unknown으로 전달한다. 변경 시 capture session/generation과 함께 controller에
통지해 그 입력의 보정·FR 배경·wizard 증거를 무효화/재평가한다. 이전 캡처의
늦은 callback은 현재 세션을 덮어쓰지 못하게 한다. 기기가 같은 논리 route
안에서 내부 마이크를 조합하면 address만으로 물리 분리를 입증할 수 없다는
기존 실측 한계도 유지한다.

**3. CA-06 barrier 재실행**

위 7절의 12조건과 수정 전 대조군에서 원 결함은 해결됐다. 재현 코드와
가상 시간 제어를 제공한다. 이것을 정규 앱 시험 경계에 이식하면 된다.

**4. 고립된 유효 점 `[4000,4000]`**

인접 무효 대역으로 확장할 근거가 없는 현 정책에서는 타당한 보수적 선택이다.
정확히 그 주파수와 일치하는 FFT bin이 없으면 실제 보정되는 bin은 0개일 수
있다. 이 점을 넓은 유효 대역이라고 안내해서는 안 된다. 근처까지 넓히려면
별도의 측정/대역 지원 모델과 근거가 필요하다. 제조사 파일의 기존 extrapolation
계약은 이번 measured-profile 지원 마스크와 계속 구분한다.

**5. 구조 분리는 나중에 해도 되는가**

큰 구조 변경을 별도 커밋으로 나누는 것은 가능하다. 그러나 확인된 결함을
남긴 채 “다음에 시험 가능하게 만들겠다”를 근거로 승인할 수는 없다.
특정 포트 구조 자체가 필수 조건은 아니다. 이번에는 production
AndroidViewModel을 그대로 JVM에서 실행하고 시험 Main dispatcher로 취소
경계를 제어했다. AndroidViewModel이라는 이유만으로 이 경계 시험이 불가능한
것은 아니다. 임시 reflection fixture 대신 필요한 생성자 의존성/상태 입력을
작게 열어 정규 시험에 넣는 편이 유지보수에 좋다.

FR은 추출 호스트 시험이라 전체 연결의 보증 범위가 더 좁다. 포트/scope 주입을
통해 실제 VM 시험으로 옮기고, Compose는 순수 상태 전이·Snapshot 구독과
화면 연결 시험을 구분하면 된다. 현재 막힌 이유는 시험 구조의 모양이 아니라
CA-R01~05의 실제 동작이다.

## 9. 재현 자료와 남은 위험

모든 이번 자료의 경로:

`C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/ca-20260926`

| 실행 파일 | 소스/로그 |
|---|---|
| `run.ps1` | `dsp-test-output.txt` |
| `app-calibration-tests.ps1` | `app-calibration-test-output.txt` |
| `combined-regression.ps1` | `IndependentCombinedRegressionTest.kt`, `independent-combined-regression-output.txt` |
| `wizard-probe.ps1` | `IndependentWizardProbe.kt`, `independent-wizard-output.txt` |
| `route-probe.ps1` | `IndependentRouteProbe.kt`, `independent-route-output.txt` |
| `boundary-probe.ps1` | `IndependentBoundaryProbe.kt`, `NewFrHost.kt`, `OldFrHost.kt`, `independent-boundary-output.txt` |
| `axis-probe.ps1` | `IndependentAxisProbe.kt`, `independent-axis-output.txt` |
| `sequence-probe.ps1` | `IndependentSequenceProbe.kt`, `independent-sequence-output.txt` |

probe는 알려진 결함의 **관측값을 check로 확인하는 진단 프로그램**이다.
exit 0이 제품 동작의 정상 판정을 뜻하지 않는다. 수리용 회귀 시험으로 옮길
때에는 desired behavior(거절해야 함/새 사용자 실행은 성공해야 함)를 단언해야 한다.
FR/눈금 생성에는 `generate-boundary-probes.ps1`로 고정 소스 본문을 추출했다.

기존 미해결 위험인 FFT 프레임의 정확한 sample-frame 시각, 비균일 시각을
열 폭으로 그리는 문제, 실내 오탐/미탐·실제 USB/내장 라우팅·장시간 부하·
실제 SPL 비교는 이번 변경으로 해결됐다고 보지 않는다. 새 기록/CSV 기능도
이 보고서로 승인한 것이 아니다.

