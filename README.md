# SELAH RTA

**Real-Time Worship Audio Analyzer** — 교회 예배당용 Android 음향 측정 앱

설교와 찬양의 음압(SPL·LAeq·Peak), 31밴드 주파수 밸런스, 피드백 후보를 재고
예배별로 기록합니다. 휴대폰 내장 마이크만으로도 모든 핵심 기능이 동작하며,
USB-C 측정 마이크를 연결하면 정확도가 올라갑니다.

> **명세** → [`docs/spec/SELAH_RTA_Master_Spec_v1.2.md`](docs/spec/SELAH_RTA_Master_Spec_v1.2.md)
> (원본 .docx 동봉. 이 문서가 Single Source of Truth 다 — 명세 0장)

---

## 이것은 소음계가 아닙니다

**법정 계량용 소음계로 쓸 수 없습니다.** 보정되지 않은 내장 마이크의 절대
SPL 정확도는 보장하지 않습니다. 앱이 보여주는 참고 범위는 공식 청력
안전기준과 다릅니다. 소음 규제·안전 판단의 근거로 삼지 마십시오.

보정 상태는 화면에 항상 표시됩니다 — **미보정 / 전대역 보정 / 주파수 보정**.
미보정 상태의 숫자는 「참고용」이며, 그렇게 적혀 있습니다.

## 마이크 전략

내장 마이크는 **USB 마이크의 대체품이 아니라 정식 입력 소스**입니다.
외부 마이크 없이 SPL·LAeq·RTA·피드백 탐지·기록을 모두 씁니다.
`AudioSource` 아래 `BuiltInMicSource` 와 `UsbMicSource` 가 **같은 DSP
파이프라인**을 지나며, 세션마다 어느 마이크·샘플레이트·보정 프로파일로
쟀는지 남습니다.

## 구조

```
dsp/    안드로이드에 의존하지 않는 순수 JVM 모듈.
        RMS·dBFS·가중치·Leq·FFT·1/3옥타브·피드백 판정.
        합성 신호로 에뮬레이터 없이 초 단위로 검증된다.
app/    Android 앱. Compose UI, 오디오 캡처, 저장, 리포트.
```

**DSP 를 UI 와 분리한 것은 편의가 아니라 검증 수단입니다.** 측정 수학이
안드로이드 SDK 를 모르면, 실제 마이크 없이 값을 아는 신호를 넣어 나오는
숫자를 확인할 수 있습니다. 그것이 측정 코드를 믿을 수 있는 유일한 방법입니다.

`dsp` 모듈에 안드로이드 의존성을 절대 넣지 마십시오.

## 빌드

Android Studio 로 열거나:

```bash
./gradlew :dsp:test          # 측정 수학 검증 (빠름, 기기 불필요)
./gradlew :app:assembleDebug # APK
./gradlew build              # 전체
```

필요: JDK 17+ (Android Studio 번들 JDK 21 로 확인), Android SDK 36,
`local.properties` 의 `sdk.dir`.

## 개발 역할

명세 24장에 따라 **Claude Code = Lead Developer**,
**Codex = Independent Reviewer & Verification Engineer** 로 운영합니다.
DSP 핵심 단계(Phase 4·5·7·9·12)와 Recording 단계는 독립 검증이 필수 게이트이며,
Critical/High 이슈가 남아 있으면 다음 Phase 로 넘어가지 않습니다.

## 진행 상황

| Phase | 내용 | 상태 |
|---|---|---|
| 0 | 환경/저장소 진단 | **완료** |
| 1 | 앱 골격/UI | **완료** |
| 2 | 내장 마이크 MVP | **완료** |
| 3 | 기본 Meter | **완료** |
| 4 | 정식 음압 엔진 (A/C/Z, Fast/Slow, LAeq, Peak) | **완료** |
| 5 | RTA (FFT, 31밴드) | **완료** |
| 6 | USB-C 입력 | **완료** · USB 기기 확인 대기 |
| 7 | 고급 Calibration | **완료** · 파일 가져오기 손 확인 대기 |
| 8 | 교회 모드 | **완료** |
| 9 | Feedback 탐지 | |
| 10 | 세션 기록 | |
| 11 | 리포트 (CSV/PDF) | |
| 12 | 정확도 검증 | |
| 13 | 성능/호환성 | |
| 14 | 접근성/UX | |
| 15 | Release 준비 | |
| 16 | 현장 Pilot | |
| 17 | v1.0 배포 | |
| Recording A~F | 녹음·동기 재생·재분석 | |

---

개발 **장훈 (JANGHUN)** · **Jesus On Air (JOA)**
