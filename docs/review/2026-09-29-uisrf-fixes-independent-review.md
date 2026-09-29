# UISRF-01~03 수정본 독립 재검증

검토일: 2026-09-29 · 검토자: Codex

**판정: 승인 보류. Critical 0 / High 0 / Medium 1 / Low 2.**

앞선 UISRF-01~03의 원래 재현은 해결됐다. 다만 새 보정 평균이 약속한 3초보다 오래된 에너지를 포함하는 경계에서 잘못된 오프셋이 실제 저장된다. 아래 UISRFF-01이 이번 보류 사유다. 94dB 교정기 부재와는 별개이며 합성 PCM과 제품 코드로 재현했다. Low 두 건은 진단과 출시 안내의 수정 사항이다.

## 1. 고정한 대상과 확인 경계

- 요청: `2026-09-29-uisrf-fixes-reverification-request.md`, 추가 §8 포함.
- 기준: `8375c0fde0095924426cdf0d39bdaf6806ce602f`.
- 대상: **`9cbbdadc1aba582ef6bf0c031160c8a5411212f5`**. 핵심 수정 `a3db98d`, `52cf045`, 후속 정책 링크·시험·문서까지 확인했다. PR #55와 #57을 이 검토에 연결했다.
- 기준 대비 19파일 +1889/-107에는 이전 검토 산출물도 포함된다. 이를 모두 새 제품 코드로 세지 않았다.
- 시작 작업 트리는 clean. 제품 파일은 수정하지 않았다. `build/independent-review/uisrf-20260929/source`에 대상 커밋을 풀어 빌드했다. 별도 candidate 클래스에서만 제안 수정안을 실행했다.
- 이번 회차에 기기 설치, 실제 마이크/USB, 청음, 음향 교정은 하지 않았다. 요청서의 실기기 관측은 구현자 보고로 구분했다. 키 생성·서명·배포도 하지 않았다.

## 2. 기존 지적의 상태

| 기존 항목 | 이번 독립 확인 | 판정 |
|---|---|---|
| UISRF-01: 클리핑 뒤 시간가중 잔류로 잘못 저장 | 진폭 1.0 5초 → 0.01 3.1초. 실제 ViewModel 저장값 −43.010299927 dBFS, 충분히 지난 뒤 −43.010299911. 차이 약 1.63e−8dB | 원 재현 해결. 새 창 경계는 §3 |
| UISRF-01: 입력 없는 시간을 안정 구간으로 셈 | 큰 소리 5초 → 콜백 공백 3.1초 → 정상 20ms. `cleanMs=20`, Gate=Reject | 해결 |
| UISRF-02: 첫 입력 전 감시 null | 제품 ViewModel의 실제 감시 코루틴 실행. 첫 입력 없이 10초 → age=10000ms와 경고. 정상 입력 뒤 중단 1.6초 → age=1600ms, 회복 후 0 | 해결. 빈 콜백 반복은 §4 |
| UISRF-03: 옛 세션 근거 노출 | 옛 콜백을 RTA sink 안에서 barrier로 멈추고 stop/start, 새 세션 근거 생성 후 옛 콜백 재개. 새 근거의 객체·session=2 유지. stop 및 새 시작 직후에는 null | 해결 |
| 앞선 기록 시작 구간 경합 | I/O 완료 후 Main을 보류한 채 구간을 변경. Worship/미지정/Sermon 전환 세 경우의 실제 기록 이벤트 확인 | 회귀 없음 |

통합 probe는 실제 Controller·DSP·ViewModel 메서드·CalibrationStore를 사용한다. 하드웨어, 단조 시계와 Main dispatcher를 대체하고 Android ViewModel 생성자만 우회했다. 저장 객체뿐 아니라 DataStore 파일 생성도 확인했다. Android 서비스/Activity 배선과 AudioRecord의 join까지 검증했다는 뜻은 아니다.

## 3. UISRFF-01 — Medium — 보정용 3초 창이 지난 에너지를 추가로 포함한다

**위치**

- [CleanWindow.kt:96](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CleanWindow.kt:96): 3초 `leqShort` 생성·사용.
- [SplEngine.kt:97](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/SplEngine.kt:97): 기존 `RollingLeq` 사용.
- [TimeWeighting.kt:205](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/TimeWeighting.kt:205): 닫힌 bucket 30개에 `currentSum/currentCount`를 추가한다.

**논리와 재현**

기존 RollingLeq는 100ms 단위 근사다. 3초 설정에서 닫힌 30칸과 현재 미완성 칸을 함께 더하므로 평균 구간은 3초부터 약 3.1초까지 달라진다. 일반 계기의 기존 근사 구현을, 창 밖 기여가 0이어야 한다는 새 보정 계약에 그대로 연결했다.

1. 48kHz, 연속 위상의 1kHz 순음, 20ms 블록.
2. 진폭 **0.9**로 **5.02초** 입력한다. 클리핑이 아니므로 CleanWindow가 계속 찬 상태다.
3. 같은 순음을 진폭 **0.009**로 바꾸어 **3.02초** 입력한다.
4. 이때 실제 Gate=Save. 실제 `saveCalibration`으로 기준 94dB를 저장한다.

| 관측 | 대상 코드 | 제안 수정 격리본 |
|---|---:|---:|
| 저장에 쓰인 A값 | −25.651098104 dBFS | −43.925447977 dBFS |
| 작은 소리가 총 3.10초 이어진 뒤 값 | −43.925449773 | −43.925449771 |
| 저장 offset | 119.651098104 dB | 137.925447977 dB |
| 그 offset으로 후속 표시 | **75.725648331 dB** | **93.999998206 dB** |

차이가 화면 표시로만 끝나지 않고 저장값에 남는다. 40dB 변화 중 3초 밖의 큰 에너지가 아직 들어 있어 **약 18.27dB** 잘못 맞춰진다. 교정기 숫자 94는 저장 경로를 검증하기 위한 입력이지 실제 94dB 음장을 만들었다는 뜻이 아니다.

Z 가중으로 필터 과도를 제외하고 정확히 3초 뒤를 비교해도 44.1/48kHz에서 약 **18.27489dB**가 남는다. 별도 회귀 3개는 최근 N프레임 제곱합을 직접 계산하는 기준과 비교한다. 현 코드에서 **3/3 실패**, 제안에서 **3/3 통과**했다. 제안은 10~90ms bucket 위상, 혼합 블록, 두 표본율, A/C/Z 가중을 포함한다.

**책임 범위와 시험의 빈틈**

앞선 제가 제시한 수치 시제품도 같은 `leqShort`를 이용했다. 당시 3.1초 재현이 통과한 것으로 이 경계를 확인했다고 볼 수 없었다. 이번은 구현자가 제안을 따랐다는 사실만으로 닫을 수 없었던 검증 누락도 함께 수정하는 것이다.

또한 **A/C 필터를 통과한 최근 표본의 창**과 **원시 PCM의 과거 영향이 모두 0이라는 주장**은 구분해야 한다. A/C IIR 필터의 과도는 별도로 남는다. 정확히 레벨 변화 후 3.000초에는 정확한 창에서도 그 구간 안에 포함된 필터 과도가 있었다. 따라서 실제 저장 probe는 3.020초를 사용했고, 수학적 회귀는 같은 가중 필터 출력의 최근 N표본을 직접 합했다. 안정값과만 비교해서 정상 과도까지 결함으로 세지 않았다.

**사용자 영향:** 큰 소리 후 정상 순음으로 교정을 진행하는 짧은 시점에 저장 offset이 크게 틀어져 이후 SPL 전체에 지속 적용된다.

**권장 수정:** 보정 경로만 최근 `sampleRate × 3`개의 가중 표본 에너지를 보관·교체한다. 기존 live/기록 엔진의 bucket 근사는 기본값으로 유지한다. 단순히 대기 문턱을 3.1초로 늘리는 것은 이후 레벨 변화마다 다시 생기는 경계를 없애지 못한다.

**회귀 요구:** bucket 경계 전후·여러 위상, 44.1/48kHz, 혼합 block 길이, 클리핑/빈 블록/공백 후 재충전, A/C/Z 가중 출력 기준, 실제 Save→저장값→후속 SPL, live 지표 불변을 함께 유지한다.

## 4. UISRFF-02 — Low — 빈 콜백이 유효 입력 나이를 0으로 되돌린다

**위치:** [CaptureController.kt:166](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CaptureController.kt:166), [같은 파일:723](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/ui/CaptureController.kt:723).

새 코드는 빈 블록에서도 보정 근거를 갱신해 이전 값 저장을 막는다. 이 처리는 옳다. 그러나 `inputWaitAgeMs()`가 그 근거의 시각을 **마지막 유효 PCM 시각**으로 재사용한다.

정상 입력 3.2초 뒤 400ms 간격으로 `frames=0` 블록을 5회 넣었다. 실제 유효 입력은 **2초 전**인데 나이는 **0ms**였다. `cleanMs=0`이고 저장은 거절된다. 누적 부족·읽기 오류 경고도 이미 있으므로 모든 경고가 사라지거나 잘못 저장된다고 주장하지 않는다. 현재 멈춤의 지속 시간과 해당 경고가 틀어지는 진단 문제다.

**사용자 영향:** 빈 읽기가 반복되는 동안 최근까지 정상 PCM을 받은 것처럼 감시가 판단한다.

**권장 수정:** 세션에 `lastValidInputNs`를 따로 두고 `frames > 0`에서만 갱신한다. 빈 입력으로 보정 근거를 무효화하는 현재 처리는 유지한다. 제안 격리본에서 같은 재현은 **age=2000ms**, 입력 중단 경고로 바뀌었다.

**회귀 요구:** 첫 정상 입력 전 빈 블록 반복, 정상 입력 후 반복, 정상 입력 회복, 음수 오류 후 종료, 세션 교체를 나누어 검사한다. 이번 probe는 정상 후 반복·일반 첫 입력 전 중단·회복·세션 교체를 실행했다. 첫 입력 전 빈 블록만 반복하는 독립 시나리오는 추가 권장이다.

## 5. UISRFF-03 — Low — AAB를 만들고 다른 APK의 서명을 확인한다

**위치:** [release-signing.md:117](D:/Cowork/SELAH-RTA/docs/release-signing.md:117) 및 120~121행.

`bundleRelease` 산출물은 AAB인데 확인 명령은 `app-release.apk`를 대상으로 한다. APK가 없으면 실패하고, 옛 APK가 있으면 이번 업로드 파일과 무관한 서명을 검사한다.

**사용자 영향:** 이번에 만든 업로드 번들의 서명이 확인됐다는 잘못된 확신을 줄 수 있다.

**권장 수정:** AAB를 만들었다면 같은 실행에서 생성된 AAB를 검사하고, APK를 배포할 때만 별도로 `assembleRelease`와 `apksigner`를 사용한다. 예시(PowerShell, 저장소 루트):

```powershell
.\gradlew.bat :app:bundleRelease
& "$env:JAVA_HOME/bin/jarsigner.exe" -verify -verbose -certs `
  app/build/outputs/bundle/release/app-release.aab
& "$env:JAVA_HOME/bin/keytool.exe" -printcert -jarfile `
  app/build/outputs/bundle/release/app-release.aab
```

검증 성공, 서명된 엔트리, 인증서 SHA-256을 확인하고 Play Console의 **업로드 인증서**와 대조한다. 프로세스 종료 코드 0만으로 unsigned/경고 출력을 무시하면 안 된다. 실제 키로 위 명령을 실행한 것은 아니다. [Android 서명 문서](https://developer.android.com/studio/publish/app-signing), [JDK jarsigner 문서](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jarsigner.html).

같은 문서 10행의 「키를 잃으면 영영 갱신 불가」도 적용 대상을 한정해야 한다. Play App Signing의 별도 업로드 키는 재설정할 수 있다. 본문 61행에는 맞게 설명했으므로 도입부와 일치시키면 된다. [Android 키 분실 설명](https://developer.android.com/studio/publish/app-signing#reset_lost_or_compromised_private_upload_key).

**회귀/검증 요구:** 새 AAB만 남은 디렉터리에서도 절차가 끝나는지, 잘못된 인증서·서명 없는 번들을 거절하는지 확인한다. 키 없는 개발 PC의 debug 빌드는 허용하되 release 패키징에는 네 서명 설정과 실제 키 파일을 검사해 명시적으로 실패시키는 것을 권한다. `signingConfig`를 지정했다는 사실만을 누락 시 차단의 시험 결과로 대신하지 말아야 한다.

## 6. 요청서 질문에 대한 답

1. **3초와 SNR:** 유한 창은 시간가중의 긴 잔류와 별개로 정의할 수 있지만, 안정된 음원·정확한 기준 레벨·충분한 SNR까지 보장하지 않는다. 정상적인 비상관 배경 잡음이라도 상대 에너지로 생기는 편향은 `10 log10(1 + 10^(−SNR/10))`: SNR 10dB에서 약 0.414dB, 20dB에서 약 0.043dB다. 시간을 길게 평균 내도 이 편향은 없어지지 않는다. 숫자 3초만으로 음향 정확성을 선언하지 말고, 알려진 안정 순음 조건을 유지한다.
2. **공백 500ms:** 보정 근거의 나이와 연속 캡처 공백은 서로 다른 계약이다. 같은 숫자를 쓰는 것이 곧 결함은 아니지만 별도 상수·회귀로 관리해야 한다. 500ms 이하의 모든 누락을 검출하거나 시간축 연속성을 보증하는 것은 아니다. 현재 정수 ms 나눗셈 때문에 500~501ms 사이 처리도 정확한 실수 freshness 검사와 조금 다르므로 경계 시험을 추가하는 편이 좋다.
3. **live/기록 영향:** 제품 Controller에 클리핑·레벨 변화가 포함된 PCM 500블록을 넣고 별도 live MultiWeightEngine과 대조했다. 실제 방출 125개의 A/C/Z current·MAX·Peak·Leq 등을 담은 프레임이 모두 동일했다. 제안 격리본에서도 동일했다. 기록 구간 이벤트도 세 경우 확인했다. 모든 녹음 PCM/타임라인 행의 전체 파일 동등성이나 장시간 기기 부하는 이번에 확인하지 않았다.
4. **Slow 고정:** 보정은 `currentDbfs`가 아닌 `leqShortDbfs`를 취하므로 지수 Fast/Slow가 이 값에 개입하지 않는다. 실제 Controller 설정을 바꾼 probe에서도 동일했다. 두 표본율·비정상 길이 시험은 이번 추가분과 별도 경계 시험으로 확인했다.
5. **옛 callback의 입구 guard:** 입구를 통과한 후 멈춘 옛 callback은 guard만으로 막지 못한다. 현재는 근거·CleanWindow가 세션 소유이고 getter가 소유자를 재확인하므로 새 세션 근거를 덮지 않았다. 실제 그 순서를 barrier로 고정했다.
6. **정책 링크 문자열 시험:** URL 오타를 찾는 보조 시험으로는 유효하다. 다만 화면에서 `PolicyLinks(...)` 호출을 삭제하는 변이를 격리 소스에 넣어도 **5개 모두 통과**했다. 공개 주소 두 곳은 이번에 독립 HTTP GET으로 200을 확인했고, 현재 버튼의 URL·ACTION_VIEW·실패 표시 배선도 읽었다. 기기 클릭은 확인하지 않았다. 두 버튼 클릭 시 목적 URL, 런처 실패 시 주소 표시를 검증하는 Compose 시험을 추가한다. URL/런처를 주입하면 테스트하기 쉽다. ActivityScenario만이 유일한 방법은 아니다.
7. **실기기 손뼉 보고:** 현재 저장 버튼 `enabled`는 `currentDbfs != null`이므로, 3초 동안 「버튼을 누를 수 없다」보다는 「저장 요청을 Gate가 거절한다」가 코드에 맞다. 실제 구현의 저장 방어는 확인했다. 손뼉 사진의 두 숫자 차이는 기준 음압의 정답을 입증하지 않으며 19.7→10→6.7→8.3은 단조 감소도 아니다.

## 7. 실행한 시험과 제안 코드

### 대상 커밋의 정규 빌드

JDK: Android Studio 내장 JBR. 첫 빌드의 cache 결과를 독립 실행 수로 세지 않고 다음을 다시 돌렸다:

```powershell
.\gradlew.bat --offline --no-build-cache --rerun-tasks :dsp:test :app:testDebugUnitTest
```

- **DSP 616 + app 847 = 1,463개, 실패/오류/건너뜀 0.** XML 집계. 29 tasks 모두 executed.
- 별도 수동 JVM 부분집합 실행은 위 수에 더하지 않았다.
- `:app:assembleDebug` 성공, debug APK 생성(11,653,604 bytes). 설치/기기 실행은 하지 않았다.
- **`:app:lintDebug` 실패:** error 1 / warning 11 / hint 4. [SessionExport.kt:42](D:/Cowork/SELAH-RTA/app/src/main/java/kr/joa/selahrta/recording/SessionExport.kt:42)의 문자열 안 실제 BOM 문자에 대한 `ByteOrderMark`. 기준 커밋에도 있고 이번 범위에서 변경되지 않아 새 회귀로 세지 않았다. `private const val UTF8_BOM = "\uFEFF"`로 바꾸면 의미를 유지하며 소스의 숨은 문자를 없앨 수 있다. 현재 대상에 대해 **전체 build+lint 통과라고 보고할 수 없다.**

### 수정 제안과 검증

- [수정 제안 patch](D:/Cowork/SELAH-RTA/docs/review/2026-09-29-uisrf-window-liveness-proposed.patch): UISRFF-01/02. 6개 파일의 한정 변경. 제품에는 적용하지 않았다. `git apply --check` 성공.
- [DSP 독립 회귀 3개](D:/Cowork/SELAH-RTA/docs/review/2026-09-29-uisrf-exact-window-regression.kt): 제품에서 3개 실패, 제안에서 3개 통과. 정규 테스트 경로에 `IndependentExactWindowTest.kt`로 옮겨 실행할 수 있다.
- [통합 probe 원본](D:/Cowork/SELAH-RTA/docs/review/2026-09-29-uisrf-independent-probe.kt): 실제 저장, 공백, 세션 경합, live 지표, 기록 이벤트, 감시를 재현한다. 알려진 결함이 관측됨을 check하는 **probe**이므로 실행 성공 자체가 제품 합격은 아니다. 제안 비교 시 `expect.window.fixed=true`, `expect.valid.clock.fixed=true`, 기존 해결 검사는 `expect.safety.fixed=true`를 사용했다.
- 제안 DSP와 기존 CleanWindow/TimeWeighting/SplEngine/MultiWeightEngine 회귀 및 독립 회귀: **54개 통과**. 제안의 Controller/ViewModel도 별도 컴파일하고 같은 통합 probe를 실행했다. 제안 전체에 대한 Android APK/lint 빌드를 했다는 뜻은 아니다.
- 패치는 live 기본 계산을 바꾸지 않고 CleanWindow만 exact short window를 선택한다. 최근 제곱값의 ring과 보상 합산을 쓰며 clip/gap reset 때 버퍼를 재사용한다. 48kHz A/C/Z 세 창의 추가 double 버퍼는 총 **3,456,000 bytes(약 3.30MiB)**. 기기 CPU/GC·2시간 성능은 아직 측정하지 않았다.

로그와 실행 환경은 `build/independent-review/uisrf-20260929/`에 남겼다. `gradle-tests-fresh.txt`, `gradle-full-results.txt`, `probe-output.txt`, `candidate-probe.txt`, `current-exact-failures.txt`, `candidate-dsp-tests.txt`, `policy-link-mutation.txt`를 구분해 보관했다.

승인 전 필요한 핵심 작업은 UISRFF-01의 정확한 창과 실제 저장 경로 회귀를 제품에 반영하는 것이다. 제안 코드는 그 경로에서 재현을 해소했지만 제품 반영·정규 빌드 확인을 대신하지 않는다. 그 이후에도 실제 교정기 정확도, USB/기기 동작, release 서명·Play 배포 검증은 별도 범위로 남는다.
