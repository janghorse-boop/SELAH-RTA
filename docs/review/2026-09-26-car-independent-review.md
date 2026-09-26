# SELAH RTA CA-R01~R05 독립 재검토

검토일: 2026-09-26 · 검토자: Codex

**판정: 승인 보류. Critical 0건, High 3건, Medium 1건, Low 1건.**

CA-R04의 정상 재진입 회귀는 해결됐다. CA-R01의 저장 시 채널/source 비교,
CA-R02의 경로별 증거 분리 및 잡음 측정 실패 처리, CA-R03의 원본 주소 전달과
주파수 프로파일 판정, CA-R05의 기존 네 머리글 반례도 개선을 확인했다.
그러나 각 부류를 종료하기에는 아래 경로가 남는다.

## 1. 범위와 독립 실행

- 기준: `0e72cc0..45c34ae925e8b2a5ed5b73ec2c23dd5fd0427f3e`.
- 요청 문서: `docs/review/2026-09-26-car-rereview-request.md`.
- 비교 문서: `docs/review/2026-09-26-ca-independent-review.md`.
- 요청서를 읽을 때 HEAD는 문서 커밋 `e2adc49`였다. 실행용 소스는 **45c34ae의
  git archive 복사본**으로 고정했다. 검토 중 원본에 후속 커밋 `d81bed3`와
  미추적 `SessionRecorder.kt`가 생겼지만 이 검토에 섞지 않았다.
- 이번 승인 판단은 CA-R 수정 및 연결 경로에 한정한다. 같은 범위에 들어간
  Phase 10·11 기록/CSV/RowSlicer의 별도 설계·구현 승인을 뜻하지 않는다.
  공유 `ProfileCodec`의 교정 회귀 시험은 아래 앱 시험에 포함된다.
- 요청서의 완료 주장·자체 실기기 확인은 검증 대상으로 읽었다. 이를 독립
  실행 결과로 합산하지 않았다. Production 코드와 사용자 저장 자료는 수정하지 않았다.

| 실행 | 결과 | 검증 경계 |
|---|---|---|
| DSP JUnit 전체 | **538건 통과**, 68.475초 | Kotlin 2.2.20 / JBR 21 / JVM target 17 |
| 앱 교정 선택 JUnit | **188건 통과**, 9.459초 | 11개 교정 시험 클래스. 앱 전체 시험은 아님 |
| 기존 독립 회귀 JUnit | **4건 통과**, 0.097초 | 지원 마스크·봉우리·기존 CAL 반례; 앞 묶음과 중복 있음 |
| 실제 CalibrationWizardViewModel / WizardRunner | 아래 정상 대조군 및 미해결 반례 확인 | mock Application, 가짜 WizardCapture, 실제 MeasurementTap/DSP, 시험 Main dispatcher |
| 경로/보정 판정 | 주파수 프로파일 거절·전대역 키 충돌 확인 | 실제 모델/키/판정 함수. Android DataStore 및 실제 AudioRecord 실행은 아님 |
| CAL 파일 입력 | 기존 반례 4개 해결, 새 반례 3개 확인 | 실제 CalibrationFile.load → declaresColumns/signEvidence → CalInfo.readingSettled |
| CA-R04 되돌리기 | 독립 VM probe 실패, 기존 Runner 시험 **17건 통과** | scratch 복사본에서만 inForeground=false를 되살림 |

VM fixture에 CAL을 넣는 입구만 reflection을 사용했다. 입력 검사, 각 측정 단계,
최종 품질 판정, 저장 guard, 프로파일 파일 저장은 production 메서드 그대로
실행했다. 합성 FFT 전력 자료를 공급했으므로 실제 음향·기기 시험은 아니다.
Gradle 전체 build/lint, APK 설치, 화면 렌더링, 실기기 재생·USB 전환은 이번에
독립 실행하지 않았다. 시험 수를 합쳐 고유 시험 수로 주장하지 않는다.

## 2. CAR-01 — High: 저장 키를 넓혔지만 수집 신원을 끝까지 보존하지 않는다

관련: CA-R01, CA-R02. 저장 직전 다른 채널/source를 거르는 대조군은 통과했다.
다음 세 경로는 서로 연결된 수집 신원 문제다.

**위치**

- [CalibrationWizardViewModel.kt:421](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:421): 증거 키는 시작 시 읽는다.
- [CalibrationWizardViewModel.kt:448](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:448), [457](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:457): reference/targetCalKey는 완료 때 현재 캡처에서 다시 읽는다.
- [CalibrationWizardViewModel.kt:475](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:475): reference-after는 deviceKey만 비교한다.
- [CalibrationWizardViewModel.kt:652](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:652): 저장 검사에 쓰는 environment.key()에는 routedAddress가 없다.
- [WizardCaptureBridge.kt:28](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/WizardCaptureBridge.kt:28): 현재 opened로 키를 만들고, 실제 route 주소/세대를 마법사에 전달하지 않는다.

**재현 A — 전후 기준 채널이 다름**

실제 measureStep으로 reference ch0 → target → reference ch1을 순서대로
실행했다. 같은 전력 자료라 반복성 검사는 통과하지만, 그것이 같은 마이크/
CAL/감도라는 증거는 아니다.

```text
REFERENCE_CHANNEL_CHANGE before=0 after=1 frames=120 verdict=Pass
```

**재현 B — 마지막 target 장과 완료 처리 사이에 채널 변경**

target ch0의 120번째 장을 넣은 tick에서, collector가 반환하기 전에 캡처 키를
ch1으로 바꿨다. 실제 VM이 만든 결과:

```text
measuredChannel=0 stampedChannel=1
evidence=cal|Usb|target|a|Unprocessed|ch0|fs48000|n4096
verdict=Pass acceptsCh1=true
```

장과 증거는 ch0인데 저장 대상은 ch1이다. 새 전체 키 guard도 잘못 붙인 이름표를
믿으므로 통과한다. 이는 가짜 캡처가 제어한 VM 경계 재현이며, 실제 화면에서
그 순서를 조작해 확인했다는 주장은 아니다.

**재현 C — 내장 대상 재개방 후 저장**

내장 대상의 back 측정 후 기준으로 돌아갔다가 bottom으로 재개방하여 저장하는
조건을 구성했다. 두 주소의 CalibrationKey는 같다. WizardCapture 자체에
주소 입력이 없어서 VM은 이 차이를 받을 수도 없다. 실제 save로 scratch 폴더에
프로파일/곡선 파일을 썼고 다음 판정을 확인했다.

```text
BUILTIN_SAVE measuredRoute=back savedRoute=bottom sameKey=true auto=true
```

`measuredRoute=back`은 이 시험의 입력 시나리오다. 실제 기기에서 back을 녹음한
것이 아니다. 핵심은 **주소가 다른 환경도 같은 저장 guard를 지나 현재 주소로
새 프로파일을 만들 수 있음**이다. 그 뒤 CA-R03의 주소 검사는 이미 bottom으로
잘못 저장된 메타데이터를 보므로 차이를 발견하지 못한다.

**사용자 영향:** 다른 기준 채널의 CAL을 적용하거나, 다른 입력의 자료를
대상 채널/내장 경로에 귀속시켜 이후 보정이 조용히 틀릴 수 있다.

**권장 수정:** 저장소의 기존 키와 별개로 수집용 불변 신원을 둔다. 확정된
기기·source·channel·rate·FFT·route 증거·capture generation을 시작 시 보존하고
수집 완료/저장 시 대조한다. 완료 때 현재 키로 덮어쓰지 않는다. reference-after도
reference-before의 전체 신원과 비교한다. 잘못된 단계의 장을 세션에 반영하기
전에 거절하거나 해당 시도를 폐기해야 한다. `routeConfirmed`도 입구에서 요구한다.

**필요한 회귀:** 위 A/B/C와 각각의 동일 입력 대조군. 마지막 장 직전/직후
신원 변경, 미확정→확정 경로 변경, null 경로를 포함한다. 잘못된 입력은
maySave=false 및 파일 저장 0회, 정상 입력은 원래 신원으로 1회 저장을 단언한다.

## 3. CAR-02 — High: 재검사를 취소하면 옛 성공 증거가 여전히 승인에 쓰인다

관련: CA-R02. 일반 잡음 실패는 지워지지만 취소는 그 분기로 들어가지 않는다.

**위치**

- [CalibrationWizardViewModel.kt:99](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:99): stopWork는 job/busy만 정리한다.
- [CalibrationWizardViewModel.kt:247](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:247): 새 검사 시작 시 이전 증거가 계속 유효하다.
- [CalibrationWizardViewModel.kt:259](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:259): 삭제는 RunOutcome.Failed에만 있다.
- [CalibrationWizardViewModel.kt:498](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:498): 최종 판정이 map의 옛 값을 그대로 사용한다.

**재현/근거**

실제 VM으로 SNR 40dB 조건의 입력 검사를 성공시켰다. 같은 경로의 배경이
높아진 조건에서 다시 검사하고, 가상 시간으로 잡음 39장까지만 공급한 뒤
stopWork를 호출했다. 소리/tap은 정상 정리되지만 옛 noise와 DSP 검증은 남았다.
이어 실제 reference-target-reference 측정과 finishMeasurement를 실행했다.

```text
CANCELLED_RECHECK retainedDsp=true retainedNoise=true extraStarts=0 detached=true
CANCELLED_RECHECK_VERDICT verdict=Pass staleUsable=31 actualUsable=0
```

새 target 배경은 합성 자료상 SNR 2dB다. 그 배경을 품질 함수에 넘기면 유효
밴드 0개인데, 취소 전의 옛 배경으로는 Pass다. 이는 몇 시간 지난 증거를
허용할지의 경계 문제가 아니라 **재검사를 시작해도 취소 전후의 증거가 구별되지
않는 즉시 재현 문제**다.

추가로 입력 검사 중 잡음 40장 뒤 ch0→ch1을 바꿔도 ch1의 DSP 결과를 ch0 키에
저장했다(`CHECK_INPUT_CHANGED … savedUnderCh0=true`). map의 키 폭을 넓히는
것만으로 수집 중 신원 변화를 검증하지는 못한다. 수집 신원 대조는 CAR-01과
같은 경계에서 해결해야 한다.

**사용자 영향:** 배경이 바뀌거나 입력 점검이 중단된 뒤에도 이전 성공으로
교정을 승인하여 낮은 SNR 자료가 검증된 결과로 남을 수 있다.

**권장 수정:** 검사 시도 ID/수집 세대를 두고 시작할 때 이전 증거를 현 시도와
분리한다. 취소·실패·입력 변경 결과는 승인에 쓰지 않는다. noise와 DSP가 동일한
완료 시도에서 나온 유효한 증거 쌍인지 확인한다. 오래된 성공을 참고용으로
보존할 수는 있지만 현재 검사의 승인 근거로 자동 사용해서는 안 된다.

**필요한 회귀:** 성공→재검사→quiet 취소, DSP 수집 취소, ON_STOP/닫기,
입력 변경 후 완료를 실제 VM에서 고정한다. 재검사 완료 전과 취소 후
maySave=false, 새 정상 검사를 완료했을 때만 복구되는지 확인한다.

## 4. CAR-03 — High: 실제 주소 보호가 전대역 SPL 보정에는 연결되지 않았다

관련: CA-R03. **측정 주파수 프로파일**의 주소 보호는 개선됐지만 **전대역
오프셋** 경로는 별도로 남았다.

**위치**

- [CaptureViewModel.kt:1203](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:1203): watchCalibration.
- [CaptureViewModel.kt:1208](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:1208): saved를 주소 검사 없이 ActiveCalibration.from으로 적용한다.
- [CalibrationStore.kt:28](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/calibration/CalibrationStore.kt:28), [45](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/calibration/CalibrationStore.kt:45): 저장/조회 키에 route 주소·유효성 증거가 없다.
- [InputDevices.kt:56](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/audio/InputDevices.kt:56): 내장 stableKey는 여전히 같은 문자열이다.

**재현/근거**

실제 OpenedFormat을 routedAddress=bottom/back으로 만들고, 접힌 표시 목록을
통해 환경과 보정 키를 생성했다. 주파수 프로파일은 거절됐다. 그러나 실제
CalibrationKey와 ActiveCalibration.from을 사용한 전대역 조회 모델은 다음과 같다.

```text
MEASURED_ROUTE old=bottom new=back auto=false unknownAuto=false
GLOBAL_ROUTE sameStorageKey=true offset=110.0 state=GlobalCalibrated referenceOnly=false
```

DataStore 자체를 실행한 시험은 아니다. 실제 저장 키 생성/적용 함수의 실행과
`store.watch(key) → ActiveCalibration.from(saved)` 호출 코드를 확인했다.
동일 키에서 읽은 +110dB 값에 경로를 검사할 메타데이터가 없다. 측정을 멈춘 뒤
다른 주소로 재개방해도 같은 경로를 따른다. 주파수 프로파일 판정의 경고는
이 전대역 구독을 통제하지 않는다.

**사용자 영향:** 내장 입력 경로가 달라졌어도 다른 경로에서 구한 전대역 감도를
계속 쓰며 보정된 SPL로 표시한다. 주소 변화만으로 실제 물리 마이크/감도가
달라졌다고 단정하는 것은 아니다. **같은 교정을 써도 된다는 근거 없이
보정 완료로 승인한다는 점**이 문제다.

**권장 수정:** 기존 키/저장 파일을 보존하면서 GlobalCalibration에도 측정
경로 증거와 적용 판정을 연결한다. 신원이 없거나 달라진 기존 자료는 남겨 두고
재확인 전 자동 적용을 보류한다. curve와 scalar의 적용 정책을 각각 끝까지
추적해야 한다. 저장소 키를 무조건 새 문자열로 교체해야만 해결되는 문제는 아니다.

**필요한 회귀:** bottom에서 오프셋 저장→back 재개방→실제 store 구독/미터
상태까지 검증. 동일 주소 정상 적용, 주소 없음, 구버전 자료 보존, source/channel
변경을 포함한다. 주파수 프로파일이 차단되는지만 시험해서는 부족하다.

**관련 잔여 위험:** [MicSource.kt:318](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/audio/MicSource.kt:318)은
routedDevice가 null이거나 info 변환이 실패하면 callback에서 바로 반환한다.
이 경우 이전 확인 상태를 무효화하지 않는다. 실제 단말에서 이 callback 순서를
유발하지 않았으므로 별도 실기기 확인이 필요하지만, unknown을 상위에 알리라는
기존 권고는 아직 코드에 반영되지 않았다.

## 5. CAR-04 — Medium: 열 선언 여부와 둘째 열의 의미가 서로 분리돼 있다

관련: CA-R05. 기존 네 문자열은 모두 사용자 확인을 요구하도록 바뀌었다.
그러나 24자 제한을 조정하는 것으로 남은 문제를 해결할 수 없다.

**위치:** [CalibrationSign.kt:78](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:78),
[88](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:88),
[239](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:239).

**재현:** 실제 파일 parser부터 CalInfo.readingSettled까지 실행했다.

| 파일 앞부분 | declared / settled | 오류 |
|---|---|---|
| `# Frequency range: 20 Hz, reference SPL: 94 dB` | true / true | 짧은 조건 문장도 쉼표로 나뉘면 열 선언으로 간주 |
| `# Reference SPL: 94 dB` 다음 줄 `Frequency,Value` | true / true | 다른 줄의 SPL과 의미 불명의 Value 열을 결합 |
| `Frequency,Phase,SPL` | true / true | 둘째 열은 Phase인데 다른 열의 SPL로 Response 확정 |
| `Frequency,SPL,Phase` | true / true | 정상 대조군 |

숫자 행을 `1000,0,6`으로 주면 parser는 항상 둘째 값 0을 응답으로 읽는다.
위 세 번째 반례에서는 SPL 값 6이 아니라 위상 0을 주파수 보정으로 사용한다.

**사용자 영향:** 조건/다른 열의 설명을 둘째 열의 의미로 오인해 부호나 값이
잘못된 기준 CAL을 확정한다. 그 기준으로 만든 교정도 잘못될 수 있다.

**권장 수정:** declarations를 Boolean으로 압축하지 말고 해당 선언의 열별
역할을 보존한다. 현재 parser가 첫 열=Hz, 둘째 열=dB만 지원한다면 정확히
그 순서의 알려진 열 이름만 자동 확정하고, 그 외는 지원하지 않음/사용자
확인으로 남긴다. 전역 설명문의 signEvidence와 별개로 둘째 열에 직접 연결된
의미를 판단한다. 확인되지 않은 선언에서 columnDeclared의 기본 true에도 의존하지 않는다.

**필요한 회귀:** 위 파일 세 개와 올바른 열 선언, 열 순서 교체, 조건 문장,
모르는 이름, 여러 줄/상충 선언을 parser→마법사 관문까지 시험한다.

## 6. CAR-05 — Low: 잘못된 fixture와 실제 VM을 거치지 않는 회귀 시험이 남았다

**위치**

- [TestProfiles.kt:123](D:/Cowork/SELAH-RTA/app/src/test/java/kr/joa/selahrta/calibration/TestProfiles.kt:123): 공통 fixture는 여전히 `BuiltIn|SM-S918N|$address`를 만든다.
- [MeasuredProfileTest.kt:154](D:/Cowork/SELAH-RTA/app/src/test/java/kr/joa/selahrta/calibration/MeasuredProfileTest.kt:154): “다른 내장 마이크” 시험은 가짜로 서로 다른 키를 넣고 Block을 기대한다.
- [WizardRunnerTest.kt:172](D:/Cowork/SELAH-RTA/app/src/test/java/kr/joa/selahrta/calibration/WizardRunnerTest.kt:172): 새 재진입 시험은 로컬 foreground 변수를 true로 바꾼다.

**근거**

같은 bottom/back 자료에서 가짜 키를 실제 키로 바꾸면 판정은
`Block → ApplyWithWarning`이다. 자동 적용 차단 자체는 새 별도 시험도 확인하지만,
기존 시험의 “Block이어야 한다”는 계약은 여전히 실제 입력과 다르다.

또 scratch VM의 stopWork에 `inForeground=false`를 되살렸을 때 독립 실제 VM
probe는 재진입 단언에서 실패했다. 반면 새 두 시험을 포함한 WizardRunnerTest
**17건은 전부 통과**했다. 이 시험은 runner의 허용 함수만 검사하므로 실제
CA-R04 회귀가 다시 생겨도 감지하지 못한다.

**사용자 영향:** 직접적인 실행 결함은 아니지만, 잘못된 계약/연결 변경을
검증된 수정으로 오판할 수 있다.

**권장 수정:** 실제 InputDeviceInfo.stableKey/OpenedFormat 생성기를 공통
fixture에 사용한다. 구버전 키는 명시적인 migration fixture로 분리한다.
이번 실제 VM의 stopWork/onBackground/onForeground 호출 순서를 정규 회귀
시험에 추가한다. Runner 단위 시험은 그 경계의 시험으로 유지한다.

**필요한 회귀:** 실제 키로 주소 변화/unknown/동일 경로 판정을 검증하고,
전경 상태 되돌림 mutation을 VM 시험이 잡는지 확인한다.

## 7. 기존 지적의 상태

| 기존 ID | 결론 |
|---|---|
| CA-R01 | 저장 시 channel/source 불일치·null 대상 거절은 개선. 수집 완료 귀속, 전후 reference, 내장 주소 저장은 CAR-01로 미해결 |
| CA-R02 | 경로/rate/FFT map 및 일반 잡음 실패 삭제는 개선. 취소/수집 중 변경/세대는 CAR-02로 미해결 |
| CA-R03 | 원본 주소 전달과 측정 주파수 프로파일의 다른/빈 주소 차단은 확인. 전대역 자동 적용은 CAR-03으로 미해결 |
| CA-R04 | **해결 확인.** stopWork 뒤 새 사용자 실행 가능, onBackground 뒤 재생 거절, onForeground 뒤 복구. 정상 취소도 확인 |
| CA-R05 | 기존 네 반례 해결. 파일의 실제 둘째 열 의미까지 연결되지 않아 CAR-04로 미해결 |

## 8. 요청서의 여섯 질문에 대한 답

**1. 기준 전후를 전체 키로 바꿔야 하는가?**

그렇다. referenceBefore의 channel/source/CAL/감도와 referenceAfter가 같다는
조건을 보존해야 한다. deviceKey만 같은 두 채널은 같은 기준이 아니다. CAR-01의
실제 measureStep 시험에서 120장이 수용되고 Pass가 났다. 전체 저장 키와 함께
수집 route/generation도 확인해야 한다.

**2. 증거 수집 세대/시간과 유효 기간은?**

세대/시도 신원은 필요하다. 취소 시 옛 성공이 남는 반례 때문에, 단순히 같은
경로를 다시 검사하면 덮어쓴다는 설명은 충분하지 않다. 검사 완료 전과 완료 후를
구분하고, 입력 변경·재개방·재검사 실패/취소 등 무효화 사건을 먼저 정의한다.
EMM-6↔대상 치환 때문에 재개방을 항상 금지하면 정상 흐름도 막힐 수 있으므로,
연속 캡처 세대와 교정 시도 안에서 재사용 가능한 증거의 범위를 구분해야 한다.

시간은 출처/진단용으로 기록하되 **근거 없는 N분 TTL을 정하지 않는다**.
같은 키여도 방의 배경/게인/가공이 바뀔 수 있다. 우선 해당 교정 시도에 묶고,
새 측정 시도에는 재확인하며, 신원/조건이 바뀌면 만료시키는 보수적 정책이
가능하다. 시간만 짧다고 유효해지는 것도, 숫자 하나로 모든 조건을 검증하는
것도 아니다.

**3. 빈 주소를 차단하는 중간 단계는 수용 가능한가?**

측정 주파수 프로파일에 한해서 수용 가능하다. 기존 파일을 보존하고 자동 적용을
중지하는 것은 파괴적 마이그레이션 없이 위험을 줄인다. 새 판 형식으로 전체를
변환해야만 이 조건을 닫을 필요는 없다. 다만 전체 기능은 CAR-01의 잘못된 새
메타데이터 생성, CAR-03의 scalar 적용, null route 미통지를 함께 해결해야 한다.
현재 주소를 과거 측정 주소로 채우는 마이그레이션은 피한다.

**4. ON_STOP 없이 가려지면 재생을 허용해도 되는가?**

현재 코드는 “STARTED/보이는 동안 허용하고 STOP에서 중단”하는 정책에
가깝다. 다중 창에서 포커스를 잃어도 Activity가 보일 수 있으므로 ON_STOP이
없다는 것 자체는 결함이 아니다. [Android Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle),
[다중 창 공식 문서](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode).

사용자가 시작한 측정을 보이는 창에서 계속한다는 제품 정책이라면 수용 가능한
선택이다. “포커스를 잃으면 자동 후속 신호도 금지”가 요구사항이면 별도 조건이
필요하다. ON_STOP만으로 그 더 강한 정책을 구현했다고 설명해서는 안 된다.
이번에는 다중 창을 기기에서 시험하지 않았다. 이전 보고서는 전경과 작업 취소의
소유권 분리를 권고했으며, 구체적인 다중 창 정책을 승인한 것은 아니었다.

**5. 열 이름 24자 제한의 대안은?**

길이보다 열 역할/위치의 명시적 문법이 우선이다. CAR-04의 반례들은 모두
짧아서 한도를 줄이거나 늘려도 본질이 달라지지 않는다. 지원 형식의 Hz 열과
둘째 응답/보정 dB 열을 식별하고 불명확한 경우 묻는다. 긴 미지원 이름을
보수적으로 거절하는 불편은 오판보다 작지만, 현재는 짧은 문장의 오판도 있다.

**6. 실제와 다른 fixture가 더 있는가?**

있다. CAR-05의 TestProfiles, MeasuredProfileTest가 직접적인 예다. Runner의
재진입 시험도 실제 VM 상태 전이를 대체한 변수 때문에 원래 결함을 재현하지
못한다. 모든 시험 fixture를 감사한 것은 아니며, 이번 범위의 연결 경계에서
확인한 사례들이다.

## 9. 재현 자료와 남은 한계

이번 scratch 경로:

`C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/car-20260926`

| 파일 | 용도 |
|---|---|
| `run.ps1`, `dsp-test-output.txt` | DSP 전체 실행 |
| `app-calibration-tests.ps1`, `app-calibration-test-output.txt` | 교정 188건 |
| `combined-regression.ps1`, `independent-combined-regression-output.txt` | 기존 독립 4건 |
| `IndependentWizardProbe.kt`, `wizard-probe.ps1`, `independent-wizard-output.txt` | 실제 VM 정상/경합/취소/저장 재현 |
| `IndependentRouteProbe.kt`, `route-probe.ps1`, `independent-route-output.txt` | 주소 전달·주파수 판정·scalar 키·fixture 차이 |
| `IndependentHeaderProbe.kt`, `header-probe.ps1`, `independent-header-output.txt` | 실제 파일 parser와 마법사 CAL 관문 |
| `MutantWizardViewModel.kt`, `mutation-probe.ps1`, `mutant-runner-output.txt` | CA-R04 되돌림과 기존 시험의 검출 범위 |
| `fake-app-files/profiles/` | probe의 파일 저장 결과. 앱/사용자 데이터와 무관 |

probe 일부는 **결함 관측값 자체를 check**하므로 exit 0을 제품 정상 판정으로
읽으면 안 된다. 정규 회귀로 옮길 때에는 원하는 동작(잘못된 입력/증거 거절)을
단언해야 한다. mutation의 실제 VM 종료 코드는 1이며 의도된 검출 결과다.

확인하지 않은 실기기 동작, 전체 Android build/lint, 기록/CSV/캡처 연결,
기존 FFT 시각·비균일 스펙트로그램 시간 축·현장 오탐/미탐 위험은 이 보고서로
해결 또는 승인됐다고 보지 않는다.
