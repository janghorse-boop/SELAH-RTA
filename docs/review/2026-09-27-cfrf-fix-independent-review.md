# CFRF-01 수정분 독립 재검토

검토일: 2026-09-27 · 검토자: Codex

**판정: 기존 CFRF-01 스위치·기존 파일 우회는 해결 확인. 새 CAL 확인 기능의 전체 승인은 부분 보류 — Critical 0 / High 0 / Medium 2 / Low 0.**

이전 시간 순서 High에 대한 승인도 이번 결과로 번복하지 않는다. 이번 범위에서 Critical/High는 발견하지 못했다. 남은 두 Medium은 (1) 화면이 보여 준 파일과 확인 API가 승인하는 파일의 연결, (2) 지원하지 않는 수량/단위의 실제 적용이다. 기준 소음계나 94dB 교정기 부재가 이유가 아니다.

## 1. 범위와 독립 실행

- 요청서: `docs/review/2026-09-27-cfrf-fix-rereview-request.md`.
- 고정 대상: **c2758235f32a8dab9ed8b2ae32b2fa952dfc3871**. 이 커밋의 10개 변경 파일과 CAL 적용 연결을 검토했다.
- `feat/factory-default-calibration`의 기종 기본값·활성 마이크 등 다른 커밋은 승인 범위에서 제외한다. 테스트를 컴파일하기 위해 연결 모델 의존성은 포함했다.
- git archive로 고정했다. 시작·종료 확인 HEAD는 `29e8de53`였고, 종료 시 micdb·assets·CaptureViewModel·Gradle 등 사용자 작업 변경이 있었다. 해당 작업은 수정하지 않았다.
- Production 파일·사용자 설정·브랜치를 변경하지 않았다. 제안은 별도 사본/패치다.

| 실행 | 결과 | 경계 |
|---|---|---|
| DSP 전체 JUnit | **564건 통과**, 68.765초 | Kotlin/JVM 직접 컴파일 |
| 앱 교정 선택 JUnit | **277건 통과**, 9.670초 | 18개 클래스, CurveReadingGateTest 포함 |
| 원본 CurveStore + DataStore 1.1.1 | 기존 우회 차단, 확인 부호·채널·파일 교체·규칙 버전·재실행 검증 | 실제 preferences/파일, Application 경계만 대역 |
| 독립 새 반례 | 아래 두 Medium 재현 | 실제 저장 API와 선택 함수 |
| 제안 Store/Gate 사본 | 두 반례 차단, 정상·확인·재실행 대조군 유지 | 실제 Store/Gate 컴파일·실행 |
| 제안 UI/VM 연결 | 정적 확인, 패치 적용 검사 통과 | 전체 Android 컴파일·Compose 실기기는 미실행 |

Kotlin 2.2.20/JBR 21/JVM target 17, 앱 coroutines 1.9.0이다. DSP harness는 기존 coroutines 1.8.0 구성을 유지했다. DataStore는 stub이 아닌 원본 1.1.1 라이브러리를 사용했다. 임시 Application이 filesDir/applicationContext를 제공하며 저장 파일은 작업 폴더 아래에만 만들었다. 새 JVM에서 재실행도 했다. 전체 Gradle assemble/lint/APK, 화면 렌더, AudioRecord/실제 음향 측정은 수행하지 않았다. 요청서의 5개 mutation 결과를 독립 실행 수로 합산하지 않는다.

## 2. 해결 확인한 것

실제 저장소 실행 출력:
```
TOGGLE_WITHOUT_READING blocked=true
LEGACY_ON_FLAG_ABSENT held=true
CONFIRM_CORRECTION gain100=-3.0 offOnPreserves=true
FILE_CHANGED_WITH_PROOF_LEFT held=true
NORMAL_AND_UNKNOWN preserved=true
CROSS_CHANNEL confirmationNotReused=true
REIMPORT_CHANGED_BYTES invalidated=true
EIGHTH_HEADER preserved=true
OLD_RULE_DATASTORE enabled=false
REOPEN confirmed=true enabled=true gain100=-3.0
```

- 모순 파일의 일반 켜기 요청은 거절되고, 기존 on 키가 없는 활성 상태도 보류된다.
- 명시적으로 Correction을 선택하면 원본 +3dB 점이 응답 표현 -3dB로 **한 번만** 변환된다. 일반 껐다 켜기와 새 JVM에서도 유지된다.
- 같은 내용의 다른 채널에는 확인 기록이 넘어가지 않는다.
- 같은 이름에 다른 내용으로 재가져오기뿐 아니라, 확인 preferences를 남겨 둔 채 파일 바이트만 바꿔도 확인은 무효다.
- 실제 DataStore의 규칙 버전을 과거 값으로 바꾸자 재확인을 요구했다.
- 앱 소유 주석으로 8번째 제조사 머리글이 밀리던 문제는 차단됐다.
- 정상 응답 파일 및 열 선언이 없는 Unknown 대조군의 기존 사용·일반 켜기/끄기는 유지됐다.

따라서 “꺼 두는 것”만 고쳤던 이전 단계와 달리, 이번에는 읽기·켜기 경계의 기존 반례가 해결됐다.

## 3. CFRC-01 — Medium: 확인 대상이 화면의 파일에 묶이지 않는다

**위치**
- `app/src/main/java/kr/joa/selahrta/ui/components/CurveCard.kt:511`: 콜백에 reading만 전달.
- `ui/CaptureViewModel.kt:1489–1492`: 클릭 처리 시 현재 입력 키를 다시 얻어 전달.
- `calibration/CurveStore.kt:394–412`: 당시 디스크 파일을 읽어 그 내용의 해시로 확인을 새로 발급.

**재현과 근거**
1. 모순 파일 A.cal을 저장하고 반환된 화면 자료를 보관한다.
2. 같은 키에 B.cal을 저장한다. B 역시 확인이 필요한 다른 내용이다.
3. A 화면의 선택을 나타내는 `confirmReading(key, Response)`를 호출한다.
4. B가 `readingConfirmed=true`, `enabled=true`로 승인된다.

```
STALE_CONFIRM shown=A.cal current=B.cal approved=true reading=Response
```

이 순서는 실제 Store와 DataStore로 실행했다. Compose에서 특정 프레임을 고정해 터치한 실기기 시험은 아니다. 그러나 콜백에 화면의 키/해시가 없기 때문에 저장소는 새 파일과 옛 화면을 구별할 수 없다. 파일 가져오기 비동기 완료와 화면 갱신 사이, 또는 입력 전환 후 화면 갱신 전의 오래된 콜백이 해당 경계다. 채널 기록 자체가 분리돼 있다는 점과 이 확인 발급 문제는 별개다.

**사용자 영향**
확인 기록의 해시는 파일 교체 후 재사용을 막지만, **기록을 발급하는 순간부터 잘못된 파일에 붙으면** 이후 모든 해시 검사를 통과한다. 사람이 본 적 없는 B에 A를 위한 부호 선택을 적용할 수 있다. 반복 실행·타이밍 통계가 있어야만 성립하는 문제는 아니며, 위 순서를 정하면 결정적으로 재현된다.

**권장 수정**
화면 모델이 받은 대상 식별자를 콜백 끝까지 전달한다. 확인 요청 시점의 “현재 파일”에서 새 식별자를 만들면 안 된다.

```kotlin
data class CurveConfirmationToken(
    val key: CalibrationKey,
    val sourceSha: String,
    val rulesVersion: Int,
)
```

- Store의 save/watch가 ActiveCurve에 이 토큰을 넣는다.
- 버튼은 `(token, reading)`을 보낸다.
- ViewModel은 현재 입력 키가 token.key와 다르면 거절한다.
- Store는 token.key 파일을 읽고, **읽은 내용의 해시 == token.sourceSha**, 현재 규칙 버전 일치를 확인한 뒤에만 기록한다.
- 검사 후 파일이 다시 바뀌는 경우에도 기존 watch의 해시 대조가 확인을 무효화하도록 유지한다.

**필요 회귀 시험**
A 표시→B 저장→A 토큰 확인은 거절, A 표시→입력 B 전환→A 버튼은 거절, 정상 A 토큰 확인·재실행은 성공. 동일 파일 이름/다른 내용도 포함한다. 이 시험은 순수 `confirmedReadingOf`만 호출해서는 안 되고 실제 확인 발급 API를 지나야 한다.

## 4. CFRC-02 — Medium: Unsupported 수량/단위를 응답으로 자동 적용하고 확인으로도 허용한다

**위치**
- `dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:446`: Unsupported 처리를 기준 교정 용도에만 한정.
- `app/src/main/java/kr/joa/selahrta/calibration/CurveReadingGate.kt:96–117`: DisplayCurve 판정의 fallback 또는 확인 기록을 허용.
- `CurveStore.kt:288–340,394–412`: 새 저장과 확인에서 Hz/dB 형식 거절이 없음.

**재현**
아래 머리글과 숫자 행을 실제 Store.save 및 confirmReading으로 처리했다.

| 머리글 | 새 저장 enabled | Correction 확인 뒤 enabled |
|---|---:|---:|
| `Frequency,Phase,SPL` | true | true |
| `Frequency (Hz),Amplitude (Pa)` | true | true |
| `Frequency (kHz),Response (dB)` | true | true |
| `Frequency (Hz),Magnitude (linear)` | true | true |

파서는 첫 숫자를 그대로 Hz, 둘째 숫자를 dB로 쓰며 셋째 열은 무시한다. `Phase`를 응답으로 바꾸거나, kHz를 Hz로 바꾸거나, Pa/선형량을 dB로 만드는 연산은 부호 선택이 아니다.

**사용자 영향**
위상을 주파수 보정량으로 사용하거나 주파수 축을 1,000배 잘못 해석하고, 선형 수량을 dB로 적용한다. 머리글이 명시적으로 다른 수량이라고 알려 주는데도 RTA 보정에 들어간다. 이것은 이번 커밋에서 새로 생겼다고 주장하는 회귀가 아니라 요청서가 명시한 미처리 경계다. 새 확인 기능을 통해서도 그대로 승인되므로 현재 승인 범위에서 함께 막아야 한다.

**권장 수정과 범위**
지금 해야 하는 것은 새 형식 변환기 개발이 아니라 **지원하지 않는 형식의 적용 거절**이다. 이후 kHz/Pa/다중 열 변환 지원을 별도 기능으로 개발할 수 있다.

최소안:
```kotlin
val columns = columnDeclarationOf(headerLines)
if (columns is ColumnDeclaration.Unsupported) {
    // 자동 승인과 기존/새 확인 기록보다 먼저 거절한다.
    return unsupportedResolution(columns.secondKo)
}
```

watch와 setEnabled는 같은 resolution을 사용하고, confirmReading에서도 거절해야 한다. “확인 필요” 버튼을 계속 보여 주면 사람이 고르는 것으로 해결 가능한 것처럼 보이므로, 별도의 “지원하지 않는 형식” 안내로 바꾼다. 단서가 없는 Unknown과, 틀린 단위/열이 선언된 Unsupported를 섞지 않는다.

**보수적 최소안의 한계**
현재 Unsupported에는 `Response (dB) (correction)` 같은 모순 설명도 포함된다. 첨부안은 이 범주를 전부 거절한다. 정상 Hz/dB의 단순 부호 모순을 확인으로 허용하고 싶다면 먼저 기계적으로 구별되는 사유 타입(UnsupportedQuantity/UnsupportedUnit/ConflictingDeclaration 등)을 만들고, **확인 가능한 부호 모순만** 별도로 허용한다. 한국어 이유 문자열 일부를 비교해 예외를 만들지는 않는다. 요청서 질문 2와 3은 같은 적용 경계 문제이며 별도 결함 두 건으로 부풀리지 않았다.

**필요 회귀 시험**
위 네 파일의 save/watch/켜기/확인 모두 미적용, 예전 확인 기록이 있어도 거절, 정상 Hz·dB 응답/보정값 확인과 Unknown 대조군 유지. 화면에는 두 부호 선택으로 고칠 수 있다는 안내를 내보내지 않는다.

## 5. 검증한 수정 제안

동봉 패치: `2026-09-27-cfrf-confirmation-proposed.patch`.

5개 파일에 위 토큰 전달과 형식 거절을 연결했다. Store/Gate 부분은 실제 DataStore로 컴파일·실행했고, CurveCard/HistorySettingsScreens/CaptureViewModel 콜백 변경은 정적 검토와 패치 적용 확인까지 했다. **전체 Android 빌드나 UI를 검증한 완성 패치라고 주장하지 않는다.** 사용자의 최신 작업 트리에는 적용하지 않았다.

같은 반례의 제안 사본 결과:
```
STALE_CONFIRM shown=A.cal current=B.cal approved=false reading=Response
UNSUPPORTED header=Frequency,Phase,SPL importedEnabled=false confirmedEnabled=false
UNSUPPORTED header=Frequency (Hz),Amplitude (Pa) importedEnabled=false confirmedEnabled=false
UNSUPPORTED header=Frequency (kHz),Response (dB) importedEnabled=false confirmedEnabled=false
UNSUPPORTED header=Frequency (Hz),Magnitude (linear) importedEnabled=false confirmedEnabled=false
```

기존 차단·확인 성공·원본 변경 감지·정상/Unknown·다른 채널·규칙 버전·8번째 머리글·새 JVM 재실행 대조군도 유지했다. API 서명을 바꿔 화면 해시 없이 부르는 이전 confirmReading 호출이 컴파일로 드러나게 했다. 패치는 고정 소스에서 git apply --check를 통과했다.

## 6. 요청서 질문에 대한 답

1. **확인 기록 범위**: 저장된 기록을 key 아래에 두고 내용 해시/규칙 버전을 비교하는 구조는 맞으며 다른 채널 재사용 방지는 실제 실행으로 확인했다. 그러나 발급 시 화면의 대상을 전달하지 않는 문제는 CFRC-01로 남는다.
2. **Unsupported를 표시용에서 막아야 하는가**: 그렇다. 이 경로는 미리보기만이 아니라 실제 보정이다. CFRC-02와 같은 거절 경계가 필요하다.
3. **이번 Medium과 다음 작업의 구분**: 지금은 적용을 거절하는 데까지 포함한다. 단위 변환, 추가 열 선택, 제조사별 새 포맷 지원은 다음 작업이다.
4. **규칙 버전 상승 문구**: 재확인이 실제로 필요해지는 기존 파일을 차단하는 것은 확인했다. 처음 가져오기와 구별하는 안내는 유용하지만, 잘못된 적용을 새로 허용하지 않는 한 별도 승인 차단 사유로 잡지 않는다. `record != null && record.rulesVersion != current`를 상태 사유로 전달해 “파일이 바뀐 것이 아니라 앱의 확인 규칙이 바뀌었습니다”라고 안내하면 된다.

요청서의 “Context가 없으면 한 줄도 못 돌린다”는 설명은 이 저장소 경계에 그대로 적용되지 않는다. 이번에도 filesDir/applicationContext만 대역으로 두고 **원본 CurveStore와 실제 DataStore**를 실행했다. 이 패턴으로 정규 저장소 통합 시험을 추가하는 것이 다음 유사 결함을 줄이는 직접적인 방법이다. 이를 Android 실기기 검증과 동일시하지는 않는다.

## 7. 전달 자료와 남은 위험

- 실행 폴더: `../work/cfrf-20260927/`.
- 원본 확인: `IndependentStoreProbe.kt`, `store-probe.ps1`, `store-output.txt`, `store-reopen-output.txt`.
- 제안 확인: `ProposedStoreProbe.kt`, `proposed-store-probe.ps1`, `proposed-store-output.txt`, `proposed-store-reopen-output.txt`.
- 정규 실행: `dsp-test-output.txt`, `app-calibration-test-output.txt`.
- 고정 소스: `source.zip`, `source/`.

원본 probe는 결함이 관측되는 것을 check하므로 exit 0 자체가 승인이라는 뜻은 아니다. 제안 probe는 거절을 기대한다. UI의 파일 선택·토큰 전달·입력 변경·확인 안내·복귀 상태와 실제 측정 경로는 추가 기기 확인이 필요하다. 기준 장비를 사용하는 절대 SPL/주파수 정확도 확인은 여전히 별도 검증이다.
