# SELAH RTA 녹음·동기 재생 1차 설계 검토

2026-09-21 · 검토 대상 `90be5bd`의 `docs/spec/2026-09-21-recording-design.md`

**판정: 방향은 타당하지만 현재 설계의 구현 게이트 승인은 보류한다. 설계상 High 3건, Medium 2건을 먼저 명확히 해야 한다.** 기존 실시간 측정·SignalPlayer 승인에 대한 재판정은 아니다. 아래는 아직 없는 구현에서 발견한 버그가 아니라, 구현 전 확정해야 할 계약과 논리적 불일치다. Critical은 확인하지 못했다.

**Room 대신 timeline.bin을 쓰는 선택은 기술적으로 수용 가능하다.** 다만 파일을 고르는 것만으로 동기화·복구 문제가 해결되지는 않는다. 명세의 Room 선택을 바꾸는 것은 ADR/명세 변경으로 별도 기록해야 하며, 이 검토에서 저장소 명세를 변경하거나 구현을 시작하지 않았다.

## D01 — read 완료 시각을 오디오 표본의 시각으로 사용할 수 없다

**Severity: High**

**위치:** 설계서 77–82행. 실제 코드 `app/src/main/java/kr/joa/selahrta/audio/CaptureLoop.kt:77–88,114`.

**근거:** 현재 `monotonicNs`는 `recorder.read()`가 반환한 뒤 호출한 nowNs다. 단조 시계라는 점은 맞지만, 이는 해당 블록 첫 샘플의 캡처 시각이 아니다. 1024프레임/48kHz 한 블록만 해도 21.333ms이며, AudioRecord 버퍼 대기와 스케줄링 지연이 더해질 수 있다. 같은 PCM도 read 반환 시점이 200ms 늦어지면 이 값은 200ms 달라진다. “이미 그 시계이므로 새로 만들 것이 없다”는 결론은 부족하다.

REC 버튼을 누른 시각을 원점으로 쓸지, 실제 저장된 첫 프레임 시각을 원점으로 쓸지도 정해져 있지 않다. 버튼 이후 도착한 블록에 버튼 이전 캡처된 데이터가 포함될 수 있다.

**사용자 영향:** 녹음과 그래프의 상시 offset·부하 의존 jitter, REC 시작 전후 사건 위치 오류. 합성 입력을 이상적인 callback 시각으로만 공급하면 문제를 놓친다.

**권장 수정:** 최소한 `captureFrameStart`, `frames`, `sampleRate`, read 완료 시각을 구분해 보존하고, 첫 저장 프레임의 기준과 monotonic 시각에 대한 anchor 규칙을 정의한다. 하드웨어 timestamp를 얻는 경우 프레임 위치와 시각을 함께 연결하고, 얻지 못할 때의 추정 방식·오차 상태도 정한다. Android `getTimestamp`는 프레임 위치와 캡처 파이프라인의 추정 시각을 제공하며 timestamp 불가 상태도 있다. 현재 nanoTime과 맞추려면 TIMEBASE_MONOTONIC을 일관되게 사용해야 한다. [AudioRecord timestamp](https://developer.android.com/reference/android/media/AudioRecord#getTimestamp(android.media.AudioTimestamp,int)), [AudioTimestamp 시계 기준](https://developer.android.com/reference/android/media/AudioTimestamp).

프레임 누적으로 만든 시간은 상대 오디오 위치에 유용하지만 장치 샘플 클럭과 시스템 시계 사이의 drift를 저절로 없애지는 않는다. 두 시계의 주기적 anchor와 변환 정책이 필요하다. 2시간 ±100ms는 약 13.89ppm에 해당하는 목표이며, 시험 후 결과에 맞춰 통과 기준을 임의로 늘리지 않도록 허용 기준과 미달 시 동작을 먼저 정한다.

**필요 시험:** 동일 PCM/프레임 수에 callback 지터·버퍼 backlog·short read를 바꿔 넣기, REC 시작이 블록 중간에 걸림, 44.1/48kHz 및 timestamp 불가, 시간 anchor 오차, 10분/60분/2시간 drift. ‘임펄스의 어떤 시각’과 허용 오차를 명시한다.

## D02 — 손실 구간 처리와 position/500 직접 인덱싱이 양립할 조건이 없다

**Severity: High**

**위치:** 설계서 65–69,77–85,119,133–137행.

**근거:** PCM을 버리고 나머지를 이어 쓰면 WAV 시간축은 짧아진다. 예를 들어 캡처 시간 0.5–1.5초를 버리면 캡처 2.0초의 사건은 파일 1.0초에 있다. 파일 position/500은 2번 행을 가리키지만 캡처 시간의 올바른 행은 4번이다. event가 존재한다는 사실만으로 변환식이 정해지지는 않는다.

또한 “0.5초마다 최신 snapshot을 기록”과 “0.5초 격자의 행을 빠짐없이 저장”은 다르다. 행 timestamp가 [0,500,1500]이면 1500/500=3이지만 해당 행의 실제 인덱스는 2다. 현재 Controller의 UI snapshot은 66ms 제한과 주 스레드 전달을 거치므로 정확한 500ms 행 격자를 보장하지 않는다.

**사용자 영향:** 손실·일시정지 뒤 다른 시간의 SPL을 보여 주거나 잘못 seek한다. 데이터가 없는 구간을 실제 무음이나 유효한 측정값으로 표시할 위험도 있다.

**권장 수정:** 다음 중 한 정책을 고르고 파일·timeline·재생 모두에 적용한다.

- 시간축 유지: 누락된 프레임 수만큼 writer가 무음 placeholder를 쓰되 반드시 missing 플래그/구간을 표시한다. 실제 측정한 무음으로 취급하지 않는다.
- 시간축 압축: 누락 PCM은 쓰지 않고, 파일 프레임 구간 ↔ capture frame/time 구간의 명시적인 segment mapping을 저장한다. seek는 먼저 이 매핑을 거친다.

다음으로 timeline을 고정 격자로 만들 것인지, 실제 timestamp가 있는 sparse 행으로 만들 것인지 정한다. 고정 격자는 누락 행에도 missing 레코드가 필요하며, sparse는 timestamp 검색/인덱스가 필요하다. 전자의 경우에도 `position/500` 앞에 gap·pause 매핑이 필요할 수 있다. 고정 길이 레코드라는 사실만으로 고정 시간 간격이 되지는 않는다.

**필요 시험:** gap 전후 및 gap 안 seek, 여러 연속 손실, 누락 timeline 행, pause/resume, 경계 499/500ms, REC 중간 시작, 마이크 전환 후 별도 asset. 플레이어의 요청 seek 위치가 아니라 실제 보고된 재생 위치와 그래프 커서를 맞춘다.

## D03 — 주기적 WAV 헤더 갱신만으로 일관된 복구를 보장할 수 없다

**Severity: High**

**위치:** 설계서 94–98,145–147,164–167,178행.

**근거:** audio.wav, timeline.bin, session.json은 세 파일이다. WAV payload와 헤더의 길이 필드 사이, timeline 한 행 중간, JSON 사건 목록 저장 중간에 종료될 수 있다. WAV 헤더의 크기를 주기적으로 덮어쓴다는 것만으로 세 파일의 공통 유효 구간과 gap 사건의 보존이 정해지지 않는다. 저장 공간 고갈은 payload 외에도 마지막 메타데이터/헤더 쓰기를 실패시킬 수 있다.

**사용자 영향:** 오디오는 재생되지만 정렬에 필요한 사건/보정 이력이 없어지는 경우, 손상된 마지막 행, 실제 길이보다 긴 헤더, 복구된 것처럼 보이지만 잘못 정렬된 파일. “거기까지는 재생 가능”이라는 보장이 현재 설계에서 성립하지 않는다.

**권장 수정:** writing/finalized/recovered/failed 같은 파일 상태와 재시작 시 복구 절차를 정의한다. 마지막으로 확인된 payload byte/frame 수, 완전한 timeline 행 수 및 사건 offset을 공통 checkpoint로 관리한다. 메타데이터의 원자적 교체 또는 append journal, 파일 형식의 header magic/version/recordSize/byte order, 불완전 tail 처리와 오래된 파일 reader 정책을 정한다. WAV는 정렬된 실제 PCM 길이와 기록된 checkpoint를 검증해 길이를 보정하며, 저장되지 않은 자료를 있다고 가정하지 않는다. 어떤 데이터 손실 범위까지 보장하는지도 문서화한다.

**필요 시험:** payload short write, WAV 크기 필드 갱신 중 중단, timeline 반 행, JSON/event 갱신 중 중단, flush/close 실패, ENOSPC, 디스크에 남은 파일만으로 재시작 복구. 복구 후 모든 seek가 검증된 공통 구간 안에 있어야 한다.

## D04 — 저장할 측정값의 설정·보정·누적 범위가 부족하다

**Severity: Medium**

**위치:** 설계서 42–44,133–147,151–157행. 코드 `CaptureController.kt:428–494`, `CaptureViewModel.kt:192`, `:706`의 withMeasurement, `MeterSettings.kt:27–31`.

**근거:** onBlock의 MeasurementSnapshot은 보정 전 A/C/Z DSP 결과이고, 화면 보정과 설정은 나중에 주 스레드에서 합성된다. 녹음 worker가 나중의 ‘현재 보정’을 읽으면 이전 PCM에 새 보정이 붙을 수 있다. 세션 metadata에 보정 한 개만 두는 것으로 녹음 도중 보정/곡선 변경을 설명할 수 없다.

또한 실제 긴 Leq 창은 10초/1분/5분 설정인데 설계 행은 leq1m으로 고정돼 있다. MAX/Peak는 REC 전에 이미 누적됐을 수 있고 사용자 reset·시간가중 변경도 영향을 준다. 클리핑의 현재 snapshot `anyClipping`은 세션 누적 상태여서 이를 행별 clipping 사건처럼 쓰면 이후 모든 행이 clipping으로 읽힌다.

**사용자 영향:** 과거 값을 잘못된 보정/가중/평균창으로 읽거나, 녹음에 없는 REC 이전 peak를 ‘이 파일의 최대’로 오해한다.

**권장 수정:** 각 행의 immutable 설정/보정 epoch와 적용 경계를 정한다. 프레임 인덱스로 해당 calibration offset·curve 버전·reference 상태·time weighting·Leq 창을 연결한다. 녹음 중 변경을 금지/분할하는 정책도 가능하나 실제 동작으로 강제해야 한다. 이름이 1m이면 진짜 1분 결과를 저장하거나 필드를 configuredLeq+windowMs로 바꾼다. live measurement 누적 범위를 유지할지 REC 기준 통계를 따로 둘지 명시하며 REC 때문에 live DSP를 reset하지 않는다. reset/setting/segment 사건도 식별 가능해야 한다.

**필요 시험:** 녹음 직전 큰 소리 후 REC, 녹음 도중 보정/곡선/가중/평균창 변경, peak reset, 무보정→보정 전환, writer 지연 중 설정 변경, 클리핑 한 블록 이후 정상 입력. 저장된 행이 수집 당시 정의를 유지해야 한다.

## D05 — 포화 시 사건 보존과 비블로킹 종료/버퍼 반환 계약이 미정이다

**Severity: Medium**

**위치:** 설계서 48–69,155–157,173–175행.

**근거:** 같은 가득 찬 큐에 ‘버렸음’ 사건을 넣는다면 사건 자체도 버려질 수 있다. 풀 부족·queue offer 실패·writer 오류·stop 뒤 늦은 onBlock에서 누가 복사본을 반환하는지도 정의되지 않았다. 과거 SignalPlayer 규칙을 따른다는 설명만으로 마지막 PCM, timeline, 종료 사건의 순서가 결정되지는 않는다.

**사용자 영향:** 기록 손실이 조용히 사라지거나, pool 누수·buffer 재사용 오염, stop/close 대기로 측정 지연이 생길 수 있다.

**권장 수정:** queue/pool 용량을 프레임 또는 시간·메모리 단위로 정하고, tryAcquire/tryOffer 실패 시 대체 할당이나 I/O 대기를 하지 않는다. drop 구간은 미리 확보한 제어 공간이나 병합 가능한 손실 누적 상태 등으로 보존해 데이터 큐 포화에도 알려야 한다. 손실 종료 문턱의 단위·연속/누적 기준, 제어 이벤트 순서와 recorder generation, 마지막 수락 frame, drain/abort/finalize 상태와 버퍼 반환 책임을 명시한다. enqueue 후 writer가 소유하고 성공·실패 어느 경로에서도 한 번만 반환하도록 한다.

측정 결과 불변 시험은 **같은 PCM·설정의 DSP 수치**에 대한 것으로 한정한다. 처리 소요시간 같은 diagnostics까지 bit 동일할 수는 없다. 함수 시간 한 번 측정만으로 비블로킹을 입증하지 말고, writer를 barrier로 계속 막아도 capture 호출이 독립적으로 끝나는지 검사한다.

**필요 시험:** 데이터 큐와 pool 동시 고갈, gap 사건 저장 공간도 포화, STOP/마이크 전환/늦은 callback 경합, writer 예외·close timeout, 모든 경로의 pool 회수, 종료 상태에서 재시작. UI 제한(Controller onBlock의 lastEmitNs early return)과 녹음 공급 경로를 분리해 모든 수락 PCM이 녹음 분기에 도달해야 한다.

## Room 대체 제안의 판단

파일 기반 append-only timeline은 이 1차 범위에서 합리적이다. 그러나 비교표의 “Room은 기기/Robolectric, 파일만 순수 JVM” 구분은 수정해야 한다. Android 공식 문서는 Room KMP와 BundledSQLiteDriver를 이용한 host JVM 시험도 안내한다. 현재 프로젝트에 바로 도입된다는 뜻은 아니지만, JVM 시험 가능성이 파일만의 독점적인 장점은 아니다. [Room 공식 시험 지침](https://developer.android.com/training/data-storage/room/testing-db#test-host-machine).

선택 근거는 ‘이 기능의 순차 저장/조회에 충분하고 당장 의존성을 늘리지 않는다’ 정도가 적절하다. 대신 파일 방식에서는 호환 reader, 복구, 손상 검사, 사건/asset/analysis 관계, 인덱스와 삭제 정책을 직접 책임진다. 버전 바이트를 추가하는 것만으로 과거 파일 호환 문제가 없어지지 않는다.

**권장 결론:** 위 계약을 보완하는 조건으로 timeline.bin 채택에 기술적으로 동의한다. 세션/asset/analysis의 식별자와 저장소 인터페이스를 남겨 나중에 Room을 세션 목록·검색용으로 병용할 수 있게 한다. 타임라인 파일 선택을 기존 Phase 10의 전체 세션 관리/DB 요구까지 폐기하는 결정으로 확대하지 않는다. 실제 명세 예외는 ADR에 범위를 적어야 한다.

## 형식과 단계 범위에 대한 보완

- **PCM16은 손실 변환이다.** 현재 capture는 float 경로도 사용한다. 단순 round(x×32768) 예에서 x=0.00001은 0이 된다. ‘정밀도의 상한을 적는다’는 방침은 맞지만 float 입력 대비 bit-preserving 분석 원본이라고 부르면 안 된다. WAV float32/PCM24 등의 선택은 원본 보존 요구와 용량을 비교해 결정하고, PCM16 MVP라면 quantization·clamp·비정상값·dither 여부와 재분석 한계를 명시한다. live DSP는 계속 변환 전 PCM을 사용한다.
- f16도 무손실이 아니다. 우선 float32 timeline을 쓰면 제시된 38개 수치+6byte 기준 2시간 약 **2.28MB**다. 약 691MB인 같은 길이 PCM16 오디오에 비해 작으므로, binary16 변환·오차·null/NaN 규칙의 복잡성을 지금 추가할 실익을 재검토할 만하다. 고정 길이 레코드와 f16은 별개의 선택이다.
- 마이크/샘플레이트가 바뀌면 현재 파일을 종료하는 방향은 타당하다. 녹음을 자동 재개할지 사용자가 새 REC를 눌러야 할지 명시한다. REC 중 pause/resume을 이번 MVP에서 지원하는지도 명확히 한다.
- RTA 표시·marker UI를 미루는 것은 단계 축소로 수용 가능하다. 다만 사건 데이터 저장까지 미루는지 구분하고, 필수 데이터가 없는데 나중에 UI만 붙이면 된다고 가정하지 않는다. 최종 Recording-D/F 완료로 표기해서는 안 된다.
- 명세의 REC 표시·경과시간, 음성 저장 안내, 분석만 삭제/오디오 포함 삭제 구분과 RecordingAsset/AnalysisRun 관계를 구현 체크리스트에 유지한다. 내부 디렉터리 이름만으로 세션/녹음 asset/분석 run의 관계를 대체하지 않는다.

## 다음 단계 권고

먼저 짧은 보완 설계로 ① 프레임/시각 anchor ② gap/pause 매핑과 timeline 행 규칙 ③ 복구 checkpoint ④ 보정·설정 epoch ⑤ 큐/종료 소유권을 확정한다. 그 뒤 RecordingSink와 pool의 독립 실험부터 시작하는 순서는 적절하다. 저장 포맷을 먼저 고정하고 시간 의미를 나중에 맞추는 순서는 권하지 않는다.

## 검증 경계

설계서, 마스터 추가 명세, CaptureLoop/Controller, AudioSource/MicSource 관련 부분, MeasurementSnapshot·화면 합성·MeterSettings·MultiWeightEngine을 대조했다. 관련 Android 공식 문서도 확인했다. 아직 녹음 구현이 없으므로 앱 전체 시험/build를 재실행하지 않았으며, 과거 287개 통과를 이 설계의 검증으로 인용하지 않는다.

동기화/크기 예시는 별도 `recording-design-arithmetic.txt`에 산술 반례로 저장했다. 실제 PCM recorder·복구·기기 동기화 시험을 수행했다는 뜻은 아니다. 원본 파일/명세/production 코드는 변경하지 않았다.
