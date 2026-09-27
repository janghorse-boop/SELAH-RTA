# CF2-01~03 수정분 독립 재검토

검토일: 2026-09-27 · Codex

**판정: 승인 보류 — Critical 0, High 1, Medium 1, Low 0.**

기존 CF2-01의 다른 채널 반례, CF2-02의 기준 CAL 모순 반례 셋, CF2-03의 실제 취소 시험은 해결됐다. 다만 경로 일치만으로 시간 순서를 대신할 수 없다. 완료 후 같은 경로에서 일부만 다시 측정해도 Pass가 되는 문제가 남는다. 이는 기준 장비가 없어서 생긴 보류가 아니다.

## 범위와 실행

- 요청: `docs/review/2026-09-27-cf2-fix-rereview-request.md`.
- 고정 대상: `96d2d2869236026deb0a01b6af31edec8b73d4c2`, 이전 검토 `dbc8e73` 대비. git archive 사본으로 컴파일·실행했다.
- PR: https://github.com/janghorse-boop/SELAH-RTA/pull/11 — 작업에 연결했다.
- 시작 시 저장소 HEAD `e086b147`, 깨끗함. 검토 중 HEAD가 `cdd10e70`으로 바뀌고 SpectrogramChart.kt 작업 변경이 관측됐다. 해당 동시 작업은 건드리지 않았고, 판정·시험은 고정 사본에 한정한다. 이 보고서의 줄 번호도 96d2d28 기준이다.
- Production 파일, 브랜치, 커밋, 사용자 프로파일은 변경하지 않았다. 수정 제안은 별도 파일과 JVM 클래스 오버레이로만 시험했다.

| 실행 | 결과 | 경계 |
|---|---|---|
| DSP 전체 JUnit | 559건 통과, 78.677초 | 직접 Kotlin/JVM 컴파일 |
| 앱 교정 선택 JUnit | 258건 통과, 10.145초 | 17개 클래스, coroutines 1.9.0 |
| 실제 VM wrapper → Coordinator probe | 기존 반례 차단, 아래 시간 순서 반례 재현 | 실제 core/Runner/판정/ProfileStore, 합성 capture |
| CAL 파일 parser → CalInfo/decideReading | 기준용 모순 3개 보류, DisplayCurve 모순 2개 확정 | 실제 parser/판정 함수 |
| 제안 Coordinator 사본 | 재측정 반례 2개 차단, 기존 probe 대조군 통과 | 전체 앱 시험은 제안 코드로 재실행하지 않음 |
| 제안 Sign 사본 | 두 용도 모두 모순 보류, 정상 열 대조군 통과 | 실제 DataStore/화면은 미실행 |
| 제안 패치 | 고정 소스에서 git apply --check 통과 | 사용자의 최신 작업 트리에 적용하지 않음 |

Kotlin 2.2.20, JBR 21, JVM target 17, 캐시된 의존성으로 직접 실행했다. Android mockable jar와 최소 Application/capture owner 대역을 사용했다. DSP harness는 이전 1.8.0 coroutines 구성을 유지했다. Gradle assemble/lint, APK, Compose, AudioRecord, 실제 DataStore와 기기 마법사 전체 동작은 이번에 검증하지 않았다. 정규 시험 통과 수는 이 경계를 넘는 증거가 아니다.

## CFR-01 — High: 같은 경로의 재측정이 기준 측정의 시간 순서를 깨뜨린다

**위치**
- `app/src/main/java/kr/joa/selahrta/calibration/WizardCoordinator.kt:429` — 선택한 단계만 폐기.
- 같은 파일 `:518` — 첫 기준의 경로가 같으면 마지막 기준을 보존.
- 같은 파일 `:537` — 최종 단계 신원 검사. 시간 순서는 보지 않음.
- `CaptureIdentityGate.kt:160`의 `sessionIdentityMismatchKo`는 경로 조합 검사다.

**재현**
1. 동일한 합성 신호로 기준 처음 → 대상 → 기준 마지막을 정상 완료한다.
2. 대상으로 다시 열고 대상 신호 전력만 10배(+10dB) 높인다.
3. 대상 단계만 재측정한다. 기준 처음·마지막 120장씩은 옛 값이다.
4. `judgeCalibration(...).maySave == true`, verdict는 Pass다.

실행 출력:
```
TARGET_AFTER_FINAL_REFERENCE retainedBefore=120 retainedAfter=120 targetPowerRatio=10 verdict=Pass
BEFORE_RETAKEN_LAST sameRoute=true verdict=Pass
```
두 번째 줄은 정상 완료 후 같은 기준 입력으로 처음 기준만 다시 잰 경우다. 마지막 기준이 시간상 앞에 있는데도 승인된다. 이 실행은 저장 가능 판정까지 확인했으며 새 반례 결과를 실제 파일로 저장하지는 않았다.

**논리와 사용자 영향**
CalibrationSession.kt:9–14의 계약은 기준→대상→기준이며, 두 기준의 차이로 측정 중 변화 여부를 판단한다. 두 기준을 모두 먼저 잰 뒤 대상을 바꾸면, 현재의 음원 변화는 그 차이에 나타날 수 없다. 음원 변화를 마이크 경로 차이로 해석하여 레벨 이전 또는 곡선 계산에 사용할 수 있다. 순서가 맞는다고 모든 환경 변화가 검출되는 것은 아니지만, 순서가 틀리면 의도한 전후 점검 자체가 성립하지 않는다.

**권장 수정과 제안 코드**
아직 뒤 단계가 없는 첫 기준 재시도는 그대로 허용한다. 뒤 단계까지 존재하면 새 측정 단계와 그 이후 자료를 함께 폐기한다. 대상 재시도는 처음 기준을 보존하고 마지막 기준만 다시 요구한다. 같은 경로여도 시간 의존성은 동일하다.

`measureStep`의 현재 `if (session.frameCount(step) > 0) discardStep(step)` 자리에:
```kotlin
val dependentSteps = when (step) {
    MeasureStep.ReferenceBefore -> listOf(
        MeasureStep.ReferenceBefore, MeasureStep.Target, MeasureStep.ReferenceAfter,
    )
    MeasureStep.Target -> listOf(MeasureStep.Target, MeasureStep.ReferenceAfter)
    MeasureStep.ReferenceAfter -> listOf(MeasureStep.ReferenceAfter)
}
dependentSteps.forEach { discardStep(it) }
```
이 최소안은 부분 보존 자체를 없애지 않는다. 시간상 먼저 쓴 유효 단계는 보존한다. UI에는 버린 후속 단계와 다시 재야 하는 이유를 알려야 한다. 경로 변경 전용 dropStaleReferenceAfter는 이 정책에서 불필요해지므로 후속 정리와 설명 갱신이 가능하지만 제안 패치는 최소 변경으로 남겼다.

단계 자료를 꼭 보관하려면 수집 구간/세대도 저장하고 `before.end <= target.start && target.end <= after.start`를 최종 판정에 요구해야 한다. 순서가 어긋난 옛 자료를 화면 기록으로 남길 수는 있어도 새 승인 조합으로 쓸 수는 없다.

**검증한 제안 결과**
```
TARGET_AFTER_FINAL_REFERENCE retainedBefore=120 retainedAfter=0 targetPowerRatio=10 resultCleared=true
BEFORE_RETAKEN_LAST dependentStagesCleared=true
```
정상 순방향과 기존 신원·저장 환경·보류 오프셋·취소 probe도 통과했다.

**회귀 시험**
- 완료 후 대상 재측정 → 마지막 기준 제거, outcome/quality/levelTransfer 제거, 저장 불가.
- 완료 후 같은/다른 경로의 처음 기준 재측정 → 대상·마지막 기준 제거.
- 처음 기준만 존재할 때 같은 경로 재시도 → 정상 수집 가능.
- 후속 단계를 새로 완료한 경우 정상 Pass 복구.
- 재시도 중 취소/입력 변경에도 옛 결과가 다시 승인되지 않음.
- 현재 “같은 입력이면 보존” 시험은 제품 계약 변경에 맞춰 수정해야 한다. 제안 코드로 기존 전체 시험까지 통과했다고 주장하지 않는다.

## CFR-02 — Medium: DisplayCurve는 실제 보정 경로인데, 모순을 자동 확정한다

**위치**
- `dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSign.kt:385` — Conflicting 보류를 ReferenceForCalibration에만 한정.
- 같은 파일 `:482` — DisplayCurve의 Conflicting을 Settled(Response)로 반환.
- `app/src/main/java/kr/joa/selahrta/calibration/CurveStore.kt:199,222` — 해당 판정으로 새 파일을 enabled=true로 저장.
- `ActiveCorrection.kt:149` → `CaptureViewModel.kt:1285` → `RtaEngine.setCurve` — 실제 FFT 보정에 전달.

**재현·근거**
실제 CAL parser로 아래 머리글을 읽었다:
```
# Correction factors
Frequency (Hz),Response (dB)
```
또는 `Frequency (Hz),Response (dB) (correction)`.
두 경우 기준 CAL은 이제 올바르게 보류된다. 그러나 DisplayCurve 판정은 `settled=true`다. 위 저장·선택·DSP 경로를 코드로 추적했으며, 실제 Android DataStore 저장 자체는 실행하지 않았다.

이는 이번 커밋에서 처음 생겼다는 뜻은 아니다. 이전 Unknown에 숨었던 정책이 Conflicting으로 구분된 뒤에도 남은 문제이며, 요청서 질문 2에 대한 답이다. 그래프 미리보기만이라면 잠정 해석과 경고로 충분할 수 있다. 여기서는 가져오자마자 측정 보정에 쓰므로 그 전제가 맞지 않는다.

**영향**
실제 값이 보정값인데 응답으로 해석하면 보정 부호가 뒤집힌다. 예를 들어 1kHz 기준 상대 보정이 +3dB여야 할 대역에 -3dB를 적용하면 차이는 6dB다. 이 수치는 예시이며 특정 제조사 파일을 관측했다는 뜻이 아니다.

**권장 수정·제안 코드**
최소안은 용도와 무관하게 Conflicting에서 NeedsPerson을 반환하는 것이다:
```kotlin
if (evidence == SignEvidence.Conflicting) {
    val suggest = (columns as? ColumnDeclaration.Second)?.reading
        ?: CurveReading.Response
    return ReadingDecision.NeedsPerson(
        suggest,
        "응답과 보정값 설명이 서로 어긋납니다. 읽는 방식을 확인하기 전에는 보정을 적용하지 않습니다.",
    )
}
```
제안 패치는 기존 분기의 용도 조건만 제거한다. 문구/주석과 도달 불가능해지는 DisplayCurve 관례 분기는 구현 시 정리해야 한다. 읽는 방식 확인 뒤 활성화하는 기존 흐름과 연결하고, 다시 열 때도 결정 상태가 보존되는지 검증해야 한다. 이미 자동 활성화된 파일의 재검토 정책도 별도로 정해야 하며, 이 패치만으로 기존 저장 파일까지 복구했다고 볼 수 없다.

**제안 검증**: 두 모순 모두 `CONFLICT_DISPLAY settled=false`. 기준용 반례, 정상 `Frequency,SPL,Phase`와 `Frequency (Hz),Response (dB)` 대조군을 유지했다.

**필요 회귀 시험**: parser→판정→새 파일 enabled=false, chooseCorrection에서 미적용, 사용자 확인 후 적용, 재시작 뒤 결정 유지. 단서 없음(Unknown)과 모순(Conflicting)을 별도로 시험한다.

## 기존 지적 처리와 요청서 답변

1. CF2-01 다른 채널 재측정은 해결됐다. 그러나 시간 순서 공백은 CFR-01로 남는다. “같은 경로”는 “같은 음향 조건/올바른 전후 순서”의 증거가 아니다.
2. CF2-02 기준 CAL의 모순 셋은 모두 보류된다. 표시용 정책은 실제 사용처 때문에 CFR-02 수정이 필요하다.
3. CF2-03은 해결됐다. 정규 시험은 첫 검사 verifiedBySignal=true, 두 번째 검사에 실제 delay, 취소 직전 tap/busy 존재, 취소 후 증거와 tap/busy 제거를 단언한다. 이 계약의 JVM 취소 시험으로 충분하며 실기기 lifecycle 전체 검증을 대신하지 않는다.
4. 순수 함수는 통과하나 흐름이 잘못 조합되는 자리는 CFR-01이다. 새 stepIdentities는 각 자료의 입력을 보장하지만 순서를 보장하지 않는다.
5. restartMeasurement의 신원/키/이전값 제거도 코드에서 확인했다. 이번 범위 밖 기능이나 미제공 최신 커밋을 승인한 것은 아니다.

## 전달 자료

- 제안 패치: `2026-09-27-cf2-proposed.patch` (이 보고서와 같은 outputs 폴더).
- 고정 소스·실행 스크립트·로그·probe: `../work/cf2-20260927/`.
- 주요 로그: `dsp-test-output.txt`, `app-calibration-test-output.txt`, `independent-wizard-output.txt`, `independent-column-output.txt`, `proposed-output.txt`, `proposed-column-output.txt`.
- 제안 사본: `ProposedWizardCoordinator.kt`, `ProposedCalibrationSign.kt` 및 `ProposedFixProbe.kt`, `ProposedColumnProbe.kt`.

독립 원본 probe는 잘못된 Pass가 관측되는지를 check하므로 exit 0을 승인으로 해석하면 안 된다. 제안 probe는 반대로 차단을 기대한다. 취소의 옛 잘못된 fixture를 설명하는 CANCEL_FIXTURE 출력은 비교용으로 남아 있으며, 고친 정규 시험이 여전히 잘못됐다는 뜻이 아니다.

CFR-01 수정·재검증과 CFR-02 처리 후 이번 범위의 승인 판단이 가능하다. 기준 장비 부재와 Android 실기기 검증 공백은 별도로 기록한다.
