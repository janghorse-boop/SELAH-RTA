# CFR-01·CFR-02 수정분 독립 재검토

검토일: 2026-09-27 · Codex

**판정: CFR-01 승인. CFR-02의 신규 가져오기 자동 차단은 확인했지만, 전체 적용 경계는 부분 해결이다. Critical 0 / High 0 / Medium 1 / Low 0.**

기존 시간 순서 High는 해결됐다. 현재 수정 범위에서 새 Critical/High는 발견하지 못했다. High 때문에 유지하던 개발 진행 보류는 해제할 수 있다. 다만 아래 Medium(CFRF-01)을 조치하기 전에는 “미확정 CAL은 적용되지 않는다”는 전체 기능 승인을 할 수 없다. 이는 94dB 기준 장비 부재와 무관하다. 전체 제품 출시나 실기기 정확도를 승인하는 보고서는 아니다.

## 1. 고정 범위와 독립 실행

- 요청서: `docs/review/2026-09-27-cfr-fix-rereview-request.md`.
- 대상: **5b48460dff073bb3892294e48ac71d3aff53ac18**, [PR #13](https://github.com/janghorse-boop/SELAH-RTA/pull/13).
- 이전 대상 `96d2d28`에서 해당 대상까지 다른 UI 변경도 포함되므로, 이번은 CFR 교정 수정 및 연결 경계를 검토한다. 중간 UI 변경 전체를 승인하지 않는다.
- git archive로 소스를 고정했다. 시작 시 작업 트리는 깨끗했고 HEAD는 `5981172b`였다. 종료 확인에서는 `14de093b` 및 CalibrationProfile/Measurement/CaptureViewModel/Theme/FactoryCalibration 작업 변경이 관측됐다. 그 변경은 사용자의 동시 작업이며 손대지 않았다. 이 보고서와 패치는 5b48460 기준이다.
- Production 소스·사용자 데이터·브랜치를 수정하지 않았다. 제안은 작업 폴더의 별도 사본이다.

| 검증 | 독립 결과 | 검증 경계 |
|---|---|---|
| DSP 전체 JUnit | **561건 통과**, 72.216초 | Kotlin/JVM 직접 컴파일 |
| 앱 교정 선택 JUnit | **260건 통과**, 9.826초 | 17개 클래스 |
| 실제 VM → Coordinator probe | 이전 반례 차단, 재측정 취소·재개·순서 변경·전체 초기화 대조군 통과 | capture만 합성 대역 |
| CAL parser → 판정 | 두 용도에서 Conflicting 보류 | 정상 열/Unknown 대조군 포함 |
| **원본 CurveStore + 실제 DataStore 1.1.1** | 새 파일 꺼짐 확인, 일반 스위치 적용 우회와 별도 JVM 재실행 뒤 유지 재현 | 임시 파일, Application의 filesDir/applicationContext만 대역 |
| 제안 CurveStore + 같은 DataStore | 기존 활성 파일 보류, 스위치 우회 차단, 정상 곡선 유지, 별도 JVM 재실행 통과 | 실제 preferences 파일 I/O |
| 옛 Coordinator 되살림 | 36건 중 2건 예상 실패 | 동일 컴파일 클래스 경계에서 96d2d28 소스만 되돌림 |
| 제안 패치 | git apply --check 통과 | 고정 사본 대상 |

Kotlin 2.2.20/JBR 21/JVM target 17, 앱 coroutines 1.9.0, DSP harness는 기존 1.8.0 구성을 유지했다. mockable Android jar를 사용했지만 CurveStore/AtomicWrite/DataStore/Preferences 구현은 원본이다. 이번에는 저장 함수 흉내만으로 결과를 낸 것이 아니다. 반면 Android 프로세스/Compose/AudioRecord/전체 Gradle assemble·lint/APK는 실행하지 않았다. 제안 UI 조각도 전체 Android 컴파일·렌더 검증 전이다.

## 2. CFR-01 — 해결 확인

`WizardCoordinator.kt:446`의 dependentSteps 폐기와 최종 신원 검사를 함께 읽었다. Runner는 충분한 수집이 끝난 뒤에만 장을 session에 기록하고, 정상 종료 시 단계 신원을 붙인다. 현재 세션은 Coordinator 내부 메모리에 새로 만들어지며 디스크에서 단계 자료를 복원하는 경로는 확인되지 않았다.

독립 실행:
```
TARGET_AFTER_FINAL_REFERENCE retainedBefore=120 retainedAfter=0 targetPowerRatio=10 resultCleared=true
BEFORE_RETAKEN_LAST dependentStagesCleared=true
OUT_OF_ORDER blocked=true RESTART_ALL_CLEARED=true RESTART_NORMAL_PASS=true
CANCEL_TARGET_RETAKE oldOutcomeCleared=true afterCleared=true resumedSequencePass=true
```

- 대상 재측정은 처음 기준을 보존하고 마지막 기준과 옛 판정을 없앴다.
- 같은 경로의 처음 기준 재측정도 대상·마지막 기준을 없앴다.
- 처음 기준→마지막 기준→대상 순으로 요청해도 대상 수집 시 옛 마지막 기준이 폐기되어 완성 판정을 내리지 않았다.
- 정상 완료 후 대상 재측정을 실제 delay 중 취소했다. 옛 결과가 복원되지 않았고, 대상과 마지막 기준을 새로 모으면 정상 승인으로 돌아왔다.
- restartMeasurement로 단계 장수·신원·targetCalKey·levelTransfer를 비우고, 정상 순서로 다시 완료할 수 있었다.

**시간 구간 검사 추가는 지금 승인에 필수인가?** 현재 단일 작업·메모리 세션·폐기 규칙에 한해서는 필수가 아니다. 최종 조합이 완성될 때 뒤 단계가 앞 단계보다 먼저 수집된 채 남지 않는 것을 규칙과 흐름 시험으로 확인했다. 영속 세션 복원, 병렬 수집, 임의 단계 자료 주입을 추가할 때는 단계 수집 구간/세대와 최종 순서 검사를 함께 넣는 것이 좋다. 단순한 단조 증가 수집 번호만으로도 순서 검증이 가능하며, 하드웨어 음향 시각을 새로 측정해야만 하는 문제는 아니다.

**앞선 측정이 오래됐을 때는?** 시간 순서가 올바르다는 것과 음향 조건이 유지됐다는 것은 별개다. 지금 근거 없이 5분 같은 실패 임계값을 새로 만들지는 않는다. 우선 단계별 단조시각과 경과 시간을 보여 주고, 위치·스피커 볼륨·마이크 방향·케이블/게인 변경 시 처음부터 다시 재게 한다. 중단 후 재개 시 조건 유지 여부를 명시적으로 확인시키는 방식도 가능하다. 강제 만료값은 실제 반복성 자료로 정책화할 사항이며 이번 High를 계속 열어 둘 이유는 아니다. 전후 기준 차이 검사는 두 시점 사이의 일시적 변화까지 모두 증명하지 못한다는 기존 한계도 유지된다.

되살림 시험에서는 대상 재측정과 같은 경로 처음 기준 재측정의 두 시험이 실패했다. 요청서의 “3건 실패”를 그대로 재확인했다고 보고하지 않는다. 이 검토의 정확한 mutation은 **96d2d28 Coordinator 전체 교체**이며, 다른 채널 경우는 그 버전의 dropStaleReferenceAfter가 여전히 막는다. 결함 검출 자체는 확인됐다.

## 3. CFRF-01 — Medium: 일반 스위치와 기존 활성 파일이 CAL 확인을 우회한다

### 위치

- `app/src/main/java/kr/joa/selahrta/calibration/CurveStore.kt:185` — 읽을 때 on 플래그만으로 enabled 결정.
- 같은 파일 `:238` — setEnabled(true)가 해석 상태 확인 없이 true 저장.
- `ui/components/CurveCard.kt:208` — 해석 상태와 관계없이 일반 적용 스위치 제공.
- `ui/CaptureViewModel.kt:1449` — 그대로 setEnabled 호출.
- 같은 파일 `:1399` — 새 파일이 보류돼도 “적용했습니다”라고 알림. 이 문구는 같은 수정에 포함할 사항이며 별도 이슈로 늘리지 않는다.

### 재현

머리글 `# Correction factors` + `Frequency (Hz),Response (dB)`인 파일을 원본 CurveStore.save로 저장했다. 판정대로 disabled였다. 그 뒤 화면과 같은 setEnabled(true)를 호출하고 chooseCorrection을 실행했다. 원본 DataStore에 저장하고 새 JVM에서 다시 읽었다.

```
NEW_CONFLICT savedEnabled=false watchedEnabled=false
TOGGLE_WITHOUT_READING enabled=true correction=FromFile
REOPEN enabled=true correction=FromFile
```

따라서 **새로 가져온 파일도** 일반 스위치만 켜면 우회된다. 옛 버전에서 enabled=true 또는 on 키 누락 상태로 남은 모순 파일도 같은 watch 경로를 탄다. 이번 실행의 기존 활성 대조군은 실제 옛 앱을 설치해 만든 것이 아니라, 원본 저장 API로 같은 활성 preferences 상태를 만든 뒤 새 프로세스에서 읽은 것이다.

### 영향과 판정

스위치는 “사용할 것인가”만 받으며 “응답/보정값 중 무엇인가”를 받거나 저장하지 않는다. 참으로 바뀌어도 계산 규약은 자동으로 확정되지 않는다. 모순된 값이 응답으로 적용되어 실제 규약과 반대이면 주파수별 보정 오차가 생긴다. 이 경로는 RTA·Spectrum·Spectrogram 보정에 연결되며, 큰 광대역 SPL 숫자까지 모두 바뀐다고 주장하지 않는다.

CFR-02의 새 가져오기 기본값 변경은 맞다. 남은 문제는 그 보류를 읽기/재활성 경계가 지키지 않는 것이다. 악의적인 입력이나 기준 장비가 있어야만 재현되는 문제가 아니다.

### 즉시 적용 가능한 수정

일괄 삭제·일괄 비활성 migration보다 **읽을 때 현재 판단으로 실제 적용 여부를 계산**하는 것을 권장한다.

```kotlin
val decision = decideReading(loaded.signEvidence, ReadingStakes.DisplayCurve)
val effectiveEnabled = requestedEnabled && decision.settled
```

그리고 setEnabled(true)도 같은 판단으로 보류한다. UI 스위치만 막으면 다른 호출자가 우회하므로 저장 경계의 검사가 함께 필요하다. 꺼 두기(false)는 항상 허용한다.

첨부 `2026-09-27-cfr-curve-hold-proposed.patch`는 다음 세 파일을 최소 변경한다:

1. **CurveStore**: watch 재검사 + setEnabled 검증 + ActiveCurve.readingConfirmationNeeded 제공. 앱이 파일 앞에 붙인 정확한 자기 소유 주석은 파싱 전에 제거해 MAX_HEADER_LINES 8칸 중 하나를 차지하지 않게 한다. 그 외 제조사 머리글은 보존한다.
2. **CurveCard**: “파일 확인 필요”, 적용되지 않는 미리보기 안내, 확인 전 일반 스위치 비활성.
3. **CaptureViewModel**: 보류 저장을 “적용했습니다”로 알리지 않음.

이 제안은 **차단을 완성하는 최소안**이다. 확인 후 다시 활성화하는 선택 UI/API까지 구현한 완성 기능은 아니다. NeedsPerson인 파일은 확인 UI를 붙이기 전까지 적용할 수 없고, Conflicting뿐 아니라 기존 LooksLikeCorrection도 현재 판정상 NeedsPerson이므로 보류한다. 정상/Unknown 파일을 전부 끄거나 파일을 삭제하지 않는다.

### 제안 사본의 실제 실행

```
REOPEN enabled=false correction=None                 # 원본에서 활성화한 파일을 먼저 읽음
NORMAL_CURVE preserved=true toggleOffOn=true
EIGHTH_HEADER preservedConflict=true enabled=false
NEW_CONFLICT savedEnabled=false watchedEnabled=false
TOGGLE_WITHOUT_READING enabled=false correction=None
REOPEN enabled=false correction=None                 # 제안 사본 실행 후 새 JVM
```

실제 파일을 다시 쓰거나 삭제하지 않고 기존 활성 파일을 보류했다. 정상 곡선은 활성 및 일반 켜기·끄기를 유지했다. 8번째 원본 머리글에만 모순 단서가 있는 경우도 저장/읽기 뒤 같은 판정을 유지하도록 검증했다. 패치의 Store 부분은 컴파일·실행했고 UI/ViewModel 문구 조각은 정적 검토와 패치 적용 가능성만 확인했다.

## 4. 확인 후 활성화를 완성하는 구체적 설계

일반 스위치와 CAL 해석 확인을 분리한다. 마법사의 기존 읽기 선택 UI를 재사용하되, 프로파일 마법사 결과와 가져온 파일의 확인 기록을 혼동하지 않는다.

```kotlin
data class CurveReadingConfirmation(
    val sourceSha256: String,
    val reading: CurveReading,
    val rulesVersion: Int,
)
```

- sourceSha256는 앱이 추가한 소유 주석을 제외한 **저장 원본 CAL 내용**의 해시다. 기기/채널 CalibrationKey 아래에 보관한다. 파일 이름이나 전역 Boolean만으로 확인을 인정하지 않는다.
- 명시적 “응답으로 사용” / “보정값으로 사용”에서만 기록한다. 일반 스위치는 requestedEnabled만 바꾼다.
- 읽을 때 원본 내용 해시와 확인 기록이 같으면 선택된 reading으로 곡선을 생성한다. `CalibrationCurve.of(CalibrationFile.parse(source).points, confirmation.reading)`처럼 원본에서 다시 생성해야 하며, 이미 뒤집은 점을 또 뒤집지 않는다.
- 해시가 다르거나 규칙 버전이 바뀌면 확인을 다시 요구한다. 파일 변경과 확인 저장이 경합해도 해시 불일치가 적용을 막도록 한다.
- Unknown은 현재 자동 판정 정책을 유지한다. 단위가 Hz/dB가 아니거나 둘째 열이 다른 수량인 파일까지 “응답” 선택만으로 유효해지는 것은 아니므로, 수량/단위 오류는 별도 거절한다.
- 기존 정상 파일은 현재 자동 판정으로 계속 적용한다. 기존 모순/확인 필요 파일만 보류하고 알린다. 켜기 의도, 실제 적용 상태, 보류 이유를 별도 표현한다.

확인 API를 추가할 때의 핵심 순서는 다음과 같다:
```
현재 저장 원본 읽기 → 화면이 확인한 해시와 대조 → 선택된 reading으로 검증/생성
→ (해시, reading, 규칙버전) 저장 → watch에서 해시를 다시 대조 → 실제 적용
```
위 설계는 제안이며 이번 패치에 구현됐다고 주장하지 않는다.

### 이 Medium을 닫는 회귀 시험

- 모순 새 파일: save 반환·watch·chooseCorrection 모두 미적용.
- 일반 setEnabled(true): 부호 확인 없이 적용되지 않음; setEnabled(false)는 허용.
- 이미 활성화된 모순 파일, on 키가 없는 옛 파일: 새 프로세스에서도 보류.
- 정상/Unknown 파일: 기존 상태와 일반 켜기·끄기 유지.
- 명시적 Response/Correction 확인: 맞는 부호 적용, 재실행 뒤 같은 결정 유지.
- 확인 뒤 파일 교체·같은 이름 다른 내용·다른 채널: 확인 기록 재사용 안 됨.
- 머리글 8번째의 모순과 앱 소유 주석: save/watch 판정 일치.
- UI: 보류를 “적용됨”이라고 쓰지 않음, 확인 절차로 이동 가능.

## 5. 남은 실기기 검증과 전달 자료

이번 Medium의 Store 부분은 더 이상 “기기가 없어서 전혀 확인할 수 없다”에 해당하지 않는다. 실제 DataStore와 파일 왕복을 JVM에서 재현했다. 그래도 Compose의 파일 선택·해석 확인·스위치·복귀 표시와 Android 마법사/AudioRecord lifecycle은 기기에서 확인해야 한다. 전체 마법사의 절대 SPL/주파수 정확도 검증에는 기준 장비가 별도로 필요하다.

- 패치: 이 보고서와 같은 outputs 폴더의 `2026-09-27-cfr-curve-hold-proposed.patch`.
- 실행 사본/로그: `../work/cfr-20260927/`.
- 핵심 로그: `dsp-test-output.txt`, `app-calibration-test-output.txt`, `independent-wizard-output.txt`, `independent-column-output.txt`, `store-output.txt`, `store-reopen-output.txt`, `proposed-existing-store-output.txt`, `proposed-store-output.txt`, `proposed-store-reopen-output.txt`, `mutation-output.txt`.
- 핵심 재현 코드: `IndependentFixProbe.kt`, `IndependentStoreProbe.kt`, `ProposedStoreProbe.kt` 및 각 ps1.

원본 Store probe는 우회가 관측된다는 기대값으로 실행되므로 exit 0이 안전 판정을 뜻하지 않는다. 제안 Store probe는 반대로 차단을 기대한다. mutation exit 1은 의도된 검출이다. 사용자의 최신 작업 트리에는 패치를 자동 적용하지 않았다.
