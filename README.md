# SELAH RTA

**Real-Time Audio Analyzer** — Android 음향 측정 앱 (주 쓰임은 교회 음향)

설교와 찬양의 음압(SPL·LAeq·Peak), 31밴드 주파수 밸런스, 피드백 후보를 재고
예배별로 기록합니다. 휴대폰 내장 마이크만으로도 모든 핵심 기능이 동작하며,
USB-C 측정 마이크를 연결하면 정확도가 올라갑니다.

> **명세** → [`docs/spec/SELAH_RTA_Master_Spec_v1.2.md`](docs/spec/SELAH_RTA_Master_Spec_v1.2.md)
> (원본 .docx 동봉. 이 문서가 Single Source of Truth 다 — 명세 0장)
>
> **무엇이 끝났고 무엇이 남았나** → [`CHANGELOG.md`](CHANGELOG.md)
> **새 대화창을 열었으면 여기부터 본다.** `docs/spec/` 의 날짜별 문서는
> **그때의 사진**이라, 끝난 일을 할 일로 오인하기 쉽다(실제로 겪었다).
>
> **USB 오디오인터페이스를 꽂아 볼 때** →
> [`docs/manual/usb-audio-interface-check.md`](docs/manual/usb-audio-interface-check.md)
>
> **음압 교정기(ND-9)를 들고 나갈 때** →
> [`docs/manual/calibrator-nd9-check.md`](docs/manual/calibrator-nd9-check.md)
> **내장 마이크로는 절대값이 안 닫힌다** — 무엇이 닫히고 무엇이 안
> 닫히는지가 그 문서 0장에 있다.
>
> **무엇을 아직 모르는가** → [`docs/unverified.md`](docs/unverified.md)
> 시험이 다 통과해도 **확인하지 못한 것**이 있다. 모아 두지 않으면
> 모른다는 사실이 사라지고 「시험이 통과했으니 됐다」가 된다.

---

## 법정 측정을 대신하지 않습니다

**앱과 일반 Android 기기·마이크를 함께 쓰는 것만으로는** 법령·규격이
요구하는 형식승인·검정·정도검사나 공인 교정을 갖춘 것이 아닙니다. 소음
평가·작업환경측정처럼 장비와 방법, 자격 요건이 정해진 판단에는 그 요건을
갖춘 별도의 측정이 필요합니다.

보정되지 않은 절대 SPL 정확도는 보장하지 않습니다. 앱이 보여주는 참고
범위는 공식 청력 안전기준과 다릅니다.

> 2026-09-28 에 「법정 계량용 소음계로 쓸 수 없습니다」에서 고쳤다.
> 법정계량·형식승인·공인교정·작업환경측정은 **서로 다른 제도**라,
> 한 낱말로 뭉뚱그리면 무엇을 대신하지 못하는지가 흐려진다
> (홈페이지 방침 검토 지시서 2절). 사용자에게 보이는 글은
> `joaworks.com` 의 이용약관 제4조와 같은 취지로 맞춘다.

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

> **2026-09-29 에 이 표를 코드와 맞춰 고쳤다.** 9·10·11 과 Recording 이
> **빈칸**이었는데, 셋 다 코드도 시험도 화면 연결도 있었다. 빈칸은
> 「아직 안 만들었다」로 읽힌다 — **다 된 것을 다시 만들 뻔한 자리**다.
> (같은 사고가 실제로 났기 때문에 [CHANGELOG.md](CHANGELOG.md) 가 생겼다.)
>
> **여기 적는 「만듦」은 「독립 검토가 봤다」가 아니다.** 코드·시험·화면이
> 있다는 뜻이다.
>
> **아직 검토를 안 받은 것**은 [docs/review/PENDING-REVIEW.md](docs/review/PENDING-REVIEW.md)
> 에 모아 둔다(2026-09-30부터 — 크레딧을 아껴 한 번에 보낸다). 그보다 앞의
> 회차가 무엇을 봤는지는 여전히 `docs/review/` 의 요청서·회신 짝으로만
> 알 수 있다.

| Phase | 내용 | 상태 | 근거 · 남은 것 |
|---|---|---|---|
| 0 | 환경/저장소 진단 | **만듦** | |
| 1 | 앱 골격/UI | **만듦** | |
| 2 | 내장 마이크 MVP | **만듦** | |
| 3 | 기본 Meter | **만듦** | |
| 4 | 정식 음압 엔진 (A/C/Z, Fast/Slow, LAeq, Peak) | **만듦** | 2026-09-21 Phase 4~8 검토는 **보류** 판정이었고, 그 뒤 여러 회차로 고쳤다 |
| 5 | RTA (FFT, 31밴드) | **만듦** | 위와 같음 |
| 6 | USB-C 입력 | **만듦** | USB 인터페이스가 없어 **기기 확인 대기** |
| 7 | 고급 Calibration | **만듦** | 파일 가져오기 **손 확인 대기** |
| 8 | 교회 모드 | **만듦** | |
| 9 | Feedback 탐지 | **만듦** | `dsp/FeedbackDetector` + 시험, 분석 화면에 연결(18곳). 2026-09-21 검토는 **보류**였고 그 뒤 고쳤다. **현장 오탐률·놓침률은 미확인** |
| 10 | 세션 기록 | **만듦** | `recording/` 15벌 시험, `HistoryScreen` 569줄. 기록이 **쌓였을 때**는 미확인 |
| 11 | 리포트 (CSV/PDF) | **만듦** | `SessionExport.writeCsv` · `ReportPdf`(#83). 글자는 `Type3` 로 박히되 `ToUnicode` 지도가 함께 들어가 **뽑아낼 수 있다**(읽개별 품질은 미확인). 실제 인쇄도 미확인 |
| 12 | 정확도 검증 | **아직** | 교정기·기준 소음계가 있어야 한다 |
| 13 | 성능/호환성 | **부분** | 주 스레드 지연은 재서 시험으로 굳혔다. **2시간 부하·44.1kHz 경로 미확인** |
| 14 | 접근성/UX | **부분** | `contentDescription` · 고른 것을 **색 말고 글로도** 적는다(악기 EQ 「보는 중」). **TalkBack 통과 확인은 안 했다** |
| 15 | Release 준비 | **부분** | 서명 절차는 [release-signing.md](docs/release-signing.md). **업로드 키스토어는 비밀값이라 사람이 만든다** |
| 16 | 현장 Pilot | **아직** | |
| 17 | v1.0 배포 | **아직** | |
| Recording A | PCM(WAV) 녹음 · REC | **만듦** | `AudioFileRecorder`·`WavWriter`·`SessionRecorder`. **「분석 중 dropout 없음」은 미확인**(2시간 부하) |
| Recording B | 압축 녹음(AAC/M4A) | **만듦** | `MediaCodec`+`MediaMuxer`. **장시간 파일 검증은 미확인** |
| Recording C | 동기 분석 저장 | **만듦** | `RowSlicer`(500ms 행)·`TimelineIo`·`RecordingEpoch` |
| Recording D | 동기 재생 UI | **만듦** | **양방향이 된다** — `SplTimelineGraph` 를 누르면 그 시점으로 간다(`PlaybackSeek`). 사건 표·31밴드도 붙었다. **소리가 실제로 그 자리에서 나는지는 귀로 확인 안 했다** |
| Recording E | 재분석 | **만듦** | `WavReader`·`Reanalysis`·`SessionReanalyzer`. 기록 화면에서 **지금 설정으로 다시 분석** — 소리와 **처음 잰 타임라인은 남는다**(`timeline-v1.bin`). **긴 녹음의 시간·전력은 미확인** |
| Recording F | 장시간·현장 검증 | **아직** | 60분·USB 전환·pause/resume·저장공간 |
| §7 저장 | RTA 측정 저장·차이·차례 | **만듦 · 고침** | #73·#75·#77 → 12회차 **승인 보류**(High 3) → #84 로 여섯 가지 고침. **재검토 대기** |
| 악기 EQ | 가이드 화면 개편 | **만듦** | #86. 위에 RTA 고정 · 고른 대역을 띠로 강조. **실제 악기·믹서 실습은 미확인**(지시서 §36) · 검토 대기 |

**「만듦」에 머물러 있는 것이 왜 남아 있는지**는
[docs/unverified.md](docs/unverified.md) 에 「누가 닫을 수 있는가」로
묶여 있다 — 장비가 필요한 것, 시험으로 일으킬 수 없는 것, 시간이 걸리는 것.

**고치는 때**: 기능이 끝나면 그 줄을 함께 고친다. 빈칸으로 두지 않는다 —
빈칸은 「안 했다」로 읽힌다.

---

개발 **장훈 (JANGHUN)** · **Jesus On Air (JOA)**
