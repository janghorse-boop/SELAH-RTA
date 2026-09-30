# 3회차 독립 검토 — 2026-09-30

판정: **Recording-E 승인 보류. Critical 0 / High 3 / Medium 3.** 기존 R2-01~04 수정은 확인했다. 재분석의 원본 보존·저장 원자성·보정 소유권은 별도로 고쳐야 한다. 시험 통과 수를 이 세 계약의 증거로 대신할 수 없다.

## 범위와 실행 경계

- 요청: `PENDING-REVIEW.md` 3회차, PR #99~#108. 고정본 **78aa7cbe578cd55616da43b6d6b7b136da2527b9**를 git archive로 분리해 검토했다.
- 시작 당시 HEAD d38013f, 검토 도중 HEAD 10f310c로 문서 변경이 들어왔다. 이 보고서의 판정·위치는 고정본 기준이며 그 뒤 문서 답변은 승인 범위가 아니다.
- production 소스는 변경하지 않았다. 수정안 검증은 `build/independent-review/round3-20260930/source`에서만 수행하고 해당 소스도 복구했다. 커밋·푸시·PR 회신·병합은 하지 않았다.
- 독립 전체 JVM 실행: DSP **633**, app **1,055**, 합계 **1,688**, 실패 0. debug APK와 androidTest APK 빌드 성공. 이번 회차 lint는 실행하지 않았다.
- 추가 독립 JVM 반례 **4건 모두 실패**하여 아래 원본/저장/메타데이터 문제를 재현했다. 최초 probe의 enum 이름 컴파일 오류는 검토자 오류이며 제품 실패로 세지 않았다.
- 별도 무음·헤드리스 에뮬레이터 5586에서 기존 계측 **18건 통과**, 추가 그래프 반례 **1건 실패**. 수정안 적용 후 그래프 반례와 기존 seek 시험 **2건 통과**. 다른 작업의 5584는 사용하지 않았고 5586은 종료했다.
- 실제 생성한 PDF 2개, 총 **4페이지를 렌더링해 눈으로 확인**했다. 긴 메모의 마지막 결과 페이지에도 반복 경고가 남고, 확인한 페이지에서 잘림·겹침은 없었다.
- 실기기 음향 출력/seek 지연, USB 조합, 교정기 정확도, 장시간 부하 및 전체 Android 98건은 이번 회차 검증하지 않았다.

## R3-01 — High: 원본 타임라인을 해석할 원래 보정표를 잃는다

**위치:** `app/src/main/java/kr/joa/selahrta/recording/SessionReanalyzer.kt:65–66,117–135`.

원본 보존은 `timeline-v1.bin` 복사뿐이다. 새 `meta.txt`는 기존 offset·epochs·events를 새 분석 값으로 덮는다. 원본 행은 raw 값과 epoch 참조를 가지므로 파일 바이트가 남아도 원래 SPL과 여러 보정 구간을 그대로 복원할 근거가 사라진다.

**재현:** offset 100으로 저장한 기록을 120으로 재분석했다. 원본 bin은 남지만 파일 목록은 `[meta.txt, sound.wav, timeline-v1.bin, timeline.bin]`이며 원래 epoch/offset을 보존한 메타데이터가 없다. `originalEpochMappingMustRemainRecoverable` 실패.

**영향:** “처음 잰 값도 그대로 남아 있습니다” 안내와 달리 원래 판독·내보내기를 재현할 수 없다.

**수정:** 첫 재분석 전에 원본의 **timeline + meta**를 한 불변 revision으로 보존한다. 원래 epoch/event, 보정·곡선 식별자, 분석 설정을 함께 남기고 반복 재분석에서 바꾸지 않는다. 오디오는 같은 세션 아래 공유해도 되며 새 세션 폴더를 만드는 것이 필수는 아니다.

**회귀:** 다중 epoch의 MAX/Peak 소유 epoch를 포함한 기록을 두 번 재분석하고 재시작한 뒤, 원본 표시와 CSV가 원본 저장 시점과 동일함을 확인한다.

## R3-02 — High: 실패를 반환해도 타임라인은 이미 교체된다

**위치:** `SessionReanalyzer.kt:69–88`.

타임라인 교체 후 `writeMeta`를 호출한다. 메타 쓰기가 실패하면 새 행과 옛 메타가 함께 남는다. `renameTo` 실패 시 `writeBytes(readBytes())`로 기존 파일을 덮는 경로는 중단 시 부분 파일도 남길 수 있다. 두 파일을 차례로 rename하는 것만으로는 트랜잭션이 되지 않는다.

**재현:** 정상 기록의 `meta.txt.tmp`를 디렉터리로 만들어 메타 쓰기 실패를 유발했다. 결과는 `failure=true, sameTimeline=false, metaWeight=A, oldWeight=A`. 실패했는데 타임라인 바이트는 새 C 분석으로 바뀌었다. `metaWriteFailureMustNotReplaceTimeline` 실패.

**영향:** 실패 안내 후에도 기존 측정값이 변하고, 그래프·CSV·PDF가 서로 맞지 않는 설정으로 값을 해석한다.

**수정:** 새 revision의 timeline과 meta를 별도 경로에 완성·검증한 뒤, 활성 revision 포인터 하나를 원자적으로 게시한다. 독자는 그 revision을 고정해서 읽는다. 덮어쓰기 fallback은 제거하고, 실패 시 기존 활성 revision을 유지한다. 동일 세션의 수정·삭제·내보내기와 경쟁하는 정책도 정한다.

```kotlin
// 설계 예시: 실제 저장소의 sync/atomic-replace 구현과 함께 완성해야 한다.
val revision = writeAndValidateRevision(newTimeline, newMeta)
// 위 단계의 실패는 active를 바꾸지 않는다.
publishActiveRevisionAtomically(revision.id)
```

**회귀:** timeline/meta 쓰기, flush, 게시 각 단계 실패·프로세스 종료 주입. 재시작과 동시 reader에서 이전 또는 새 완전한 revision만 읽혀야 한다. 이 보고서에서는 메타 쓰기 실패 지점만 실행 재현했다.

## R3-03 — High: 현재 입력의 보정을 다른 입력으로 녹음한 기록에 적용한다

**위치:** `app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:3521–3542`.

`reanalyzeSession`은 선택 기록의 입력 신원과 비교하지 않고 현재 `st.calibration.offset`, `isReferenceOnly`, 활성 `st.curve`를 가져온다. 전달하는 설정에는 이 보정이 어느 입력 소유인지 확인할 정보가 없다. `channelIndex = meta.channelIndex`만으로 마이크·USB·이득 조합의 일치를 증명하지 못한다.

**논리적 재현 경로:** 내장 마이크 기록 A를 저장 → 다른 USB 입력 B의 보정/곡선을 활성화 → A를 재분석. 현재 경로에는 A/B 불일치 거절이나 명시적인 변환 승인 지점이 없다. 이 항목은 코드 경로 검토이며 실제 USB 기기 재현은 하지 않았다.

**영향:** 다른 마이크의 감도·주파수 보정으로 과거 기록이 바뀌며 보정 완료 수치처럼 제시될 수 있다.

**수정:** 기록의 입력 신원/채널/소스와 연결된 보정만 선택하도록 한다. 보정 설정을 숫자로 해체하기 전에 소유자·곡선 출처·확인 상태를 검증하고, 일치하지 않거나 과거 신원 정보가 없으면 참고용 결과로 제한하거나 적용을 막는다. 사용자가 고르는 재분석 옵션과 실제 적용 근거를 함께 저장한다.

**회귀:** A 기록+B 현재 보정, 동일 이름의 다른 USB, 신원 미기록, 같은 입력의 정상 보정 갱신. 불일치가 적용 전에 차단되고 정상 갱신만 통과하는 ViewModel/저장 통합 시험이 필요하다.

## R3-04 — Medium: 새 분석의 숫자와 신뢰도·가중치 설명이 다르다

**위치:** `SessionReanalyzer.kt:117–135`, `Reanalysis.kt:80–87`, `MeasurementReport.kt:226–246`.

`merged`는 일부 설정만 바꾸고 `conditions.calibrationState`, `peakWeighting`, `analysisWeighting`, 곡선 설명 등을 기존 값으로 남긴다. 재분석 peak는 선택 SPL 가중 엔진에서 나오지만 옛 peak 라벨이 유지된다. RTA는 새 엔진의 기본 가중을 쓰면서 옛 분석 가중 설명을 유지할 수 있다.

**재현:** 새 `referenceOnly=true`인데 옛 `GlobalCalibrated`가 남아 `reportWarnings`에 미보정 경고가 없다. 별도 시험에서 새 C 분석인데 `peakWeighting=A`가 남았다. `newReferenceOnlyResultMustHaveReferenceWarning`, `peakWeightMetadataMustFollowReanalysis` 실패.

**영향:** 사용자가 결과의 보정 여부와 가중치를 잘못 이해하며 PDF의 경고 반복 기능도 없는 경고를 복구하지 못한다.

**수정:** 녹음 당시 사실과 재분석 설정을 구분한다. 실제 계산에 전달한 SPL/Peak/RTA 가중, 시간가중, 곡선 식별자·적용 여부, 새 calibration 상태/출처를 하나의 분석 descriptor에서 메타로 만든다. 참고용 결과에 옛 보정 상태를 남기지 않는다.

**회귀:** 보정→참고용 및 역방향, C Peak, A/Z RTA 저주파 입력, 곡선 교체/해제 각각의 결과·표시·PDF 메타를 함께 단언한다.

## R3-05 — Medium: 같은 세션의 메타 변경이 그래프 캐시를 무효화하지 않는다

**위치:** `app/src/main/java/kr/joa/selahrta/ui/components/SplTimelineGraph.kt:79–80`.

`timelineSeries`와 `timelineMarkers`는 meta를 읽지만 remember 키는 `meta.id`만 본다. 같은 id·rows에서 offset/epoch/event가 바뀌면 옛 결과가 남는다.

**재현:** 같은 세션 offset 100→103. 순수 함수의 top은 71→74인데 Compose 캡처의 `changedPixels=0`. 새 계측 `sameSessionNewCalibrationMustRedraw` 실패. 제안 패치의 `remember(meta, …)` 적용 후 해당 시험과 기존 seek 시험 2건 통과했다.

**영향:** 메타 갱신 후 그래프가 이전 보정 값을 보여줄 수 있다. ViewModel의 중간 빈 rows 상태가 실제 렌더링되면 재생성될 수 있으므로, 모든 재분석 화면에서 반드시 발생했다고 주장하지 않는다. 그 우연에 컴포넌트의 갱신 계약을 맡겨서는 안 된다.

**수정/회귀:** 첨부 최소 패치와 계측 시험. 추가로 같은 id의 epoch와 event만 바꾸는 시험을 보강한다. 패치는 이 항목만 해결하며 R3-01~04 해결책은 아니다.

## R3-06 — Medium: 교정 절차서가 관측 이상을 특정 결함으로 단정한다

**위치:** `docs/manual/calibrator-nd9-check.md:82–84,92–95`.

1 kHz 시험에서 A/C/Z가 1 dB 이상 다르면 “가중 필터 결함, 커플링과 무관”, 94/114 차이가 20이 아니면 “폰의 자동 게인·압축”이라고 단정한다. 이상적인 순음·선형·안정 입력이라면 유용한 점검이지만, 이 절차는 누설/주변 소음의 가중별 기여, 교정기·결합 상태, 입력 clipping·왜곡 등의 대안을 배제하지 않는다. 같은 틈이라도 신호와 배경 잡음의 에너지 합은 레벨 차이를 보존하지 않는다.

**영향:** 정상 DSP를 오판하거나 입력/음원 문제를 AGC로 잘못 진단할 수 있다. 제시한 숫자만으로 실제 SPL 교정 정확도가 검증되는 것도 아니다.

**수정:** “추가 확인이 필요한 이상 징후”로 표현하고, 디지털 순음 시험으로 DSP 경로를 분리한 뒤 실제 입력의 SNR·clipping·반복성·교정기 조건을 점검한다. 0.3/1 dB 기준은 근거 또는 잠정 점검 기준임을 밝힌다. 음향 시험 없이 숫자 기준을 새로 정하지 않는다.

**회귀:** 순음과 같은 순음+배경 소음의 가중 비교, 선형 입력에 일정 배경을 더한 94/114 에너지 차이 반례를 문서에 포함한다. 이번 회차 실물 교정기의 특성과 커플링은 검증하지 않았다.

## 기존 지적과 승인 가능한 부분

- **R2-01:** 무음을 유한 floor로 유지하고 비유한 저장을 거절하는 수정 확인. 전체 JVM 통과.
- **R2-02:** 캡처 스레드에서 시작 경계를 잡고 완전히 밖인 창을 합산하지 않는다. 부분 중첩 창은 전체 창을 포함한다는 계약과 coverage의 시간 합집합 계산은 서로 구분되어 있다.
- **R2-03:** close 시 평균·창 수·coverage를 같은 잠금 안에서 반환하고 이후 add를 닫는 코드 및 호출부 확인.
- **R2-04:** 옛 평균의 오차 방향 단정을 철회하고 실제 PCM 입력의 청크 분할 독립성을 확인하는 시험으로 바꾼 점 확인. 실제 시간 원점·부분 창 정책까지 중립이라는 뜻은 아니다.
- 실제 저장 경로의 기존 coverage 계측 통과: `saved=1 cov=1.0 windows=236 hop=2048 notice=null`. 분포 조사 C는 이 계약/버전 정보를 함께 남겨 진행할 수 있다. D의 거절 문턱은 이 한 표본으로 승인하지 않는다. 사용자가 이미 승인한 C·D 진행 자체를 다시 허락받을 필요는 없다.
- Recording-D의 기존 tap→seek 계측 통과. 실제 소리가 그 시점으로 이동하는 지연·연속성은 별도 기기 확인이 남는다.
- PDF 긴 메모/반복 경고 계측과 4페이지 렌더 확인은 통과. 이는 R3-04처럼 잘못된 입력 메타까지 검증했다는 뜻이 아니다.

## 다음 수정의 권장 순서

1. R3-01·02를 revision 저장 설계로 함께 해결한다. 원본과 활성 revision을 reader/export까지 일관되게 연결한다.
2. R3-03·04를 입력 신원 검증과 완전한 분석 descriptor로 해결한다.
3. R3-05 최소 패치를 적용하고 그래프 계측을 상시 시험으로 넣는다.
4. R3-06 진단 문구를 제한하고 실제 교정은 장비가 준비된 뒤 수행한다.

위 보류는 94 dB 기준 장비가 없어서 생긴 것이 아니다. 저장·보정 소유권·표시 계약은 JVM 및 에뮬레이터로 먼저 해결할 수 있다. 실제 음압 정확도의 검증은 별도다.

## 재현 자료

- `2026-09-30-round3-independent-regression.kt`: 기존 SessionReanalyzerTest fixture를 사용하는 독립 JVM 반례 4건. 해당 package의 test 경로로 복사해 실행한다.
- `2026-09-30-round3-graph-independent-regression.kt`: androidTest 경로의 Compose 반례.
- `2026-09-30-round3-graph-proposed.patch`: 격리본에서 빌드·계측 확인한 부분 수정안.
- 로컬 원시 증거: `build/independent-review/round3-20260930/`의 `baseline.log`, `probes-final.log`, `independent-jvm-results.xml`, `android-selected.log`, `android-logcat.txt`, `graph-fix-build.log`, `graph-fix-android.log`, `graph-fix-logcat.txt`, PDF·PNG. build 아래 자료는 Git 보존을 전제하지 않는다.
