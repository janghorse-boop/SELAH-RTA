# UISRFFF 수정 독립 재검증 — 6회차

- 검토일: 2026-09-29
- 요청서: `2026-09-29-uisrfff-fixes-reverification-request.md`
- 기준: `1cf5e9f`
- 검증한 대상: **`852b862ed96e88554af0cd2c97185062cbdd5ee7`**. 요청서의 구현 커밋 `065f806`, `28304de`와 후속 문서를 포함한 병합 결과다.
- 방법: 해당 커밋을 별도 폴더에 `git archive`로 풀어 실행했다. 구현자의 성공 보고는 독립 실행 결과와 구별했다.

## 1. 판정

**기존 코드 승인 유지. Critical 0 / High 0 / Medium 0 / Low 3.**

이전 UISRFFF-01 문서 수정은 해결됐다. 자체 서명·신뢰 체인 경고만으로 정상 AAB를 거절하지 않는 설명이 맞으며, 이번에는 임시 시험 키로 실제 APK/AAB를 만들어 확인했다. 현재 AGP의 최종 release 생성 경로에서 서명 설정 누락을 우회하는 경로는 발견하지 못했다.

남은 세 건은 정상 상대 경로의 오거절, release 단위 시험까지 막는 빌드 범위, 실제 설정 화면 호출을 덮지 못하는 회귀 시험이다. 현재 제품에서 링크가 사라졌다는 발견은 아니다. 이 세 건 때문에 승인 보류로 되돌리지는 않는다.

이 판정은 고정한 커밋의 코드에 관한 것이다. 실제 업로드 키·Play Console 등록·기기에서의 신규 화면 시험·음향 정확도·2시간 실기기 성능까지 승인했다는 뜻은 아니다. DSP와 캡처 제어 코드는 이번 범위에서 바뀌지 않아 재심사하지 않았다.

검증 도중 다른 작업의 변경이 `9ef7d68`로 커밋되어 작업 폴더의 HEAD가 이동했다. `CHANGELOG.md`, `InputDevices.kt`, `MeasureScreen.kt`, `InputDevicesTest.kt`의 그 후속 변경은 검증한 `852b862`에 포함되지 않는다. 보존했으며, 이 보고서의 승인 범위에서도 제외한다. 제품 소스에는 수정안을 적용하지 않았다.

## 2. 독립 실행 결과

실행 환경: Windows / Android Studio JBR / 저장소 Gradle 8.14.3 / AGP 8.13.0. 산출물과 로그는 `build/independent-review/uisrfff-20260929/`에 남겼다. 이 폴더는 검토용이며 Git에 넣을 배포 산출물이 아니다.

### 대상 커밋 그대로 실행

```powershell
.\gradlew.bat --offline --no-build-cache --rerun-tasks `
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug `
  :app:compileDebugAndroidTestKotlin
```

| 검증 | 독립 결과 |
|---|---|
| 앱 debug JVM 시험 | **848개, 실패 0 / 오류 0 / 생략 0** |
| Debug APK | 생성 성공 |
| Lint | **오류 0**, 경고 11, hint 4 |
| 새 Compose 시험 4개 | 컴파일 성공. 기기 실행은 이번 검토에서 하지 않음 |
| 서명 속성 전부 없음 | `verifyReleaseSigning`이 빠진 네 이름을 알리고 실패 |
| 파일·alias만 있음 | 빠진 두 비밀번호 속성 이름을 알리고 실패 |
| 네 속성은 있지만 키 파일 없음 | 파일 없음으로 실패 |
| 정상 임시 키의 `bundleRelease` + `assembleRelease` | 모두 성공 |
| 틀린 키 비밀번호로 `signReleaseBundle --rerun` | `Cannot recover key`로 실패 |
| 틀린 저장소 비밀번호로 같은 task 실행 | `password was incorrect`로 실패 |
| 생성 APK의 `apksigner verify --print-certs` | 종료 코드 0, 시험 키 지문 일치 |
| 생성 AAB의 `jarsigner -verify -verbose -certs` | 종료 코드 0, `jar verified.`, 서명된 payload 확인 |
| AAB의 `keytool -printcert -jarfile` | 시험 키 지문 일치 |

임시 키는 검토자가 만든 **유효기간 2일짜리, 공개된 시험용 비밀번호의 폐기용 키**다. 실제 업로드 키를 읽거나 만들지 않았다. 시험 APK/AAB를 기기에 설치하거나 배포·업로드하지 않았다.

키·APK·AAB에서 일치한 SHA-256:

```text
02:2C:A6:D0:17:88:CE:87:E2:24:69:3C:F0:B7:D5:9D:F2:F9:D7:40:C9:3C:11:A0:8B:A6:3C:21:56:CD:D0:47
```

AAB 검사에는 실제로 self-signed 및 PKIX 신뢰 경로 경고가 함께 나왔다. 서명 검증·동일 키 지문 확인과 JVM의 신뢰 체인 확인을 구별해야 한다는 문서 수정의 근거다. 실제 배포에서는 이번 임시 지문이 아니라 등록된 업로드 인증서와 대조해야 한다. [Android 공식 앱 서명 문서](https://developer.android.com/studio/publish/app-signing)

**그 밖의 경고도 남겼다.** 시험 키의 짧은 유효기간·timestamp 부재·POSIX 속성 경고와 `JarFile`/`JarInputStream` 읽기 차이 경고가 나왔다. 마지막 경고는 추가 probe로 확인했다. ZIP의 136항목 중 manifest가 맨 마지막(index 135)에 있었고, `JarFile`로 끝까지 읽은 payload 133개 모두 서명자가 있으며 unsigned payload는 0이었다. 중앙 디렉터리와 순차 ZIP 읽기의 모든 항목 이름·내용 SHA-256도 일치했다. `JarInputStream`의 서명 검증은 manifest가 앞에 오는 순서를 요구하므로 이 순서 차이와 관측이 일치한다. 따라서 이를 “payload가 실제로 무서명”이라고 판정하지 않았으며, 반대로 **경고가 전혀 없었다거나 Play가 수락했다고도 주장하지 않는다.** 로그: `aab-archive-probe.txt`, 코드: 같은 폴더 `ArchiveSignatureProbe.java`. [JDK 공식 JarInputStream 설명](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/jar/JarInputStream.html)

DSP 시험은 이번에 다시 실행하지 않았다. 과거 620개와 이번 앱 848개를 합쳐 **“이번에 1,468개를 독립 실행했다”라고 표현하면 안 된다.**

### 별도 수정안 복사본에서 실행

| 검증 | 결과 |
|---|---|
| 동일한 정상 상대 키 경로 | 검증 task 통과 |
| 키 없이 `:app:testReleaseUnitTest` | **848개 전부 통과** |
| 실제 `SettingsScreen`을 여는 추가 시험 | `compileDebugAndroidTestKotlin` 성공. 기기 실행 미확인 |
| 키 없이 `:app:bundleRelease` | 검증 task에서 거절 — 차단 유지 |
| 최종 APK/AAB task 의존 그래프 | 검증 task에 도달함 |
| release classes/resources 및 단위 시험 그래프 | 검증 task에 의존하지 않음 |

수정안 시험과 대상 커밋 그대로의 시험을 섞지 않았다. 수정안의 release 848개는 같은 앱 시험의 다른 변형 실행이며, 새로 추가된 848개 시험이 아니다.

## 3. 발견 사항

### UIS6-01 — 정상 상대 키 경로를 검증 task가 거절함

- **Severity: Low**
- **위치:** `app/build.gradle.kts:38,151`
- **근거:** 실제 signing config는 `file(signStore)`로 app 프로젝트 기준 상대 경로를 해석한다. 검증 task는 `File(storePath)`로 다른 기준에서 존재 여부를 확인한다.
- **재현:** 별도 검증 폴더의 `source/app`에서 `../../fixture/review-only.jks`가 가리키는 파일을 만들고 네 속성을 넘겼다. signing config 관측은 `exists=true`와 올바른 절대 경로를 출력했지만, `verifyReleaseSigning`은 “키 파일이 없습니다”로 실패했다. 로그: `guard-relative-path.txt`.
- **사용자 영향:** 문서의 절대 경로 예제는 정상이다. 프로젝트 기준 상대 경로를 쓰는 개발자·CI는 유효한 키가 있어도 release를 만들지 못한다. 잘못된 키를 허용하는 보안 우회는 아니다.
- **권장 수정:** 두 경로를 동일한 `Project.file(...)` 또는 공통으로 계산한 파일 객체로 해석한다.

```kotlin
val fileMissing = storePath != null && !file(storePath).isFile
```

- **수정안 확인:** 위 한 줄 변경으로 같은 재현이 통과했다(`candidate-relative-path.txt`).
- **필요한 회귀 시험:** 정상 절대 경로, 정상 app 기준 상대 경로, 없는 파일, 누락된 속성, 키 없는 debug 구성. 단순 문자열 검사가 아니라 Gradle을 실제 실행해 성공·실패를 확인한다.

### UIS6-02 — 패키징 차단이 release 단위 시험까지 키를 요구함

- **Severity: Low**
- **위치:** `app/build.gradle.kts:167-172`
- **근거:** `startsWith("bundleRelease")`가 최종 번들뿐 아니라 `bundleReleaseClassesToCompileJar`, `bundleReleaseClassesToRuntimeJar`에도 걸린다. `packageReleaseResources` 역시 넓은 prefix에 포함된다. 이들은 서명과 무관한 release JVM 시험의 선행 작업이다.
- **재현:** 키 속성을 주지 않은 `:app:testReleaseUnitTest`가 시험에 도달하기 전에 `verifyReleaseSigning`에서 실패했다(`release-test-no-key.txt`). task 의존 그래프에서도 같은 경로를 확인했다.
- **사용자 영향:** 현재 debug 개발은 가능하지만, release 변형의 단위 시험·일부 공통 검증 경로까지 서명 비밀값이 있어야 실행된다. “release 패키징만 막는다”는 주석보다 범위가 넓다.
- **권장 수정:** 현 AGP에서 최종 패키징·서명 task를 정확하게 선택한다. flavors/AGP 변경 때 이 목록을 다시 검증하거나 variant 기반 설정으로 옮긴다. 검증을 최상위 `assembleRelease.doFirst`로 되돌리면 안 된다.
- **수정안 확인:** 첨부 patch를 별도 복사본에 적용한 뒤 키 없이 release 단위 시험 848개가 통과했고, 키 없는 `bundleRelease`는 계속 실패했다. 최종 `packageRelease`, `packageReleaseBundle`, `packageReleaseUniversalApk`, `signReleaseBundle`의 검증 의존성도 확인했다.
- **필요한 회귀 시험:** 키 없이 debug/release JVM 시험은 성공, 최종 APK/AAB 생성과 직접 package/sign task 호출은 실패. 정상 시험 키는 최종 생성·서명 검증까지 통과해야 한다.

### UIS6-03 — 실제 설정 화면에서 앱 정보 호출을 빼는 회귀는 아직 잡지 못함

- **Severity: Low (시험 보장 범위)**
- **위치:** `app/src/androidTest/java/kr/joa/selahrta/ui/screens/SettingsPolicyLinksTest.kt:24-42`; 실제 연결은 `app/src/main/java/kr/joa/selahrta/ui/screens/HistorySettingsScreens.kt:445`
- **근거:** 새 시험은 `Column { AppInfoWithPolicyLinks(...) }`를 직접 만든다. 나머지 세 시험은 `PolicyLinks(...)`를 직접 만든다. 어느 것도 실제 `SettingsScreen`을 실행하지 않는다.
- **재현:** 소스 시험이 읽는 별도 fixture에서 실제 설정 화면의 `AppInfoWithPolicyLinks()` 호출만 `// AppInfoWithPolicyLinks()`로 바꿨다. 현재 `PolicyLinkTest` **6개가 모두 통과**했다(`policy-outer-comment.txt`). 새 Compose 네 시험도 이 호출을 거치지 않는 구조다. 단, 이 변이를 넣은 Compose 시험을 기기에서 실행했다고 주장하지는 않는다.
- **사용자 영향:** 현재 커밋에는 정상 호출이 존재한다. 그러나 향후 호출이 주석 처리되어 사용자가 설정에서 방침에 접근하지 못하게 되어도 기존 검증이 놓칠 수 있다. “둘이 함께 네 가지 변이를 모두 막는다”는 KDoc의 보장은 여전히 과하다.
- **권장 수정:** 부품 시험은 유지하고, 실제 `SettingsScreen`에 기본 `CaptureUiState()`와 no-op 콜백을 넣어 두 링크까지 스크롤하고 표시를 확인하는 시험을 추가한다. 이 화면의 인자는 이미 상태와 콜백이며, 이를 위해 실제 캡처나 DataStore를 열 필요는 없다.
- **제안 코드 확인:** `2026-09-29-uisrfff-settings-screen-proposed-test.kt`를 실제 androidTest 경로에 넣은 별도 복사본에서 컴파일 성공. **기기에서 성공/변이 실패까지는 미검증**이므로 다음 기기 실행에서 정상 통과와 바깥 호출 주석 변이 실패를 각각 확인해야 한다.
- **필요한 회귀 시험:** 실제 화면의 호출 삭제·주석 처리, 개인정보 주소를 약관 주소로 바꿈, 실패 안내 상태 갱신 제거. 어느 연결 단계의 변이를 넣었는지 기록한다.

## 4. 요청서의 질문에 대한 답

### 서명 차단과 task 이름

현재 대상의 의존 그래프에서 최종 `packageRelease`, `packageReleaseBundle`, `packageReleaseUniversalApk`는 검증 task를 거친다. 이름이 prefix에 직접 맞지 않는 `signReleaseBundle`도 `packageReleaseBundle`을 통해 검증 task에 의존한다. 따라서 현재 AGP·variant에 대해 **“설정 누락이 최종 release 생성을 막는다”는 계약은 확인됐다.**

이름 넷만 보고 보장한 것이 아니라 실제 task 그래프와 정상·비정상 키 실행을 함께 봤다. 지금의 문제는 빠진 최종 경로보다 너무 넓게 잡은 중간 경로다. 향후 AGP/variant 변경과 의도적인 `-x verifyReleaseSigning` 같은 검증 제외까지 막는 보안 경계는 아니다.

`verifyReleaseSigning` 자체는 속성과 파일 존재 여부만 검사한다. 암호·키 유효성은 실제 서명 작업에서, 배포할 인증서의 정체는 산출물 지문과 Play 등록값 대조에서 확인해야 한다. “이 task 하나가 성공하면 배포 가능한 올바른 키”라고 문서화하면 안 된다.

### Compose 시험의 CI 위치

일반 호스트 검증에는 JVM 시험·빌드·lint와 `compileDebugAndroidTestKotlin`을 둔다. 별도의 emulator/device 작업에서 `connectedDebugAndroidTest`를 실행하고, UI를 바꾸는 PR 및 배포 전 검증에 그 결과를 요구하는 구성을 권한다.

기기가 없는 기본 명령에 무조건 넣어 모든 호스트 검증을 실패시키거나, 반대로 컴파일만 하고 화면 시험 통과로 기록하는 두 방법은 피한다. 자동화 전에는 사람의 기기 실행도 증거가 되지만, **커밋·기기/API·실행 개수·실패 수·변이 위치**를 남겨야 한다. 이번 구현자의 SM-S918N 4개 통과 보고는 참고 증거이고, 검토자의 독립 기기 실행은 아니다.

### `AppInfoWithPolicyLinks` 분리

앱 정보와 방침 링크는 함께 표시되는 작은 UI 묶음이므로 분리 자체는 타당하다. 제품 경계를 되돌릴 필요는 없다. 다만 그 부품을 시험하는 것으로 설정 화면과의 연결까지 검증했다고 이름과 설명을 넓히면 안 된다. 부품 시험 이름은 `AppInfoPolicyLinksTest`처럼 맞추고, UIS6-03의 실제 화면 시험을 한 개 보태면 된다.

## 5. CPU 표본에서 말할 수 있는 범위

원시 trace를 수집하거나 실기기 부하를 이번에 다시 측정하지 않았다. 다음은 요청서의 여섯 수치에 대한 해석이다.

- ON 평균은 약 **86.0%**, OFF 평균은 **97.6%**다. 이 짧은 관측에서 ON의 앱 전체 CPU 평균이 더 높지는 않았다.
- 세 표본씩이고 실행 순서·온도·CPU 주파수·백그라운드 부하·짝지음 조건을 확인하지 못했다. 편차가 평균 차보다 크다는 이유만으로 **비용 0, 동등성, 비용 상한**을 입증할 수 없다. “링 비용이 앱 CPU에 묻힌다”도 가능한 해석이지 검증된 원인은 아니다.
- 192 kHz USB의 과거 88~102%와 48 kHz 내장의 현재 수치를 비교해 원인이 같거나 창과 무관하다고 결론 낼 수 없다.
- 한 시점 PSS 183,493 kB로 링의 3.3 MiB를 분리 검증하거나 누수·장시간 안정성을 판정할 수 없다.

문구 제안:

> 같은 기기·입력에서 20초씩 세 번 관측한 앱 전체 CPU는 ON 평균 86.0%, OFF 평균 97.6%였다. 이 짧은 관측에서는 ON의 증가를 관찰하지 못했다. 표본과 통제 조건이 제한되어 정확 창의 추가 비용이나 동등성을 판정하지 않는다. p95/p99, GC pause, read 누락 및 2시간 부하는 미측정이다.

다음 측정은 warm-up 뒤 ON/OFF 순서를 번갈아 바꾸고 같은 입력·녹음 상태·화면 상태를 유지한다. 각 구간의 실제 길이, 온도/주파수, 처리 시간 p95/p99, GC pause, read 누락을 함께 남긴다. 2시간 시험은 별도로 수행한다. 여기서 새 임계값을 임의로 정해 통과시키지는 않는다.

## 6. 제공한 수정안과 증거

- **빌드 수정안:** `2026-09-29-uisrfff-signing-guard-proposed.patch` — UIS6-01/02. 대상 커밋에 `git apply --check` 성공. 별도 복사본에서 위의 실행 결과를 확인했다.
- **실제 화면 시험안:** `2026-09-29-uisrfff-settings-screen-proposed-test.kt` — UIS6-03. 컴파일 확인, 기기 실행은 남음.
- **대상 빌드:** `debug-verification.txt`, `source/app/build/test-results/testDebugUnitTest/`, `source/app/build/reports/lint-results-debug.txt`.
- **서명:** `guard-none.txt`, `guard-partial.txt`, `guard-missing-file.txt`, `signing-valid.txt`, `signing-wrong-key-password.txt`, `signing-wrong-store-password.txt`, `signing-restored-valid.txt`, `aab-verify.txt`, `aab-certificate.txt`, `apk-verify.txt`, `key-certificate.txt`.
- **발견 재현:** `guard-relative-path.txt`, `release-test-no-key.txt`, `task-graph.txt`, `policy-outer-comment.txt`.
- **수정안 확인:** `candidate-relative-path.txt`, `candidate-validation.txt`, `candidate-task-graph.txt`, `candidate-no-key-bundle.txt`.

위 로그의 공통 위치는 `build/independent-review/uisrfff-20260929/`다. 시험 키와 시험 서명 산출물은 이 검증에만 사용한다. 실제 배포 자료로 재사용하지 않는다.
