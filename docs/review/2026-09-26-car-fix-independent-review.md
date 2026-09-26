# SELAH RTA CAR-01~05 수정분 독립 재검토

검토일: 2026-09-26 · 검토자: Codex

**판정: 승인 보류. Critical 0건, High 3건, Medium 2건, Low 1건.**

이전의 구체적인 반례들은 상당 부분 해결됐다. 실제 ViewModel에서 전후 기준
채널 변경, 마지막 장 직후 입력 변경, 다른 내장 주소로 저장, 취소된 재검사의
옛 증거 재사용을 다시 시험해 차단을 확인했다. 그러나 새 관문과 실제 소비·저장
경로 사이에 아래 문제가 남는다. 순수 함수 시험의 통과만으로 전체 수정 완료를
판정할 수 없다.

## 1. 범위와 실행 경계

- 요청: `docs/review/2026-09-26-car-fix-rereview-request.md`.
- 구현 범위: `644816136f3327f7fe09483ffebb8e36dafa79a8..d9ba75cd030b3f0100c173e4d4debf61453da04e`.
  `fix/car-collection-identity`의 구현 4커밋, 27파일이다.
- 최초 확인 HEAD: 요청 문서 커밋 `f944f8e`.
- 실행 소스는 **d9ba75c의 git archive**로 고정했다. 검토 중 원본은 `5ec8a13`으로
  이동했고 BandMeter/SpectrumChart/AnalyzeScreens의 작업 중 변경이 생겼다.
  이 변경과 기록 기능의 후속 작업은 시험·판정에 섞지 않았다.
- 명세의 입력 경로별 교정, 장치/보정 메타데이터 분리, 미보정 SPL 표시 원칙과
  이전 CAR 보고서를 대조했다. 요청서의 완료 주장과 자체 기기 로그는 검증
  자료로 읽었으며 독립 실기기 결과로 합산하지 않았다.
- Production 소스, 사용자 데이터, 브랜치, 커밋은 수정하지 않았다.

| 독립 실행 | 결과 | 범위 |
|---|---|---|
| DSP 전체 JUnit | **544건 통과**, 78.059초 | Kotlin 2.2.20 / JBR 21 / JVM target 17 |
| 앱 교정 선택 JUnit | **221건 통과**, 9.848초 | 14개 클래스. 앱 전체 시험은 아님 |
| 실제 CalibrationWizardViewModel probe | 기존 차단 대조군 통과, 아래 반례 재현 | 실제 VM·Runner·MeasurementTap·판정·파일 저장 |
| 실제 WizardCaptureBridge probe | 보류된 scalar 유출 재현 | Bridge는 원본 그대로, 소유 CaptureViewModel의 상태 껍데기만 stub |
| 실제 CAL 파일 parser probe | 기존 7개 반례 차단, 새 4개 반례 확인 | CalibrationFile.load → 열 판정 → CalInfo.readingSettled |
| CA-R04 되돌림 시험 | 실제 VM probe 실패(exit 1), Runner/새 관문 시험 **48건 통과** | scratch의 stopWork에만 inForeground=false 삽입 |

앱 시험은 캐시된 라이브러리와 mockable android.jar로 직접 컴파일했다.
Application의 filesDir를 scratch로 바꾸고, VM의 CAL 입력 설정에만 reflection을
썼다. 실제 오디오 대신 합성 FFT 전력을 공급했다. 파일 저장은 실제 ProfileStore로
scratch에 수행했다. ActiveCurve 모델은 원본 정의를 별도 파일로 옮겨 DataStore
의존성을 분리했다. Android DataStore·실제 CaptureViewModel 전체·AudioRecord·
Compose는 실행하지 않았다. Gradle 전체 build/lint, APK 설치, 실제 음향·USB
경합·화면 동작은 이번 독립 검증에 포함되지 않는다. mutation 48건은 선택 시험과
중복되므로 고유 시험 수에 더하지 않는다.

위치의 행 번호는 d9ba75c 기준이다. 아래 주요 결함 파일은 원본과 동일함을
확인했으며, 후속 변경으로 행이 이동한 SelahApp은 고정 복사본에 링크했다.

## 2. CARF-01 — High: 검사 증거의 키에는 여전히 실제 주소가 없다

**위치:** [CaptureIdentity.kt:49](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/calibration/CaptureIdentity.kt:49),
[CalibrationWizardViewModel.kt:443](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:443),
[같은 파일:519](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:519).

CaptureIdentity에는 routedAddress가 들어갔지만 `evidenceKey()`는 여전히
`calKey.storageKey + fs + n`뿐이다. 내장 bottom/back은 같은 CalibrationKey를
쓰므로, 한 주소에서 얻은 잡음·DSP 증거가 다른 주소에서도 조회된다.
`sameAs`의 완료 검사는 각 수집이 진행되는 동안의 변경만 막는다. 검사를 끝내고
다른 경로에서 측정을 시작한 경우는 막지 않는다.

**독립 재현:** 실제 VM으로 기준을 재고, 내장 bottom에서 입력 검사를 성공시켰다.
back으로 재개방해 잡음 조건을 SNR 2dB로 바꾸고, 새 검사를 하지 않은 채 대상을
측정한 다음 기준 재측정을 완료했다.

```text
ROUTE_EVIDENCE checked=bottom measured=back sameEvidenceKey=true
verdict=Pass staleUsable=31 actualUsable=0
```

같은 측정 자료를 실제 back 잡음으로 품질 계산하면 유효 밴드는 0개다. VM은
bottom의 조용한 배경과 성공한 DSP 검사를 사용해 31개를 유효하다고 판정했다.
이 재현은 세대만 다른 정상 재개방의 정책 문제가 아니라 **실제 주소가 다른
검사 증거의 오사용**이다.

**사용자 영향:** 검증하지 않은 마이크 경로의 가공·잡음 상태를 검증된 것으로
승인하고 저장/자동 적용 근거로 삼는다.

**권장 수정:** 재사용 가능한 증거의 키에도 확인된 실제 주소를 포함한다.
저장소 키와 검사 증거 키를 분리하고, 가능하면 문자열 대신 경로·격자 타입을
사용한다. 주소를 모르는 증거를 다른 캡처로 재사용하지 않는다. 세대의 재사용
정책은 별도로 정하되, 주소 차이를 무시해서는 안 된다.

**필요 회귀:** 동일 저장 키·다른 bottom/back에서 증거 미조회 및 승인 거절,
원래 주소로 돌아온 정상 대조군, 빈 주소/미확인 주소, 채널/source/격자 변경.
증거 키 함수의 단언과 함께 실제 VM의 최종 judgeCalibration까지 시험한다.

## 3. CARF-02 — High: 적용을 보류한 SPL 보정이 기준 마이크 값으로 유출된다

**위치:** [WizardCaptureBridge.kt:50](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/WizardCaptureBridge.kt:50),
[CalibrationWizardViewModel.kt:483](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:483),
[같은 파일:544](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:544).

ActiveCalibration.from은 unknown/different 주소의 보정을 올바르게 보류한다.
이때 saved는 보존해야 하므로 non-null이다. 그런데 Bridge.openedOffsetDb는
활성 여부를 보지 않고 `calibration.saved?.offsetDb`를 그대로 반환한다.
VM은 이 값을 referenceOffsetDb로 저장해 절대 레벨 치환에 사용한다.

**독립 재현:** 실제 ActiveCalibration과 원본 Bridge를 연결했다. owner의 상태
공급만 stub이며, getter/판정은 제품 코드다.

```text
savedRoute=null  held=true  referenceOnly=true  bridgeOffset=110.0
savedRoute=other held=true  referenceOnly=true  bridgeOffset=110.0
```

같은 주소의 정상 대조군은 held=false다. 위 보류 상태에서 Bridge가 돌려준
110dB를 실제 VM의 기준→대상→기준 흐름에 공급한 결과:

```text
HELD_TRANSFER referenceOffset=110.0 targetOffset=110.0
verdict=Pass transferAllowed=true
```

**사용자 영향:** 화면에서는 미보정/적용 보류라고 표시하면서, 그 값을 다른
마이크의 절대 SPL 보정으로 복제할 수 있다. 옛 기록을 자동 적용에서 제외한
CAR-03의 보호가 이 경로에서는 무효다.

**권장 수정:** Bridge는 현재 경로에 적용 가능한 보정만 반환하고, 보류/미확인/
미보정이면 null로 반환한다. 더 명확하게는 값과 승인된 기준 경로를 묶은 타입을
전달한다. 단순히 assumed.offset을 대신 반환하면 안 된다. 그것도 검증되지 않은
절대값이다.

**필요 회귀:** legacy route 없음, 다른 주소, route 미확인에서 Bridge offset=null,
실제 VM에서 levelTransfer 미생성 및 이유 표시, 같은 주소의 유효 교정은 치환 성공.
DataStore에 값이 남아 있다는 사실과 현재 적용 승인 여부를 별개로 단언한다.

## 4. CARF-03 — High: 검사한 신원과 파일에 저장하는 환경이 서로 달라도 통과한다

**위치:** [CalibrationWizardViewModel.kt:640](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:640),
[같은 파일:667](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:667),
[같은 파일:680](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:680),
[고정 SelahApp.kt:518](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/car-fix-20260926/source/app/src/main/java/kr/joa/selahrta/ui/SelahApp.kt:518).

save는 `environment`와 `now`를 따로 받는다. 검사에는 now만 쓰고, 파일에는
environment를 쓴다. 이전에 있던 environment.key 비교도 삭제됐다. 따라서
검사를 통과한 입력과 파일의 소유 입력이 일치한다는 보장이 없다.

실제 호출부도 두 값이 같은 스냅샷에서 나오지 않는다. environment는 Compose가
수집한 capture.opened에서, now는 Bridge가 호출 시점에 다시 읽는 vm.state에서
나온다. 입력 전환 후 재구성 전에는 둘이 다를 수 있다.

**독립 재현:** 정상 측정을 마친 실제 VM에 대상의 현재 identity와 기준 입력의
environment를 함께 전달했다. 실제 ProfileStore 파일 저장까지 성공했다.

```text
SAVE_ENV_MISMATCH measured=Usb|target|a checked=Usb|target|a
saved=Usb|reference|a
```

이는 **서로 다른 두 인자를 주었을 때 저장 API가 실제로 허용하는 것**을 검증한
결과다. Compose에서 그 클릭 타이밍을 기기로 재현했다는 주장은 아니다.
호출부에서 값이 갈라질 수 있는 근거는 위 정적 데이터 흐름이다.

**사용자 영향:** 대상의 주파수 교정이 기준 또는 다른 경로의 프로파일로 기록돼
잘못 적용될 수 있다. 올바른 identity를 검사했어도 저장 산출물은 틀린다.

**권장 수정:** 동일한 캡처 스냅샷에서 검증·저장 환경을 만들고, 저장 API 안에서도
environment의 key/address/sampleRate가 승인된 측정 경로와 일치하는지 검사한다.
독립 인자를 없애 검증된 저장 대상 객체를 만드는 방법도 가능하다. UI의 사전
판정에만 의존하지 말고 실제 저장 경계가 보장해야 한다.

**필요 회귀:** now는 정상이지만 environment의 기기/주소/채널/source/fs가 다른
경우 파일이 생성되지 않아야 한다. 렌더 시점 환경과 클릭 시점 캡처가 다른
호출 연결 시험, 둘이 일치하는 실제 저장 대조군을 포함한다.

## 5. CARF-04 — Medium: 장을 폐기한 뒤에도 이전 완료 판정이 저장 가능 상태로 남는다

**위치:** [CalibrationWizardViewModel.kt:469](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:469),
[같은 파일:616](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CalibrationWizardViewModel.kt:616).

입력 변경을 잡으면 내부 session.discard(step)를 실행하지만, StateFlow에 이미
발행한 session/quality/outcome/levelTransfer 및 신원은 무효화하지 않는다.
처음 시도만 시험하면 모두 null이라 드러나지 않는다.

**독립 재현:** 정상 측정 세 단계를 끝낸 뒤 대상의 「다시」를 실행하고 마지막
장에서 채널을 바꿨다. 폐기 후 원래 대상으로 돌아오면:

```text
DISCARD_OLD_RESULT liveFrames=0 publishedFrames=120
sameOutcome=true transferAllowed=true
```

화면의 「다시」는 이미 장이 있는 단계에도 활성화된다
(`CalibrationWizardScreen.kt:816–860`). 내부 집계는 대상 0장인데, 화면/저장 관문이
보는 결과는 대상 120장의 옛 Pass다. 이전 측정이 반드시 잘못됐다는 뜻은 아니다.
**폐기된 현재 시도와 보존한 과거 결과를 구분하지 않는 상태 불일치**다.

**사용자 영향:** 재측정이 실패·폐기됐는데 완료 판정과 옮길 값은 그대로라,
사용자가 새 시도의 결과로 오인해 옛 교정을 저장할 수 있다.

**권장 수정:** 재시도 시작/폐기 때 현재 결과와 파생 판정·transfer를 함께
무효화한다. 과거 성공을 보존하려면 별도 이력으로 명시하고 현재 시도의 저장
승인에 재사용하지 않는다. session과 발행된 결과를 같은 상태 전이로 관리한다.

**필요 회귀:** 최초 실패 외에 성공→재측정→신원 변경/취소/자료 부족,
frameCount와 published result의 일관성, 실패 뒤 옮기기·저장·다음 단계 차단,
정상 재측정 후 새 결과로 복구를 실제 VM으로 시험한다.

## 6. CARF-05 — Medium: 열 이름 정규화가 단위와 상충하는 부호 단서까지 버린다

**위치:** [CalibrationSign.kt:138](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:138),
[같은 파일:148](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:148),
[같은 파일:309](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:309).

normalizeColumn은 괄호·대괄호의 내용을 모두 지운다. RESPONSE_COLUMNS는
amplitude/magnitude/level도 응답 dB로 확정한다. 하지만 parser는 단위 변환 없이
첫 숫자를 Hz, 둘째 숫자를 dB로 사용한다. 괄호 안에 단위 외의 `correction`이
있어도 제거되며, 전체 문장에 response와 correction이 함께 있으면 SignEvidence가
Unknown이어서 상충 검사도 열리지 않는다.

**실제 파일 입력 재현:** 모두 `ColumnDeclaration.Second(Response)`,
`CalInfo.readingSettled=true`가 나왔다.

```text
Frequency (kHz),Response (dB)
Frequency (Hz),Amplitude (Pa)
Frequency (Hz),Magnitude (linear)
Frequency,Response (correction)
```

입력 숫자는 변환되지 않고 그대로 gainDb가 된다. 기존 7개 설명문/다른 열 반례는
모두 차단됐으며, `Frequency (Hz),Response (dB)` 정상 대조군은 통과했다.

**사용자 영향:** 명시적으로 다른 단위이거나 부호가 모순인 파일을 교정 기준으로
자동 승인한다. 예를 들어 선형 크기 2를 2dB 응답으로 읽는 것은 같은 수량이 아니다.

**권장 수정:** 이름·단위·부호 단서를 분리해 파싱한다. 자동 확정은 지원하는
Hz/dB 조합에 한정하고 다른 단위·선형 값·상충 선언은 질문 또는 거절로 보낸다.
Unknown에서 단서 없음과 서로 모순됨을 분리한다. 모든 지원 형식을 구현하라는
뜻은 아니다. 해석하지 못하는 형식을 이미 이해한 것으로 승인하지 않으면 된다.

**필요 회귀:** Hz/dB 정상, kHz/linear/Pa 미지원, 괄호 내 correction,
설명문과 열 선언의 모순, 단서 없음 각각의 실제 load→CalInfo 판정을 단언한다.
알려진 이름 목록만 따로 시험해서는 이 연결 오류를 잡지 못한다.

## 7. CARF-06 — Low: 순수 관문 시험이 실제 VM 생명주기 회귀를 대체하지 못한다

**위치:** [WizardRunnerTest.kt:198](D:/Cowork/SELAH-RTA/app/src/test/java/kr/joa/selahrta/calibration/WizardRunnerTest.kt:198),
[EvidenceLifecycleTest.kt:64](D:/Cowork/SELAH-RTA/app/src/test/java/kr/joa/selahrta/calibration/EvidenceLifecycleTest.kt:64).

fixture의 stableKey 수정과 물리 주소 판정 계약 정정은 확인했다. 그러나
Runner 재진입 시험은 여전히 지역 변수 foreground를 false→true로 바꾼다.
EvidenceLifecycleTest도 실제 runInputCheck/stopWork 대신 상태 헬퍼를 직접
호출한다. VM에는 헬퍼와 다른 직접 copy 경로도 남아 있다.

**독립 되돌림:** scratch VM의 stopWork에만 `inForeground=false`를 되살렸다.

```text
실제 VM 재진입 probe: Check failed, exit 1
WizardRunnerTest + CaptureIdentityGateTest + EvidenceLifecycleTest
  + CalibrationRouteTest: OK (48 tests)
```

**영향:** 실제 사용자 흐름이 다시 깨져도 새 시험은 모두 통과할 수 있다.
요청서에서 VM 회귀를 아직 옮기지 않았다고 밝힌 점은 정확하다. 따라서 CAR-05의
fixture 부분은 종료 가능하지만 통합 회귀 부분은 완료로 표시할 수 없다.

**권장 수정/회귀:** 실제 VM의 stopWork→재진입, onBackground→자동 재생 금지,
onForeground→사용자 재시작, 증거 수집 취소·신원 변경, 결과 폐기까지 시험한다.
Robolectric 도입 자체가 필수는 아니다. 이번처럼 테스트 Application/dispatcher로
실제 VM을 실행하거나, 상태와 coroutine 소유권까지 포함한 coordinator를 분리해
그것을 시험할 수 있다. 판단 함수만 떼는 것으로는 이 범위를 대체하지 못한다.

## 8. 이전 지적의 상태

| 이전 항목 | 이번에 확인한 수정 | 종료 판단 |
|---|---|---|
| CAR-01 A | 전후 reference 채널 변경 차단, 같은 경로 재개방 허용 | 해당 반례 해결 |
| CAR-01 B | 마지막 장 직후 채널 변경 시 장 폐기, 새 신원 미부착 | 최초 시도 해결; 재시도 상태는 CARF-04 |
| CAR-01 C | 실제 VM에서 다른 bottom/back 저장 거절, 같은 주소 파일 저장 성공 | 해당 반례 해결; 저장 인자 불일치는 CARF-03 |
| CAR-02 | 39장째 재검사 취소 시 옛 잡음/DSP 제거, tap 분리, 추가 재생 없음 | 취소 반례 해결; 주소별 증거는 CARF-01 |
| CAR-02 입력 변경 | quiet 40장 완료 때 채널을 바꾸면 증거 미등록 | 해당 반례 해결 |
| CAR-03 | ActiveCalibration의 unknown/different 보류, 같은 주소 적용 | 직접 적용 수정 확인; 마법사 우회는 CARF-02 |
| CAR-04 | 이전 설명문 4개와 열 관련 3개 반례 차단 | 기존 반례 해결; 단위·모순은 CARF-05 |
| CAR-05 | stableKey fixture/주소 계약 정정 | 해당 부분 해결; VM 회귀는 CARF-06 |
| CA-R04 | 실제 VM stopWork 재진입/배경 차단/복귀 동작 | 해결 유지 |

MicSource의 null routing 통지는 코드상 추가됐다. 실제 AudioRouting callback과
상위 stop/재개방의 기기 동작은 이번에 독립 실행하지 않았다. CalibrationStore의
route 저장·보존·clear·confirmRoute도 코드를 검토했으나 Android DataStore에서
독립 실행한 결과라고 주장하지 않는다.

## 9. 요청서의 질문에 대한 답

1. **세대와 경로의 구분:** 타당하다. 같은 수집 중 재개방은 폐기하고, 기준→대상→
   기준 복귀에서는 동일 경로의 새 세대를 허용할 수 있다. 다만 재사용 증거에는
   실제 주소가 필요하다(CARF-01). generation을 무조건 모든 키에 넣는 것이
   해결책은 아니다. 주소 미확인의 재개방도 같은 경로의 증거로 간주하면 안 된다.
2. **옛 보정 자동 적용 보류:** 과하지 않다. 저장값 보존과 적용 승인을 구분한
   방향은 맞다. 사람이 누른 확인은 수동 승인이지 하드웨어가 과거 마이크를
   검증했다는 증거는 아니다. 이를 구별해 기록/안내하고, 다른 주소가 이미 있으면
   덮어쓰지 않는 정책을 유지한다. 먼저 CARF-02의 우회를 막아야 한다.
3. **열 이름 집합:** 이름을 무작정 늘리기보다 이름·위치·단위·부호를 함께 본다.
   지원하지 않는 이름을 질문으로 보내는 보수성은 허용할 수 있다. 반대로 단위와
   모순을 지우면서 익숙한 이름만 남기는 현재 동작은 자동 확정의 근거가 못 된다.
4. **순수 함수로 VM 회귀 대체:** 일부 판단의 시험 가능성은 좋아졌지만 대체하지
   못한다. 라이브러리 선택보다 실제 상태 전이와 연결 경계를 시험하는 것이
   요구사항이다. `AndroidViewModel이라 기기 없이는 한 줄도 돌릴 수 없다`는
   요청서/주석의 단정은 맞지 않다. 이번에도 실제 VM을 JVM에서 실행했다.
5. **가려진 분기 추가 확인:** saved가 있으면서 적용은 보류된 분기(CARF-02),
   이미 성공한 결과가 있는 상태에서 discard되는 분기(CARF-04), 서로 모순된
   단서를 Unknown으로 합치는 분기(CARF-05)가 구체적인 추가 사례다. 저장소 전체의
   모든 기본값/분기를 감사했다는 뜻은 아니다.

## 10. 재현 자료

모든 자료는 다음 폴더에 있다:

[car-fix-20260926](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/car-fix-20260926)

| 파일 | 용도 |
|---|---|
| `source.zip`, `source/` | d9ba75c 고정 소스 |
| `run.ps1`, `dsp-test-output.txt` | DSP 544건 |
| `app-calibration-tests.ps1`, `app-calibration-test-output.txt` | 교정 선택 221건 |
| `IndependentFixProbe.kt`, `wizard-probe.ps1`, `independent-wizard-output.txt` | 실제 VM 차단 대조군·주소 증거·보류값 치환·저장 환경·폐기 상태 |
| `BridgeCaptureStateStub.kt`, `IndependentHeldProbe.kt`, `held-probe.ps1`, `independent-held-output.txt` | 원본 Bridge의 보류값 노출 |
| `IndependentColumnProbe.kt`, `column-probe.ps1`, `independent-column-output.txt` | 실제 파일 parser/열 판정 |
| `MutantWizardViewModel.kt`, `mutation-probe.ps1`, `mutant-pure-test-output.txt` | 실제 VM과 순수 시험의 검출 차이 |
| `fake-app-files/profiles/` | 정상/잘못된 환경의 실제 저장 결과. 사용자 데이터와 무관 |

probe는 결함 관측값 자체를 check한 부분이 있으므로 exit 0을 제품 정상 판정으로
읽으면 안 된다. 정규 회귀로 옮길 때에는 잘못된 승인·저장이 거절된다는 기대를
단언해야 한다. 이전 검토에서 복사해 둔 미사용 probe 파일은 이번 결과의 근거가
아니며 위 표의 실행 파일과 출력만 해당한다.

실기기 검증, 전체 Android build/lint, 기록 기능/CSV/캡처 연결, 실제 현장의
교정 정확도 및 DSP 오탐·미탐은 이 재검토로 승인하거나 해결했다고 보지 않는다.
