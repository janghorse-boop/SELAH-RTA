# 5회차 독립 검토 — 2026-09-30

**판정: 승인 보류. Critical 0 / High 1 / Medium 3.**

최신 요청서는 `PENDING-REVIEW.md` 5회차(#115~#121)다. 이전의 복구 실패 숨김, 재시작 뒤 목록/상세 불일치, 입력 source 누락, 곡선 신원 누락과 문서 오류는 수정됐다. 그러나 저장 경합은 실제 데이터 손실로 재현됐으므로 이번에 제안한 “잠금 없이 유지”하는 절충은 받아들이기 어렵다.

## 고정본과 실행 증거

- **aafd85db222947745ed543574cdf5cb6577f44a1**를 git archive로 분리했다. 검토 시작 HEAD는 db978fe, 진행 중 f3491ab로 바뀌었다. 고정본 이후 차이는 요청 문서이며 코드 판정은 고정본에 한정한다.
- 격리본: `build/independent-review/round5-20260930/source`.
- 기존 전체 JVM **1,731건**: DSP **638**, app **1,093**, 실패/건너뜀 0. debug 앱 빌드 성공.
- 독립 반례: **3건 중 2건 실패**, 잡음 바닥을 제공한 대조군 1건 통과. 메모 경합은 별도 저장 스레드를 사용한 최종 시험에서도 실패했다.
- 이번에는 Android 계측·실기기 USB·음향 시험·lint를 실행하지 않았다. 구현자가 보고한 USB 무음 원인이나 실기기 계측 결과를 독립 확인한 것으로 세지 않는다.
- 두 제안 패치를 격리본에 적용한 뒤 **DSP 전체 640건(기존 638+독립 2) 통과**, debug 앱 빌드 성공 및 두 패치의 `git apply --check` 통과를 확인했다. 이후 격리본의 production 소스도 원복했다. 지원 속성 패치의 실제 Android 보고서 동작까지 계측했다는 뜻은 아니다.
- 운영 소스 변경·커밋·푸시·PR 회신 없음. 제안 패치의 빌드/시험은 격리본에서만 수행한다.

## R5-01 — High: 세션 연산이 겹치면 성공한 저장을 잃고 판도 섞일 수 있음

**위치:** `recording/SessionReanalyzer.kt:53,81–103,150–181`, `recording/SessionStore.kt:169–172,198–208,258–262`, `ui/CaptureViewModel.kt:3434–3454,3690` (모두 `app/src/main/java/kr/joa/selahrta/` 아래).

`run()`은 분석 시작 전에 old meta를 읽고, 분석이 끝나면 그 old를 기반으로 새 meta를 게시한다. 그 사이 메모를 저장해도 새 meta에 합쳐지지 않는다. readSnapshot도 meta와 행을 연속으로 읽을 뿐 writer를 배제하지 않는다. CSV는 readSnapshot을 쓰지 않고 recover→readMeta→timelineFile을 따로 부른다. 화면의 삭제·메모·내보내기도 재분석 중 허용된다.

**실행 재현:** 분석 진행 콜백 시점에 별도 스레드에서 `setMemo`를 실행해 성공시켰다. 분석 게시 후 메모는 빈 문자열로 돌아갔다.

```text
R5_MEMO saveAcknowledged=true finalMemo=
```

이 시험은 저장소의 실제 메모/재분석 API를 사용한다. 올바른 잠금 구현은 메모 저장을 분석 게시 뒤로 미뤄도 시험을 통과할 수 있게 만들었다. 최초 단일 스레드 교차 실행 결과도 같았으나, 최종 회귀 소스는 별도 스레드 버전이다.

**논리적 추가 경로:** reader가 옛 meta를 읽은 뒤 writer가 게시·복구하고, reader가 새 timeline을 읽으면 여전히 서로 다른 판이다. 이 타임라인 경쟁 자체를 기기/스트레스 시험으로 재현했다고 주장하지 않는다. 코드상 임계구역이 없고 UI에서 해당 연산을 함께 허용한다는 근거다.

**영향:** 저장 성공한 사용자 메모 소실, 그래프·CSV의 보정/epoch 혼합, 삭제와 게시의 충돌. 재시작 반례가 고쳐졌다는 것만으로 runtime 안전성을 승인할 수 없다.

**구체적인 수정 방향 — 잠금은 세션 단위가 적합하다.**

1. 키는 **canonical root + session id**. 동일 폴더를 가리키는 서로 다른 SessionStore 인스턴스도 같은 잠금을 써야 한다. 메서드별 서로 다른 잠금이나 instance의 `synchronized`만으로는 부족하다.
2. 최소 수정은 재분석의 **old 읽기→원본 보존→분석→게시→복구 전체**, restoreOriginal 전체, setMemo의 read-modify-write 전체, delete, recover, readSnapshot 전체를 같은 재진입 잠금으로 보호하는 것이다. 분석이 긴 동안 같은 세션 편집은 기다린다. UI/main/capture 스레드에서는 기다리지 말고 IO 작업으로 수행하며 진행 중 상태를 표시한다.
3. CSV는 `withSnapshot`에서 meta와 행을 읽고 직렬화가 끝날 때까지 같은 잠금을 유지하거나, 잠금 안에서 만든 불변 snapshot만 내보낸다. 가변 File 경로를 반환한 뒤 잠금을 풀면 다시 틈이 생긴다. 목록도 각 세션 잠금 안에서 복구+meta 읽기를 한다. 목록 전체 잠금은 필요 없다.
4. 파일을 읽는 동안 오래 잠그기 싫다면 불변 revision으로 옮긴다. 분석을 잠금 밖에서 한다면 게시 시 revision 비교/재시도 및 최신 메모 병합 규칙이 필요하다. 단순히 마지막 두 write만 잠그면 이번 메모 반례는 남는다.
5. 잠금 레지스트리에서 기다리는 사용자가 있는데 lock을 제거·교체하지 않는다. 아래 코드는 범위를 설명하는 설계 예시이며 그대로 적용 가능한 완성 패치는 아니다.

```kotlin
sessionLocks.withLock(canonicalRoot, id) {
    recoverLocked(id)
    val old = readMetaLocked(id)
    val next = analyzeAndMerge(old)
    preserveOriginalLocked(old)
    publishLocked(next)
    recoverLocked(id)
    readSnapshotLocked(id)
}
```

**필수 회귀:** 첨부 메모 시험, 두 reader+writer barrier 순서, CSV 중 게시, 분석 중 삭제, 복원과 메모 경쟁, 동일 세션의 두 Store 인스턴스. 서로 다른 세션 동시 작업도 함께 확인한다. fsync/전원 손실 내구성은 별도 계약으로 남기되 현재 runtime 경합 해결을 미루는 이유로 삼지 않는다.

## R5-02 — Medium: 한 장의 소리로 무음 비교 창을 “검증됨”으로 판정

**위치:** `dsp/src/main/kotlin/kr/joa/selahrta/dsp/DspProbe.kt:208–227`.

새 무음 검사는 **전체 프레임의 최대 광대역값**만 본다. 정작 DSP 변화를 비교하는 앞/뒤 창이 모두 무음이어도 중간 한 장이 크면 통과하며, 두 무음 창의 변화는 0으로 계산된다.

**실행 재현:** 60장 중 59장은 SILENCE_DBFS, 가운데 한 장만 대역별 -30dBFS. 잡음 바닥을 생략하면:

```text
R5_TRANSIENT verdict=NoTimeVaryingFound verified=true drift=0.0 shape=0.0 bands=31
R5_NOISE_CONTROL verdict=NotEnoughData verified=false
```

**검증 경계:** 두 번째 줄은 동일 입력에 실제 무음 잡음 바닥을 제공한 대조군이다. 정상 마법사의 잡음 측정 경로는 이 반례를 추가로 거절한다. 따라서 **마법사 전체를 우회해 프로파일이 실제 저장됐다는 결과가 아니다.** nullable noiseFloor를 받는 DSP/runner API의 검증 오판으로 Medium으로 분류한다.

**수정:** 판정에 실제 사용하는 앞/뒤 창 각각에 신호 근거가 있어야 한다. 첨부 패치는 기존 중앙값 방식과 -120dBFS 정책을 재사용해 두 창의 중앙값 중 작은 쪽을 검사한다. 문턱을 높이는 식으로 반례를 가리지 않는다. 더 강한 coverage·유효 프레임 조건은 목적과 실측을 명시해 추가한다.

**회귀:** 전체 무음, 중간 transient, 한쪽 창만 유효, 앞/뒤 창 소수 transient, 안정된 저레벨 유효 신호, 잡음 바닥 제공/미제공 양쪽. `Silent`와 `NotEnoughData` 구분은 사용자 조치가 달라 타당하지만 어느 쪽이든 검증 성공으로 세면 안 된다. **-120dBFS를 실제 모든 기기에서 유효한 교정 신호 기준으로 승인한 것은 아니다.**

## R5-03 — Medium: USB 시도 정책이 지원 근거로 저장됨

**위치:** `audio/MicSource.kt:105,117,268`, `audio/AudioFormatSpec.kt:168–169,191–192`, `recording/MeasurementReport.kt:78–83`.

`shouldTryUnprocessed(property, Usb)`는 property=false여도 true다. 그런데 이 값을 `tryOpen(..., unprocessedSupported)`로 넘겨 OpenedFormat과 기록에 저장한다. 결과적으로 실제 속성 false가 보고서의 “가공 없는 입력 지원: 예”로 바뀐다. USB 시도 자체와 실제 지원 증거가 섞였다.

**논리적 재현:** property=false, target=Usb → 시도 정책 true → OpenedFormat.unprocessedSupported=true. 이 경로는 소스와 기존 정책 시험으로 확인했으며 실제 USB를 연결해 보고서를 생성하지는 않았다.

또한 기존 trustNote는 source enum이 Unprocessed이면 “가공 없는 입력”이라고 단정한다. 이번에 공식 지원 속성이 false인 경로도 시도하므로 그 문구의 전제가 약해졌다. Android API는 UNPROCESSED가 제공되지 않으면 DEFAULT처럼 동작할 수 있다고 설명한다. **열림 성공만으로 무가공을 확정할 수 없다.** [Android AudioSource 문서](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#UNPROCESSED).

**영향:** 탐색적 USB 요청이 공식 지원/무가공 확인처럼 기록되고 표시된다. DSP 시간 변화 시험 역시 고정 처리를 배제하는 증거는 아니다.

**수정:** 첨부 최소 패치처럼 원래 속성값을 따로 저장하고 시도 정책과 분리한다. 표시도 `요청 소스=UNPROCESSED`, `지원 속성=false/미확인`, `실제 경로 확인`, `시간 변화 검사 결과`를 구별해야 한다. 최소 패치는 값의 오기만 고치며 기존 trust 문구 전체까지 해결하지 않는다.

**회귀:** property=false+USB의 성공/실패/fallback, property=true+내장, 지원 false인 채 source=Unprocessed일 때 보고서와 UI가 확인되지 않은 무가공을 단정하지 않는 통합 시험.

## R5-04 — Medium: 모든 USB 입력에 폰 스피커 출력을 강제하는 회귀

**위치:** `audio/SignalOutputChoice.kt:40–41`, `ui/CaptureViewModel.kt:754–774`, `audio/SignalSink.kt:145–154`.

새 조건은 기종·사용 목적·사용자의 출력 선택과 무관하게 입력 종류가 USB이면 폰 스피커를 선호 출력으로 지정한다. 이는 특정 SM-S918N+UMC404HD 조합의 회피책을 모든 USB의 일반 정책으로 확장한 것이다. 구현자도 인정했듯 USB 출력→PA→USB 측정 마이크라는 사용 흐름이 달라진다.

**영향:** 의도한 PA 대신 휴대폰에서 시험 신호가 나올 수 있다. setPreferredDevice 실패 시 경고 로그만 남기고 계속하므로, 반대로 기대한 회피책이 적용되지 않아도 사용자는 실제 출력 경로를 알기 어렵다. 이는 선호 장치 요청이며 실제 경로 확인과 다르다. [Android AudioRouting 문서](https://developer.android.com/reference/android/media/AudioRouting.html).

**수정:** `시스템 기본 / 폰 스피커(USB 동시 입출력 문제 회피) / 특정 출력`을 명시적으로 선택하게 한다. 호환성 문제를 관측한 조합에는 우회를 추천하되 사용자 선택을 덮어쓰지 않는다. 마법사는 한 측정 회차 동안 같은 음원/출력 경로를 고정하고, 바뀌면 결과를 폐기하거나 다시 측정하게 한다. 실제 재생 경로를 표시하고 요청 실패/불일치를 알린다. 단지 MicKind만 보고 조용히 강제하는 현재 기본값은 권하지 않는다.

**회귀:** 정상 full-duplex USB는 명시한 USB 출력을 유지, 문제 조합의 우회 선택은 내장 출력 요청, 요청 거절/실제 경로 불일치, USB 연결·해제와 입력 변경, L/R/L+R 및 마법사 기준→대상→기준의 출력 경로 고정. 이번에 실기기 음향 출력은 검증하지 않았다.

## 기존 수정 판정과 나머지 질문

| 항목 | 판정 |
|---|---|
| R4-01 복구 실패 숨김 | 수정 확인. 실패 전파가 들어갔고 기존 이식 반례를 포함한 전체 JVM 통과 |
| R4-02 재시작 뒤 불일치 | 해당 순차 반례 수정 확인. runtime 경합은 R5-01로 계속 보류 |
| R4-03 source 누락 | 비교/unknown 거절 수정 확인. 전체 프로파일 경로 주소·활성 마이크 조합까지 검증한 것은 아님 |
| R4-04 곡선 신원 | 설정→곡선 이름/reading/확인 상태/출처 전달과 해제 시 제거 수정 확인 |
| R4-05 디지털 루프 오인 | 문서 정정 확인 |
| BandMeter 흐림 | resolved에 따라 alpha 적용하는 코드 확인. 0.35의 실제 가독성·접근성 계측은 이번에 하지 않음 |

**0.35는 검증된 최적값이라고 할 근거가 없다.** 후보의 색상 의미를 유지하면서 alpha만 달리하는 선택은 가능하다. 다만 흐림만으로 구별하기 어려운 사용자를 위해 텍스트/범례를 유지하고 실제 배경·낮은 막대·하울링 후보 상태에서 확인한다. 44.1kHz 상단 미해결 밴드도 있으므로 “아래 밴드만”이라는 설명으로 범위를 제한하지 않는 것이 좋다.

**USB에서 UNPROCESSED를 시도하는 변경 자체는 거절하지 않는다.** 지원 속성, 열림 성공, 실제 라우팅, 무가공 보장을 구별하면 된다. 한 기기에서 입력 무음이 사라졌다는 관측은 원인 규명이나 모든 USB에 같은 우회를 적용할 증거가 아니다.

## 산출물과 한계

- `2026-09-30-round5-store-independent-regression.kt`: 별도 스레드 메모/재분석 경합.
- `2026-09-30-round5-dsp-independent-regression.kt`: transient 반례와 잡음 바닥 대조군.
- `2026-09-30-round5-silence-window-proposed.patch`: 비교 창의 신호 근거 검사 제안.
- `2026-09-30-round5-support-evidence-proposed.patch`: 시도 정책과 지원 속성 분리 제안. UI의 무가공 단정은 별도 수정 필요.
- 원시 로그: `build/independent-review/round5-20260930/`의 baseline.log, baseline-counts.txt, probes.log, store-probe-final.log, probe-results.xml, store-results.xml, proposals.log.
- 핵심 수치 보존본: `2026-09-30-round5-evidence.txt`. build 아래 원시 로그는 Git 보존을 전제하지 않는다.

보류의 핵심은 장비 부재가 아니라 R5-01의 파일/상태 일관성이다. 전체 시험 통과와 구현자의 솔직한 미완료 명시는 유용하지만 이 계약을 대신하지는 않는다.
