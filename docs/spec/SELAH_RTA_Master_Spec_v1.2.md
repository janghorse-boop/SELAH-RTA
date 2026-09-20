> 이 파일은 동봉된 .docx 에서 기계적으로 추출한 것이다.
> 원본과 어긋나면 .docx 가 기준이다.

SELAH RTAClaude Code 전체 개발 마스터 명세서
Real-Time Worship Audio Analyzer교회 예배당용 Android 실시간 음향 분석 · RTA · Feedback · Calibration · 기록/리포트
| 항목 | 정의 |
|---|---|
| 플랫폼 | Android Native / Kotlin / Jetpack Compose |
| 입력 원칙 | 휴대폰 내장 마이크와 USB-C 외부 측정 마이크를 모두 정식 지원 |
| 핵심 원칙 | 외부 마이크 없이도 모든 핵심 기능 사용 가능. 외부 측정 마이크는 정확도 향상 옵션 |
| 버전 | v1.2 / 2026-09-19 |

# 0. Claude Code 최상위 실행 지침
이 문서를 Single Source of Truth로 사용한다. 변경은 ADR/CHANGELOG에 이유를 기록한다.
기존 repository가 있으면 구조·build·test를 먼저 분석한다.
Phase별 build → unit test → 실기기 체크 → 결과 요약 후 다음 단계로 간다.
입력이 없을 때 Mock 숫자를 실제 측정값처럼 표시하지 않는다.
DSP는 UI와 독립시키고 synthetic signal 테스트를 갖는다.
# 1. 제품 비전
교회 방송실/예배당에서 청중 입장의 설교와 찬양 음압, 시간평균, 주파수 밸런스, 피드백 후보를 측정하고 예배별 결과를 기록하는 현장 도구다.
법정 계량용 소음계로 주장하지 않으며 보정되지 않은 내장 마이크의 절대 SPL 정확도를 보장하지 않는다.
# 2. 마이크 전략 — 핵심 요구사항
내장 마이크는 fallback이 아니라 정식 입력 소스다. 외부 마이크 없이 SPL, LAeq, RTA, Feedback, 기록을 모두 사용한다.
AudioSource abstraction 아래 BuiltInMicSource와 UsbMicSource가 동일 DSP Pipeline을 사용한다.
USB가 없으면 PHONE MIC로 정상 측정하고 보정 상태를 표시한다.
USB 연결 시 감지하되 자동 전환 여부는 설정으로 둔다.
USB 분리 시 일시정지 또는 내장 마이크 전환 정책을 적용하고 세션 이벤트에 기록한다.
내장 마이크도 기기/입력경로별 Calibration Profile을 지원한다.
세션마다 device, input path, sample rate, encoding, calibration profile을 기록한다.
# 3. 최종 기능
Current SPL, dBA/dBC/dBZ, Fast/Slow, LAeq 10s/1m/session, MAX, Peak.
31-band 1/3-octave RTA, smoothing, Peak Hold.
내장/USB 입력 선택·자동 감지·진단.
Global SPL Calibration 및 frequency correction profile.
설교/찬양/자유 측정 모드.
Feedback 후보 탐지.
예배 세션/구간 기록과 비교.
CSV/PDF 리포트와 공유.
# 4. 기술 스택/아키텍처
Kotlin, Jetpack Compose, Material 3, Gradle Kotlin DSL, Coroutines, Flow/StateFlow.
AudioRecord + AudioManager/AudioDeviceInfo, DataStore, Room, Storage Access Framework.
MVVM + UDF. 흐름: AudioSource → PCM → DSP → Repository → ViewModel → Compose.
패키지: app, audio, dsp, calibration, domain, data, ui, export, debug, testsupport.
# 5. 오디오 캡처
기본 목표 48 kHz mono. 안정적인 24-bit 지원 시 사용, 아니면 16-bit fallback.
가능하면 UNPROCESSED 입력을 우선하고 AGC/NS/AEC 경로를 피한다.
capture thread는 UI와 분리하고 hot path allocation을 최소화한다.
clipping, dropped frame, disconnect를 진단 상태로 발행한다.
# 6. DSP 파이프라인
PCM normalize → DC 관리 → clipping → calibration correction → A/C/Z weighting → RMS/energy → Fast/Slow → SPL reference → Leq → MAX/Peak → FFT → 1/3-octave → Feedback → UI throttling.
dBFS=20×log10(RMS normalized). dBFS와 실제 SPL을 코드에서 분리한다.
A/C weighting은 sample-rate 의존 digital filter이며 단순 offset 금지. Z는 flat.
Fast 약 125 ms, Slow 약 1 s의 에너지 기반 exponential weighting.
Leq는 linear energy 평균 후 dB 변환. rolling 10초/1분 및 session 전체.
MAX와 waveform Peak를 별도 지표로 관리한다.
# 7. FFT/31-band RTA
48 kHz, FFT 4096, Hann window, overlap을 출발점으로 한다.
UI refresh 10~20 FPS.
각 1/3-octave 경계 안 FFT bin power를 합산 후 dB 변환한다.
중심주파수: 20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1k, 1.25k, 1.6k, 2k, 2.5k, 3.15k, 4k, 5k, 6.3k, 8k, 10k, 12.5k, 16k, 20k Hz.
RTA smoothing과 SPL Fast/Slow는 별도 설정.
# 8. Calibration
상태: Uncalibrated / Global calibrated / Frequency calibrated.
간편 보정: 안정화 현재값과 기준 소음계 값 차이로 offset 계산→확인→저장.
전문 보정: 제조사 calibration file 또는 frequency-dB pair import 후 interpolation.
Profile key에 device identity와 input config를 포함한다.
내장 마이크도 기기별 profile을 지원한다.
# 9. Feedback Detector
최대 FFT bin 하나를 하울링으로 단정하지 않는다.
prominence, narrowness, level, persistence, 반복성을 조합한다.
상태 NONE → SUSPECT → PERSISTENT.
frequency, level, prominence, duration, timestamp를 기록한다.
UI에는 피드백 후보로 표시하고 음악 sustain 오탐 회귀 테스트를 둔다.
# 10. 교회 모드
설교: 초기 참고 68~75 dBA, Current/LAeq/MAX/Peak와 음성대역 강조.
찬양: 초기 참고 78~85 dBA, LAeq/Peak/RTA/dBA-dBC 비교 강조.
자유 측정: 참고 범위 판정 없이 계측 중심.
범위는 보편적 표준이 아닌 사용자 수정 가능한 참고값으로 표시한다.
# 11. UI/UX
Dark Theme. 상단 앱명/활성 마이크/보정 상태.
중앙 큰 Current SPL, 하단 LAeq/MAX/Peak와 mini RTA.
모드: 설교/찬양/RTA/피드백. Bottom nav: 측정/분석/기록/설정.
PHONE MIC/USB MIC를 명확히 표시한다.
미보정 내장 마이크는 절대 SPL 참고용 상태를 표시한다.
색과 함께 낮음/범위내/높음 텍스트를 병행한다.
# 12. 세션 기록
Start/Pause/Stop 및 설교/찬양/기타 구간 태그.
기본적으로 raw audio를 저장하지 않고 1초/5초 측정 요약과 이벤트를 저장한다.
세션에 마이크, sample rate, encoding, calibration profile, 장치 전환 이벤트를 기록한다.
상세: 시간 추세, 구간 LAeq, MAX/Peak, 주요 bands, feedback 후보, 메모.
# 13. 리포트
CSV: timestamp, segment, device, calibration, weighting, current, leq, max, peak, band values.
PDF: 세션 정보, 장치/보정, 핵심 수치, 추세, RTA, feedback, 메모.
Storage Access Framework와 Android Sharesheet 사용.
# 14. 설정
A/C/Z, Fast/Slow, LAeq window, RTA smoothing, Peak Hold.
설교/찬양 참고 범위.
입력 선택, USB 자동전환, USB 해제 fallback.
Calibration profile 관리, 화면 유지, 데이터 관리.
# 15. 테스트
RMS/dBFS synthetic sine.
A/C weighting 주파수별 기대 감쇠.
Fast/Slow step response.
Leq energy average.
FFT 125 Hz/1k/2.5k/4k peak.
1/3 octave band mapping.
Calibration interpolation.
Feedback tone 탐지와 pink noise/말/음악 오탐 억제.
Room migration, DataStore, USB connect/disconnect, Compose UI test.
# 16. Debug/진단
SignalGenerator: 125 Hz, 315 Hz, 1 kHz, 2.5 kHz, 4 kHz sine, white/pink noise, level step.
Debug build에서 synthetic AudioSource 주입.
Diagnostics: device, sample rate, encoding, buffer, processing time, dropped frames, clipping, calibration.
# 17. 성능/안정성
오디오 캡처 안정성을 UI보다 우선한다.
버퍼 재사용, hot path 객체 생성 최소화.
RTA 렌더링 10~20 FPS.
장시간 예배 기준 memory leak, thermal, battery, ANR 테스트.
# 18. 개인정보/안전
기본 설정에서 raw 음성을 저장하지 않는다.
세션은 음압/주파수 요약 중심.
마이크 권한 이유를 명확히 설명한다.
앱 참고 범위를 공식 청력 안전기준과 동일시하지 않는다.
# 19. 개발 Phase — 최종 단계까지
| Phase | 목표 | 핵심 산출물 |
|---|---|---|
| 0 | 환경/Repository 진단 | 기존 코드·SDK·Gradle·build/test 확인 |
| 1 | 앱 골격/UI | Navigation, theme, 주요 화면, 상태 모델 |
| 2 | 내장 마이크 MVP | 권한, BuiltInMicSource, 실제 PCM capture |
| 3 | 기본 Meter | RMS, dBFS, Global calibration, Current/MAX |
| 4 | 정식 음압 엔진 | A/C/Z, Fast/Slow, LAeq, Peak |
| 5 | RTA | FFT, 31-band, smoothing, Peak Hold |
| 6 | USB-C 입력 | UsbMicSource, 감지, 선택, 전환/fallback |
| 7 | 고급 Calibration | 장치별 profile, frequency file/import/interpolation |
| 8 | 교회 모드 | 설교/찬양/자유, 참고 범위, 상태 UI |
| 9 | Feedback | 후보 알고리즘, persistence, 이벤트 |
| 10 | 세션 기록 | Room, segment, summary, 상세 |
| 11 | 리포트 | CSV/PDF, 공유 |
| 12 | 정확도 검증 | 기준 소음계/측정마이크와 레벨·주파수 비교 |
| 13 | 성능/호환성 | 다기종 Android, 장시간, USB 다양성 |
| 14 | 접근성/UX | 가독성, 큰 글씨, 색각, onboarding/error |
| 15 | Release 준비 | 권한/개인정보, signing, release build, store assets |
| 16 | 현장 Pilot | 실제 예배당 설교/찬양 운용 및 issue 수정 |
| 17 | v1.0 배포 | 최종 QA, 문서, release notes, tag |

# 20. Phase 완료 보고 형식
구현 완료 항목
변경/추가 파일
중요 설계 결정과 이유
실행한 build/test 명령 및 결과
실제 기기 확인 항목
알려진 문제/제약
다음 Phase 계획
빌드 또는 핵심 테스트가 실패하면 다음 Phase로 넘어가지 않는다.
# 21. 최종 v1.0 완료 기준
외부 마이크 없이 휴대폰 내장 마이크만으로 전체 핵심 측정 흐름이 동작한다.
USB-C 측정 마이크 연결/해제/선택/fallback이 안정적이다.
Calibration 상태가 정확히 표시되고 profile이 장치별로 적용된다.
A/C/Z, Fast/Slow, LAeq, MAX/Peak, 31-band RTA가 테스트 벡터를 통과한다.
Feedback 후보 탐지가 실제 음악에서 허용 가능한 오탐 수준으로 조정된다.
예배 세션/구간 기록과 CSV/PDF export가 동작한다.
장시간 실제 기기 테스트에서 crash/ANR/memory leak이 없어야 한다.
Release build에서 debug signal/mock 기능이 노출되지 않는다.
README에 설치, 권한, 내장/USB 마이크, Calibration, 한계, 테스트 방법을 문서화한다.
# 22. Claude Code에 처음 입력할 실행 프롬프트
첨부된 SELAH RTA 마스터 명세서를 먼저 전체 읽고 요구사항과 Phase 0~17을 작업 계획으로 정리하라. 현재 repository를 분석한 뒤 Phase 0부터 시작하라. 특히 휴대폰 내장 마이크는 USB 마이크의 단순 fallback이 아니라 정식 입력 소스이며, 외부 마이크가 없어도 모든 핵심 기능이 동작해야 한다. 각 Phase 종료 시 명세서의 완료 보고 형식으로 결과를 보고하고 build/test 실패가 있으면 다음 Phase로 넘어가지 말라. 측정값을 Mock으로 위장하지 말고 DSP 정확성을 UI보다 우선하라.
# 23. 제품명 및 브랜드 컨셉
앱 공식 명칭: SELAH RTA
영문 확장명: Real-Time Worship Audio Analyzer
브랜드 의미: 시편에 반복해서 등장하는 'Selah(셀라)'의 음악적·예배적 맥락을 살리면서, RTA(Real-Time Analyzer)를 결합해 교회 음향 도구라는 정체성과 전문 오디오 소프트웨어의 인상을 함께 전달한다.
UI 표기 원칙: 앱 아이콘/상단 브랜드는 SELAH RTA를 우선하고, 온보딩·About·스토어 설명에서는 Real-Time Worship Audio Analyzer를 병기한다.
# 24. AI 개발 운영 모델 — Claude Code 개발 / Codex 검증
본 프로젝트의 공식 역할 분담은 Claude Code를 Lead Developer, Codex를 Independent Reviewer & Verification Engineer로 정의한다.
Claude Code: 마스터 명세 이해, Android/Kotlin 구현, repository 구조 관리, build/test 실행, 실제 Galaxy 테스트 결과 반영, Phase 0~17의 주 개발 책임.
Codex: Claude Code가 완료한 커밋/브랜치를 독립적으로 리뷰하고 DSP 수학, Android 오디오 처리, 테스트 누락, 회귀 위험, 성능/동시성 문제를 검증한다.
Codex는 기본적으로 검증 단계에서 production 코드를 임의 수정하지 않는다. 수정이 필요하면 먼저 문제, 재현법, 영향, 권장 수정안을 리뷰 결과로 남긴다.
Codex가 직접 수정하는 경우 별도 branch/worktree를 사용하고 Claude Code의 작업 파일과 동시에 같은 파일을 수정하지 않는다.
Phase 3 이후 측정 엔진 관련 단계는 Claude Code 완료 → commit → Codex review → 지적사항 수정 → 재테스트 → 승인 상태 기록 순서로 운영한다.
DSP 핵심 Phase(4,5,7,9,12)는 Codex 독립 검증을 필수 게이트로 한다. UI 중심 Phase도 release 전 전체 review를 수행한다.
## 24.1 Git 및 검증 절차
Claude Code는 기능 단위 branch에서 작업하고 build/test 성공 후 의미 있는 commit을 만든다.
Codex는 해당 commit 또는 branch diff를 기준으로 리뷰한다. 리뷰 기준은 correctness, numerical accuracy, lifecycle/concurrency, resource leak, compatibility, tests, security/privacy다.
Critical/High 이슈가 있으면 다음 Phase로 넘어가지 않는다. Medium은 영향과 일정에 따라 처리 결정을 ADR에 남긴다.
수정 후 Claude Code가 동일 테스트와 추가 회귀 테스트를 실행한다. 필요한 경우 Codex가 재검증한다.
main 병합 전 CI 또는 로컬 기준으로 clean build와 전체 핵심 테스트가 통과해야 한다.
## 24.2 Codex 검증 체크리스트
dBFS와 SPL의 구분 및 Calibration reference 오류 여부.
A/C/Z weighting 구현이 단순 offset이 아닌지, sample rate별 계수가 올바른지.
Fast/Slow와 LAeq가 energy domain에서 올바르게 계산되는지.
FFT scaling/window 보정 및 1/3-octave power aggregation이 올바른지.
내장 마이크/USB 마이크 전환 시 state, calibration, session metadata가 섞이지 않는지.
Feedback Detector가 음악의 지속음을 과도하게 하울링으로 판단하지 않는지.
AudioRecord lifecycle, coroutine cancellation, buffer reuse, memory/thermal/ANR 위험.
Unit/instrumentation test가 실제 실패 조건을 검증하고 단순 구현 복제 테스트가 아닌지.
# 25. Claude Code 시작 프롬프트 — 개정판
첨부된 SELAH RTA 마스터 명세서를 전체 읽고 요구사항과 Phase 0~17을 작업 계획으로 정리하라. 너는 이 프로젝트의 Lead Developer다. 현재 repository를 분석한 뒤 Phase 0부터 시작하라. 앱 공식 명칭은 SELAH RTA이며 영문 확장명은 Real-Time Worship Audio Analyzer다. 휴대폰 내장 마이크는 USB 마이크의 단순 fallback이 아니라 정식 입력 소스이며 외부 마이크 없이도 모든 핵심 기능이 동작해야 한다. 각 Phase 종료 시 build/test 결과와 변경 파일, 실제 기기 확인사항을 보고하고 commit 가능한 상태를 만든다. 이후 Codex가 독립 검증할 수 있도록 변경 범위와 테스트 근거를 명확히 남긴다. build 또는 핵심 test 실패가 있으면 다음 Phase로 넘어가지 않는다.
# 26. Codex 검증 프롬프트 — 기본형
너는 SELAH RTA 프로젝트의 Independent Reviewer & Verification Engineer다. 구현 주체는 Claude Code이며 너의 기본 임무는 독립 검증이다. 현재 branch/commit diff와 마스터 명세를 읽고 구현이 요구사항과 수학적 정의에 맞는지 검토하라. 특히 DSP 정확성, Android AudioRecord lifecycle, 내장/USB 마이크 전환, Calibration 적용 범위, concurrency/resource leak, 테스트의 충분성을 우선 검증하라. 문제마다 Severity(Critical/High/Medium/Low), 파일/위치, 재현 또는 논리적 근거, 사용자 영향, 권장 수정, 필요한 회귀 테스트를 제시하라. 근거 없이 production 코드를 광범위하게 재작성하지 말라. Critical/High 이슈가 없으면 그 사실과 남은 위험을 명확히 보고하라.

# 추가 명세: Recording & Synchronized Analysis
SELAH RTA는 실시간 측정뿐 아니라 사용자가 명시적으로 REC를 누른 경우 마이크로 입력되는 실제 소리를 녹음하고, 동일한 시간축의 SPL·LAeq·Peak·31-band RTA·Feedback 이벤트를 함께 저장하여 사후에 소리와 그래프를 동기 재생할 수 있어야 한다.
## 1. 녹음 기능의 기본 원칙
측정(Measurement)과 녹음(Recording)을 분리한다. 앱은 녹음하지 않고도 모든 실시간 분석 기능을 사용할 수 있다.
사용자가 REC를 명시적으로 시작한 경우에만 오디오 파일을 저장한다.
휴대폰 내장 마이크와 USB-C 외부 마이크 모두 녹음 소스로 사용할 수 있다.
녹음과 분석은 서로 다른 마이크 스트림을 열지 않는다. 하나의 PCM capture stream을 분기하여 DSP와 Encoder/Writer에 동시에 전달한다.
RTA/SPL 계산은 압축된 저장 파일이 아니라 마이크에서 캡처한 원본 PCM을 기준으로 수행한다.
세션에는 사용 마이크, sample rate, encoding, calibration profile, 녹음 포맷 및 장치 전환 이벤트를 저장한다.
## 2. 권장 신호 흐름
Built-in Mic / USB Mic → AudioRecord → PCM Buffer → (A) DSP Pipeline: SPL/LAeq/Peak/FFT/RTA/Feedback ＋ (B) Recording Pipeline: WAV Writer 또는 AAC/M4A Encoder → 공통 monotonic timestamp → Session Database
## 3. 저장 포맷
| 모드 | 포맷 | 용도 |
|---|---|---|
| 일반 녹음 | AAC/M4A | 예배 전체 장시간 녹음, 저장공간 절약, 재생 중심 |
| 분석 원본 | WAV/PCM | 원본 신호 보존, 향후 재분석, 알고리즘 검증 |
| 분석 데이터 | Room DB | 시간별 SPL/LAeq/MAX/Peak/RTA 및 이벤트 |

## 4. 시간 동기화 — 핵심 요구사항
Audio frame과 measurement frame은 동일한 monotonic time base를 사용한다.
벽시계(System.currentTimeMillis)만으로 오디오-그래프 동기화를 구현하지 않는다.
RecordingSession에 recordingStartMonotonicNs를 저장하고 모든 measurement/event는 start 기준 elapsed time으로 기록한다.
재생 위치(positionMs)를 measurement timestamp에 매핑하여 해당 시점의 SPL 및 RTA frame을 표시한다.
Seek 시 오디오와 그래프 커서가 동시에 이동해야 한다.
일시정지/재개, USB→내장 마이크 전환 등 discontinuity가 발생하면 timeline event를 남기고 offset을 명시적으로 관리한다.
장시간 녹음에서 timestamp drift를 테스트하고 허용 오차를 정의한다.
## 5. Measurement Timeline 저장
기본 저장 간격은 0.5초를 초기값으로 하되 0.25/0.5/1.0초 중 성능·용량 테스트 후 결정한다. 실시간 DSP 자체는 더 높은 rate로 계산하되 DB에는 down-sampled snapshot을 저장한다.
예시 저장 필드: elapsedMs, segmentType, dBA, dBC, dBZ(optional), LAeq10s, LAeq1m, maxDb, peakDb, 31 band levels, clipping, activeDeviceId, calibrationProfileId.
## 6. 재생/분석 화면
Play/Pause, ±10초 이동, seek bar, 현재시간/전체시간.
오디오 waveform 또는 단순 amplitude overview.
시간별 SPL line graph와 현재 재생 위치를 나타내는 vertical cursor.
현재 위치의 dBA/dBC, LAeq, Peak, 31-band RTA를 동시에 표시.
그래프를 탭하면 해당 시간으로 seek하고 실제 녹음도 그 위치에서 재생.
Peak marker, Feedback marker, clipping marker, 설교/찬양 segment marker를 timeline에 표시.
Feedback marker를 누르면 해당 사건 직전 몇 초부터 재생하는 '들어보기' 기능을 제공.
재생 중 RTA는 저장된 snapshot을 시간에 맞춰 표시하며 필요 시 인접 frame interpolation 여부를 설정/검토한다.
## 7. 재분석(Re-analysis)
WAV/PCM 원본을 보관한 세션은 향후 DSP 알고리즘 또는 Calibration Profile이 변경되었을 때 오프라인으로 다시 분석할 수 있게 설계한다. 재분석 결과는 원본 측정 결과를 덮어쓰지 않고 analysisVersion과 profile 정보를 가진 새 결과 세트로 저장한다.
M4A/AAC도 재분석 가능하지만 정밀 분석의 기준 원본으로는 WAV/PCM을 우선한다.
Re-analysis는 새 FFT/RTA/Feedback 알고리즘의 과거 세션 회귀검증에도 활용한다.
원본 recording, original analysis, re-analysis 결과의 관계를 DB에서 추적 가능하게 한다.
## 8. 데이터 모델 추가
| 모델 | 필드/역할 |
|---|---|
| RecordingAsset | id, sessionId, uri, codec, sampleRate, bitDepth, channels, durationMs, fileSize, createdAt |
| MeasurementFrame | sessionId, elapsedMs, SPL/Leq/Peak, rtaBands, device/profile |
| TimelineEvent | elapsedMs, type(PEAK/FEEDBACK/CLIPPING/DEVICE_CHANGE/SEGMENT), payload |
| AnalysisRun | id, sessionId, version, algorithmVersion, calibrationProfileId, createdAt |

## 9. 저장공간 및 개인정보
기본 측정은 오디오를 저장하지 않는다. REC는 사용자 행동으로만 시작한다.
녹음 중임을 화면에서 명확한 REC 표시와 경과시간으로 알린다.
예배 전체 녹음은 큰 파일이 될 수 있으므로 시작 전 예상 저장 가능 시간 또는 여유 공간 경고를 제공한다.
세션 삭제 시 '분석 데이터만 삭제 / 녹음 포함 전체 삭제'를 구분한다.
오디오 파일 공유는 사용자가 명시적으로 실행할 때만 한다.
앱 내부 개인정보/녹음 안내문에 실제 음성이 저장될 수 있음을 명시한다.
## 10. Recording Engine 구현 구조
AudioCaptureEngine은 PCM buffer를 단일 생산자로 제공한다. DspConsumer와 RecordingConsumer가 이를 소비한다. 오디오 캡처 스레드에서 파일 I/O나 무거운 FFT가 직접 실행되어 capture dropout을 일으키지 않도록 bounded buffer/worker 구조를 사용한다.
RecordingController: start/pause/resume/stop 및 상태 관리.
WavRecorder: PCM/WAV 저장.
AacRecorder: AAC/M4A 저장. 실제 Android codec/container 조합은 구현 시 호환성을 검증.
TimelineSynchronizer: audio position ↔ elapsed measurement time 변환.
PlaybackController: Media3/ExoPlayer 계열 사용을 검토하고 seek/position Flow를 UI에 제공.
RecordingRepository: RecordingAsset와 Session 연결.
ReanalysisEngine: 저장된 원본 오디오를 DSP pipeline에 offline source로 공급.
## 11. Recording 검증 — Codex 필수 Review Gate
Claude Code가 Recording Engine과 동기화 기능을 구현한다.
Codex는 동일 PCM이 DSP와 recorder에 일관되게 전달되는지 독립 검토한다.
Codex는 오디오 timestamp와 MeasurementFrame elapsedMs 사이의 drift/offset 오류를 집중 검증한다.
10분, 60분 이상의 synthetic/실기기 녹음에서 seek 후 그래프와 소리가 일치하는지 테스트한다.
Pause/Resume, 화면 전환, USB disconnect→Phone Mic fallback, 앱 lifecycle 변화 시 timeline discontinuity를 검증한다.
녹음 중 DSP 부하로 audio dropout이 발생하지 않는지 benchmark/profiling을 수행한다.
Codex 검증에서 Critical/High 오류가 남아 있으면 다음 release phase로 진행하지 않는다.
## 12. 개발 Phase 추가/수정
| Phase | 목표 | 주요 구현 | 완료 기준 |
|---|---|---|---|
| Recording-A | PCM Recording MVP | 내장/USB 공통 PCM에서 WAV 녹음, REC UI | 실기기 녹음/재생 성공, 분석 중 dropout 없음 |
| Recording-B | 압축 녹음 | AAC/M4A 옵션, 파일 관리 | 장시간 파일 생성/재생/삭제 검증 |
| Recording-C | 동기 분석 저장 | 0.5초 기준 MeasurementFrame, TimelineEvent | audio와 graph timestamp 일치 |
| Recording-D | 동기 재생 UI | seek, SPL graph, 31-band RTA, marker | 그래프 탭↔오디오 seek 양방향 동기 |
| Recording-E | 재분석 | WAV offline DSP re-analysis, analysisVersion | 원본 보존 + 새 분석 결과 생성 |
| Recording-F | 장시간/현장 검증 | 60분 이상, USB 전환, pause/resume, storage | Codex review + 실기기 회귀 테스트 통과 |

## 13. 최종 v1.x 완료 조건에 추가
내장 마이크와 USB 마이크 모두에서 REC가 정상 동작한다.
녹음된 실제 소리와 저장된 SPL/RTA timeline이 동기 재생된다.
그래프/이벤트 marker에서 해당 녹음 시점으로 이동할 수 있다.
Peak/Feedback 사건을 실제 소리로 즉시 확인할 수 있다.
오디오 녹음을 하지 않는 일반 측정 모드가 독립적으로 유지된다.
WAV 원본 세션의 재분석이 가능하다.
장시간 녹음에서도 치명적 drift, dropout, 메모리 증가가 없다.
개발 역할 원칙: Claude Code = Lead Developer / Codex = Independent Reviewer & Verification Engineer.