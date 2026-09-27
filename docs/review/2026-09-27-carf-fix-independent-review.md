# SELAH RTA CARF-01~06 수정분 독립 재검토

검토일: 2026-09-27 · 검토자: Codex

**판정: 승인 보류 — Critical 0건, High 1건, Medium 1건, Low 1건.**

이전 High 3건의 구체적인 재현 경로는 모두 차단됐다. 폐기 뒤 옛 결과가 남던
경로와 CAL 단위 반례 넷도 해결됐다. 코디네이터 분리 후 생명주기 되돌림을
정규 시험이 검출하는 것도 확인했다.

승인을 막는 남은 High는 **완료 후 처음 기준만 다른 채널로 다시 재면, 마지막
기준은 옛 채널 자료인데도 Pass가 되는 것**이다. 이는 실제 기준 소음계나
94dB 교정기가 없어서 생긴 검증 공백이 아니라, 합성 데이터로 재현되는 코드
문제다. 아래 High를 수정·재검증하고 Medium/Low의 처리 방침을 정하면 이번
코드 범위의 승인 여부를 다시 판단할 수 있다.

## 1. 범위와 검증 경계

- 요청: `docs/review/2026-09-26-carf-fix-rereview-request.md`.
- 비교: `docs/review/2026-09-26-car-fix-independent-review.md`.
  저장소 사본과 이전에 전달한 보고서는 행 단위 내용이 동일함을 확인했다.
- 구현: `7eaa043..dbc8e73efb7e9c7e3c9278d93e642dee53617e47`.
  `34e84e9`, `a43e276`, `dbc8e73`의 3커밋, 15파일.
- [PR #11](https://github.com/janghorse-boop/SELAH-RTA/pull/11)을 작업에 연결했다.
- 소스는 **dbc8e73의 git archive**로 고정했다. 저장소 HEAD는 문서 커밋
  `a600b0c10243347694e3698796e242d801e5a708`, 작업 트리는 확인 시 깨끗했다.
- 코드 검토와 시험은 이 수정 및 연결 경계에 한정한다. 이전 RTA 고정축 UI 변경,
  기록 기능, 전체 제품 출시를 이번에 승인하는 것은 아니다.
- Production 파일·사용자 데이터·브랜치·커밋을 변경하지 않았다. 요청서의 기기
  미확인 진술은 그대로 반영했고, 스크린샷의 완료 주장을 독립 결과로 합산하지 않았다.

| 실행 | 독립 결과 | 경계 |
|---|---|---|
| DSP 전체 JUnit | **557건 통과**, 77.614초 | 직접 Kotlin/JVM 컴파일 |
| 앱 교정 선택 JUnit | **251건 통과**, 9.164초 | 17개 클래스, Coordinator/Work/CarfRegression 포함 |
| 실제 VM → Coordinator 흐름 | 기존 반례 차단, 새 재측정 반례 재현 | 실제 wrapper·core·Runner·Tap·판정·ProfileStore |
| 원본 WizardCaptureBridge | unknown/different 보류값은 null, 같은 주소 110dB 반환 | owner의 상태 공급 껍데기만 stub |
| 실제 CAL 파일 parser | 기존 반례 차단, 모순 사례 3개 자동 확정 재현 | load → columnDeclaration → CalInfo |
| stop의 전경 상태 변경 되돌림 | **32건 중 2건 실패** | Coordinator 1건, Work 1건 실패. Runner 17건 통과 |

Kotlin 2.2.20/JBR 21/JVM target 17을 사용했다. 앱 시험과 최종 흐름 probe는
이번에 추가한 의존성에 맞춰 coroutines core/test **1.9.0**으로 실행했다.
DSP 실행 스크립트의 compiler classpath에는 이전 harness의 core 1.8.0이 있다.
초기 앱 실행도 이전 harness로 251건 통과했으나 위 표는 1.9.0으로 맞춘 결과다.

Application.filesDir만 scratch로 바꿨고 CAL은 실제 core.loadCal로 불러왔다.
시험 준비를 위해 VM의 core/state에 접근하는 reflection을 사용했다. 입력은
합성 FFT 전력이며 실제 음향은 아니다. Android DataStore, CaptureViewModel 전체,
AudioRecord, Compose UI는 실행하지 않았다. 전체 Gradle build/lint, APK 설치,
기기 화면 회귀도 미실행이다. 이전 APK는 이번 core 분리를 포함하지 않는다는
요청서 설명을 독립 기기 검증으로 대체하지 않았다.

## 2. CF2-01 — High: 처음 기준 재측정이 남겨 둔 마지막 기준의 신원을 우회한다

**위치:** [WizardCoordinator.kt:429](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/calibration/WizardCoordinator.kt:429),
[같은 파일:473](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/calibration/WizardCoordinator.kt:473),
[같은 파일:489](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/calibration/WizardCoordinator.kt:489),
[CaptureIdentityGate.kt:166](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/calibration/CaptureIdentityGate.kt:166).

재측정 시작 때 해당 단계만 discard하고, 다른 단계의 장은 유지한다. 처음
기준의 새 수집이 성공하면 referenceIdentity를 새 채널로 바꾼다. 마지막 기준은
독립 신원을 보유하지 않으며, 이미 120장이 있으므로 session.complete가 참이
되어 곧바로 finishMeasurement가 실행된다. 마지막 기준을 다시 수집하지 않아
`measureGateKo(ReferenceAfter, ...)`도 호출되지 않는다.

**독립 재현:** 실제 VM/core로 아래 순서를 실행했다.

1. reference ch0 → target → reference ch0를 정상 완료한다.
2. reference ch1에서 입력 검사를 마친다.
3. 화면의 「기준(처음) — 다시」에 해당하는 measureStep(ReferenceBefore)를 실행한다.
4. 마지막 기준은 재측정하지 않는다.

```text
REFERENCE_RETRY newBeforeChannel=1 retainedAfterChannel=0
retainedAfterFrames=120 verdict=Pass
```

두 채널의 전력 값을 동일하게 주었으므로 반복성 검사도 통과한다. 같은 숫자라는
사실은 같은 마이크·감도라는 증거가 아니다. 정상 순서에서 마지막 기준을 다른
채널로 바꾸는 기존 반례는 거절됐지만, **처음 기준을 나중에 바꾸는 역방향**은
거절되지 않았다. `judgeCalibration.maySave=true`까지 확인했다. 이 새 반례로
파일 저장까지 실행한 것은 아니며, 정상 저장 경계는 대상 신원만 검사하므로
이 전후 기준 불일치를 다시 판단하는 코드가 없다.

**사용자 영향:** 다른 기준 입력의 측정들을 한 기준의 전후 자료로 합쳐 교정
곡선과 절대 레벨 치환을 승인할 수 있다. 재측정 완료가 표시돼 사용자가 알아채기
어렵다. 기준→대상→기준의 시간 순서도 일부 재측정 시 별도로 보장해야 한다.

**권장 수정:** 단계 간 의존성을 무효화한다. 처음 기준을 다시 재면 대상과
마지막 기준을 포함해 다시 요구하는 방식이 가장 단순하다. 부분 보존을 택한다면
각 단계의 실제 신원·시도·시간 순서를 보관하고 최종 판정에서도 일관성을
검증해야 한다. 현재처럼 마지막 기준의 신원을 새 처음 기준의 것으로 간주하면
안 된다.

**필요 회귀:** 정상 완료→처음 기준 ch1/source/주소 변경 재측정→마지막 기준
재측정 전 승인·저장 차단, 같은 입력에서 전체 순서를 다시 밟는 정상 대조군,
대상 재측정 후 마지막 기준 재요구 여부, 실패·취소 시 이전 단계와 새 단계가
섞이지 않는지 실제 Coordinator로 시험한다.

## 3. CF2-02 — Medium: 여러 단서가 모순일 때 여전히 자동 확정된다

**위치:** [CalibrationSign.kt:159](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:159),
[같은 파일:214](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:214),
[같은 파일:356](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:356).

단일 괄호의 kHz/Pa/linear/correction 반례는 해결됐다. 그러나 splitColumn은
**첫 괄호만** note로 읽고 모든 괄호를 이름에서 제거한다. 둘째 괄호의 모순은
사라진다. 또 signEvidenceOf는 response와 correction이 함께 있으면 Unknown으로
합치고, decideReading은 Unknown을 단서 없음으로 처리해 열 선언을 확정한다.

**실제 파일 입력 결과:** 세 사례 모두 `Second(Response)`, `readingSettled=true`.

```text
# Correction factors
Frequency (Hz),Response (dB)

Frequency (Hz),Response (dB) (correction)

Frequency (Hz) (kHz),Response (dB)
```

앞 두 사례는 SignEvidence.Unknown이고 마지막은 LooksLikeResponse다. 이전의
단순 `Response(correction)`과 달리, 첫 괄호에 정상 단위를 두면 추가 단서가
검사를 우회한다. 요청서가 요구한 “이름·단위·부호를 구분”하는 변경과 직접
연결된 잔여 문제다.

**사용자 영향:** 뜻이 모순인 파일을 확인 없이 기준 CAL로 사용할 수 있다.
부호가 반대라면 파생 교정이 반대로 적용된다. 세 사례가 실제 제조사 파일에서
얼마나 흔한지는 이번에 조사하지 않았다. 재현은 입력 검증 경계에 대한 것이다.

**권장 수정:** 괄호·대괄호의 모든 토막을 검토하고 모순이면 질문/거절로 보낸다.
SignEvidence에서 “단서 없음”과 “응답·보정 단서가 충돌”을 구분한다. 선언된
둘째 열 자체를 포함해 수집한 전체 문장을 Unknown으로 만든 뒤 그것을 다시
자동 확정의 허가로 쓰지 않는다.

**필요 회귀:** 위 세 실제 load 사례, 정상 Hz/dB 단일 선언, 설명문만 있는 경우,
response와 correction이 함께 명시된 경우를 독립적으로 단언한다. 단순히 알려진
이름 목록을 늘리는 수정은 이 반례를 해결하지 못한다.

## 4. CF2-03 — Low: 정규 취소 시험의 흐름은 stop 호출 전에 이미 끝난다

**위치:** [WizardCoordinatorTest.kt:105](D:/Cowork/SELAH-RTA/app/src/test/java/kr/joa/selahrta/calibration/WizardCoordinatorTest.kt:105),
[같은 파일:112](D:/Cowork/SELAH-RTA/app/src/test/java/kr/joa/selahrta/calibration/WizardCoordinatorTest.kt:112),
[같은 파일:117](D:/Cowork/SELAH-RTA/app/src/test/java/kr/joa/selahrta/calibration/WizardCoordinatorTest.kt:117).

UnconfinedTestDispatcher와 일시중단하지 않는 tick을 함께 쓴다. 첫 실행은 잡음과
신호에 같은 1e-9 전력을 주므로 가청 상승 검사를 통과하지 못하고, 성공한 DSP
증거를 만들지 않는다. 두 번째 실행의 빈 tick도 suspend하지 않아 400번 제한을
모두 소진한 뒤 실패로 돌아온다. 그 뒤 호출하는 stopWork는 진행 중 작업을
취소하는 것이 아니다.

동일한 tick/dispatcher 패턴을 실제 VM/core에 공급해 관측했다:

```text
CANCEL_FIXTURE verifiedBefore=false busyBeforeStop=null tapBeforeStop=null
```

**영향:** 이름과 주석의 “성공→재검사→수집 중 취소”를 이 정규 시험이 검증하지
못한다. 제품의 취소가 현재 깨졌다는 뜻은 아니다. 별도의 독립 probe에서는
StandardTestDispatcher와 delay를 사용해 39장째 실제로 취소했고, 옛 잡음/DSP
제거·tap 분리·추가 재생 없음이 모두 통과했다.

**권장 수정/필요 회귀:** 첫 검사에서 신호 전력을 올려 `verifiedBySignal=true`를
전제로 단언한다. 재검사는 실제 suspend하는 tick으로 정지시키고, stop 직전
busy 및 설치된 tap, 수집 장 수를 확인한다. stop 후 finally 정리와 증거 제거를
단언한다. 이번 독립 probe의 실제 취소 순서를 정규 시험에 옮길 수 있다.

## 5. 이전 지적의 종료 상태

| 이전 항목 | 이번 독립 확인 | 판단 |
|---|---|---|
| CARF-01 | bottom 증거를 back에서 조회하지 못함. 최종 보정 생성도 거절 | 기존 High 해결 |
| CARF-02 | 보류된 saved는 보존하되 Bridge 반환 null, VM levelTransfer 없음 | 기존 High 해결 |
| CARF-03 | 정상 now + 다른 environment 저장 거절, 올바른 환경 저장 성공 | 기존 High 해결 |
| CARF-04 | 대상 재측정 폐기 뒤 내부 0장·발행 결과 null·transfer 차단 | 기존 반례 해결; 처음 기준 재측정은 CF2-01 |
| CARF-05 | kHz/Pa/linear/단일 correction 괄호 모두 미확정 | 기존 네 반례 해결; 복합 모순 CF2-02 |
| CARF-06 | 실제 VM이 Coordinator를 사용, stop 전경 변경 되돌림을 새 시험이 검출 | 구조·재진입 회귀 해결; 취소 fixture CF2-03 |
| 이전 CAR-01 A/B/C | 기준 채널 변경 거절, 완료 직후 변경 폐기, 다른 주소 저장 거절 | 해결 유지 |
| 이전 CA-R04 | stopWork 뒤 사용자 재시작·배경 재생 거절·전경 복귀 | 해결 유지 |

되돌림은 scratch의 WizardWork.stop에만 `inForeground=false`를 넣었다.
CoordinatorTest의 「끊은 뒤에도 소리를 낼 수 있다」와 WorkTest의 「끊어도 다시
시작할 수 있다」가 실패했다. Runner 17건은 통과했다. 요청서가 설명한 결과를
독립 실행에서도 확인했다. Production에는 되돌림을 적용하지 않았다.

보류 상태의 transfer probe 출력에는 `transferAllowed=true`가 남는다. 이 함수는
품질/대상 경로 관문이고, 실제 생성된 levelTransfer는 null이다. 화면은 transfer가
있을 때에만 옮기기 버튼을 만든다. 따라서 이 출력만으로 CARF-02가 남았다고
판정하지 않았다.

## 6. 요청서의 질문에 대한 답

1. **nullable appliedOffsetDb로 충분한가?** 이번에 지적한 보류값 유출 경로는
   닫혔다. 타입을 새로 만드는 것 자체가 승인 조건은 아니다. 미보정/보류에서
   null을 유지하고, 승인된 같은 경로의 값만 소비하는 계약을 시험하면 된다.
2. **호출부 대신 저장 경계에서 대조해도 되는가?** 맞다. 실제 소비되는 환경을
   경계에서 검사한 것이 중요하며 기존 서로 다른 인자 반례도 막혔다. 호출부의
   단일 스냅샷화는 불필요한 거절과 유지보수 부담을 줄이는 개선 사항이다.
3. **과거 성공을 버려도 되는가?** 가능하다. 이력 화면을 새로 만들 필요는 없다.
   다만 과거 결과뿐 아니라 재측정과 더는 함께 쓸 수 없는 다른 단계도 정리해야
   한다(CF2-01). “버린 단계의 이름표만”이라는 규칙으로는 의존성이 보장되지 않는다.
4. **지원 단위에 빠진 표기:** 실 제조사 CAL 표본을 추가 수집하지 않았으므로
   흔한 표기가 빠졌다고 단정하지 않는다. 보수적으로 질문하는 집합은 허용할 수
   있다. 우선 모순을 정상 선언으로 확정하는 CF2-02를 해결해야 한다.
5. **Coordinator와 fake capture로 이 범위가 닫히는가?** 결정적인 상태 전이 시험에
   적합한 구조다. fake capture 자체가 결함은 아니다. 실제 Bridge는 별도로
   확인했고, 제품의 핵심 상태 전이도 원본 core로 실행했다. CF2-03처럼 가짜 시간이
   취소를 실제로 발생시키는지 확인하고, 실제 Android/UI 연결은 기기에서 별도로
   검증해야 한다. 모든 Android 검증을 순수 JVM 결과로 대신할 수는 없다.
6. **남은 우회로:** CF2-01이 해당한다. 마지막 기준을 새로 모을 때의 신원 검사는
   있지만, 처음 기준을 바꿔 남겨 둔 마지막 기준을 재사용하면 그 검사를 지나지
   않는다. 이번 판정은 이 구체적 재현에 근거한다.

## 7. 재현 자료와 남은 검증

폴더: [carf-20260927](C:/Users/jangh/Documents/Codex/2026-09-21/bash-selah-rta-independent-reviewer-verification/work/carf-20260927)

| 파일 | 용도 |
|---|---|
| `source.zip`, `source/` | dbc8e73 고정 소스 |
| `run.ps1`, `dsp-test-output.txt` | DSP 557건 |
| `app-calibration-tests.ps1`, `app-calibration-test-output.txt` | 앱 선택 251건, coroutines 1.9.0 |
| `IndependentFixProbe.kt`, `wizard-probe.ps1`, `independent-wizard-output.txt` | 실제 VM/core 기존 반례·재측정 우회·실제 취소·fixture 관측 |
| `BridgeCaptureStateStub.kt`, `IndependentHeldProbe.kt`, `held-probe.ps1`, `independent-held-output.txt` | 보류값 반환 경계 |
| `IndependentColumnProbe.kt`, `column-probe.ps1`, `independent-column-output.txt` | CAL 파일 판정 |
| `MutantWizardWork.kt`, `mutation-probe.ps1`, `mutation-output.txt` | 정규 시험의 재진입 결함 검출 |
| `fake-app-files/profiles/` | 정상 저장 대조군. 사용자 파일과 무관 |

probe는 결함이 실제로 관측됐다는 사실을 check하는 부분도 있어 exit 0 자체가
승인을 뜻하지 않는다. 정규 회귀로 옮길 때는 잘못된 Pass/자동 확정이 거절된다는
기대값으로 바꿔야 한다. mutation의 exit 1은 의도한 결함 검출이다.

기기에서 마법사 열기, CAL 파일 선택, 단계 이동, 입력 전환, 닫기/배경/재진입,
취소 뒤 소리·tap 정리, 실제 저장 화면을 확인하는 작업은 남아 있다. 이는
이번 코드 결함과 별개의 Android/UI 검증이다. 기준 장비를 통한 절대 SPL
교정·정확도 확인도 추후 과제로 유지하며, 그 장비의 부재가 이번 승인 보류
이유는 아니다.
