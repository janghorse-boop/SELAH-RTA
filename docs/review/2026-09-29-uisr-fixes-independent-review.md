# UISR-01~04 수정본 독립 재검증

검토일: 2026-09-29 · 검토자: Codex

**판정: 승인 보류. Critical 0 / High 0 / Medium 2 / Low 1.**

이전 재현 중 10초 묵은 값 거절, 과거 클리핑의 영구 차단 해제, 누적 지연 설명, 기록 시작 구간 경합은 고쳐졌다. 다만 클리핑 뒤 잘못된 값으로 보정이 승인·저장되는 경로와 첫 입력이 전혀 없을 때 감시가 작동하지 않는 경로가 남는다. 이번 보류는 94dB 교정기 부재 때문이 아니다. 두 문제 모두 실제 장비 없이 제품 코드로 재현했다.

## 1. 대상과 검증 경계

- 요청서: `docs/review/2026-09-29-uisr-fixes-reverification-request.md`.
- 작업 트리: 시작 시 clean. 읽기·시험 시 HEAD `8375c0fde0095924426cdf0d39bdaf6806ce602f`.
- 코드 기준: `fdea615cbb7349a89b6bbd10dc2a2a9322733f21`.
- 코드 수정 대상: `43f71a06278ae1df5038344f8d674129d8425b49`까지, PR #53. 9파일 +509/-125. 이후 HEAD까지 요청서·CHANGELOG·대비 시험 주석도 확인했다.
- 요청서 표의 `be8c92e (= ... + fdea615)` 설명은 Git 관계와 다르다. `fdea615`를 실제 기준으로 삼았다. 이 차이 때문에 PR #52의 시작 지연 처리를 새 결함으로 중복 집계하지 않았다.
- 이전 독립 회신과 재현 코드를 참고하되, 이번 대상에서 직접 다시 실행했다. 요청서의 통과·실기기 주장을 독립 확인 결과로 대신하지 않았다.
- production 파일은 수정하지 않았다. 아래 패치와 계산 시제품은 제안 산출물이다.

## 2. 기존 지적의 상태

| 기존 ID | 재검증 결과 | 판정 |
|---|---|---|
| UISR-01 | 정상 5초 후 콜백 중단, 시계만 10초 진행 → `Reject`. 확인 응답에서도 근거를 다시 읽는 코드 확인 | 원래 재현 해결. 세션 소유권 계약은 UISRF-03 참고 |
| UISR-02 | 첫 클리핑 뒤 정상 입력 10초 → 누적 `peakClipped=true`여도 `Save` | 영구 차단 해결. **저장 가능한 값의 안정성은 미해결**, UISRF-01 |
| UISR-03 | 누적 부족량을 현재 표본 나이로 단정하는 문구 제거. 정상 입력 후 중단 → 실제 감시 코루틴이 1.6초 나이·경고 생성, 입력 회복 후 나이 0 | 부분 해결. **첫 입력 전 공백**, UISRF-02 |
| UISR-04 | 실제 ViewModel의 기록 시작 IO 완료를 주 스레드에서 보류하고 구간 변경/전부 삭제. 부착 후 추가 변경도 실행 | 해결 |

UISR-04 관측:

```text
설교 → 부착 전 찬양: [0ms 찬양]
설교 → 부착 전 전부 삭제: [0ms 구간 없음(null)]
부착 전 찬양 → 부착 후 설교: [0ms 찬양, 100ms 설교]
```

이 시험은 `SessionRecorder.note()`만 직접 부른 것이 아니다. 실제 `CaptureViewModel.startRecording()`의 IO→주 스레드 연속 실행과 `CaptureController` 명령 큐를 통과했다. 설정 변경은 실제 수용 경계인 `noteSegmentToRecording()`을 호출했다. Android 화면/설정 DataStore observer 전체를 실행한 UI 시험은 아니다. 이 세 순서를 정규 통합 회귀 시험으로 유지하는 것을 권한다.

## 3. UISRF-01 — Medium — 3초 대기가 현재값의 잔류 오차를 제거하지 않는다

**위치**

- `app/src/main/java/kr/joa/selahrta/calibration/CalibrationEvidence.kt:70` — 벽시계 차이만으로 `cleanWindow` 승인.
- `app/src/main/java/kr/joa/selahrta/calibration/CalibrationGate.kt:83` — cleanWindow 및 시작 시 settled를 저장 허용 근거로 사용.
- `app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:1918` — 그 근거의 `currentDbfs`로 오프셋 저장.
- 관련 정의: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/TimeWeighting.kt:127`. `settled`는 엔진 시작 이후 표본 수이며, 마지막 레벨 변화 이후 안정성 검사가 아니다.

**재현 A — 실제 입력 3.1초를 기다려도 틀린 값을 저장한다.**

실제 CaptureController·RTA·MultiWeightEngine에 48kHz, 1kHz, 20ms 블록을 사용했다. 진폭 1.0을 5초 입력해 클리핑을 만든 뒤, 진폭 0.01로 낮춰 3.1초 입력했다. Slow, A 가중이다.

```text
gate = Save
보정에 사용된 dBFS = -16.493283534967425
같은 작은 입력이 충분히 안정된 뒤 = -43.01060602744041
잔류 차이 = +26.517322492472985 dB
```

실제 `saveSimpleCalibration(94, Calibrator)`와 실제 CalibrationStore/DataStore까지 실행했다. 저장된 파일 `calibration.preferences_pb`와 watch 결과를 확인했다.

```text
reference = 94.0
measured = -16.493283534967425
offset = 110.49328353496742
```

따라서 작은 입력이 안정된 뒤의 환산값은 약 **67.48dB**가 된다. 94dB로 맞추려던 동일한 입력이 약 26.52dB 낮게 표시되는 오프셋이 저장되는 것이다. 이 수치는 합성 입력으로 계산된 소프트웨어 오차이며, 실제 마이크의 음향 정확도 측정값은 아니다.

**재현 B — 입력이 끊긴 시간을 깨끗한 입력 시간으로 센다.**

같은 큰 입력 5초 → 콜백 없이 시계만 3.1초 → 작은 입력 20ms 한 블록:

```text
실제로 받은 작은 입력 = 20ms
마지막 클리핑 이후 벽시계 = 3120ms
evidence age = 0ms
gate = Save
usedDbfs = -3.1262621980717054
```

fresh와 cleanWindow는 모두 통과하지만, 측정기는 거의 큰 입력의 상태 그대로다. 타이머만 더 길게 바꾸면 이 반례는 대기 시간만 늘려 다시 만들 수 있다.

**수학적 근거**

지수 시간가중의 에너지 응답은 `y(t) = Pnew + (Pold - Pnew) exp(-t/τ)`이다. 3τ는 이전 에너지의 약 5%가 남는다는 뜻이다. 새 입력 대비 dB 오차가 작다는 뜻이 아니다. 진폭이 100배 바뀌면 에너지는 10,000배 바뀌므로 그 5%도 새 에너지보다 훨씬 크다. 또한 PCM을 처리하지 않는 동안 이 구현의 시간가중 필터는 감쇠하지 않는다. Master Spec §8의 “안정화 현재값” 조건을 시작 후 `settled=true`만으로 충족했다고 볼 수 없다.

**사용자 영향**

입력 볼륨을 낮추고 안내대로 기다린 사용자가 큰 오차의 보정값을 저장한다. 화면에 즉시 기준값이 나타나므로 잘못된 보정을 알아채기 어렵고, 이후 SPL 환산에 계속 영향을 준다.

**권장 수정**

1. 깨끗한 구간 길이는 실제 유효 프레임 수로 센다. 클리핑·읽기 오류·입력 공백·세션/엔진 교체로 그 구간을 무효화한다.
2. 보정 값의 계산 구간도 동일해야 한다. live Slow 값은 그대로 보존하고, 교정기에 사용할 별도 깨끗한 구간의 에너지 평균 또는 별도 초기화 가능한 추정기를 사용한다.
3. 저장 직전에도 그 구간의 세션·입력 형식·순음 근거·가중 선택과 신선도를 함께 확인한다. 오래된 live 상태를 `settled` 하나로 승인하지 않는다.
4. 고정 대기 시간을 임의로 늘리는 수정은 권하지 않는다. 임의 레벨 변화에 대한 dB 오차 상한을 보장하지 못한다.

동봉 `2026-09-29-uisr-estimator-proposal.kt`는 **고정 1kHz 교정기용 수치 시제품**이다. 세션당 별도 추정기를 두고 클리핑/오류/500ms 초과 공백 뒤 초기화하며, 유효 입력 3초 이상에서 최근 3초의 유한 창 에너지 평균을 사용한다. 같은 PCM으로 계산한 결과:

```text
cleanMs=3100, mean=-43.01029992982378
안정된 기준=-43.0106100609469, 차이=+0.0003101311231dB
이후 잘리지 않는 입력을 20dB 높여 3.1초: mean=-23.010299939327574
공백 후 20ms: 승인할 값 없음
오류 블록: 승인할 값 없음
```

이는 해결 방향의 수치 검증이다. 계산 대상을 현재 Slow 값에서 구간 평균으로 바꿀 경우 교정 카드도 “보정에 사용한 3초 평균”을 표시해야 한다. live Slow 값은 별도로 수렴하므로 저장 직후 그 값까지 정확히 94가 된다고 안내하면 안 된다. 실제 앱 연결, 별도 추정기의 CPU/메모리 비용, 입력 안정성·배경잡음·순음 검증, 44.1kHz, 비정상 블록 크기, 수동 기준소음계와의 시간 구간 일치는 별도 구현/검증 대상이다. 이 시제품을 곧바로 production 수정안의 승인으로 읽으면 안 된다.

**필요한 회귀 시험**

- 큰 클리핑 입력 → 40dB 작은 입력: 잘못된 live 값이 저장되지 않으며, 승인 시 저장값의 허용 오차를 직접 단언.
- 같은 순서에서 중간 콜백을 3초/10초 중단: 무입력 시간을 깨끗한 프레임으로 인정하지 않음.
- UI 방출 사이 한 블록 클리핑, 오류 0프레임, 엔진/세션 변경: 구간 폐기.
- A/C/Z, Fast/Slow, 44.1/48kHz에서 동일한 승인 구간과 저장 구간 유지.
- live MAX·Leq·기록 결과가 보정 전용 추정기의 초기화로 바뀌지 않음.

## 4. UISRF-02 — Medium — 첫 유효 입력이 없으면 감시가 영원히 null이다

**위치:** `app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:889`.

**재현/근거:** 입력 열기와 경로 확인을 성공시킨 뒤 첫 블록을 보내지 않았다. `CaptureViewModel$4` — 해당 소스의 **실제 컴파일된 감시 suspend lambda** — 를 테스트 디스패처에서 실행하고 400ms씩 25회 진행했다. 감시 코드를 시험에 복사하지 않았다.

```text
running=true
elapsedMs=10000
lastInputAgeMs=null
warning=null
```

나이를 `calibrationEvidence()?.let { ... }`에서만 만들기 때문에 첫 evidence가 없으면 시간 경과를 표시할 수 없다. UI가 Running인 상태에서 유효 입력이 전혀 없어도 조용하다. 정상 입력 뒤 중단하는 경우에는 같은 코루틴이 1600ms를 내므로 감시 전체가 실행되지 않은 시험 환경 문제도 아니다.

**사용자 영향:** 열기 성공 후 read/처리가 진행되지 않는 경우 측정이 동작하지 않는다는 경고를 받지 못한다. 보정 게이트 자체는 evidence=null을 거절하므로 이 항목을 잘못된 보정 저장과 혼동하지 않는다.

**권장 수정:** 현재 CaptureSession이 시작 시각과 마지막 유효 입력 시각을 소유하게 한다. 첫 입력 전에는 시작 이후 대기 시간, 입력 후에는 마지막 유효 입력 이후 시간을 계산한다. “아직 받은 입력 없음”과 “마지막 값이 남음”은 다른 문구로 표시한다. 정지 시 null, 재시작 시 새 시작 시각을 사용한다. 시작 직후 경고의 유예 시간은 기기에서 관측한 초기화 지연으로 별도 조정할 수 있다.

**필요한 회귀 시험:** 첫 입력 없음, 정상 입력 후 중단, 회복, 오류만 도착, 정지, 새 세션 시작, 시작 지연이 긴 기기. helper의 age 인자만 직접 넣는 시험 외에 주기 감시→상태 업데이트 경계를 실행해야 한다.

## 5. UISRF-03 — Low — evidence의 세션 소유권 계약이 구현되어 있지 않다

**위치:** `CaptureController.kt:133`, `:143`, `:686`; `CaptureViewModel.kt:1896`.

`evidence`가 Controller 전역이다. stop/start에서 지우지 않고 getter도 active를 확인하지 않는다. “측정 중이 아니면 null”이라는 getter 설명과 다르다. 일반 저장용 gateFor는 현재 세션과 evidence.session의 동일성을 검사하지 않고, 확인 대화상자 응답에서만 그 검사가 있다.

실행 결과: 세션 1을 멈춘 뒤 getter는 같은 객체를 돌려주며, 즉시 세션 2를 열고 아직 블록이 없는 상태에서도 세션 1 근거를 돌려준다. 이 근거를 Meter 게이트에 전달하면 Save다.

**중요한 한계:** 현재 CalibrationCard는 Calibrator 경로만 노출한다. 재시작 때 RTA가 비워져 이 재현에서 Calibrator 게이트는 AskConfirmation이며, 확인 시 세션 비교가 막는다. 따라서 **현재 UI에서 다른 마이크로 잘못 저장되는 것이 재현됐다고 주장하지 않는다.** 이 이유로 Low이며 별도의 승인 차단 항목으로 삼지 않는다.

**사용자 영향/위험:** 새 무입력 세션의 감시가 이전 세션의 시간을 사용한다. 저장 경계를 다른 호출자가 재사용하면 현재 세션과 다른 측정값을 승인할 수 있다. 진행 중이던 이전 콜백도 전역 evidence를 덮을 수 있으므로 단순한 `stop { evidence=null }`만으로 소유권을 해결했다고 보기 어렵다.

**권장 수정:** evidence를 CaptureSession의 volatile 필드로 옮기고 getter는 현재 active의 근거만 반환한다. 직접 저장과 확인 저장 모두 세션 검사를 한다. 순음 판정도 현재 `state.rta`라는 별도 흐름을 읽으므로, 향후 근거 구조에 같은 세션/분석 구간의 tone 결과 또는 명시적 timestamp를 포함하는 것을 권한다. 마지막 문장은 이번에 저장 오동작을 입증한 별도 결함 집계가 아니다.

**필요한 회귀 시험:** 정지 후 null, 새 세션 첫 입력 전 null, 늦은 옛 콜백, 직접/확인 저장의 동일한 세션 검사, 재시작 직후 감시 시간의 기준.

## 6. 실행 가능한 수정 제안

`2026-09-29-uisr-liveness-proposed.patch`는 **UISRF-02/03용 제안**이다. UISRF-01의 보정 계산은 고치지 않는다.

- evidence를 현재 세션에 둔다.
- 감시는 첫 입력 전에도 시작 이후 시간을 계산한다.
- 직접 저장에도 세션 검사를 둔다.
- 첫 입력 없음 문구를 분리하고, “그때 잰 값”을 “마지막으로 받은 값”으로 바꾼다.
- 기존 경고 시험의 정상 입력 fixture에 실제 프레임 수를 넣고, 첫 입력 없음 문구 시험을 추가한다.

원본을 건드리지 않은 사본에 적용해 컴파일했다. 같은 독립 재현에서 다음 결과를 확인했다.

```text
새 세션 첫 입력 전: Meter/Calibrator 모두 Reject
첫 입력 없음 10초: age=10000ms, '아직 유효한 소리를 받지 못했습니다' 경고
정상 입력 후 중단/회복: 경고 생성 및 나이 초기화 유지
기록 구간 3개 순서: 모두 유지
관련 JUnit: 67 tests, 실패 0
```

`git apply --check docs/review/2026-09-29-uisr-liveness-proposed.patch` 성공. 실제 작업 트리에 적용하지 않았다. UI 초기 방출 직후의 일시적인 표시, 실제 기기의 초기화 지연, 전체 Android 빌드는 이 제안의 별도 검증 대상이다.

## 7. 요청서 질문에 대한 답

- **500ms 신선도:** 마지막 유효 블록 처리 시각에 대한 운영상 상한으로 쓸 수 있다. 현재 구현은 콜백 중단을 10초 뒤에도 승인하던 문제를 고쳤다. 이 숫자가 음향 정확도, 표본의 실제 캡처 시각, 버퍼 안 자료의 최신성, 입력 안정성을 보장하지는 않는다. 500ms 경계의 ≤/초과, 음수 나이 거절, 세션 변경을 시험으로 고정하는 것이 좋다.
- **가중 선택을 주 스레드에서:** 같은 MultiWeightFrame의 A/C/Z 중 하나를 고르는 것 자체는 문제없다. 저장의 가중 선택을 한 번 잡고, 위 세션/시간구간 계약까지 유지하면 된다.
- **3초/3τ:** 그대로 정확성 근거로 삼을 수 없다. UISRF-01의 수치 반례가 있다. 운영 문턱이라고 주석에 적어도 틀린 보정값 저장이라는 결과는 달라지지 않는다.
- **블록 단위 clipping:** 유효 블록의 stats.clipped를 UI 방출 제한 이전에 읽으므로 66ms UI 방출 사이 사건은 놓치지 않는 구조다. 문제는 사건의 수집 위치보다 그 후 사용하는 안정성 정의다.
- **1초와 400ms:** 잠정적인 UX 정책으로 가능하다. 주 스레드가 제때 실행된다는 조건에서 경계 초과 후 다음 점검까지 최대 약 400ms가 더 걸린다. OS 스케줄링/주 스레드 정체를 포함한 상한이나 “사람이 느끼기 전”이라는 보장은 아니다. 첫 입력 전 경로와 장치별 시작 지연도 확인해야 한다.
- **“화면의 값은 그때 잰 것” 문구:** 현재 timestamp는 read 완료 기준이므로 표본 캡처 시각으로 읽힐 여지가 있다. “마지막으로 받은 값이 남아 있습니다”가 더 정확하다. 제안 패치에 반영했다.
- **USB 분리 때 routing이 먼저 막으면 감시는 불필요한가:** 삭제 근거가 아니다. routing listener는 **경로 변경** 통지이고, READ_BLOCKING은 요청 데이터가 채워질 때까지 기다리는 읽기 방식이다. 같은 경로에서 첫/다음 read가 진척하지 않는 상황은 다른 상태다. 이는 API 계약과 코드에서 도출한 구별이며 UMC404HD의 실제 고장을 재현했다는 뜻은 아니다. 공식 근거: [AudioRecord.read](https://developer.android.com/reference/android/media/AudioRecord#read(float%5B%5D,int,int,int)), [AudioRouting.OnRoutingChangedListener](https://developer.android.com/reference/android/media/AudioRouting.OnRoutingChangedListener).
- **진단 상세 상자를 다시 넣어야 하는가:** 필수 아니다. 사용자가 요청한 단순한 화면을 유지하면서, 무입력/오류 같은 행동 가능한 경고와 검증 가능한 내부 상태를 유지하면 된다.

## 8. 검증 결과와 한계

**대상 코드:** DSP JUnit 604건 + 앱 JVM JUnit 808건 = **1,412건, 실패 0**. Kotlin 2.2.20/Java 17로 대상 소스를 컴파일한 결과다. 여기에 별도의 독립 재현을 실행했고 위 두 Medium 반례를 얻었다. 기존 시험 통과가 그 반례를 검증했다는 뜻은 아니다.

앱 시험은 실제 Controller·ViewModel 메서드·DSP·DataStore/파일 저장 등을 포함한다. Android mockable jar를 사용하고 MainActivity/R/ThemeMode에는 컴파일 경계용 타입을 사용했다. Compose 화면/components, LevelSpeechTest, PaletteContrastTest는 이번 808건에 포함하지 않았다. 변경된 MeasureScreen의 경고 인자 연결은 소스로 확인했다. 실제 화면을 띄운 결과는 아니다.

독립 probe는 Android 생성자 배선만 건너뛰고 실제 ViewModel scope 구현을 유지한다. FakeSource/시계/dispatcher를 주입하고, 감시는 실제 컴파일된 코루틴을 reflection으로 실행했다. DataStore 저장의 비동기 main 재개를 테스트 dispatcher에서 진행시킨 뒤 파일과 watch를 확인했다. 저장 probe의 첫 실행은 main 재개를 진행시키지 않아 timeout이 났고, harness를 바로잡은 뒤 위 저장 결과를 얻었다. 이 timeout을 제품 실패로 집계하지 않았다.

- Gradle 전체 test/assemble/lint는 시도했지만 캐시 lock 파일 `C:\Users\jangh\.gradle\wrapper\dists\gradle-8.14.3-bin\...\gradle-8.14.3-bin.zip.lck` 접근 거부로 실행하지 못했다. 새 APK/전체 Android 빌드/lint 통과를 주장하지 않는다.
- 실기기/UI, 실제 AudioRecord 정체, USB 재접속, 제조사 백업 이전, 교정기 94/114dB 정확도는 이번에 확인하지 않았다.
- 요청서의 Galaxy S23 + UMC404HD 결과는 구현자의 보고다. 독립 실기기 확인으로 합산하지 않는다.
- 수치 시제품은 위의 특정 입력으로 방향을 확인한 것이며 모든 입력/하드웨어의 오차 보장이 아니다.

산출물:

- `docs/review/2026-09-29-uisr-independent-probe.kt`
- `docs/review/2026-09-29-uisr-liveness-proposed.patch`
- `docs/review/2026-09-29-uisr-estimator-proposal.kt`

재현 작업 폴더는 `D:/Cowork/SELAH-RTA/build/independent-review/uisr-20260929`이다. `source/`는 고정한 HEAD의 git archive 사본이다. `run.ps1`, `app-tests.ps1`, `probe.ps1`, `candidate.ps1`, `probe-output.txt`, `candidate-output.txt`, `candidate-junit.txt`, `estimator-output.txt`를 보존했다. 후보 시험 67건과 계산 시제품은 **대상 코드의 1,412건에 합산하지 않는다**.

승인 전 남은 일은 UISRF-01의 올바른 계산 구간과 저장값 연결, UISRF-02의 첫 입력 전 감시를 정규 코드와 회귀 시험에 반영하는 것이다. UISRF-03은 동일한 세션 소유권 수정으로 함께 닫을 수 있다.

