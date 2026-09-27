# CFRC-01·CFRC-02 수정분 독립 재검토

검토일: 2026-09-27 · 검토자: Codex

**판정: CFRC-01의 확인 대상 연결과 CFRC-02의 기존 단일 형식 반례는 해결 확인. CAL 확인 기능의 전체 승인은 부분 보류 — Critical 0 / High 0 / Medium 1 / Low 1.**

남은 Medium은 새 `ConflictingSign` 예외가 단위·수량 오류까지 허용하는 조합이다. Low는 저장소 재시작 시험의 검증 범위다. 이전에 해결한 시간 순서 High의 판정을 번복하지 않는다. 기준 소음계·94dB 교정기가 없어서 보류한 것이 아니다.

## 1. 고정 범위와 검증 경계

- 요청서: `docs/review/2026-09-27-cfrc-fix-rereview-request.md`.
- 대상: **c7755e21b9faa0198386a4fc6a61a21c34d06336**, 해당 수정 커밋의 9파일 +551/-33 및 연결 경로.
- 직전 검토 대상은 `c275823`이다. 두 커밋 사이에는 별도 마이크 위치 DB 작업도 있으므로, 그 전체 범위를 승인했다고 해석하면 안 된다.
- `git archive` 사본에서 검토·실행했다. 시작 HEAD는 `2fe0527`, 후반 확인 HEAD는 `a72ee08`(PR #17 merge)이었다. 후반에는 CurveReadingGate, CaptureViewModel, CurveCard, CurveStoreConfirmationTest의 별도 작업 변경도 관찰됐다. 마지막 조회 HEAD는 `ae755ce`였고 작업 트리는 깨끗했다. 이 후속 변경들은 본 판정에 포함하지 않는다.
- Production 코드·설정·사용자 파일·브랜치는 변경하지 않았다. 수정안은 별도 사본 및 패치다.
- 요청서의 주장과 질문은 검증 대상으로 취급했다. 요청서의 BUILD SUCCESSFUL·실행 수를 독립 실행 결과로 대신하지 않았다.

| 독립 실행 | 결과 |
|---|---|
| 고정 대상 DSP JUnit | **564건 통과**, 75.741초 |
| 고정 대상 앱 교정 관련 JUnit, 18개 클래스 | **277건 통과**, 최종 9.367초 |
| 원본 CurveStoreConfirmationTest | **13건 통과**, 9.290초 |
| 추가 회귀 5건 + 기존 저장소 13건, 원본 코드 | **18건 중 4건 실패** — 새 조합 반례를 실제 검출 |
| 제안 CalibrationSign 사본 + 같은 18건 | **18건 통과** |
| 제안 사본으로 기존 DSP / 앱 관련 시험 | **564건 / 277건 통과**, 83.621초 / 9.278초 |
| 실제 CurveStore + DataStore 독립 probe | 기존 수정 확인, 새 조합 우회 재현, 제안 사본에서 차단 |
| 별도 JVM 재실행 | 확인 규약·활성 상태·부호 변환 유지 확인 |

Kotlin 2.2.20, JBR 21, JVM target 17을 사용했다. DSP harness는 coroutines 1.8.0, 앱/저장소 harness는 1.9.0이다. 저장소 시험은 실제 DataStore 1.1.1과 실제 파일을 쓰며 Application의 filesDir/applicationContext만 대역으로 제공한다.

앱 선택 시험의 ActiveCurve는 원본 선언을 별도 컴파일 단위로 추출했다. 제안 클래스를 처음 겹쳐 실행할 때 이전 harness의 축약 ActiveCurve 선언과 생성자 서명이 달라 **7건의 NoSuchMethodError**가 발생했다. 현재 대상의 원본 선언으로 다시 추출·컴파일한 뒤 원본/제안 각각 277건을 재실행해 통과했다. 이 7건은 제품 결함으로 세지 않는다. 실제 Store 통합 시험은 처음부터 원본 CurveStore의 ActiveCurve를 컴파일했다.

전체 Gradle assemble/lint, APK, Compose 화면, AudioRecord/실제 음향 측정은 이번에 실행하지 않았다. 위 수를 구현자의 ‘687건’과 동등한 실행 집합이라고 주장하지 않는다.

## 2. 해결 확인한 것

### CFRC-01 — 화면의 파일과 승인할 파일의 연결

`CurveConfirmationToken(key, sourceSha, rulesVersion)`이 save/watch → ActiveCurve → CurveCard → 화면 콜백 → ViewModel → Store로 전달된다. Store는 당시 파일의 해시와 토큰을 대조한다. ViewModel의 `confirmCurveReading`은 현재 입력 키가 token.key와 다르면 먼저 반환한다.

실제 저장 API로 A를 표시한 뒤 같은 키에 B를 저장하고 A 토큰으로 확인했다:

```text
STALE_CONFIRM shown=A.cal current=B.cal approved=false reading=Response
```

규칙이 다른 토큰, 확인 이후 파일 바이트 교체, 다른 채널의 확인 재사용도 거절/무효화됐다. UI 콜백 전달과 ViewModel 입력 검사는 정적으로 확인했으며 실제 화면 터치나 ViewModel 실행 시험으로 확인한 것은 아니다.

### CFRC-02 — 기존 네 단일 형식

`Phase`, `Amplitude (Pa)`, `Frequency (kHz)`, `Magnitude (linear)`은 가져오기 후 비활성이고, 읽기 규약 선택과 일반 켜기로도 적용되지 않는다. UI는 readingUnsupportedKo가 있으면 ‘지원하지 않는 형식’을 표시하고 확인 버튼을 만들지 않는다.

정상 응답 파일, 선언 없는 Unknown 파일, 순수한 부호 충돌 파일은 각각 기존 계약을 유지한다. Correction 확인 후 원본 +3dB가 응답 표현 -3dB로 한 번만 변환되며, 껐다 켜기 및 **새 JVM**에서도 유지된다.

이전 CFRF-01의 일반 스위치 우회, 기존 on 키가 없는 활성 상태, 여덟 번째 머리글 손실 반례도 이번 실행에서 다시 차단됐다.

## 3. CFRC-R01 — Medium: 부호 충돌 예외가 다른 단위·수량 오류를 가린다

**위치 — 모두 c7755e2 기준**

- `dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:150–158`: 여러 선언 중 첫 Unsupported만 선택한다.
- 같은 파일 `:192–211`: 부호 충돌을 발견하면 뒤의 괄호를 검사하기 전에 반환한다.
- 같은 파일 `:239–244`: 괄호 문자열에 sign 단어가 포함되면 나머지 문자를 확인하지 않는다.
- 결과 적용: `app/src/main/java/kr/joa/selahrta/calibration/CurveReadingGate.kt:155–182`의 `fixableBySign` 예외.

**재현**

아래 머리글 뒤에 동일한 데이터 `100,3,0 / 1000,0,0 / 20000,0,0`을 붙이고, 실제 Store의 save → confirmReading(token, Correction) → watch를 호출했다.

| 머리글 | 원본 판정 / 실제 확인 후 활성 |
|---|---|
| `Frequency (Hz),Response (dB) (correction) (Pa)` | ConflictingSign / **true** |
| 위 괄호 순서만 `(Pa) (correction)`으로 교체 | Unit / false |
| `Frequency (Hz),Response (dB) (correction)` 다음 줄에 `Frequency (kHz),Response (dB)` | ConflictingSign / **true** |
| 위 두 줄 순서 반전 | Unit / false |
| 부호 충돌 선언 다음 줄에 `Frequency,Phase,SPL` | ConflictingSign / **true** |
| 위 두 줄 순서 반전 | Quantity / false |
| `Frequency (Hz),Response (dB) (correction Pa)` | ConflictingSign / **true** |
| `Frequency (Hz),Response (response Pa)` | Second(Response) / **true** |

마지막 사례는 명시적 확인 이전의 자동 판정도 Response다. 또한 부호 충돌 선언과 정상 응답 선언이 서로 다른 두 줄로 있으면 요청서에서 약속한 Ambiguous 거절 대신 ConflictingSign 확인을 허용한다.

**논리와 사용자 영향**

`ConflictingSign`은 ‘방향 이외에는 지원되는 Hz/dB 형식임을 확인했다’는 뜻이어야 한다. 현재는 ‘먼저 발견한 문제가 부호 충돌이었다’는 뜻이 된다. 따라서 단위나 열 의미를 바꾸는 숫자가 사용자의 부호 선택만으로 실제 RTA 주파수 보정에 적용된다. 순서가 달라도 같은 형식 오류는 같은 거절 결과여야 한다.

별개의 세 결함으로 부풀리지 않고, **부호만 고치면 된다는 예외의 증명 부족** 한 건으로 분류한다. 실제 파일 사용 빈도는 검증하지 않았고, 악성 파일을 전제로 한 보안 취약점 주장도 아니다.

**권장 수정**

1. 모든 괄호를 검사한 뒤에만 부호 충돌을 반환한다. 단위 오류는 즉시 거절하되 부호 충돌은 누적한다.
2. 여러 선언에서는 부호로 풀 수 없는 Unsupported가 하나라도 있으면 그 사유를 우선한다. 그 외 서로 다른 선언이 여럿이면 Ambiguous로 거절한다.
3. 괄호의 `correction` 부분 문자열만 보고 전체를 허용하지 않는다. 최소안은 알려진 단위/규약 문자열의 정확한 일치다. 복합 설명을 지원하려면 별도 문법으로 전체를 소비·검증해야 한다.

```kotlin
val bad = found.filterIsInstance<ColumnDeclaration.Unsupported>()
    .firstOrNull { !it.reason.fixableBySign }

var conflictingSign = false
for (note in second.notes) {
    val says = readingOfWord(note) // 허용 목록과 정확히 일치
    if (says == null && note !in LEVEL_UNITS) return unsupportedUnit()
    if (says != null && says != byName) conflictingSign = true
}
// 모든 단위 검사 뒤에만 ConflictingSign 반환
```

위 코드는 설명용 축약이다. **적용 가능한 완전한 한 파일 패치**는 `2026-09-27-cfrc-sign-priority-proposed.patch`에 제공했다. 원본 archive에 `git apply --check`도 통과했다. 실제 저장소에는 적용하지 않았다.

정확 일치 최소안은 `(derived from response)` 같은 자유 서술 괄호를 새로 해석해 주지 않는다. 그런 형식의 정식 지원은 제조사 fixture와 문법을 함께 추가해야 한다. `Response (dB) (correction)`, `Corr (dB) (response)` 및 기존 시험은 유지된다.

**필요한 회귀 시험 및 제공한 검증**

- 잘못된 단위와 부호 충돌의 괄호 순서 양방향.
- 서로 다른 줄의 단위·수량 오류와 부호 충돌 순서 양방향.
- 서로 다른 선언의 Ambiguous 판정.
- 같은 괄호 안에 단위와 부호 단어가 함께 있는 경우.
- 순수 부호 충돌은 두 선택 중 확인한 규약으로 적용되는 정상 대조군.
- 각각 save/watch, 두 confirmReading 선택, 일반 켜기의 거절을 검사.

이 다섯 시험은 원본에서 **4건 실패**, 제안 사본에서 **5건 모두 통과**했다. 기존 저장소 13건도 유지했다. 제안은 단위 변환이나 다른 열 선택 기능을 추가하지 않는다.

## 4. CFRC-R02 — Low: 새 CurveStore 객체가 새 DataStore/프로세스는 아니다

**위치**

- `app/src/test/java/kr/joa/selahrta/calibration/CurveStoreConfirmationTest.kt:38–44, 141–154`.
- 연결: `app/src/main/java/kr/joa/selahrta/calibration/CurveStore.kt:25–26`의 최상위 preferencesDataStore delegate.

**재현과 근거**

시험은 `CurveStore(TestApplication(dir))`를 새로 만들고 ‘앱을 껐다 켜는 것과 같다’고 설명한다. 그러나 최상위 delegate는 동일 JVM 안에서 DataStore 인스턴스를 재사용한다. 실제 getter를 두 개의 서로 다른 TestApplication/디렉터리로 호출해 동일성 비교를 했다:

```text
DATASTORE_DELEGATE sharedAcrossContexts=true
```

즉 wrapper를 새로 만드는 것만으로는 새 DataStore의 디스크 읽기나 앱 재시작을 검증하지 않는다. TemporaryFolder만 달리해도 preferences 저장소가 자동으로 분리되지 않는다.

**영향**

사용자 데이터가 실제로 사라지는 결함을 발견한 것은 아니다. 다만 이 시험으로 재시작 지속성과 테스트 간 저장소 격리를 보장했다고 할 수 없고, 해당 경계의 회귀를 놓칠 수 있다. 이번 검토의 별도 JVM 재실행에서는 지속성이 정상임을 독립 확인했다. **이 Low만으로 승인을 보류하지 않는다.**

**권장 수정**

- 지금 시험은 ‘새 CurveStore wrapper에서 같은 상태를 읽는다’로 이름과 주석을 좁힌다.
- 정규 지속성 시험에는 DataStore와 소유 scope를 주입하고, 저장 완료 후 첫 scope를 cancel/join한 다음 같은 파일을 새 DataStore로 연다. 각 테스트의 scope도 종료한다.
- 또는 이번 독립 probe처럼 별도 JVM 두 개로 write/read를 분리한다. 같은 파일에 활성 DataStore 두 개를 동시에 만들지 않는다.

**필요한 회귀 시험**

첫 인스턴스와 scope 종료 → 새 인스턴스에서 confirmation SHA/reading/rules/on을 디스크로부터 복원 → 원본 해시 변경 시 확인 취소. 독립 테스트 디렉터리 두 개가 각각 다른 preferences를 갖는지도 확인한다.

## 5. 요청서 네 질문에 대한 답

1. **Ambiguous 거절은 현재 지원 범위에서 타당하다.** 서로 다른 선언을 어느 것으로 읽었는지 선택할 UI/파서가 없기 때문이다. 다만 지금 구현은 ConflictingSign이 앞에 있으면 그 거절을 우회한다. CFRC-R01의 우선순위 수정이 필요하다.
2. **오래된 token.sourceSha 자체는 문제가 아니다.** 화면이 본 내용을 고정하는 것이 목적이다. 원본이 바뀌면 Store에서 거절하며 동일 내용이면 유효하다. 입력 키 검사도 정적으로 확인했다. 단, 요청서 표의 ‘다른 입력의 표로는 확인되지 않는다’ 시험은 A의 확인이 B에 복사되지 않는다는 저장소 격리 시험이다. **입력을 B로 바꾼 뒤 옛 A 버튼을 누르는 ViewModel 거절을 실행하지 않는다.** 그 UI/VM 경계는 별도 시험 또는 기기 smoke로 남긴다.
3. **오래된 토큰 거절 문장은 이해 가능하다.** 다만 해당 문장은 `confirmReading`의 토큰 버전 불일치에만 있다. 저장된 확인 기록의 rulesVersion 불일치를 `watch`가 발견하는 경우는 확인 기록을 무효화하고 일반 재확인 안내를 사용한다. ‘앱 업데이트 복귀 때 반드시 그 문장을 띄운다’는 증거는 아니다. 기능상 거절은 확인했다. 더 정확한 안내가 필요하면 해시 변경/규칙 변경/기록 없음의 사유를 resolution으로 전달하되, 이를 이번 Medium 해결의 필수 조건으로 추가하지 않는다.
4. **TestApplication 경계는 파일·DataStore 통합 검증에 적절하다.** 이번 수정에서 실제로 유효한 회귀 시험이 추가됐다. 다른 저장소에도 확장할 수 있지만, 각 delegate의 생명주기와 테스트 격리를 먼저 다뤄야 한다(CFRC-R02). Android UI·권한·실기기 경로·프로세스 종료를 대체하지는 않는다.

## 6. 완료 조건과 전달 파일

이번 보류를 해소할 구체적 작업은 **CFRC-R01의 조합 우회 차단**이다. 제공 패치를 검토·반영하거나 같은 불변식을 만족하는 수정 후 위 회귀 시험을 통과시키면 된다. 단위 변환·제조사 전체 형식 지원·실측 SPL 교정을 먼저 개발해야 한다는 뜻이 아니다.

별도 남은 검증은 입력 전환 중 버튼 처리와 복귀 안내의 실제 UI/VM 시험, Android 빌드/기기 smoke, 음향·절대 SPL 정확도다. 이 항목들을 이번 순수 소프트웨어 결함과 섞어 무한히 승인 조건을 늘리지 않는다.

전달 파일:

- `2026-09-27-cfrc-sign-priority-proposed.patch`: 한 파일 최소 수정안.
- `2026-09-27-cfrc-independent-regression.kt`: 실제 Store 경계를 지나는 추가 5개 회귀 시험. 기존 TestApplication과 실행 harness를 사용한다.
- `2026-09-27-cfrc-independent-store-probe.kt`: 원본에서 기존 수정/새 반례/새 JVM 재실행을 확인한 probe. 조합의 true는 취약한 동작을 관찰하기 위한 기대값이므로 제품의 올바른 계약 시험으로 그대로 사용하지 않는다.

실행 스크립트와 전체 로그는 작업 폴더 `work/cfrc-20260927`에 남겼다. 원본 안전 계약 시험은 `baseline-regression.ps1`, 제안 검증은 `proposed-current-store.ps1`이며, 후자는 proposal/CalibrationSign.kt만 별도로 컴파일해 사용한다. 저장 fixture는 이 작업 폴더 안에만 있다.