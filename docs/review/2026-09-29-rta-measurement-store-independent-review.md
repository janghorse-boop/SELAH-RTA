# RTA 저장·차이·차례 독립 검토 — 2026-09-29

**판정: #73 / #75 / #77 승인 보류. Critical 0, High 3, Medium 2, Low 1.**

기존 시험은 통과하지만 실제 ViewModel 연결부와 저장 조건에서 반례가 재현됐다. 중간에 채널이 바뀐 값을 최종 채널의 곡선으로 저장하고, 출력이 멈춘 뒤에도 차례 측정을 이어 간다. 서로 다른 분석 가중·보정 수치도 같은 조건으로 비교할 수 있다. 아래 문제는 94dB 기준기 유무와 무관하게 검증할 수 있는 코드 계약 문제다.

**11회차 신호 정지 경계 승인은 유지한다.** 이번 지적은 신호 제어의 정지 경계를 뒤집는 것이 아니라, RTA 소비자가 출력의 실제 적용·종료를 따르지 않는 별도 문제다. 첨부된 SRLRO 요청은 그 경계를 확인하는 배경 자료로 사용했다.

## 1. 고정본과 검증 경계

- 검토·빌드 고정본: `5cff7c37a3b290bd586feeb33bae020efbef08e9`.
- 요청서: `2026-09-29-rta-measurement-store-review-request.md`, 설계: `docs/superpowers/specs/2026-09-29-rta-measurement-store-design.md`.
- 별도 `git archive` 사본: `build/independent-review/rta-store-20260929/source`. 본문의 코드 줄 번호는 **이 고정본** 기준이다.
- 작업 중 실제 체크아웃은 `d394c7b2b766ed7ca5c32e3272fd23932d1f9040`으로 이동했다. 고정본과 이 HEAD의 `app/src/main`, `dsp/src/main` 차이는 CaptureViewModel KDoc 19줄 추가뿐이다. 따라서 이 시점 HEAD에도 지적한 실행 경로가 남아 있다. HEAD의 VM 줄 번호는 본문보다 19줄 뒤다.
- 실제 제품 소스는 수정하지 않았다. 작은 제안 패치만 격리 사본에서 검증한 뒤 그 사본의 제품 파일도 원복했다. 다른 작업에서 생성된 파일은 건드리지 않았다.
- Android 시험은 별도 `emulator-5586`에서 실행했다. 연결된 실제 휴대폰에는 설치·설정 변경·재생을 하지 않았다.

## 2. 직접 실행한 결과

| 실행 | 결과 | 의미 |
|---|---:|---|
| 기존 `data.rta.*` JVM 시험 | 66/66 통과 | 저장 21, 차이 15, 측정 13, 차례 9, 평균 8 |
| 고정본 debug 앱·androidTest APK 빌드 | 성공 | 컴파일·패키징 확인 |
| 기존 `RtaSequenceGateTest` | 2/2 통과 | 기존 입구 검사·시작 시험 |
| 추가 JVM 독립 시험 | **5개 중 4개 실패** | 새 PCM 없는 완료, 재시작 안정화, 가중·보정 비교 반례 |
| 추가 Android VM 독립 시험 | **5개 중 4개 실패** | 채널 혼합, 출력 확인 전 시작, 백그라운드 지속, 취소 UI |
| 제안 패치 후 JVM | 14/14 통과 | 기존 측정 13 + 새 재시작 반례 1 |
| 제안 패치 후 Android | 2/2 통과 | 취소 UI + 정상 저장 대조군 |

추가 시험의 실패는 컴파일 오류가 아니라 기대한 계약을 깨는 단언 실패다. 정상 대조군도 함께 실행했다. 선형 전력 평균은 60·80dB → **77.0329dB**로 맞고, 정상 VM 저장은 새 저장소 인스턴스로 읽어 **1개, 60dB**를 확인했다.

이번에 DSP·앱 전체 시험이나 lint를 새로 실행한 것은 아니다. 과거 전체 통과 수를 이번 결과에 합산하지 않았다. Android 시험은 실제 ViewModel·코루틴·상태·파일 저장 경로를 사용하지만, 마이크 대신 합성 MeasurementSnapshot과 소리가 나지 않는 SignalSink를 주입했다. 실기기 음향·AudioTrack·USB 경로 검증을 뜻하지 않는다.

## 3. 지적 사항

### RMS-01 — High: 조건이 바뀐 측정을 합친 뒤 마지막 상태로 저장한다

**위치:** `CaptureViewModel.kt:1735–1779`, `1897–1920`.

시작할 때 offset·FFT/sampleRate 일부만 잡고, 측정 중에는 scalar offset 변화만 검사한다. 출력 채널·레벨·신호, 분석 가중, 곡선, 입력 세션 등이 바뀌어도 기존 평균을 계속 쌓는다. 완료 시에는 다시 읽은 `baseState`로 채널·신호·마이크·보정 설명을 적는다. 주석의 “그때의 조건을 한 벌로”와 실행 코드가 다르다.

**재현:** 실제 VM에서 왼쪽 출력으로 시작하고 6초에 `setSignalChannels(Right)`를 호출했다. 전·후 합성 입력은 각각 -60/-20dBFS다. 12.3초 뒤 새 저장소에서 읽은 결과:

```text
RMS_CHANNEL saved=1 label=Right mean=97.42470853866502 name=left
```

채널 변경에서 취소하거나 새 안정화·10초 측정을 시작했다면 이 시점에 혼합 곡선이 저장되어서는 안 된다. 합성 입력으로 두 구간을 구분한 VM 계약 시험이며, 실제 스피커 차이를 측정한 수치는 아니다.

**사용자 영향:** 좌·우 또는 조정 전후 차이로 보이는 값에 서로 다른 구간이 섞인다. 저장 후에는 어느 부분이 어떤 조건인지 복구할 수 없다.

**권장 수정:** 한 측정 구간에 불변 `CaptureContext`를 붙인다. 실제 적용된 입력 세션·소스/채널, FFT·가중, 보정 수치·곡선 식별, 출력 세대·채널·신호 설정을 함께 고정한다. 프레임 수락 및 저장 직전 모두 이 context를 검사한다. 변경 시 명시적으로 취소하거나 전량 폐기 후 새 안정화부터 시작한다. 저장 설명은 완료 시 UI 상태가 아니라 그 context에서 만든다. `tick()`이 먼저 Done으로 바꾼 뒤 `cancel()`하는 방식은 `cancel()`이 running일 때만 동작하므로 최종 검증을 대신할 수 없다.

**회귀 시험:** 실제 VM 저장 경로에서 채널·레벨·가중·곡선 generation·입력 교체·FFT 변경을 각각 끼워 넣는다. 마지막 프레임/완료 직전 변경도 검사한다. 혼합본 저장 금지와 안정 조건 정상 저장을 함께 단언한다.

### RMS-02 — High: 출력 적용 확인 없이 차례 측정을 시작하고, 출력 종료도 따라가지 않는다

**위치:** `CaptureViewModel.kt:1796–1823`, `1627–1630`.

`playingSignal`은 UI 의도 상태인데 이를 출력 확인으로 사용한다. `setSignalChannels()`가 명령을 큐에 넣자마자 1.5초 안정화를 시작한다. 출력 명령이 밀리면 안정화가 실제 출력보다 먼저 끝난다. 도중 `onBackground()`가 신호를 정상 정지시켜도 RTA 작업은 취소되지 않는다.

**재현 1:** 출력 명령 작업자를 latch로 막고 Pink 시작·차례 측정을 요청한 뒤 2.3초간 프레임을 제공했다.

```text
RMS_ACK player=null uiSignal=Pink
capture=RtaCaptureUi(settling=false, remainingMs=9290, frames=9, ...)
```

실제 출력은 한 번도 열리지 않았는데 측정 구간에 들어가 9장을 쌓았다.

**재현 2:** 실제 VM에서 차례 시작 후 2.3초에 `onBackground()`를 호출하고 입력만 계속 제공했다.

```text
RMS_BACKGROUND saved=1 storedSignal= output=null sequence=2/3 · 오른쪽만
```

출력 정지는 정상이다. 그런데 무신호 구간까지 저장하고 오른쪽 단계로 넘어간다.

**사용자 영향:** 지연된 이전 채널이나 주변 소음이 L/R 비교 기록으로 남는다. 백그라운드에서 계속되는 일반 마이크 측정과 “테스트 신호를 내며 채널별로 저장”하는 작업의 수명이 분리되지 않았다.

**권장 수정:** 채널 변경마다 해당 출력 generation의 실제 시작 성공 확인을 기다린 뒤 안정화를 시작한다. 고정 지연을 늘려 해결하지 않는다. 측정 동안 출력 종료·시작 실패·포커스 상실·noisy·백그라운드·사용자 정지를 감시해 현재 구간과 다음 차례를 중단한다. 이미 정상 완료한 앞 단계 기록은 보존한다. 일반 RTA의 무신호 저장 허용 여부는 별도 모드 계약으로 두고, 차례 측정의 필수 출력 조건에 적용한다.

**회귀 시험:** 명령 큐를 1.5초보다 오래 막아도 확인 전 누적 0; 확인 후 전체 안정화; 실제 시작 실패; 각 단계 중 출력 종료; 백그라운드 후 새 단계 0; 완료된 앞 단계만 보존. 기존 두 입구 시험으로는 이 계약이 검증되지 않는다.

### RMS-03 — High: 다른 가중·보정 수치가 저장 후 ‘같은 조건’이 된다

**위치:** `RtaMeasurement.kt:83–111`, `RtaDifference.kt:67–75,116–122`, `RtaMeasurementStore.kt:172–177`.

비교 조건은 inputKey·보정 상태/출처·곡선 파일명·FFT·sampleRate뿐이다. **analysisWeighting과 실제 scalar offset 수치가 없다.** 곡선도 내용 식별이 아닌 파일명만 저장한다. 입력 소스·입력 채널을 구별하는 기존 calibration key와도 정보량이 다르다.

**재현:** 같은 100Hz PCM을 실제 RtaEngine으로 Z/A 가중 분석한 두 곡선을 저장하고 새 저장소 인스턴스로 읽었다. 또 동일 -50dBFS에 offset 100/106dB를 적용한 두 레코드를 같은 보정 상태·출처로 저장했다.

```text
RMS_WEIGHT conditionsEqual=true accepted=true delta100=19.07365167861407
RMS_OFFSET conditionsEqual=true accepted=true delta=-6.0
```

첫 시험은 실제 DSP 분석·저장·차이 계산을 통과한다. 둘째는 모델과 파일 왕복의 정보 소실을 보는 시험이며 실제 보정 마법사 실행을 재현한 것은 아니다. 둘 다 현재 모델이 해당 차이를 표현할 수 없음을 보여 준다.

**사용자 영향:** 분석 가중 때문에 생긴 약 19dB나 보정 변경의 6dB가 좌우/조정 전후 차이로 제시될 수 있다. 파일을 다시 읽어도 원인을 확인할 수 없다.

**권장 수정:** 실제 적용 가중, 수치 offset과 보정 상태, 적용 곡선 내용 hash/불변 버전, 입력 소스·채널 등 측정에 영향을 주는 조건을 기록하고 비교한다. 곡선이 없다는 사실과 식별 불가를 구분한다. 평균의 실제 구간·유효 coverage, 신호 종류의 세부 파라미터도 보존한다. 파일명이 같다고 같은 곡선으로 간주하지 않는다. 새 필수 조건이 없는 옛 자료는 미확인으로 남겨 자동 비교를 제한한다.

**출력 채널·출력 레벨 차이 자체는 막지 않는다.** 바로 그 차이를 비교하는 설계 목적을 유지한다. 구간 중 변경 금지(RMS-01)와 서로 완료된 두 측정의 의도적인 조건 차이는 다른 계약이다.

**회귀 시험:** Z/A/C; 같은 상태·출처의 서로 다른 offset; 같은 파일명의 다른 곡선 내용; 같은 USB 기기의 입력 채널/소스 차이; 새 필수 칸이 없는 옛 파일. 같은 조건 및 의도한 L/R·레벨 비교는 계속 허용한다.

### RMS-04 — Medium: 취소한 작업의 진행 UI가 남는다

**위치:** `CaptureViewModel.kt:1776–1779,1845–1847`; `ui/screens/RtaSaveControls.kt`의 진행 중 분기.

`rtaJob.cancel()`에 따른 CancellationException은 `finally { frames.cancel() }`만 실행하고 이후 `finishRtaCapture()`를 건너뛴다. UI 정리는 finish 또는 정상 tick 경로에만 있다.

**재현:** 실제 VM에서 시작 후 취소하고 job 종료를 기다렸다.

```text
RMS_CANCEL jobActive=false
ui=RtaCaptureUi(settling=true, remainingMs=1500, frames=0, nameKo=cancel)
phase=Cancelled
```

**사용자 영향:** 작업은 끝났지만 화면에는 안정화·취소만 남는다. 해당 UI 분기에서 일반 저장·차례 시작 제어로 돌아오지 못한다.

**권장 수정:** 구간의 `finally`에서 자신이 아직 소유한 `rtaRun`과 `rtaCapture`를 정리한다. 이전 작업의 늦은 정리가 새 작업을 지우지 않도록 identity를 검사한다. 첨부 최소 패치로 취소 UI와 정상 완료 저장 두 Android 시험이 통과했다.

**회귀 시험:** 안정화 중 취소, 측정 중 취소, 차례 취소, 취소 직후 새 작업, 정상 완료. 현재 제공한 Android 반례는 안정화 중 취소이며 나머지 경계도 제품 시험에 추가해야 한다.

### RMS-05 — Medium: 새 PCM이 없어도 예전 스펙트럼을 새 유효 프레임으로 센다

**위치:** `CaptureViewModel.kt:1755–1760`; `CaptureController.kt:738–776`; `RtaCaptureRun.kt:154–165`.

수집 기준인 `atMonotonicMs`는 새 FFT 결과의 번호가 아니다. Controller는 frames=0인 블록에서도 시각이 바뀐 snapshot에 기존 `session.rta.frame()`을 넣을 수 있다. VM은 이를 새 장으로 세고, `onFrame()`은 lastFrameAtMs도 갱신한다. 따라서 gap·장 수 검사가 모두 속는다.

**재현:** 실제 CaptureController/RtaEngine에 정상 PCM 128,000표본을 공급한 뒤, 100ms 간격으로 **0-frame 블록 100개**를 전달했다. VM과 같은 timestamp distinct 및 RtaCaptureRun 수집 규칙을 사용했다.

```text
RMS_STALE phase=Done averaged=103 errors=100
pcmBefore=128000 pcmAfter=128000 sameSpectrum=true
```

입력 PCM은 전혀 늘지 않았고 동일 RtaFrame 객체인데 정상 완료됐다. diagnostics의 `readErrors`에는 0-frame도 포함된다. 이 시험은 `AudioRecord.read()==0`에 대응하는 주입 반례다. 음수 오류가 난 실기기에서 10초 계속 돈다는 주장이 아니다. CaptureLoop의 음수 오류는 종료 경로다.

**사용자 영향:** 입력 공급이 멈췄는데도 낡은 값 반복을 10초 평균으로 저장할 수 있다. 현재 장 수는 실제 FFT 처리량이 아니라 UI 발행 수를 주로 나타낸다.

**권장 수정:** UI throttle 앞의 분석 결과에서 고유 FFT sequence·입력 frame 구간·적용 context를 전달받는다. 새롭고 유한한 유효 프레임만 누적하고 gap 기준을 갱신한다. 같은 spectrum, 0-frame, 잘못된 크기/비유한 장은 장 수와 유효 입력 시각을 늘리지 않는다. 10초 경과뿐 아니라 그 구간의 실제 입력 coverage를 검사한다.

**회귀 시험:** 0-frame 연속, timestamp만 바뀐 중복 FFT, 초기 stale StateFlow 값, NaN 장 연속, UI collector 지연/누락, 정상 새 PCM. `Done` 또는 파일 저장 여부까지 검사한다.

### RMS-06 — Low: restart가 새 안정화 시작 시각과 계수를 초기화하지 않는다

**위치:** `RtaCaptureRun.kt:70,117–135,178–182`.

restart는 평균·phase만 바꾸고 원래 `settleStartedAtMs`, `settleFrames`, `minFrames`를 유지한다. 4,000ms에 restart하고 4,100ms에 장 하나를 넣으면:

```text
RMS_RESTART at=4100 restarted=4000 phase=Measuring remaining=10000
```

**사용자 영향:** 현재 제품 호출부에서는 restart 사용을 찾지 못했으므로 Low다. RMS-01 해결에 이 함수를 연결하면 새 1.5초 안정화를 건너뛰고 옛 기대 장 수도 재사용한다.

**권장 수정:** 새 안정화 시작 시각·settleFrames·minFrames를 함께 초기화한다. 첨부 패치로 새 반례 1개와 기존 측정 시험 13개가 통과했다.

**회귀 시험:** 재시작 100ms 뒤 Settling 유지, 새 1.5초 뒤 전환, 새 입력률로 minFrames 산출, 이전 평균 0. 완료·실패 후 재사용 계약도 명시한다.

## 4. 요청서의 네 질문에 대한 답

**1 — 기대 장 수:** 현재 UI 발행률에 FFT 홉 공식만 대입하면 다시 실패한다. Controller는 66ms 발행 제한을 두며 StateFlow 소비에서도 합쳐질 수 있다. “실기기 12.4장/초”는 DSP 자체의 홉 처리율을 입증하지 않는다. **먼저 누적 경계를 실제 분석 프레임으로 옮긴 뒤** 엔진의 실제 sampleRate/hop과 입력 frame coverage를 사용해야 한다. FFT 4096·50% overlap·48kHz이면 nominal hop은 2048이고 steady-state FFT율은 약 23.44/s다. 시작 FFT 채움, 시간 경계에 걸친 창, 의도한 누락 허용을 계약에 반영한다. 측정 구간 10초와 coverage를 함께 만족시켜야 하며, 한 기기에서 얻은 절대 FPS 하한이나 0.6 허용치를 근거 없이 확정하지 않는다. 안정화 관측률은 진단값으로 남길 수 있다.

현재 경로는 화면용으로 평활된 밴드 값까지 재평균한다. `10^(dB/10)` 자체가 맞아도 화면 누락·평활을 거친 자료가 원래 10초 입력의 전체 전력 평균이라는 증거는 아니다. 새 tap을 만들 때 raw/weighted band power와 표시용 smoothing의 경계를 명시하고, 전체 PCM 구간을 기준으로 한 대조 시험을 둔다.

**2 — 미확인 조건:** 비교에 영향을 주는 필수 조건의 미확인은 계속 차단한다. 이름·메모 같은 표시 정보는 차단 조건으로 넣지 않는다. 현재 문제는 너무 엄격한 nullable 검사보다 **필수 조건을 아예 저장하지 않는 RMS-03**이다. 옛 기록은 겹쳐 보기와 이유 표시를 허용하되 현재 설정으로 메우지 않는다. 어떤 차이는 의도한 비교 축인지 별도 표시한다.

**3 — 형식 확장:** 의미가 호환되는 선택적 칸은 같은 호환 판에서 무시할 수 있다. 값 해석·비교 적격성을 바꾸는 필수 칸은 새 판 또는 명시적 migration이 필요하다. 모르는 큰 판을 거부하는 현재 방향은 타당하지만 `list()`가 예외를 삼켜 그냥 사라진 것처럼 보이는 UX는 개선한다. “신판이라 읽을 수 없음”을 별도 결과로 돌려 사용자에게 알린다. 옛 필수 칸의 부재를 현재 기본값으로 채우지 않는다. 향후 sweep 추가 시 method 호환 검사도 함께 추가한다.

**4 — 두 방어가 같은 변이를 가리는 경우:** 실제 도달 가능한 계약이 같다면 억지로 앞 방어를 끄거나 변이 점수를 위해 제품 코드를 바꾸지 않는다. 안정화 중 누적하지 않고 전환 때 reset하는 경우는 그런 중복 방어일 수 있다. 반면 **원자적 교체와 `end=1`은 같은 보장이 아니다.** 끝 표시는 잘린 새 파일을 배제할 뿐, 덮어쓰다 잃은 이전 파일을 살리지 않는다.

`RtaMeasurementStore.kt:135–140`은 rename 실패 시 target에 직접 쓴다. 이 분기는 원자 교체 보장을 제공하지 않는다. 기존 파일을 둔 상태에서 임시 쓰기 완료 후 교체 실패/중단을 주입하고, 기존 파일의 내용이 보존되는지 검사해야 한다. 기존 실패 시험의 디렉터리 생성 차단만으로 그 경계까지 검증됐다고 할 수 없다. FileOps 경계를 작게 주입해 “temp 작성”, “교체”, “교체 실패 시 원본 유지”를 각각 시험하고, 실패 시 직접 덮어쓰기 fallback을 제거하는 방안을 권한다. 같은 sets 파일의 read-modify-write 직렬화도 필요하다. 이번 실행에서는 실제 디스크 실패·프로세스 사망·fsync 내구성을 검증하지 않았으므로 그 보장까지 승인하지 않는다.

## 5. 수정 순서와 제안 코드

서로 의존하는 RMS-01·02·03·05는 다음 흐름으로 묶어 수정하는 편이 좋다.

```text
채널 출력 요청 → 해당 generation의 시작 성공 확인
  → 실제 적용 CaptureContext 고정
  → 고유 분석 프레임으로 1.5초 안정화
  → 같은 context의 유효 입력으로 10초 누적
  → Done + coverage + context + 출력 생존 재확인
  → 고정 context와 완성된 평균을 하나의 저장 레코드로 확정
```

이 흐름은 설계 제안이며 아직 컴파일한 새 구현은 아니다. 마이크/DSP 스레드에서 파일 I/O를 하거나 UI flow를 무제한 큐로 바꾸지 않는다. 분석 tap은 배열 소유권을 분명히 하고, 제한된 버퍼의 손실을 coverage 실패로 드러낸다. 단계 내 변경은 취소가 가장 작은 수정이며, 자동 재시작을 택한다면 새 출력 확인·전체 안정화·새 평균을 모두 보장해야 한다.

바로 적용 가능한 작은 두 수정은 [제안 패치](2026-09-29-rta-store-cancel-restart-proposed.patch)에 분리했다. **RMS-04·06만 해결한다.** 고정본에서 적용·빌드·관련 16회 실행을 통과했고, 관찰한 HEAD `d394c7b`에도 `git apply --check`가 통과했다. 제품에는 적용하지 않았다. 이 패치만으로 보류가 해제되지는 않는다.

## 6. 재현 자료와 남은 확인

- [JVM 독립 회귀 시험 5개](2026-09-29-rta-store-independent-regression.kt)
- [Android 실제 VM 회귀 시험 5개](2026-09-29-rta-store-vm-independent-regression.kt)
- [실행 수치·명령·경계·해시](2026-09-29-rta-store-evidence.txt)
- 원본 실행 로그/XML: `build/independent-review/rta-store-20260929/`.

JVM 파일은 `app/src/test/java/kr/joa/selahrta/ui/RtaStoreIndependentTest.kt`, Android 파일은 `app/src/androidTest/java/kr/joa/selahrta/ui/RtaStoreVmIndependentTest.kt`로 복사해 실행한다. 기존 test helper를 사용하는 JVM 시험과 플랫폼 ViewModel을 실행하는 계측 시험을 구분한다. Android probe는 비공개 연결부를 reflection으로 주입하므로 리팩터 때 필드 이름을 맞춰야 한다. 제품에 들일 때 작은 명시적 주입 경계로 바꾸는 것이 낫다.

실기기 출력 확인, USB 마이크·실제 route 교체, 실제 음향의 안정화/10초 평균 충분성, 긴 목록·겹친 곡선의 시각 구별, 디스크 중단 내구성은 남아 있다. 이번 반례들을 닫은 뒤에도 그 항목을 확인한 것으로 처리해서는 안 된다. **현재 보류 사유는 장비가 없어서가 아니라 재현된 저장·비교 계약 위반이다.**
