# SELAH RTA 수정분 독립 재검증

검증일: 2026-09-21 · 대상: `54111d7..b0695ef` · 판정: **Phase 9 게이트 승인 보류**.

27파일 +1401/−208, 구현 커밋 `a237a78`, `b0695ef`를 확인했다. 검사 당시 HEAD `a236363`의 추가 변경은 재검증 요청 문서뿐이다. 원본 working tree는 clean이며 production 코드는 변경하지 않았다. 첨부 화면과 요청서의 “전부 해결”, “실기기 확인”은 구현자의 보고로 취급했고, 독립 실행 결과와 구분했다.

**High 2건이 남아 있다.** 하나는 R01의 측정 중 라우팅 변경 경로이고, 다른 하나는 이전 보고서에도 남은 위험으로 적었던 종료 timeout 이후 이전 작업 스레드의 새 세션 접근이다. 추가로 Medium 5건을 기록했다. Critical은 확인하지 못했다.

## 독립 실행 결과

- `b0695ef`를 별도 사본으로 추출하고 실제 DSP 소스를 Kotlin 2.2.20/JDK 21/JUnit 4.13.2로 다시 컴파일했다. **기존 DSP 153/153 통과**. 이 안에 이식된 IndependentRegressionTest 4개가 포함된다.
- 별도 검증 코드에서 **실제 `RtaEngine.setCurve()` → `process()` → `frame()` 경로**를 실행했다. 이전 R05의 −6/0/+6 dB 곡선과 1 kHz 순음에 대해 기대 −9.030900 dBFS, 실제 −9.015380 dBFS: **+0.015520 dB 오차**, 0.05 dB 기준 통과.
- R08은 세 지점 밖의 순음을 추가로 넣어 검사했다. 125 Hz 밴드는 `resolved=true`, 표기용 손실 0.919 dB이지만 밴드 안 다른 위치에서는 **1.919–2.817 dB** 손실이 발생했다.
- 곡선 교체 직후 `RtaEngine.frame()`이 이전 곡선으로 계산한 프레임을 그대로 반환하는 것도 실행 확인했다.
- Android 앱 전체 build/lint, 앱 16개 단위 테스트, Compose UI, USB/내장 실기기, ERROR_DEAD_OBJECT 주입, 장시간 부하는 이번에 실행하지 않았다. 따라서 구현자 보고의 **169개/185회**, Galaxy S23 로그 및 실기기 결과를 독립 재현했다고 주장하지 않는다.

첨부 `fix-wave-verification-log.txt`, `FixWaveProbe.kt`, `run-fix-wave-verification.ps1`로 수치 검증을 재현할 수 있다. 이 스크립트는 DSP 검증용이며 Android lifecycle 통합 테스트를 대신하지 않는다.

아래 위치는 `b0695ef` 기준이다. `app/…/`는 `app/src/main/java/kr/joa/selahrta/`, `dsp/…/`는 `dsp/src/main/kotlin/kr/joa/selahrta/dsp/`를 뜻한다.

## 남은 결함

### F01 · High — 측정 도중 route가 바뀌면 기존 마이크 보정과 누적값을 유지한다

**위치:** `app/…/audio/MicSource.kt:246–258`; `app/…/ui/CaptureViewModel.kt:365–370, 503`.

**판정:** R01 부분 수정. 녹음 시작 후 실제 identity를 확인하는 초기 경로는 개선됐지만, 원 지적의 runtime reroute 문제는 남았다.

**논리적 재현:** A로 경로 확인 및 보정 완료 → A가 목록에는 남아 있는 상태에서 Android가 실제 capture route를 B로 변경 → listener는 다른 stableKey를 감지 → `onRoutingLost()`는 안내문만 설정. `opened`, global calibration, curve, engine, session은 바뀌지 않는다. 이 경로에서는 장치 목록 제거 이벤트가 필요하지 않으므로 scanner의 disconnect 처리가 대신 해결해 주지 않는다.

**사용자 영향:** B의 PCM에 A의 보정을 계속 적용하고, A와 B의 Leq/MAX를 한 누적값으로 합친다. “재는 도중 입력을 바꾸지 않는다”는 앱 정책도 시스템의 자동 reroute 자체를 막지는 않는다.

**권장 수정:** 확인된 route 변경 시 즉시 보정·프레임 발행을 무효화하고 측정을 종료한다. 또는 새 세션/프로필 준비를 거쳐 재시작한다. 안내문만으로 정확성 문제를 해소하지 않는다. routing 이벤트에도 세션 토큰을 붙인다.

**회귀 테스트:** 두 기기가 모두 연결된 상태에서 actual route만 A→B로 변경. 이름이 같은 하단/후면도 포함한다. 변경 이후 B PCM이 A profile/누적 엔진으로 처리되지 않아야 한다.

### F02 · High — 500 ms join timeout 뒤에도 이전 worker가 새 엔진과 명령 큐를 사용할 수 있다

**위치:** `app/…/audio/MicSource.kt:286–331, 335–352`; `app/…/ui/CaptureViewModel.kt:233–239, 527–535, 555–571, 768–780`.

**범위 구분:** 종료 순서 자체는 수정 전부터 존재했고 이전 보고서의 남은 위험에도 적었다. 이번 R02의 “DSP는 한 오디오 스레드만 접근”이라는 완료 판단에도 직접 영향을 준다.

**논리적 재현:** old worker가 blocking read나 DSP에서 500 ms 넘게 지연 → main의 `close()`가 join timeout 후 stop/release하고 반환 → 새 start가 공유 `engine/rta`와 통계 필드를 교체 → old worker가 재개. read 이후 `running` 재검사 없이 `onBlock()`을 부를 수 있고, callback은 `drainCommands()`와 **현재** `engine/rta`를 사용한다. old snapshot은 session 검사에서 버려져도 새 엔진 상태를 오염시킨 뒤다. `@Volatile`은 참조 가시성만 보장하며 두 worker의 동시 접근을 막지 않는다.

**사용자 영향:** stop/restart 또는 fallback 시 이전 장치 PCM이 새 장치 Leq/MAX/RTA에 섞이거나, 이전 worker가 새 명령을 소비할 수 있다. resource release와 read가 겹칠 수도 있다.

**권장 수정:** 캡처 세대별 engine/queue/counters를 소유하게 한다. stop 시 세대를 먼저 무효화하고 read를 해제한 뒤 실제 worker 종료를 확인한다. main thread를 장시간 막지 않는 종료 절차를 사용하고, 종료되지 않은 worker가 공유 엔진에 접근하지 못하게 한다. callback 입구의 세대 검사도 DSP 처리 전에 수행한다.

**회귀 테스트:** fake AudioRecord/read barrier로 500 ms 이상 정지시키고 stop→start→old read 반환 순서를 강제한다. 새 엔진의 입력 수·Leq·MAX·명령 소비가 새 세션 PCM만으로 결정되는지 검사한다. 실기기에서 이 경합을 관측했다고 주장하는 것은 아니며, 현 코드가 허용하는 실행 순서에 대한 지적이다.

### F03 · Medium — read 종료가 장치 제거 이벤트보다 먼저 오면 fallback이 실행되지 않는다

**위치:** `app/…/ui/CaptureViewModel.kt:263–266, 340–360, 410–416`; `app/…/audio/MicSource.kt:313–320`.

**논리적 재현:** USB 사용, disconnectPolicy=FallBack → USB 분리로 read가 먼저 음수 반환 → `onCaptureEnded()`가 stop 후 Failed로 전환 → 뒤늦게 입력 목록 변경 수신. `source == null`이므로 `onDeviceListChanged()` 자체를 건너뛴다. 반대로 목록 제거가 먼저 처리되면 내장 fallback이 실행된다.

**사용자 영향:** 동일한 분리 설정인데 callback 도착 순서에 따라 내장으로 계속 측정하거나 완전히 멈춘다. L01의 오류 통지는 개선됐지만 R11의 분리 정책과 통합되지 않았다.

**권장 수정:** terminal error와 device removal을 하나의 세션 상태 전이로 조정한다. 종료 당시 active device/정책을 보존하고 현재 목록을 재확인하여 정책을 한 번만 실행한다. 오디오 서버 오류와 물리적 분리를 구별하거나 보수적 정책을 명시한다.

**회귀 테스트:** error→remove, remove→error, 중복 error/remove, 사용자가 stop한 직후의 늦은 error. FallBack/Pause가 callback 순서와 무관하게 일관돼야 한다.

### F04 · Medium — stop이 session을 무효화하지 않아 늦은 확인 이벤트가 보정을 다시 구독한다

**위치:** `app/…/ui/CaptureViewModel.kt:379–401, 410–421, 768–796`.

**논리적 재현:** 첫 정상 read 뒤 route confirmation을 main에 큐잉 → main이 먼저 stop 실행 → 늦은 `onRouteConfirmed(mySession, fmt)` 실행. stop은 `_state.session`을 그대로 두므로 검사에 통과한다. `opened/openingKey`를 되살리고 `watchCalibration()`을 다시 시작한다. 같은 이유로 늦은 error가 사용자의 정상 stop을 Failed로 바꿀 수 있다.

**사용자 영향:** stop 직후 미보정으로 지웠던 상태가 다시 보정됨으로 바뀌고, 종료한 capture의 watcher가 재등록된다. 새 start가 이미 세대를 바꾼 경우는 걸러지지만 **stop만 한 경우**는 보호되지 않는다.

**권장 수정:** stop 진입 시 active generation을 무효화한다. session뿐 아니라 active source/허용 상태를 확인한다. callback과 watcher 응답, 지연된 save 완료 후 명령에도 origin generation을 연결한다.

**회귀 테스트:** main dispatcher를 제어해 stop 후 confirm/error/save 완료를 순서대로 전달한다. Idle 상태·보정 무효화·watcher 종료가 유지돼야 한다.

### F05 · Medium — 세 지점의 오차를 밴드 전체의 최대 오차처럼 표시한다

**위치:** `dsp/…/BandAnalyzer.kt:28–37, 84–95, 106–139`; `app/…/ui/screens/AnalyzeScreens.kt:64–69`.

**실행 근거:** 현재 시험 위치 t=0.25/0.5/0.75에서 125 Hz 밴드 손실은 최대 0.919 dB로 `resolved=true`다. 같은 밴드의 로그 위치 t=0.9에서는 1.919 dB, t=0.99에서는 2.817 dB다. 20 Hz 밴드의 표기용 6.639 dB도 t=0.1의 6.882 dB, t=0.01의 7.044 dB보다 작다. “실제보다 최대 6.6 dB”는 상한이 아니다.

**사용자 영향:** 1 dB 이내/최대 N dB라는 품질 표시가 실제 분석 가능한 입력 전부에 성립하는 것으로 읽힌다.

**권장 수정:** 1 dB을 제품 자체 기준으로 정하는 것은 가능하다. 다만 시험 영역을 “밴드 중앙 50%, 세 지점, phase=0의 순음 평가”로 명시하고 추정/표본 최대라고 표현한다. 전체 밴드 오차를 주장하려면 주파수·위상 sweep과 경계의 별도 규약을 정의한다. 기준선을 무조건 398 Hz로 옮기라는 지적은 아니다.

**수학적 판단:** 밴드 안에 있는 이상적인 단일 순음은 원래 두 주파수를 갖는 것이 아니다. 두 밴드로 나뉘는 것은 유한 창의 누설과 bin 분배 때문이므로 “원래 두 밴드에 걸친 소리라 제외”라는 근거는 성립하지 않는다. 다만 FFT 기반 근사 분석의 경계 응답을 별도 품질 항목으로 정의할 수는 있다.

**회귀 테스트:** 각 밴드 t=0.01/0.1/0.25/0.5/0.75/0.9/0.99 및 여러 시작 위상. 표기한 품질 범위 밖의 수치를 maximum으로 오인시키지 않는 UI 테스트.

### F06 · Medium — 곡선 적용 표시가 실제 DSP 적용보다 먼저 바뀐다

**위치:** `app/…/ui/CaptureViewModel.kt:628–629, 845, 865`; `dsp/…/RtaEngine.kt:83–87, 126`.

**근거/실행:** watcher는 `setCurve`를 큐에 넣자마자 base.curve를 변경한다. combine은 아직 기존 `_measurement.rta`를 새 curveApplied/coverage 정보와 합친다. queue가 처리돼도 `setCurve()`는 latest를 비우지 않아서 다음 FFT 전까지 이전 curve의 프레임을 반환한다. 별도 실행에서 curve 교체 직후 동일한 old frame/−9.01538 dBFS가 남고, 다음 FFT 뒤에야 새 +10 dB 응답 보정의 −19.03090 dBFS로 바뀌었다.

**사용자 영향:** 화면의 보정 적용 여부·범위와 숫자의 계산 조건이 일시적으로 다르다. 새 FFT 전에 stop하면 그 불일치 snapshot이 정지 화면에 남을 수도 있다.

**권장 수정:** RtaFrame에 실제 적용된 profile revision을 담고 UI metadata를 그 revision에서 만든다. 곡선 변경 명령 처리 시 이전 latest를 무효화하고 새 계산 결과까지 로딩/보류로 표시한다. time-weight/Leq 엔진 변경에도 같은 config acknowledgement 방식이 유효하다.

**회귀 테스트:** capture 정지 barrier 상태에서 curve import/clear, 명령 적용 후 next FFT 전, 즉시 stop. 숫자와 curve revision이 항상 일치해야 한다.

### F07 · Medium — R11의 자동 전환 요구를 구현 완료로 처리할 수 없다

**위치:** `app/…/ui/CaptureViewModel.kt:309–335`; `app/…/ui/screens/HistorySettingsScreens.kt:102–108`; 명세 2·14장.

**근거:** UI를 “다음 시작에 사용”으로 바꾼 것은 올바른 개선이다. 그러나 master spec은 USB 연결 시 자동 전환 여부를 설정으로 두도록 요구한다. 현재는 이 기능 자체를 시작 시 선택 정책으로 축소했다. 새 정확도 ADR은 R04/R08만 다루며 R11의 요구 변경을 기록하지 않는다. 재검증 요청서의 구현자 설명만으로 원 요구 충족을 판정할 수 없다.

**사용자 영향:** 자동 전환 기능을 기대한 사용자는 연결 후 수동으로 stop/start해야 한다.

**권장 수정:** 전환을 새 측정 세션으로 처리해 앞뒤 값을 분리하는 방식으로 원 요구를 구현하거나, 자동 전환 유보를 명세/ADR의 명시적 범위 변경으로 남긴다. 구현자가 요청서에 적은 정책을 사용자가 승인한 것으로 간주하지 않는다. 이번 검토 중 사용자 승인을 별도로 요구하거나 정책을 대신 변경하지 않았다.

**회귀 테스트:** 수동 선택, 자동 on/off, 내장 고정, hot plug, fallback 각각에 대해 결정된 정책과 UI가 일치하는지 확인한다.

## 원 지적 13건의 상태

| 항목 | 재검증 판정 |
|---|---|
| R01 초기 route 확인 | 초기 경로 수정 확인. **측정 중 reroute는 F01로 미해결** |
| R02 StateFlow 경쟁 | 원래 lost update 경로는 제거됨. DSP 명령 큐도 정상 실행 중의 동시 reset을 개선. **종료 timeout 격리는 F02**, metadata 동기화는 F06 잔존 |
| R03 이전 기기 보정 잔류 | 정상 stop→새 start에서 초기화 확인. **stop만 한 뒤 늦은 이벤트는 F04** |
| L01 read 오류 후 Running | 음수 read → main 실패 전이 코드 확인. **장치 제거 정책과 경쟁은 F03**, 실기기/주입 검증 미실행 |
| R04 광대역 주파수 보정 제외 | 잘못된 <0.5 dB 근거 삭제 및 ADR 확인. **정확도를 고친 것이 아니라 제한을 문서화한 것**. 조건부 잔여 한계 |
| R05 band correction | 실제 bin별 역응답 경로 수정 및 독립 실행 확인. 원 수치 결함 해소 |
| R06 block-end MAX | sample-wise max 집계 확인, 회귀 통과. 해소 |
| R07 첫 샘플 시간가중 | 초기 0부터 적분, settled 표시 확인, 회귀 통과. 해소 |
| R08 저역 resolved | 63/80 Hz 오판 수정. 전체 maximum 표기는 F05 잔존 |
| R09 파일명 충돌 | SHA-256으로 원 collision 해소. 아래 migration/저장 실패 위험 별도 |
| R10 Peak 단위 | 무가중 표기로 수정 확인. MAX와 구분됨 |
| R11 입력 선택 정책 | running 상태에서 실제 입력/다음 선택 구분 개선. 자동 전환 요구는 F07, disconnect 순서는 F03 |
| R12 PHONE MIC 고정 | confirmed format.micKind 사용 확인. 코드상 고정 표시 결함 해소, 실제 USB UI 미검증 |

## 요청서의 다섯 판단 지점에 대한 답

1. **R08:** 중앙 세 지점을 품질 표본으로 쓰는 것은 가능하지만 전체 밴드의 maximum으로 표현하면 안 된다. 1 dB은 제품 기준이지 규격 수치가 아니다. F05 참고.
2. **R11:** 입력 혼합을 피하려는 목적은 타당하다. 자동 전환 시 새 세션을 열면 혼합을 피하면서 기능을 제공할 수 있다. 기능을 미룰 경우 정식 요구 변경으로 기록해야 한다. F07 참고.
3. **route 끝내 미확인:** 잘못된 마이크 보정을 추정 적용하는 것보다 미보정 상태가 낫다. 제한 시간 후 “확인 실패·보정 불가”로 전환하고 재시도 경로를 제공하는 것이 좋다. 또 `saveSimpleCalibration`/`importCurve`는 routeConfirmed를 검사하지 않으므로 미확인 상태의 요청 key로 저장되지 않게 막아야 한다.
4. **bandGainsDb:** 지금은 측정 경로에서 빠져 있으므로 삭제 자체가 필수는 아니다. `bandCenterResponseDb`처럼 뜻을 분명히 하거나 deprecated 처리하는 편이 낫다. **원 재현 테스트가 통과한 것만으로 새 경로를 검증한 것은 아니다.** 원 테스트는 여전히 이 helper를 빼는 옛 경로이며 helper 의미 변경으로 통과한다. 추가 CurveCorrectionTest와 이번 실제 RtaEngine 검증이 새 경로의 근거다. “검증 논리 그대로 이식”과 “동일 production 경로 검증”을 구분해야 한다.
5. **명령 큐:** main에서 stop이 동기 실행되는 동안 다른 main coroutine이 중간에 끼어 명령을 넣는다는 가정은 맞지 않는다. 그러나 이것은 `source != null`이 보장한 것이 아니다. 진짜 문제는 timeout 뒤 살아 있는 old worker(F02), stop 뒤 지연 callback(F04), 다음 start 뒤 도착하는 이전 비동기 작업의 명령이다. 명령에도 generation을 연결해야 한다.

## 추가 한계

- CurveStore는 새 hash 파일만 찾는다(`100–114`). 이전 버전의 underscore 파일은 migration이 없어 저장돼 있던 곡선이 더 이상 로드되지 않는다. 기존 profile이 있는 업그레이드에는 이관 또는 재import 안내가 필요하다. 충돌 가능했던 옛 파일을 무조건 어느 장치에 귀속시키는 migration도 피해야 한다.
- `writeAtomically()`는 rename 실패 시 target에 직접 덮어쓰므로 모든 실패 경로가 atomic하지 않다. 같은 key의 동시 save가 동일 `.tmp`를 사용하는 것도 시험해야 한다. 실제 파일 격리에는 USB 실물이 필수는 아니며, 임시 저장소에 synthetic key 두 개를 넣는 테스트로 검증 가능하다.
- `startRecording()` 실패/오디오 callback 예외의 cleanup은 이번 수정에서도 충분히 보장되지 않는다. fake capture와 dispatcher 주입 지점을 만들어 USB가 없어도 오류/순서 테스트를 수행할 수 있게 하는 것이 우선이다.
- stop 후 `opened`를 보존하므로 상단 active 색과 입력 목록의 “사용 중”이 실제 측정 종료 상태와 어긋날 수 있다. “마지막 입력”과 active capture 표시는 분리해야 한다.
- R04의 자세한 제한은 보정 카드에만 있고 보정 후 큰 측정값 옆에는 없다. 현재 SPL 보정 범위를 해당 측정 화면에서도 명확히 표시하는 것이 좋다. 기준 소음계·현장 녹음·USB·장시간 정확도 승인은 여전히 별개다.

**다음 게이트:** F01/F02를 먼저 수정하고 fake capture를 사용한 종료·재시작·route/error 순서 테스트를 추가한다. F03–F07은 수정 또는 명세에 따른 명시적 처리 결정을 남긴다. DSP 153개 통과와 Galaxy의 정상 경로 확인만으로 High 경합 경로까지 해결됐다고 판정하지 않는다.
