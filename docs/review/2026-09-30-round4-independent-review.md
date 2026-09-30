# 4회차 독립 검토 — 2026-09-30

**판정: Recording-E 승인 보류. Critical 0 / High 3 / Medium 2.**

원본 보존과 정상 복원, 그래프 캐시 수정은 확인했다. 그러나 게시/복구와 보정 소유권의 핵심 문제가 남았다. 아래 두 저장 반례는 동시 스레드 없이 발생한다.

## 검증 범위

- 요청 고정본: **1cbcb230685d243889e90d0b7b314092a0adeb23** (#112·#113). 시작 HEAD는 `dc1d2d856a4ab5f565be9f733ac2561407b9d7d3`였고 당시 차이는 요청서뿐이었다. 검토 중 HEAD가 `764cc50`으로 이동했으며 이후 변경은 본 고정본 검증에 포함하지 않는다.
- git archive 격리본: `build/independent-review/round4-20260930/source`.
- 독립 실행: DSP **633**, app **1,075**, 합계 **1,708** 시험, 실패/건너뜀 0. `:app:assembleDebug` 성공. 별도 반례 **5건 중 4건 실패, 원본 복원 1건 통과**.
- 이번 회차 실기기·에뮬레이터 계측 및 lint는 실행하지 않았다. 구현자의 실기기 89건 주장을 독립 확인한 것으로 세지 않는다.
- production 소스 변경·커밋·푸시·PR 조작 없음. 검토 중 별도 작업의 `dsp/src/test/kotlin/kr/joa/selahrta/dsp/NyquistProbe.kt`가 나타났으며 이 검토에 포함하거나 수정하지 않았다.

## R4-01 — High: 복구 쓰기 실패를 숨겨 혼합된 기록을 성공으로 반환

**위치:** `app/src/main/java/kr/joa/selahrta/recording/SessionStore.kt:95–103`, `SessionReanalyzer.kt:102–103`.

`recover()`는 활성 timeline을 먼저 덮고 meta를 덮는다. 두 번째 쓰기가 실패하면 catch에서 그냥 return한다. 호출자는 복구 실패를 모르므로 `readMeta()`가 옛 겉장을 정상 반환하고 재분석도 성공 처리한다. ready 파일이 남는다는 사실만으로 현재 독자가 보호되지 않는다.

**독립 재현:** Windows에서 기존 meta.txt를 읽기 전용으로 설정하고 실제 쓰기 거부를 먼저 단언했다. 이후 정상 WAV를 재분석했다.

```text
R4_RECOVERY success=true returnedOffset=100.0 readable=true timelineChanged=true ready=true
```

**사용자 영향:** 새 타임라인을 옛 보정/가중으로 해석하면서 “다시 분석했습니다”라고 안내한다. 저장공간 부족 등 실제 쓰기 실패에서도 같은 제어 흐름이며, 이 시험은 읽기 전용 파일로 지속 실패를 주입했다.

**권장 수정:** 최소한 복구 실패를 예외/Result로 전파하고 완료 전 읽기를 막는다. 첨부 패치는 이 좁은 방어만 한다. **부분 덮어쓰기 자체를 원자적으로 만들거나 원본 상태로 롤백하는 해결책은 아니다.** 최종적으로 아래 R4-02의 snapshot API와 함께 불변 revision + 원자적 활성 포인터를 사용하거나, 저널을 유지한다면 완료까지 모든 reader/writer를 동일 세션 잠금과 실패 상태로 통제해야 한다. 프로세스 종료와 전원 손실의 내구성 계약도 구분하고 필요한 fsync를 설계한다.

**회귀:** timeline/meta 쓰기 각각 실패, 실패 중 반복 read/list/export, 용량 회복 후 복구, 게시 전후 프로세스 중단. 성공 반환과 실제 게시 revision의 일치까지 단언한다.

## R4-02 — High: 재시작 후 목록의 겉장과 상세의 타임라인이 다른 판

**위치:** `SessionStore.kt:181–190`, `app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:3345–3356,3392–3403`.

`list()`는 `recover()`를 호출하지 않고 meta.txt를 직접 읽는다. 상세/CSV는 목록에서 받은 meta를 그대로 사용하면서 `timelineFile()`에서 뒤늦게 복구한다. 따라서 “읽는 자리마다 먼저 복구한다”는 주장이 성립하지 않는다.

**독립 재현:** 활성은 offset 100, staging은 offset 120의 완전한 새 판으로 만들고 staged.ready를 둔다. 이는 게시 직후 종료된 상태다. 다음 실행의 목록→상세 순서로 호출하면:

```text
R4_LIST listedOffset=100.0 activeOffset=120.0 newTimeline=true
```

**사용자 영향:** 상세 그래프와 CSV는 새 행에 옛 epoch/보정을 적용하고, 보고서는 옛 요약을 표시한다. **동시 실행 없이도 발생한다.** 또한 화면에서 CSV·메모·삭제가 재분석 중 비활성화되지 않고 별도 IO 코루틴으로 실행되므로 “한 화면이라 경쟁하지 않는다”는 전제도 안전하지 않다.

**권장 수정:** `list()`도 실패를 드러내며 복구하되, 그것만으로 끝내지 않는다. `openSession(meta)`/`exportSession(meta)`에 과거 meta를 전달하는 대신 **id로 한 번에 meta+timeline을 가져오는 `readSnapshot(id)`**를 제공한다. 독자는 한 불변 revision을 고정한다. 저널 방식이면 snapshot을 읽는 전체 구간과 게시·복구·삭제·메모 갱신을 같은 잠금으로 보호하고, 외부로 가변 File 경로를 반환해 잠금을 빠져나가지 않게 한다.

```kotlin
// 인터페이스 제안. 구현은 revision 수명 및 stream 닫기까지 포함해야 한다.
store.withSnapshot(id) { snapshot ->
    SessionExport.writeCsv(snapshot.meta, snapshot.timeline, out)
}
```

**회귀:** 위 list→open 재시작 반례, list→CSV, 복구 사이의 reader, 재분석 중 삭제/메모/내보내기. meta와 행을 각각 검사하지 말고 동일 판의 한 쌍인지 단언한다.

## R4-03 — High: 기기는 같지만 입력 소스가 다른 보정을 허용

**위치:** `app/src/main/java/kr/joa/selahrta/recording/ReanalysisIdentity.kt:40–74`, `ui/CaptureViewModel.kt:3551–3572`; 비교 기준은 `calibration/CalibrationProfile.kt:16–46`.

새 검사는 deviceKey·MicKind·채널만 비교한다. 기존 `CalibrationKey`는 **deviceKey + CaptureSource + 채널**이다. 즉 같은 기기라도 VoiceRecognition과 Unprocessed는 다른 보정 소유자다. 기록에는 `conditions.audioSource`가 이미 있지만 검사 인자로 현재 source를 받지 않는다.

**독립 재현:** 같은 deviceKey/종류/채널이고, 기록 source는 VoiceRecognition, 현재 보정 키는 Unprocessed인 경우:

```text
R4_IDENTITY saved=cal|k|VoiceRecognition current=cal|k|Unprocessed blocked=null
```

이 시험은 순수 판정 함수와 실제 CalibrationKey의 불일치를 검증한다. USB 실물 전환을 시험한 것은 아니다.

**사용자 영향:** 서로 다른 입력 처리 경로의 감도 보정이 과거 녹음에 적용된다. 현재 입력에 보정이 올바르게 연결돼 있다는 불변식을 가정해도 이 누락은 남는다.

**권장 수정:** 최소한 `openedAudioSource`를 받아 null/불일치를 막는다. 더 명확한 API는 저장된 전체 CalibrationKey와 현재 적용된 보정의 실제 key를 비교하는 것이다. 채널의 mono null 정규화도 동일 규칙을 사용한다. 경로 주소·활성 마이크 조합 등 프로파일 적용 조건까지 기록 당시 조건과 비교하고, 정보가 없으면 근거 없는 일치로 처리하지 않는다.

```kotlin
if (meta.conditions.audioSource == null || openedAudioSource == null ||
    meta.conditions.audioSource != openedAudioSource) {
    return "기록 당시와 현재 입력 경로가 같다는 근거가 없어 적용하지 않습니다."
}
```

**회귀:** 동일 기기/채널에 source만 변경, source 미기록, 실제 저장 키가 다른 활성 보정, 동일 전체 키의 정상 갱신. ViewModel에서 검사한 보정과 전달한 설정이 같은 snapshot인지도 확인한다.

## R4-04 — Medium: 새 곡선에 옛 곡선 이름과 확인 근거가 남음

**위치:** `SessionReanalyzer.kt:198–241`의 `merged`, `MeasurementReport.kt:297–304`, `CaptureViewModel.kt:3562–3572`.

가중과 referenceOnly 상태 수정은 반영됐지만 새 설정에는 곡선 값만 전달되고 label·reading 확인 여부·calibration source가 없다. merged도 기존 `curveLabel`, `conditions.curveReading`, `curveReadingConfirmed`, `calibrationSource`를 남긴다.

**독립 재현:** old-mic.cal로 표시된 기록에 별도의 새 곡선을 넣어 재분석했다.

```text
R4_DESCRIPTOR curveApplied=true curveLabel=old-mic.cal
```

**영향:** 보고서의 “주파수 보정” 출처와 사람이 확인했다는 근거가 새로 계산한 곡선과 다를 수 있다. 곡선 해제 시 숨겨지는 라벨만을 문제 삼는 것이 아니라, **새 곡선 적용 상태에서 옛 이름이 표시되는 경우**다.

**수정:** 분석 descriptor에 곡선 이름/해시/reading/확인 상태와 실제 활성 보정 출처를 포함하고 계산과 결과 메타가 이를 공유하게 한다. FactoryDefault와 Uncalibrated도 같은 참고용이라는 이유로 원래 근거 차이를 잃지 않도록 명시적으로 전달한다. 기억한 옛 metadata를 새 설정의 출처로 대체하지 않는다.

**회귀:** 서로 다른 곡선 교체, 곡선 해제, 부호 확인 상태 변경, 전대역 보정 출처 변경 후 숫자뿐 아니라 보고서·직렬화 메타도 검사한다.

## R4-05 — Medium: 앱의 테스트 신호를 디지털 내부 루프로 오인한 안내

**위치:** `docs/manual/calibrator-nd9-check.md:413–416`; 실제 출력은 `audio/SignalPlayer.kt:56,436`.

이전의 “20이 아니면 AGC”, “1dB 차이면 필터 결함” 단정은 낮췄다. 그러나 새 절차는 도구→테스트 신호를 쓰면 “커플링도 배경 소음도 없다, 거기서도 20이 아니면 앱 안쪽”이라고 한다. 해당 기능은 AudioTrackSink로 재생한다. 음원 샘플이 디지털이라는 사실은 그 출력과 마이크 입력 사이의 스피커·공간·잡음을 제거하지 않는다. 이 안내에서 사용할 내부 PCM→DSP 직결 루프는 확인되지 않는다.

**영향:** 스피커/마이크/환경의 비선형·잡음 문제를 앱 DSP 결함으로 다시 잘못 판정할 수 있다.

**수정:** 기존 단정 철회는 유지하고 해당 문장을 삭제하거나 “스피커로 재생하면 음향 경로가 포함된다”고 명시한다. 순수 앱/DSP 검증은 생성 PCM을 실제 분석 엔진에 직접 넣는 JVM 시험 또는 명시적인 내부 입력 모드로 수행한다. 물리 루프백도 인터페이스 이득·왜곡 조건을 별도로 포함한다.

**회귀:** 문서에 디지털 직결 시험 명령/시험 이름과 실제 스피커 시험을 분리해 적고, 서로 같은 증거라고 설명하지 않는다. 이번에 음향 재생은 수행하지 않았다.

## 이전 지적 판정과 질문 답변

| 이전 항목 | 이번 판정 |
|---|---|
| R3-01 원본 meta 소실 | **수정 확인.** 원본 meta+timeline 동일성 및 정상 복원 독립 시험 통과. 복원 게시 경로의 R4-01·02는 별도 미해결 |
| R3-02 게시 원자성 | **미해결.** ready 파일만으로 독자 일관성/실패 격리가 보장되지 않음 |
| R3-03 보정 소유권 | **부분 수정.** 다른 device/채널 차단은 개선, source 키 누락 R4-03 |
| R3-04 결과 설명 | Peak·분석 가중·미보정 경고 수정 확인, 곡선/출처는 R4-04 잔여 |
| R3-05 그래프 캐시 | 수정 확인. meta를 remember 키에 포함. 이번 회차 추가 Android 계측은 하지 않음 |
| R3-06 문서 단정 | 기존 문구 완화 확인. 새 디지털 시험 안내는 R4-05 수정 필요 |

**Peak C→Z 변경은 옳다.** `SplEngine.process`는 가중 전 PCM으로 blockPeakAbs를 구해 blockPeakDbfs를 만든다. 기록기가 저장하는 것은 이 값이다. 이전 독립 시험에서 “SPL 가중 C이므로 저장 Peak도 C여야 한다”고 단언한 것은 검토자 오류였다. 이번에는 Z가 실제 계산을 설명한다는 점을 확인했다. 화면의 weighted Peak와 동일한 측정치를 저장하려는 제품 요구가 있다면 별도 변경으로 다루되, 이 라벨 수정을 거절할 이유는 없다.

**원본 복원에 현재 마이크 검사를 걸지 않는 구분은 타당하다.** 새 보정을 적용하는 일이 아니라 저장 당시 meta+행을 복원하기 때문이다. 현재 마이크 대신 원본 무결성과 게시 일관성을 검증해야 한다.

**모르면 막는 정책도 허용된다.** 이번 문제는 차단 정책 자체가 아니라 “같다”를 판정하는 키가 실제 보정 키보다 약하다는 것이다.

## 해결 순서와 증거

1. R4-01 최소 방어로 복구 실패가 성공처럼 보이지 않게 한다. 첨부 패치는 **부분 완화**이며 저장 설계 승인용 패치가 아니다. 격리본에 적용해 해당 독립 반례 1건이 실패→통과함을 확인했고, `git apply --check`도 통과했다. 검증 뒤 격리본 소스는 복구했다.
2. R4-02를 포함해 reader까지 단일 revision/snapshot으로 연결한다. list에 recover 한 줄만 추가하고 완료로 판단하지 않는다.
3. 보정 전체 키와 분석 descriptor를 한 snapshot으로 전달한다(R4-03·04).
4. 문서의 디지털 직결/음향 경로를 분리한다.

회귀 소스: `2026-09-30-round4-independent-regression.kt`. 앱 test의 recording package에 넣어 `:app:testDebugUnitTest --tests '*Round4IndependentTest*'`로 실행한다. 저장 실패 주입은 Windows 파일 읽기 전용 속성을 이용하며, 주입 성공을 먼저 단언한다. 다른 OS는 동등한 실패 주입으로 바꿔야 한다.

원시 결과는 `build/independent-review/round4-20260930/`의 baseline.log, baseline-counts.txt, probes-final.log, independent-results.xml에 있다. build 경로는 Git 보존되지 않을 수 있다. 기기·교정기 부재와 무관하게 재현되는 항목들이며 실제 음압 정확도 검증과 분리한다.
