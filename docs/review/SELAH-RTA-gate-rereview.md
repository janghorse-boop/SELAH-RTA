# SELAH RTA 게이트 재검증

2026-09-21 · 대상 `666260f..de4f0cc` · **판정: 승인 보류**

기존 C01·C02·C03의 지적 경로에는 수정이 들어갔다. **기존 시험 229개(DSP 183 + 앱 46)를 독립 실행해 모두 통과**했다. 그러나 추가로 실제 CaptureController를 실행한 결과 **High 1건, Medium 1건**이 확인됐다. Critical은 발견하지 않았다. production 코드는 변경하지 않았다.

## 검증 범위와 실행 방법

- `941abc0`, `de4f0cc`의 변경 및 연결되는 ViewModel·Controller·SignalPlayer·FeedbackDetector와 회귀 시험을 검사했다. 요청서의 ‘수정 완료’와 실기기 보고는 독립 확인 결과와 구분했다.
- 검사 시 checkout HEAD는 `9218a8f`였다. 이번 시험은 지정된 **`de4f0cc`를 별도로 추출한 사본**에서 수행했다. 후속 HEAD에 대한 승인은 아니다.
- Kotlin 2.2.20 / JDK 21 / JVM target 17 / JUnit 4.13.2를 사용했다. 실제 DSP 및 관련 앱 production 소스, 저장소의 모든 JVM 시험을 직접 컴파일해 실행했다. 필요한 Android API jar와 AndroidX 원본 AAR/JAR는 로컬 캐시에서 읽었다. 시험용 production 로직 사본이나 수정본으로 대체하지 않았다.
- 처음 구성한 수동 컴파일 환경에서 변환된 runtime jar의 Kotlin 확장 함수 해석이 실패했다. 원본 AAR의 classes.jar로 정리한 뒤 전체 컴파일 및 **229개 시험이 통과**했다. 이는 검증 환경 구성 문제였으며 프로젝트 빌드 실패로 판정하지 않았다. 수동 classpath는 coroutines-core 1.8.0, Lifecycle 2.8.7, DataStore 1.1.1 등을 사용했으며 Gradle의 전체 의존성 해석 환경과 동일하다고 주장하지 않는다.
- 추가 `GateProbe.kt`는 저장소의 FakeSource와 실제 CaptureController, 실제 `withMeasurement()`를 사용한다. 두 결함을 모두 실행 재현했다. probe의 exit 0은 **결함 재현 성공**을 뜻한다.
- Gradle clean build, Compose/UI instrumentation, 실제 Android ViewModel 및 DataStore 실행, AudioRecord/AudioTrack 하드웨어, USB, 장시간 부하는 이번에 실행하지 않았다. 따라서 JVM 229개 통과를 앱 전체 빌드·실기기 승인으로 확대하지 않는다.

이하 경로·줄 번호는 `de4f0cc` 기준이다. `app/…/`는 `app/src/main/java/kr/joa/selahrta/`를 뜻한다.

## 발견 사항

### G01 · High — Controller 추출 뒤 정상 중지하면 SPL·MAX·Leq·RTA가 사라진다

**위치:** `app/…/ui/CaptureController.kt:475, 498–503`; 기본 상태 정의 `70`; 화면 합성 `app/…/ui/CaptureViewModel.kt:332`.

**원인:** 추출 전 `stop()`의 `state.value`는 측정 스냅샷을 합친 화면 상태였다. 새 Controller의 `state`는 `_state.asStateFlow()`이며 **측정 스냅샷을 합치지 않은 기본 상태**다. 같은 표현을 옮겼지만 의미가 달라졌다. `frozen = state.value`에서 읽는 meter/rta/diagnostics는 기본값이고, 이후 `_measurement=null`로 실제 결과도 제거한다.

**독립 실행 재현:** 실제 Controller에 48 kHz/1 kHz 순음 1024샘플을 100회 전달한 뒤 중지했다.

| 값 | 중지 직전 합성 상태 | 중지 후 합성 상태 |
|---|---:|---:|
| Current SPL | 103.012611 | null |
| MAX | 103.013066 | null |
| RTA | 있음 | 없음 |
| 진단 frames | 102400 | 0 |
| 하울링 기록 | 있음 | 1개 보존 |

수치 단위는 시험의 기본 미보정 눈금이며 물리적 음압 검증 결과가 아니다. 문제는 절대값이 아니라 **이미 표시하던 측정 결과의 소실**이다. Leq/Peak도 같은 MeterReading 객체가 기본값으로 대체되는 경로다.

**사용자 영향:** 측정 종료 후 결과를 적거나 확인할 수 없다. 일반 stop뿐 아니라 홈 이동·오류 종료 등 동일 종료 경로에도 영향을 준다. 하울링 기록만 별도로 복사하므로 그 항목의 테스트는 통과하지만 주요 측정 결과는 사라진다. 명시적으로 유지하려던 종료 결과가 정상 동작에서 매번 소실되는 회귀이므로 High로 분류했다.

**권장 수정:** 세션을 무효화하고 calibration을 초기화하기 **전에**, 현재 세션의 최신 measurement와 base state를 실제 합성한 불변 최종 상태를 확정한다. 그 값에서 meter/rta/diagnostics를 복사한다. 예컨대 기존 production 합성 함수 활용을 검토할 수 있다. 기본 상태에 오디오 스레드가 다시 직접 쓰도록 바꾸지는 않는다. 종료 snapshot 소유와 G02의 늦은 발행 방지를 함께 정리한다.

**회귀 테스트:** 중지 전 Current/Leq/MAX/Peak/RTA/diagnostics가 존재함을 먼저 단언하고, stop 후 동일 값이 유지되는지 비교한다. 보정값 95 dB 등 기본 120 dB와 다른 설정, ON_STOP, 오류 종료, 반복 stop, 새 start 초기화를 포함한다. feedbackLog만 검사하면 이 결함을 놓친다.

### G02 · Medium — 이미 진입했던 옛 콜백이 새 세션의 measurement를 덮어쓴다

**위치:** `app/…/ui/CaptureController.kt:416–421, 450–466`; 소비 측 `app/…/ui/CaptureViewModel.kt:692` 부근의 session 불일치 처리.

**원인:** active 검사와 세션별 엔진 소유는 새 세션 DSP 오염을 막지만, 콜백 마지막의 **공유 `_measurement` 발행**까지 보호하지 않는다. 입구 검사를 통과한 이전 콜백이 stop/start 뒤에 끝나면 새 세션이 이미 발행한 스냅샷을 이전 세대의 스냅샷으로 대체한다. 소비 측에서 세대가 다르면 ‘마지막 유효한 결과’가 아니라 기본 상태를 반환하므로 화면 값이 빈다.

**독립 실행 재현:** public `postToCapture` 명령 큐에 latch를 넣어 **실제 onBlock 입구 검사 이후** A 콜백을 정지시켰다. main에서 stop→B start→B 입력 및 정상 발행을 진행한 뒤 A를 재개했다. production Controller와 상태 합성은 수정하지 않았다. 출력:

```text
LATE_SNAPSHOT active=3 published=2 visibleCurrent=null
```

FakeSource.close는 진행 중 콜백을 기다리지 않는다. 이는 실제 close가 500 ms timeout 후 이전 worker를 남길 수 있는 조건을 모델링한다. Android에서 해당 지연을 직접 재현했다는 뜻은 아니다.

**사용자 영향:** 이전 기기 결과를 새 보정으로 계산하지는 않지만, 새 세션의 유효한 측정 표시가 사라지고 다음 정상 발행까지 빈 상태가 된다. 세션별 DSP 객체를 나눈 수정은 유효하나 snapshot 전달 경로의 격리는 아직 완성되지 않았다.

**권장 수정:** 최종 snapshot 수락을 세션 전환과 같은 소유 스레드에서 처리하고, **수락 시점**에 현재 세대를 확인한 뒤 저장한다. 또는 세션별 결과 흐름을 사용하여 종료한 producer가 현재 세션의 저장 위치에 쓸 수 없게 한다. 소비 측 세대 불일치 검사는 유지하되 그것만으로 공유 최신값 교체를 방지했다고 보지 않는다. emit 직전 검사만 추가하면 검사와 대입 사이의 경합이 남으므로 직렬화 경계를 정해야 한다.

**회귀 테스트:** 이번 latch 순서를 그대로 사용해 B의 measurement 및 합성 수치가 A 복귀 전후 변하지 않는지 단언한다. A가 늦게 도착하기 전부터 이미 onBlock 안에 있었음을 확인해야 한다. A 콜백을 stop 뒤 처음 호출하는 기존 시험은 이 조건을 검증하지 않는다.

## 기존 C01·C02·C03 재판정

| 항목 | 이번 판단 | 근거와 한계 |
|---|---|---|
| C01 process/finish 경합 | 원 지적 수정 확인 | 같은 lock에서 process/finish/reset 및 events 접근을 보호하고 finished 이후 process를 무시한다. 관련 DSP 시험 통과. Controller의 옛 주석 ‘입구에서 막히므로 경합하지 않음’은 이제도 틀린 설명이므로 실제 잠금 근거로 고쳐야 한다. |
| C02 늦은 출력 오류의 UI 갱신 | 원 지적 코드 수정 확인 | onEnded가 세대를 전달하고 main에서 playGeneration과 대조한다. 이전 통지가 새 UI를 지우는 경로는 차단됐다. AudioTrack 오류 통합 시험은 아직 없다. |
| C03 무음 중 Persistent 승격 | 수정 확인 | 현재 프레임에서 본 track만 record하고, duration을 lastSeen−firstSeen으로 계산한다. 무음 비승격 및 실제 지속의 긍정 시험 모두 통과. |
| 종료 사유 | 개선 확인 | SilenceGap/MeasurementEnded와 durationIsComplete를 추가해 관측 중단과 소리 종료를 구분한다. |
| 가까운 track 선택 | 개선 확인 | 첫 허용 track 대신 가장 가까운 track을 선택한다. 교차·합류 정책 전체를 검증한 것은 아니다. |

SignalPlayer 내부에서는 여전히 generation/track/공유 running의 스레드 간 수명주기를 정밀하게 검증해야 한다. 이번 main callback 세대 수정이 worker 내부의 모든 동시성 문제를 해결했다는 주장은 하지 않는다. 즉각적인 추가 확정 결함으로 중복 계상하지는 않았지만, 출력 오류·timeout·빠른 재시작 시험은 남는다.

## 새 상태 전이 시험의 평가

실제 production Controller를 추출하고 시험하는 방향은 맞다. 기본 시작/정지, 기록 보존, late route/error 거절, 보정 전후 25 dB 차이를 실제 경로로 검사한 점도 확인했다. 다만 요청서의 최소 항목 대응표 중 다음은 정확히 구분해야 한다.

- `CaptureControllerTest.kt:129`의 ‘덩어리를 처리한 직후에 멈춰도’ 시험은 `deliver()` 호출이 **모두 반환된 뒤** stop한다. onBlock 내부에서 stop과 겹치는 시험이 아니다. Controller 수준의 C01 경합 검증으로 부를 수 없다.
- `:211`의 이전 세션 시험은 stop/start **이후** 옛 source의 deliver를 시작한다. 입구에서 거절되는 경우만 확인한다. G02처럼 입구 통과 후 지연된 발행을 검증하지 않는다.
- 두 분리 순서 시험은 각각 한 종류의 첫 이벤트를 보내는 방식이다. error→remove와 remove→늦은 error의 **두 이벤트 전체**, 중복 이벤트, post 큐 지연을 함께 넣어야 한 번만 전환된다는 성질을 검증할 수 있다.
- `post={ it() }`는 기본 기능 검증에는 유용하나 실제 main queue 대기와 순서 역전을 만들지 못한다. 보류·배출 가능한 fake dispatcher/queue를 추가해 late event를 검사해야 한다.
- Controller 테스트가 `withMeasurement()`로 합성한 값을 읽는 반면 stop 구현은 base state를 읽는 차이를 시험하지 않아 G01을 놓쳤다. 각 기능이 ‘측정 중 동작함’에서 끝나지 않고 종료 후 결과까지 이어지는지 검사해야 한다.

DSP 동시성 시험의 20회 경쟁 실행은 보조 근거다. 두 스레드를 동시에 출발시켜도 원하는 지점에서 반드시 충돌하는 것은 아니다. 잠금 이후 이전 재현을 그대로 쓰면 교착될 수 있다는 설명은 맞지만, 테스트 제어 스레드가 latch를 풀어 주고 다른 호출이 잠금에서 대기함을 확인하는 식의 **결정적 직렬화 시험**은 여전히 가능하다. 기존 시험의 구조적 보호를 인정하면서도, 반복 통과 횟수만으로 경합 커버리지를 판단하지 않는다.

## 요청서의 세 질문에 대한 답

### 1. 잠금이 오디오 스레드에 맞는 해법인가?

이번처럼 작은 detector의 process와 종료를 직렬화하는 것은 허용 가능한 correctness 수정이다. 반드시 명령/응답 구조로 다시 만들 필요는 없다. 다만 **3.42 ms 실측은 최대 대기시간 보장이 아니다.** lock을 소유한 worker가 GC·스케줄링 등으로 지연되면 main의 대기시간도 늘어난다. 현재 close의 최대 500 ms join도 별도 main 지연 요인이다. 장시간·부하 시험에서 측정하고, UI 지연이 문제면 완료 응답을 비동기로 받는 쪽으로 옮긴다. ‘최대 한 프레임 3.42 ms’ 대신 ‘해당 기기·조건의 관측값’으로 기록해야 한다.

### 2. 관측된 track만 기록하면 duration이 짧아지는가?

소리가 마지막으로 관측된 시각까지 기록하는 것은 이번 정의와 일치한다. 잠깐 사라졌다가 허용 gap 안에서 돌아오면 **firstSeen부터 새 lastSeen까지**이므로 내부 공백도 duration에 포함된다. 따라서 순수하게 소리가 존재한 누적 시간보다 짧아진다기보다, ‘허용 공백을 포함한 사건의 관측 구간 길이’에 가깝다. 그 정의를 명시하고 active sound time이 필요하면 별도 값으로 누적한다. 무음만으로 새 승격이 일어나지 않는 이번 수정은 유지하는 것이 맞다.

### 3. Controller 경계와 밖에서 오는 calibration/settings는 적절한가?

경계 자체는 적절하다. 실제 DataStore 내부를 전부 재현할 필요는 없다. 대신 밖에서 발생 가능한 순서를 시험으로 주입해야 한다: A 보정 구독→stop/B start→A 결과 지연 도착, 초기 설정 지연, 곡선 변경 중 stop, watcher 취소, 같은 값 재발행 등이다. 지금 `update{}`는 세대를 강제하지 않으므로 ViewModel 배선의 구독 취소·귀속 규칙도 최소한 검증해야 한다. 특히 base state와 composed state의 역할을 이름·API로 명확히 구분하는 것이 G01 재발 방지에 도움이 된다.

## 게이트 조건과 남은 범위

G01의 최종 측정값 보존을 복구하고, 종료 전후 **실제 합성 상태**의 동일성을 단언하는 회귀 시험이 필요하다. G02는 입구 이후 barrier를 사용한 세션 전환 시험으로 수락 경계를 고쳐야 한다. 기존 C01 해결 자체는 인정하지만, 추출 중 생긴 정상 종료 데이터 소실 때문에 현재 게이트를 승인할 수 없다.

44.1 kHz, USB 두 대 정책, reroute 재시작 상한, 실제 출력 오류 지연, 현장 오탐률/놓침률, 기준 계측기 비교, 60분 부하·열·메모리는 미검증 항목으로 유지한다. 이것들을 이번에 모두 새 차단 결함으로 계상한 것은 아니다.

재현 자료는 같은 outputs 폴더의 `GateProbe.kt`, `run-gate-verification.ps1`, `gate-verification-log.txt`이다. 스크립트는 저장소를 읽어 임시 사본에서 컴파일하며 production 소스나 전역 설정을 변경하지 않는다.

검증 종료 시 원본 작업 트리에서 FeedbackDetectorTest.kt 수정과 SampleRateEquivalenceTest.kt·TestNoise.kt 추가를 발견했다. 이번 검증자가 만든 변경이 아니며 그대로 두었다. 해당 미커밋 작업은 `de4f0cc` 사본 검증에 포함되지 않는다.
