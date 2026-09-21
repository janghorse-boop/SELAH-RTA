# Instrument EQ Guide EQ-A 독립 검토

2026-09-21 · 검토 HEAD `b191cc4` · 요청서 baseline `d38bdfb`

**판정: EQ-B 정적 카탈로그·설명 UI 착수 가능. 분석 구현 전 명세 정정과 ADR이 필요하다. Critical/High는 현재 검토 범위에서 발견하지 못했으며, 명세 Medium 2 / Low 1을 기록한다.** EQ-A의 종료 조건에는 ADR이 있으므로 아직 ‘EQ-A의 모든 종료 조건 완료’로 승인하지 않는다. 기능 출시나 EQ-D/E 성능 검증 완료를 뜻하지 않는다.

EQ 요청 범위는 문서·baseline 조사이며 아직 EQ production 코드 변경은 없다. 명세 §16의 구현 지시는 사용자의 이번 검토 요청과 구분했다. production 구현·마이크/스피커 조작은 하지 않았다. 이번 독립 실행은 행 집계기 11건 및 DSP 수학 probe이며 보고된 314건/build/lint 전체는 재실행하지 않았다.

## 질문 A — 칸별 보정 수정안에 동의

**EQ01 / Medium — 명세 §7.1의 일반 보정식이 기존 실제 계산과 모순됨.**

**위치:** `docs/spec/SELAH_RTA_Instrument_EQ_Guide_v1.0.md:260–270`; 실제 `BandAnalyzer.toBandPower`, `CalibrationCurve.binCorrectionLinear`, `RtaEngine.runFft`.

**근거:** 밴드 내 마이크 응답이 달라지면 밴드 중심/평균 보정 하나를 곱하는 것으로 정확한 칸별 보정을 대신할 수 없다. 이미 코드가 구현한 아래 식을 명세의 일반식으로 쓴다.

`P_corrected,b = Σ_k w[b,k] * P_raw[k] * 10^(-g(f_k)/10)`

raw 쪽은 같은 w로 `Σ_k w[b,k]*P_raw[k]`이다. `P_corrected,b=P_raw,b*10^(C_b/10)`는 밴드 내부 보정이 일정한 특수 경우 또는 신호별 유효 보정량을 사후 정의했을 때만 일반식과 일치한다. 이를 고정 중심 보정 구현 지시로 사용하지 않는다. 부호는 **현재 프로젝트의 파일 해석 계약**인 응답 g를 빼는 방식으로 명시한다. 모든 외부 제조사 파일이 같은 부호라고 확대하지 않는다.

**사용자 영향:** 식을 문자 그대로 구현하면 이미 고친 보정 오차를 EQ 스냅샷에 재도입할 수 있다. 아직 새 구현에서 회귀가 발생한 것은 아니다.

**권장 수정:** 식과 T03의 변수 정의를 함께 정정한다. ‘보정 +6dB’는 응답 g=-6dB일 때의 correction +6dB라는 뜻임을 분명히 한다. 이미 보정된 밴드에 두 번째 곡선을 곱하지 않는다.

**회귀 시험:** 밴드 내부 응답이 급변하는 곡선과 서로 다른 위치의 순음으로 독립 기대값을 계산한다. 평탄 correction +6dB, 0dB, raw/corrected 동시 저장, 중복 보정 금지도 확인한다.

## 질문 B — 불변성 증명은 맞지만, T09를 억지로 실패하게 만들 필요는 없다

완전 밴드에서 `bw=c*f_exact`이고 회귀 x가 `log2(f_exact)`이면 `10log10(bw)`는 x의 affine 함수다. 절편 포함 최소제곱 직선에 이를 더하거나 빼면 잔차는 변하지 않는다. 이는 독립 JVM 계산에서도 밀도 변환 유무 차이 약 `4.9e-15dB`로 확인했다.

**권고:** 밀도 값은 물리적 의미와 향후 활용을 위해 유지해도 된다. 다만 지금 알고리즘에서는 기울기 제거가 폭 효과도 제거하므로 밀도 변환이 prominence를 추가로 바꾼다고 설명하지 않는다. T09는 유효한 end-to-end 무돌출 시험으로 남긴다. **수학적으로 동등한 단계를 제거했을 때 반드시 실패해야 좋은 시험인 것은 아니다.** q를 만들기로 한 계약 자체는 별도 단위 시험으로 단언한다(예: P=1, bw=10이면 -10dB, bw=100이면 -20dB).

**EQ02 / Medium — 회귀 centerHz가 정확값인지 호칭값인지 미정.**

**위치:** 명세 §7.4 6~7단계(303–304행 부근); `ThirdOctave.kt`의 `CENTERS_HZ`와 `exactCenter()`.

실제 저장소의 UI 중심 125Hz는 정확한 밴드 중심 약 125.8925Hz와 다르다. 경계는 exactCenter로 계산하므로 x에 `CENTERS_HZ`를 사용하면 폭이 x에 정확히 비례한다는 증명이 깨진다. 실제 코드의 315/400/500Hz 부근 세 target 밴드와 양쪽 reference 각 두 개로 계산한 probe에서 호칭값 사용 시 밀도 변환 유무 차이가 약 **-0.017240dB**였다. 큰 청감 차이라고 주장하는 것이 아니라 알고리즘이 모호해 두 구현이 달라질 수 있다는 문제다.

**사용자 영향:** 임계값 경계에서 결과가 달라지고 불변성 시험 해석이 틀릴 수 있다.

**권장 수정:** 분석 회귀에는 `ThirdOctave.exactCenter(index)` 또는 실제 경계의 기하평균을 쓰고 UI에는 호칭값을 쓴다고 명시한다. 영역 T/R 선정에서 어떤 중심을 사용하는지도 고정한다.

**회귀 시험:** exact center에서 밀도 유무 잔차 불변, 호칭 중심을 잘못 넣은 변이의 차이, white/pink/affine tilt 무돌출, target 6dB 경계와 부분 밴드 배제. 시험의 기대값을 구현 함수에서 다시 계산하지 않는다.

## 질문 C — bandResolved로 자동 탐지 정책을 무조건 대체하지 않는다

직접 코드 실행 결과 4096점·48/44.1kHz 모두 두 기준이 갈리는 밴드는 125Hz 하나였다. 이 비교 보고는 재현됐다. 하지만 일치율 30/31이 두 기준의 목적까지 같게 만들지는 않는다.

- `bandResolved`: 현재 창/FFT/집계에서 정해 둔 순음 누설 기준을 만족하는가.
- 명세 `width >= 3*Fs/N`: 자동 후보에 사용할 밴드를 제한하는 추가 제품 정책.

명세도 후자를 ‘분해능 보증’이 아닌 ‘보수적 초기 조건’이라고 명시했다. 따라서 기존 코드를 지킨다는 이유만으로 125Hz를 자동 탐지에 새로 넣을 근거는 부족하다. 이는 최적 음향값을 지금 추정해서 정할 문제가 아니다.

**권고:** RTA 표시의 `bandResolved`는 재사용하고, 가이드의 `eligibleMask`는 `bandResolved ∧ 후보 정책 ∧ Nyquist/분석 범위 ∧ 보정 범위 ∧ 품질`로 구분한다. 초기 가이드에서는 명세의 3-bin 제한을 유지한다. 완화하려면 탐지 평가 세트에서 근거를 확보하고 ruleVersion을 올린다. 누설 허용치를 0.5dB로 바꾸는 것도 3-bin 규칙과 동등한 정책이 아니므로 단순 치환하지 않는다.

## 나머지 질문과 착수 순서

**1. 평활 전 tap은 필요하다.** GuideFrame은 같은 FFT 관측점의 **31개 raw 밴드 power와 31개 corrected 밴드 power**를 받아야 한다. 요청서 계획의 보정 전 `power`는 FFT bin 배열이므로 31밴드와 혼동하지 않는다. raw 밴드 합산은 기존 weights로 하고 corrected 경로를 재사용한다. 카탈로그 탐지기 자체에 FFT 전체를 보관할 필요는 없다. 기존 SpectrumSink 하나를 덮어써 FeedbackDetector를 끊지 않는다.

**2. UI snapshot만 쓰면 안 된다는 판단은 맞다.** 42.67ms hop에 66ms UI 주기라면 화면만 본 집계가 모든 FFT를 받지 못한다. 35%는 이상적인 지속 주기 계산값이지 측정된 비율이 아니다. 실제 emit가 블록 콜백에서만 일어나면 간격이 더 길어질 수도 있다. runFft 관측점에서 모든 hop의 데이터·유효 구간을 전달한다.

**3. hop 시간과 시각은 구분한다.** 오버랩 창 길이가 아니라 고유 hop 구간을 평균 가중치로 쓴다. 500ms 경계를 가로지르는 hop의 시간 배분, 첫 창 warm-up, stop tail, 입력 drop·clipping의 창 중첩 전파를 정한다. UI의 readDone 시각을 FFT 표본 시각이라고 부르지 않는다. 이후 guide timestamp를 바꿀 때 기존 FeedbackDetector의 관측 시각까지 바꾸지 않도록 별도 adapter 경계를 둔다.

**4. stable fingerprint는 EQ-D 전에 필요하며 EQ-B를 막지 않는다.** curveGeneration은 세션 내부 동기화 토큰으로 남기고 영속 동일성에는 내용 hash/적용 범위/입력 route/offset 등을 사용한다. 이전 DataStore를 반드시 즉시 바꿔야만 가능한 것은 아니다. 로드한 곡선·보정 내용을 정규화해 안정적인 content ID를 만들고 snapshot에 원본 메타데이터를 보존하는 방안도 있다. ID를 저장 스키마에 새로 추가한다면 migration 및 구 버전 값의 보존을 시험한다. 추적용 UUID와 비교 가능한 내용 버전을 혼동하지 않는다.

**5. 저장 방향은 Room을 우선 권고한다.** 프리셋 편집·즐겨찾기·목록, snapshot 연결·삭제, schema migration과 향후 Session 관계는 구조화된 저장소의 역할이다. 이는 파일-per-snapshot이 불가능하거나 성능이 나쁘다는 실측 결론이 아니라, 필요한 관계·원자성·migration을 직접 구현하는 부담을 줄이려는 설계 판단이다. Room은 SQLite 위의 구조화된 저장 계층과 migration 지원을 제공한다. [Android 공식 Room 문서](https://developer.android.com/training/data-storage/room).

WAV·타임라인 순차 파일과 Room 메타데이터/프리셋은 공존할 수 있다. Preferences는 작은 설정에 유지한다. 파일 방식을 선택한다면 생성/삭제/인덱스 갱신 중단·복구·schema migration을 ADR과 시험으로 책임져야 한다. **EQ-B 정적 카탈로그는 이 선택과 독립적**이므로 먼저 진행해도 된다. Room의 구체적 버전과 빌드 도구 호환성은 실제 도입 때 검증한다.

**6. A/B 오디오 재생을 이번 EQ 범위에서 빼는 데 동의한다.** 사용자에게 제공되는 REC/WAV/RecordingAsset가 없으므로 그래프 비교만 한다. 새 녹음 엔진·실시간 마이크 모니터링을 EQ 때문에 만들지 않는다.

## EQ03 / Low — A/B seek의 기준 문구가 현재 녹음 계약과 다르다

**위치:** 명세 §8.3 마지막 부분(344행).

‘기존 monotonic timeline 계약’이라고 적었으나 녹음 보완 설계 2차 이후 정본은 미디어 프레임 좌표다. mono anchor는 외부 시각 연결 메타데이터다.

**사용자 영향:** 나중에 EQ snapshot과 RecordingAsset를 연결할 때 두 시간축을 혼용하면 오디오·그래프가 다른 구간을 가리킬 수 있다. 현재 오디오 재생 제외 단계에는 즉시 영향이 없다.

**권장 수정:** ‘RecordingAsset의 상대 프레임 범위로 seek하고 anchor로 단조 시각을 연결한다’로 맞춘다.

**회귀 시험:** ±50ppm 가상 시계, snapshot 시작/끝과 실제 프레임 범위 매핑, 미디어 seek 왕복. 녹음 연결 구현 단계에서 수행한다.

## 정적 EQ/Gate/Compressor 카탈로그 판단

§18의 게이트·컴프레서를 **추천값·조건·bypass 설명만 제공**하는 범위는 유지한다. 마이크 dBFS를 외부 믹서 threshold로 변환하거나 실제 GR을 추정하는 구현은 넣지 않는다. 권한 없이 카드 열람과 악기/subtype별 nullable 범위·단위 검증은 EQ-B의 적절한 시험이다.

문서의 각 악기별 시간·ratio·주파수 범위를 현장 최적값으로 검증한 것은 아니다. Shure와 iZotope의 교육 자료는 청취하며 전체 믹스에서 판단하는 원칙을 뒷받침하지만 앱의 6dB/70% 후보 기준이나 개별 추천 조합을 보증하지 않는다. [Shure 교회 음향 자료](https://www.shure.com/en-EU/insights/professional-mixing-tips-for-church-sound), [iZotope EQ 안내](https://www.izotope.com/community/blog/eq-cheat-sheet). 음향 담당자의 콘텐츠 검토와 알고리즘 평가 세트/현장 시험은 여전히 필요하다.

**진행 권고:** 위 A/B/C 판단을 ADR과 명세에 반영하면서 EQ-B 정적 모델·카탈로그부터 진행한다. DSP tap/시간 집계는 별도 작은 변경으로 review하고, 기존 SPL/RTA/Feedback 회귀와 데이터 소유권을 확인한 뒤 EQ-D/E로 연결한다. 저장소 조사 완료와 구현/현장 검증 완료를 분리해서 보고한다.
