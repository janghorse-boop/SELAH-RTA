# 신호 정지 경계 독립 재검증 — 11회차

검토일: 2026-09-29 · 검토자: Codex

## 1. 판정과 범위

**SRLRO-01(Medium)·SRLRO-02(Low) 해결을 확인했습니다. PR #76의 신호 정지 경계 수정은 코드 승인하며, 앞선 신호 제어의 승인 보류를 해제합니다. 이번 범위에서 새 Critical/High/Medium 결함은 발견하지 않았습니다.**

이는 실제 PA 음향 품질이나 앱 전체 출시 승인이 아닙니다. 전화·이어폰 분리·실제 출력 경로의 확인 항목은 아래에 별도로 남깁니다. 이 수정의 승인에 94 dB 교정기는 필요하지 않습니다.

- 요청서: `2026-09-29-srlro-stop-boundary-reverification-request.md`.
- 검토한 수정 커밋: **`f895d7e92a2e5f45d950eb25f0f3c605de353824`**, PR **#76**. 이전 검토본 `8fdca3a`의 트리는 병합본 `dd796fb`와 같습니다.
- 독립 실행 고정본: **`5bcc5da9eddd891921ba26b43b4f23e0757ab76f`**. 검토를 시작했을 때 #76과 후속 #77까지 병합돼 있어, 이 커밋을 `git archive`로 격리했습니다.
- 실행본에는 #73 RTA 저장, #75 두 곡선 차이, #77 L→R→L+R 순서 측정이 포함됩니다. 공통 신호 제어 경로와 기존 시험을 확인했지만, **그 기능들의 저장 포맷·측정 조건 일관성·수치·UI 전체를 이번에 승인한 것은 아닙니다.**
- 검토 도중 HEAD가 `36a7de36b6d424a1a7eaf744a8918085977867d2`로 바뀌었습니다. 고정본 이후 변경은 README와 문서·이미지였으며 검증본에 섞지 않았습니다.
- 제품 작업 소스는 수정하지 않았습니다. 추가 시험과 의도적 결함 삽입은 `build/independent-review/srlro-20260929/source/` 안에서만 실행했습니다. 실제 휴대폰에는 설치하거나 재생하지 않았습니다.

## 2. 기존 지적의 종결 근거

| 항목 | 위치: 고정본 기준 | 확인 결과 |
|---|---|---|
| SRLRO-01: 정지 후 같은 순음 재시도가 retune으로 포커스 재획득 우회 | `CaptureViewModel.kt:815`, `1464`, `1546` | 정지 의도를 worker 실행 전에 기록하고, 정지보다 오래된 재생을 retune에서 제외. 기존 재현과 추가 12개 경로 통과 |
| SRLRO-02: 계약과 주석의 불일치 | `SerialCommands.kt:70–90`, `AudioInterruptions.kt:94–113` | postAlways는 close 시 생략될 수 있고 finalizer가 정리를 인수한다는 예외 명시. 무포커스 재생은 폐기된 정책이며 caller가 실패를 처리해야 한다고 정정 |

여기서 `held`는 **수신기 등록 상태**입니다. 이 값만으로 포커스 획득을 증명하지 않았습니다. 거절 시험은 Android AppOps로 요청을 거절시키고, 별도 `AudioInterruptions.acquire()`가 false인 것을 먼저 확인합니다.

기존 `SrlCommandIndependentTest` 7개와 `SrlOwnershipIndependentTest` 4개도 조건을 나누어 모두 통과했습니다. late open, 늦은 결과 게시, 실패 시작, 옛 generation 종료, 채널·주파수 변경 병합, 정상 Hz-only retune에 대한 앞선 회귀가 유지됩니다. JVM의 `SerialOwnershipIndependentTest` 4개도 전체 실행에서 통과했습니다.

## 3. 요청서 질문 1 — 다른 정지 경로와 onCleared

**버튼·백그라운드·noisy·포커스 상실은 같은 정지 번호를 올립니다. onCleared까지 같은 번호를 올린다는 설명은 사실과 다릅니다. 다만 onCleared도 별도의 종료 장치로 안전하게 처리됩니다.**

| 진입 경로 | 실제 호출과 보호 | 독립 확인 |
|---|---|---|
| 정지 버튼 | `stopSignal()` → `lastSignalStopIntent.set(signalIntent.incrementAndGet())` | 거절/허용 모두 통과 |
| 백그라운드 | `onBackground():1627` → `stopSignal()` | 복귀 후 재시도 거절/허용 모두 통과 |
| 출력 변경 알림 | 실제 noisy 수신기 → VM interruption 콜백 `:891` → `stopSignal()` | 거절/허용 모두 통과 |
| LOSS / LOSS_TRANSIENT / CAN_DUCK | 실제 focus listener → 같은 VM 콜백 → `stopSignal()` | 세 종류 각각 거절/허용 모두 통과 |
| ViewModel 종료 | `onCleared():3145` → `signalClosed=true`, 일반 `signalIntent` 증가, `signalCommands.close { stopSignalOnCommandThread() }` | 종료 후 재시도가 출력 생성 없이 무시되고, 기존 출력 해제·실행자 종료 확인 |

마지막 경우의 관측은 다음과 같습니다.

```text
SRLRO_CLEAR stopBefore=0 stopAfter=0 intentBefore=1 intentAfter=2 closed=true sinks=1 playing=null
```

정지 번호는 그대로입니다. 대신 `playSignal():1416`의 닫힘 검사와 `signalIsCurrent():823`의 닫힘·최신 의도 검사로 늦은 시작/결과를 막고, 같은 실행자의 finalizer가 진행 중인 일 뒤에서 자원을 정리합니다. **이를 맞추려고 onCleared에 정지 번호 갱신을 추가할 필요는 없습니다.** 요청서의 설명만 이 구별에 맞춰 고치면 됩니다.

## 4. 독립 회귀와 되돌림 검증

[추가 계측 코드](2026-09-29-srlro-stop-boundary-independent-regression.kt)는 실제 ViewModel을 `ViewModelStore`로 생성·종료하고, 실제 종료 콜백을 유지한 `SignalPlayer`에 무음 sink만 주입합니다. 실행자 앞의 latch는 시간이 지나면 저절로 풀리지 않습니다. 정지와 재시도의 순서는 시험이 명시적으로 고정합니다.

여섯 정지 원인 각각에 대해 다음 두 계약을 검사했습니다.

1. **포커스 재획득 거절:** Custom 출력 중 worker를 보류 → 실제 정지 진입 경로 → 같은 Custom 재시도와 1.7 kHz 변경 → worker 재개. 새 sink가 열리지 않을 뿐 아니라 **기존 출력 종료, UI null, 수신기 해제, 안내 표시**까지 확인합니다.
2. **포커스 재획득 허용:** 같은 순서에서 **새 sink가 열리고 기존 sink가 해제**되는지 확인합니다. 그 뒤 3 kHz 변경은 새 트랙을 또 만들지 않고, PCM의 영교차로 주파수 반영도 확인합니다. “모든 retune을 막으면 통과하는 시험”으로 만들지 않았습니다.

별도로 `ViewModelStore.clear()` 후 재시도 1개를 추가했습니다.

| 정지 원인 | 거절 | 허용 후 재개·정상 retune |
|---|---|---|
| BUTTON | 통과 | 통과 |
| BACKGROUND | 통과 | 통과 |
| NOISY | 통과 | 통과 |
| LOSS | 통과 | 통과 |
| TRANSIENT | 통과 | 통과 |
| DUCK | 통과 | 통과 |
| onCleared | 영구 종료 계약 1개 통과 | 재개하지 않는 것이 계약 |

**시험 자체의 검출력도 확인했습니다.** 격리본에서 아래 조건 한 줄만 제거하고 같은 13개를 실행했습니다.

```kotlin
activeSignalIntent > lastSignalStopIntent.get() &&
```

결과는 **12개 실패·1개 통과**입니다. 여섯 거절 시험은 `expected null, but was:<Custom>`, 여섯 허용 시험은 새 출력 수 `expected:<2> but was:<1>`로 실패했습니다. `onCleared`는 별도의 닫힘 보호를 사용하므로 그대로 통과했습니다. 이 변이는 이번 수정이 없을 때 정지 원인 전체에서 같은 결함이 재현됨을 보입니다. 모든 가능한 경합을 탐색했다는 뜻은 아닙니다.

## 5. 실행 결과

기본 빌드는 `--offline --no-build-cache --rerun-tasks --max-workers=2`로 아래 작업을 실행했습니다.

```text
:dsp:test :app:testDebugUnitTest :app:assembleDebug
:app:assembleDebugAndroidTest :app:lintDebug
```

| 확인 | 결과 |
|---|---|
| 전체 JVM | DSP **633** + 앱 **921** = **1,554개**, 실패·오류·건너뜀 0 |
| 앱·계측 APK / lint | 빌드 성공. lint 오류 0, Warning 11, Hint 4 |
| 기존 계측 전체 | runner `OK (45 tests)`. 상태 코드는 **44 통과·거절 조건 1개 건너뜀** |
| 거절 조건 별도 실행 | AppOps ignore에서 해당 **1개 통과**, 이후 allow 복구 |
| 새 정지 경로 회귀 | **13개 통과**, 건너뜀 0 |
| 조건 한 줄 제거 변이 | **13개 중 12개 실패**, onCleared만 통과 |
| 정확한 원본 복구 후 재실행 | **13개 전부 다시 통과**. 백업/복구 소스 SHA-256 일치 |

API 34 별도 무음 에뮬레이터에서 실행했습니다. 기존 계측의 `OK (45 tests)`를 “45개 모두 단언 실행”으로 해석하지 않았습니다. 포커스 거절 조건 시험은 기본 허용 환경에서 assumption으로 건너뛰며, 별도 거절 실행으로 채웠습니다. 중복 실행을 서로 다른 시험 수에 합산하지 않았습니다. 마지막에도 AppOps allow 복구를 확인했습니다.

로그 원본은 `build/independent-review/srlro-20260929/`, 보존한 핵심 출력과 명령은 [실행 증거](2026-09-29-srlro-stop-boundary-evidence.txt)에 있습니다.

## 6. 요청서 질문 2·3 — 남은 틈과 다음 회귀 방식

이번 범위에서는 retune의 추가 우회를 재현하지 못했습니다. 다음 조건이 함께 유지돼야 합니다.

- Custom·세기·채널이 같고, 실제 player가 retune을 받아들여야 합니다.
- 재사용하려는 활성 요청은 마지막 정지보다 뒤에 시작한 것이어야 합니다.
- 실패 시작·자연 종료·정리에서는 활성 요청을 지워야 합니다. 옛 generation의 종료가 새 재생의 포커스를 해제해서도 안 됩니다.
- 종료한 ViewModel은 새 시작과 늦은 게시를 거절하고, 진행 중이던 자원의 정리는 finalizer가 맡아야 합니다.

**적극 권장하는 변경은 제품 재작성보다 첨부한 13개 계약 시험의 편입입니다.** 현재의 정지 원인 × 재획득 결과 표를 공통 회귀로 유지하면 새 정지 경로가 생길 때 같은 계약을 적용할 수 있습니다. 이어서 이미 있는 “open 진행 중 정지”, “결과 게시 전 정지”, “옛 writer 종료가 새 시작 뒤 도착” 시험을 같은 계약 목록에 연결하십시오. 단순히 sink를 새로 열지 않았다는 한 가지 단언만으로 종료를 판정하지 마십시오.

시험 파일은 `app/src/androidTest/java/kr/joa/selahrta/ui/SrlStopBoundaryIndependentTest.kt`로 편입할 수 있습니다. **별도 검토용 에뮬레이터에서 실행하십시오.** 실제 앱의 AppOps를 allow→ignore→allow로 바꾸므로 사용 중인 휴대폰에 그대로 실행할 시험은 아닙니다.

```powershell
adb -s emulator-5584 shell am instrument -w -r -e class kr.joa.selahrta.ui.SrlStopBoundaryIndependentTest kr.joa.selahrta.test/androidx.test.runner.AndroidJUnitRunner
```

현재 코드에 추가 production 패치는 제안하지 않습니다. 확인된 반례를 막는 최소 수정이 이미 반영됐고, 일반 retune도 유지됐습니다.

## 7. 남은 확인 범위

- 포커스 listener/noisy 수신기에는 값을 **직접 전달**했습니다. 실제 전화 수신·다른 앱 선점·이어폰/USB 분리에 따른 시스템 알림 및 라우팅을 확인한 것은 아닙니다.
- 무음 sink의 PCM·생명주기를 확인했습니다. 실제 PA의 클릭음·청감·44.1 kHz 출력·2시간 부하는 별도입니다.
- 이미 진행 중인 native 작업이 반환한 뒤 정리되는 계약을 봤습니다. 정지 요청 직후 출력 표본이 0개라는 보장이나, 반환하지 않는 native open의 유한 시간 종료 보장은 아닙니다.
- 이전 회차의 지연 시험 50.1 ms 실패는 이번 전체 실행에서는 재현되지 않았습니다. 원인을 규명한 것은 아니며, 통과 한 번으로 실제 화면 성능까지 확정하지 않습니다. 기존 지연 시험의 직접 ViewModel 생성/300 ms 대기 정리는 여전히 개선 여지가 있습니다. 다른 소유권 시험처럼 store 종료와 실행자 정리 완료를 확인하는 것이 좋습니다.
- #73/#75/#77의 RTA 저장·차이·자동 순서 측정 전체 승인은 별도 검토입니다. 이번 신호 정지 경계 보류 해제와 구별하십시오.
