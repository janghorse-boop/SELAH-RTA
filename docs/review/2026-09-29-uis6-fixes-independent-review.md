# UIS6 수정 독립 재검증 — 7회차

- 검토일: 2026-09-29
- 요청서: `2026-09-29-uis6-fixes-reverification-request.md`
- 기준: `1319b39`
- 고정한 대상: **`7db47805a4293ddf7cbc2cc7a770ed31de7ee85f`**, PR #63 병합 결과
- 추가 확인: 이전 승인 범위 밖이었던 `9ef7d68`의 입력 기기 알림 제거
- 원칙: 대상 커밋을 `git archive`로 분리했다. 재현·변이·수정안은 검토용 복사본에서 실행했으며 제품 소스에는 적용하지 않았다.

## 1. 판정

**UIS6-01·02·03 해결 확인. 기존 코드 승인 유지.**

이번 검토에서 추가로 보고할 사항은 **Low 1건(UIS7-01, 기존 시험의 종료 계약 오류)**이다. Critical / High / Medium 이슈는 발견하지 못했다.

다만 **대상 그대로의 전체 JVM 시험이 모두 통과한 것은 아니다.** 첫 실행은 849개 중 1개 실패했고, 해당 두 시험만 코드 변경 없이 다시 실행하면 통과했다. 그 실패를 지우거나 전체 성공으로 합산하지 않았다. 아래의 시험 수정안과 독립 probe를 넣은 별도 실행은 850개 전부 통과했다.

승인은 검증한 코드 범위에 대한 판단이다. 실제 업로드 키·Play Console, 음향 정확도, USB 실기기 전환, 2시간 부하는 이번에도 검증하지 않았다. DSP에는 이번 수정이 없어서 DSP 전체 시험을 재실행하지 않았다.

검토 중 별도 작업의 `SignalPlayer.kt`, `CaptureViewModel.kt`, `SignalGeneratorCard.kt`, `Navigation.kt`, `ToneRetuneTest.kt` 및 변경 이력이 `36683ff`, `d92a148`로 커밋됐다. 이 보고서는 그 후속 변경을 포함하지 않는다. 고정 커밋의 복사본만 사용했고 다른 작업은 건드리지 않았다.

## 2. 기존 세 건의 해결 근거

| 항목 | 독립 확인 | 결론 |
|---|---|---|
| UIS6-01 상대 경로 오거절 | app 기준 `../../fixture/review-only.jks`로 실제 `bundleRelease` 성공, AAB 서명 검증 및 시험 키 지문 일치 | 해결 |
| UIS6-02 단위 시험까지 서명 요구 | 키 없이 release JVM 시험이 실행됨. 실제 `bundleRelease`·`assembleRelease`는 키 누락으로 실패. 중간 classes/resources와 unit task는 guard에 의존하지 않음 | 해결 |
| UIS6-03 설정 화면 호출 누락 미검출 | 에뮬레이터에서 기존 UI 5개 정상 통과. 실제 화면의 `AppInfoWithPolicyLinks()` 호출만 주석 처리하면 새 화면 시험만 실패(5개 중 1개) | 해결 |

UIS6-02의 **서명 차단 범위**는 해결됐지만, 그 시험 실행 중 발생한 별도 실패는 UIS7-01로 분리했다. 실행이 서명 설정 때문에 막힌 것과 실행된 시험의 단언이 실패한 것은 다른 문제다.

### 서명 실행

- 키 없이 `:app:bundleRelease`: 실패, 네 속성 누락을 표시.
- 키 없이 `:app:assembleRelease`: 실패.
- 파일과 alias만 제공한 `:app:verifyReleaseSigning`: 두 비밀번호 속성 누락으로 실패.
- 상대 경로와 정상 **검토용 임시 키**로 `:app:bundleRelease`: 성공.
- 생성된 AAB의 `jarsigner -verify -verbose -certs`: 종료 코드 0, `jar verified.`.
- 같은 AAB에서 추출한 인증서 SHA-256이 시험 키와 일치:

```text
02:2C:A6:D0:17:88:CE:87:E2:24:69:3C:F0:B7:D5:9D:F2:F9:D7:40:C9:3C:11:A0:8B:A6:3C:21:56:CD:D0:47
```

6회차에서 만든 유효기간 2일의 폐기용 시험 키를 재사용했다. 실제 업로드 키가 아니며 이 AAB를 배포하거나 업로드하지 않았다. 이전에 설명한 자체 서명·짧은 유효기간·JAR 읽기 순서 관련 경고가 남아 있다. `jar verified`를 **Play 등록 키 일치 또는 Play 수락**으로 확대하지 않는다.

현재 AGP 8.13.0의 task 그래프에서 `packageRelease`, `packageReleaseBundle`, `packageReleaseUniversalApk`, `signReleaseBundle`이 모두 guard에 도달하고, `testReleaseUnitTest`는 도달하지 않는 것도 확인했다. 의도적으로 `-x`로 검증을 제외하는 것을 막는 보안 경계는 아니다.

### 실제 화면 시험

기존 `Medium_Phone` AVD(API 34)를 `-read-only -no-window -no-audio -no-snapshot-load -no-snapshot-save`로 실행했다. 모든 설치·시험 명령은 **`emulator-5580`을 명시**했다. 연결된 SM-S918N 휴대폰에는 설치하거나 시험을 실행하지 않았다. 시험 후 검토용 에뮬레이터에 종료 명령을 보내 정상 종료 응답을 받았다.

| 제품 코드 상태 | 기존 계측 시험 결과 |
|---|---|
| 고정 커밋 그대로 | **5개 통과** |
| `SettingsScreen`의 앱 정보 호출만 주석 처리 | **4개 통과 / `SettingsScreenPolicyPresenceTest` 1개 실패** |

이것은 새 시험이 실제 화면 → 앱 정보 → 링크 연결을 거친다는 실행 증거다. 구현자의 휴대폰 보고를 그대로 재인용한 결과가 아니다.

## 3. UIS7-01 — `stop()` 반환을 해제 완료로 단정하는 확률 시험

- **Severity: Low (시험 안정성·계약 오류)**
- **위치:** `app/src/test/java/kr/joa/selahrta/audio/SignalPlayerStopContractTest.kt:76-95`; 계약 근거 `app/src/main/java/kr/joa/selahrta/audio/SignalPlayer.kt:369-392,422`
- **범위:** 이 제품 코드와 시험은 이번 수정에서 바뀌지 않았다. 독립 실행에서 드러난 기존 문제다.

**관측:** 대상 그대로 `:app:testReleaseUnitTest`를 실행하면 849개 중 `멈추면 언제나 소리가 멎는다`가 실패했다. 200회 중 즉시 `released=false`였던 횟수는 1이었다. 원본 XML과 로그를 따로 보존한 뒤, 코드 변경 없이 이 클래스의 두 시험만 재실행하면 둘 다 통과했다.

```text
놓지 않은 채로 끝난 적이 있다 — 장치를 붙든 채 남는다
expected:<0> but was:<1>
```

**논리적 근거:** 제품 `stop()`은 `join(500ms)` 뒤에도 writer가 살아 있으면 자원을 강제로 해제하지 않고 pending에 남긴다. writer가 끝나면 스스로 해제한다. 그런데 시험은 `Thread.sleep(2)` 후 `stop()`을 호출하고 **반환 즉시** `sink.released`를 읽어, 아직 실행 기회를 받지 못한 writer까지 영구 누수처럼 판정할 수 있다. `sleep(2)`는 writer가 실행됐거나 종료됐다는 동기화 증거가 아니다.

**결정적 probe:** writer를 latch로 `write()` 안에 묶어 두고 `stop()`을 호출한 뒤, 그 장벽을 푸는 순서를 사용했다. 제품 코드를 고치지 않고 다음 결과를 얻었다.

```text
AFTER_STOP released=false pending=1
AFTER_WRITER_RESUMES released=true pending=0
```

probe는 writer가 자원을 쓰는 동안 `release()`가 호출되지 않는 것도 확인한다. 즉, 미해제 상태가 일시적으로 관측되는 것은 이 종료 계약에서 허용된다. **최초 확률 시험 실패 시점의 writer 스택이나 이후 해제를 추적하지는 않았으므로, 최초 한 건의 원인을 스케줄러 지연으로 확정하지 않는다.** 확인된 것은 즉시 관측을 영구 누수의 증거로 삼는 단언이 부족하다는 점이다.

**영향:** 부하·실행 순서에 따라 CI가 실패하고, 시험 실패를 없애려다 제품의 정상적인 지연 해제 경로를 잘못 고칠 수 있다. 이번 실행에서 실제 AudioTrack 누수가 입증된 것은 아니다.

**권장 수정:** 시험의 fake sink에 해제 관측 latch를 두고 완료를 기다린 뒤 판단한다. timeout은 여전히 실패로 남기되 메시지는 “제한 시간 안에 완료를 관측하지 못했다”로 쓴다. `stop()`에서 살아 있는 writer의 자원을 억지로 해제하는 수정은 이 문제의 해결책이 아니다.

**제공·검증:** `2026-09-29-uis6-stop-contract-proposed.patch` 및 `2026-09-29-uis6-deferred-release-probe.kt`. 별도 복사본에서 수정한 기존 849개 + 새 probe 1개, **850개 모두 통과**. 200회 시험의 `notReleased=0`, `stoppedAfterRelease=0`도 확인했다.

**필요한 회귀 시험:** writer가 늦게 끝나는 순서를 latch로 고정하여 pending 추적 → writer 복귀 → 최종 해제를 확인한다. 기존의 해제 후 stop 금지 시험과 병행하고, 시간 초과 실패는 재실행 성공으로 덮지 않는다.

## 4. flavor 질문에 대한 답과 제안

**flavor를 추가할 때는 variant 선택 방식으로 옮기는 것을 권한다.** 현재 앱은 flavor가 없어 고정한 여덟 이름으로 계약이 지켜진다. 이를 지금의 제품 결함으로 다시 세지는 않는다.

주석만 확인한 것이 아니라 별도 복사본에 가상 `free` / `paid` flavor를 추가하여 그래프를 비교했다.

| 확인 task | 현재 고정 이름 목록 | variant 기반 제안 |
|---|---|---|
| `packageFreeReleaseBundle` | guard 없음 | guard 있음 |
| `signFreeReleaseBundle` | guard 없음 | guard 있음 |
| `packagePaidRelease` | guard 없음 | guard 있음 |
| `testFreeReleaseUnitTest` | guard 없음 | guard 없음(의도한 동작) |

`androidComponents.onVariants(selector().withBuildType("release"))`에서 `variant.name`으로 최종 task 이름을 구성하는 patch를 제공했다. flavor 없는 현재 구성에서도 최종 번들 guard와 unit 분리가 유지되는 **실제 Gradle 구성·의존 그래프**를 확인했다. 이 가상 flavor로 APK/AAB 전체 빌드까지 실행한 것은 아니다. [AGP 8.13 onVariants API](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/variant/AndroidComponentsExtension), [VariantSelector API](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/variant/VariantSelector)

제안 파일: `2026-09-29-uis6-variant-guard-proposed.patch`.

**이 방식도 AGP 내부 task 이름 변경까지 자동으로 해결하지 않는다.** AGP 업데이트 시에는 최종 APK/AAB task가 guard에 도달하고 classes/resources/unit task는 도달하지 않는 그래프 회귀를 실행해야 한다. flavor 도입과 함께 이 변경을 하면 된다. 이를 이유로 지금의 승인을 보류할 필요는 없다.

## 5. UI 시험이 여전히 보장하지 않는 것과 보완 코드

새 시험은 링크 **존재와 화면 연결**을 지킨다. 기존 클릭 시험은 `openUrl`을 주입하므로 실제 기본 opener의 `Intent` 구성·`startActivity` 호출·예외 변환은 덮지 않는다. 이 한계는 이미 기존 KDoc에도 구별되어 있으므로 새로운 제품 결함으로 세지 않는다.

경계를 실행으로 확인했다. 기본 opener의 `context.startActivity(...)`만 건너뛰게 하고 `true`를 반환하도록 변이시켰다.

- 기존 계측 시험 **5개 모두 통과**.
- 실제 설정 화면을 열고 기본 opener가 부르는 `Context.startActivity`를 관측하는 제안 시험 **2개 모두 실패**.
- 실패 원인은 빈 Intent 목록 및 누락된 실패 안내였다.
- 변이를 복원한 정상 제품 코드에서 기존 5개 + 제안 2개, **7개 모두 통과**했다.

제안 파일: `2026-09-29-uis6-policy-intent-proposed-test.kt`. 하나는 두 링크의 `ACTION_VIEW`와 정확한 URL을 확인하고, 다른 하나는 `ActivityNotFoundException`을 넣어 두 URL 모두의 실패 안내를 확인한다. 제품에 시험 전용 API를 추가할 필요 없이 `LocalContext`의 wrapper로 Android 호출 경계만 관측한다.

이 시험을 추가해도 **실제 브라우저의 수신·서버 페이지 내용, 앱 내 내비게이션 전체, 모든 기기 크기·폰트 배율**까지 입증하지는 않는다. 현재 필요에 맞춰 화면 연결, Intent 호출, 실브라우저 확인을 각각 명시하면 된다.

## 6. 알림 제거와 실행 범위

`9ef7d68`의 변경은 `PreferredMissing` 알림 및 인라인 문구 제거이고, `chooseInput()`의 선택·사유 값, 실제 열린 기기명 표시, `NoDevice` 오류는 유지됐다. 초기 실행에서 `InputDevicesTest` 16개가 통과했다. Controller의 새 측정 시작 시 보정·곡선을 비우고 경로 확인 후 보정을 적용하는 연결도 이 변경에서는 바뀌지 않았다. 실제 USB 분리 시험을 이번에 했다는 뜻은 아니다.

요청서의 앱 시험 수 848개는 현재 대상과 다르다. `NoDevice` 시험 한 개가 추가된 대상의 기본 앱 시험 수는 **849개**다. 검토용 probe를 추가한 복사본에서만 850개다.

## 7. 파일과 재현 로그

공통 로그 위치: `build/independent-review/uis6-20260929/`.

| 증거 | 파일 |
|---|---|
| 최초 전체 실행 849개·1실패 | `baseline-validation.txt`, `initial-test-results/` |
| 같은 시험 두 개 무수정 재실행 | `stop-contract-retry.txt` |
| 시험 수정안 및 probe 포함 전체 실행 | `candidate-final-validation.txt`, `source/app/build/test-results/testReleaseUnitTest/` |
| 해제 전후의 결정적 관측 | 위 결과의 `TEST-kr.joa.selahrta.audio.DeferredReleaseIndependentProbe.xml` |
| 서명 실패·성공 | `guard-summary.txt`, `no-key-bundleRelease.txt`, `no-key-assembleRelease.txt`, `partial-key.txt`, `relative-key-bundle.txt` |
| AAB 자체 서명 검사 | `relative-aab-verify.txt`, `relative-aab-certificate.txt` |
| task 그래프 | `current-task-graph.txt`, `flavor-before-graph.txt`, `flavor-after-graph.txt`, `variant-no-flavor-graph.txt` |
| 기존 UI 정상·호출 제거 변이 | `ui-baseline.txt`, `ui-call-mutation.txt` |
| 기본 opener 무동작 변이 | `ui-opener-mutation.txt` |
| 보완 UI 정상 실행 | `ui-proposed-normal.txt` |

제안 patch 둘은 고정 커밋의 파일에 `git apply --check`로 적용 가능함을 확인했다. 제품 변경은 적용하지 않았으며, 검토용 변이는 복원했다. 새 결과와 기존 결과를 합쳐 실행 개수를 부풀리지 않았다.

최종 검토용 복사본은 제품 코드가 고정 커밋과 같고 시험 수정안·추가 시험만 들어 있다. 이 상태의 **JVM 850개 통과, debug APK·계측 APK 생성 성공, lint 오류 0 / 경고 11 / hint 4, 에뮬레이터 UI 7개 통과**를 확인했다. 이것은 제안 검증 결과이며, 최초 대상의 JVM 1건 실패를 소급하여 통과로 바꾸지는 않는다.
