# SELAH RTA 독립 검토 보고서

검토일: 2026-09-23 · 역할: Independent Reviewer · 구현 담당: Claude Code

## 1. 결론과 검토 기준

SELAH RTA에는 자체 FFT, 전력 기반 1/3옥타브 분석, A/C/Z 동시 처리, Fast/Slow, Leq, 입력 경로 확인, 교정 품질 판정의 기반이 구현되어 있다. 그러나 **전문 측정기의 정확도 또는 Smaart와 동등한 기능이 검증된 상태는 아니다.** 우선순위는 새 그래프 추가보다 가중 필터 정확도, 보정 범위의 명확한 표시, 실제 교정 흐름 연결, 교정 데이터의 신뢰성 확보에 있다.

- 확인된 P0 결함은 없다. P1은 측정값 신뢰성 또는 교정 기능 완성 전에 해결할 항목이다.
- 기존 DSP 단위시험 **401개가 통과**했다. 별도 수치 확인에서 고역 A 가중 오차와 시간창 근사를 재확인했다. 시험 통과가 전체 주파수 정확도나 규격 적합성을 뜻하지 않는다.
- Smaart는 공개된 기능 범주의 참고자료로만 사용했다. 코드·바이너리·화면·문구·그래픽을 가져오거나 역공학하지 않았다.
- 이번 작업은 분석과 보고서 작성이다. 저장소 소스 수정, 커밋, 푸시, PR 생성은 하지 않았다.

### 고정한 소스와 검토 한계

| 항목 | 확인 결과 |
|---|---|
| 저장소 접근 | GitHub 연결 도구로 `janghorse-boop/SELAH-RTA` 조회 및 main 커밋 읽기 성공 |
| 검토 SHA | `181a5b6173a946ae826ad2c08348ca933e0e64d1` |
| 커밋 시각 | 2026-09-23 15:31:49 KST |
| 커밋 내용 | 교정 측정에 실시간 스펙트럼을 공급할 MeasurementTap 추가 |
| 로컬 대조 | `D:/Cowork/SELAH-RTA`의 HEAD가 동일했고 시작 시 작업 트리가 깨끗했음 |
| 분석 방법 | 커밋 아카이브를 별도 작업 폴더에 풀어 주요 DSP·입력·교정·UI 연결·의존성 선언 검토 |
| 실행 검증 | DSP main/test Kotlin 전체 컴파일 후 JUnit 실행, 별도 DSP 수치 프로브 |
| 미검증 | Android 전체 빌드, 앱 UI 자동시험, 실기기 오디오 재측정, 최종 APK 구성, 전체 Git 이력·모든 자산 출처 |

이 문서는 위 SHA에 고정된다. 이후 Claude Code의 변경은 포함하지 않는다. 아래의 ‘실기기 기록’은 저장소에 남은 기록을 검토한 것이며 이번 검토자가 직접 재측정한 결과가 아니다.

종료 시 로컬 작업 트리에 `SpectrumAverage.kt` 수정과 `WizardRunner.kt`·`WizardRunnerTest.kt` 신규 파일이 나타났다. 이번 검토에서 만든 변경이 아니며, 병행 작업으로 보고 건드리지 않았다. 이 미커밋 변경은 아카이브 기반 시험과 R03 교정 연결 판정에 포함되지 않는다. 따라서 구현 담당자는 해당 후속 작업과 본 보고서를 대조해야 한다.

## 2. 현재 구현

| 영역 | 실제 구현과 경계 | 주요 근거 |
|---|---|---|
| FFT | 자체 radix-2 FFT, 대칭 Hann 창, 단측 전력 및 창 에너지 정규화 | [Fft.kt][fft] |
| RTA | 기본 FFT 4096, 50% 겹침, 31개 1/3옥타브, 경계 bin의 겹친 폭에 따른 전력 배분 | [RtaEngine.kt][rta], [BandAnalyzer.kt][bands] |
| 평균·평활화 | 실시간 전력 EMA와 Peak Hold; 교정 측정용 전력 평균·안정 프레임 선별 별도 구현 | [BandAnalyzer.kt][bands], [SpectrumAverage.kt][average] |
| SPL | A/C/Z 동시 처리, Fast/Slow, MAX, 짧은/긴 rolling Leq | [SplEngine.kt][spl], [MultiWeightEngine.kt][multi], [TimeWeighting.kt][time] |
| Peak | 원 PCM의 sample peak. 재구성 true peak 또는 C 가중 peak라고 볼 수 없음 | [SplEngine.kt][spl] |
| 주파수 보정 | 로그 주파수 보간, 응답값을 빼는 부호, bin 전력 단계 보정 후 밴드 합산 | [CalibrationCurve.kt][curve] |
| 절대 레벨 교정 | 장치·입력 source·채널별 scalar offset 저장. 미교정 기본값은 참고용으로 구분 | [CalibrationProfile.kt][scalar] |
| 내장 마이크 교정 | 기준-대상-기준 세션, 기준 CAL 증거, SNR·품질 마스크 및 적용 판정 기반 있음. 실제 측정 UI와 저장/자동 적용 연결은 미완성 | [CalibrationSession.kt][session], [MeasuredProfile.kt][profile], [CalibrationWizardScreen.kt][wizard] |
| USB 입력 | float/int16, 채널 수 선택, 녹음 시작 후 실제 route 확인. 선택한 한 채널을 분석기로 전달 | [MicSource.kt][mic] |
| 신호 발생 | pink noise, sweep, 여러 고정 주파수 tone 및 재생 코드 존재. 이번 검토에서 출력 스펙트럼/장치별 출력은 실측하지 않음 | `audio/TestSignals.kt`, `audio/SignalPlayer.kt` |
| 기록 | PacketPipe·RowAggregator·TimelineIo 등의 기반 있음. 사용 가능한 기록/내보내기 흐름은 화면에서 미완성 | [HistorySettingsScreens.kt][history] |
| 악기 가이드 | 악기별 대역/청취 안내 자료 존재. 측정값만으로 자동 EQ나 컴프레서 Threshold를 결정하는 근거로 확대하면 안 됨 | `InstrumentCatalog.kt` |

FFT 에너지 정규화는 올바른 분석 기반이다. 다만 창을 씌운 임의의 과도 신호 에너지가 항상 창 적용 전 원 신호 에너지와 정확히 같다는 뜻은 아니다. 현재 ‘smoothing’은 주로 시간 평활화이며, 사용자가 고르는 1/n옥타브 주파수 평활화와 구분해야 한다.

## 3. 주요 발견 사항

### R01 · P1 · A/C 가중 필터의 고역 정확도 한계 — 기존에 알려진 한계, A는 수치 재확인

[Weighting.kt][weight]는 아날로그 특성을 디지털 필터로 옮긴 구조이고, 기존 시험도 고역 warping 한계를 인정한다. 이번 별도 프로브는 연속 주파수 A 기준식을 1kHz에서 정규화해 구현의 주파수 응답과 비교했다.

| 입력 표본율 | 주파수 | 구현 − 기준, dB |
|---|---:|---:|
| 44.1kHz | 8kHz | -0.662 |
| 44.1kHz | 10kHz | -1.501 |
| 44.1kHz | 16kHz | -8.530 |
| 44.1kHz | 20kHz | -24.542 |
| 48kHz | 8kHz | -0.544 |
| 48kHz | 10kHz | -1.216 |
| 48kHz | 16kHz | -6.434 |
| 48kHz | 20kHz | -15.842 |

이는 해당 주파수 성분의 가중 오차이며, 모든 음악/소음의 전체 LAeq가 그만큼 틀린다는 뜻은 아니다. 반대로 몇 가지 합성 음성·예배 스펙트럼에서 오차가 작았다는 시험으로 모든 실제 입력의 정확도를 보장할 수도 없다. C 가중도 전체 대역 검증이 필요하지만 위 표는 A 가중만 별도 재계산한 결과다.

**개선:** 지원 표본율별 목표 대역과 허용 오차를 먼저 명시하고, 독립적인 디지털 필터 설계로 전체 대역을 맞춘다. 고역 보정 필터나 오버샘플링은 후보이며 안정성·위상·CPU·aliasing까지 비교 후 선택한다. 전체 입력을 단순 증폭하는 offset으로 해결하지 않는다.

**검증:** A/C 각각 표준 주파수 점과 조밀한 sweep, 저역 안정성, impulse 후 수렴, 여러 입력 레벨, 44.1/48kHz 등 실제 지원율에서 시험한다. 내부 공학 목표와 IEC 등급 판정을 구분한다. IEC 적합성에는 주파수 응답 외의 요구와 측정 불확도 평가도 필요하다. [IEC 61672-2 공식 설명](https://webstore.iec.ch/en/publication/5709)

### R02 · P1 · 마이크 주파수 CAL은 RTA에 적용되지만 광대역 SPL·Leq에는 적용되지 않음 — 알려진 설계 범위

[CaptureViewModel.kt][capture]의 보정 감시는 RTA에 곡선을 설정한다. SPL은 별도 PCM 경로에서 가중 필터와 scalar offset을 사용한다. 따라서 같은 입력에서도 ‘보정된 RTA’와 ‘주파수 응답까지 보정된 LAeq’는 동일한 상태가 아니다. 저장소의 측정 정확도 한계 문서도 이를 이미 명시한다.

**영향:** 기준 신호에서 맞춘 단일 offset은 측정 대상의 주파수 분포가 바뀌면 마이크 응답 차이를 상쇄하지 못한다. EMM-6 개별 CAL을 불러왔다고 절대 SPL 전체가 교정되는 것은 아니다.

**개선:** 먼저 화면·기록에 ‘RTA 주파수 보정’과 ‘SPL 레벨 교정’을 분리한다. SPL까지 교정하려면 연속 PCM 보정 필터 또는 요구하는 시간응답까지 정의한 별도 전력 처리 경로를 설계한다. magnitude CAL만으로 마이크의 위상 보정이 증명되는 것은 아니다.

**검증:** 서로 다른 응답 곡선, 두 개 이상의 주파수 분포, 동일 RMS 입력으로 보정 전후 RTA·Z/A/C·Leq 결과를 비교한다. 외부 기준 계기와 비교 시 두 계기의 시간·가중 설정을 일치시킨다.

### R03 · P1 · 실제 교정 마법사와 측정 프로파일 적용의 연결이 미완성 — 기능 완성 항목

[교정 화면][wizard]은 예제/비교 UI 성격을 명시하고, [SelahApp.kt][app]의 연결도 예제 결과를 전달한다. 새 [MeasurementTap.kt][tap]은 스펙트럼 수집 기반이나 이 SHA의 앱 실제 측정 흐름에는 연결되지 않았다. 측정 프로파일 목록의 활성화 메타데이터와 CaptureViewModel의 DSP 적용 경로도 연결되지 않았다.

이는 교정 알고리즘이 전혀 없다는 뜻이 아니다. 기준 CAL 증거와 SNR·재현성·환경 일치 판정 등의 코드는 이미 존재한다. **남은 핵심은 실제 입력 → 세션 → 판정 → 저장 → 재시작 후 조건부 적용의 전체 흐름이다.**

**개선:** 기존 판정을 우회하지 말고 실제 측정 이벤트에 연결한다. 실패/미확인 대역은 자동 적용하지 않는다. 장치·source·채널·표본율·DSP 조건이 달라지면 재검증하고, 논리 경로만 확인된 S23 마이크는 독립 물리 마이크 프로파일로 단정하지 않는다.

**검증:** 정상 세션 외에 CAL 없음, 기준 전후 drift, 낮은 SNR, clip, 중도 USB 분리, 경로 변경, 앱 재시작, 표본율 불일치, 잘못된 프로파일 선택을 포함한다. 저장된 보정이 실제 DSP 출력에 한 번만 적용되는지 확인한다.

### R04 · P1 · CAL 부호가 의심스러운 파일도 경고와 함께 즉시 적용 — 일반 파일 입력의 조건부 오류

[CalibrationCurve.kt][curve]는 파일의 양수 값을 마이크 응답 상승으로 해석해 뺀다. 그러나 correction 형식은 양수 값을 더해야 할 수 있다. [CaptureViewModel.kt][capture]의 `importCurve`는 저장 후 경고를 표시한다. 헤더 판별은 증거 보조이며 형식 확인 절차를 대신하지 못한다.

예를 들어 실제로 +2dB를 더하라는 correction 값을 응답으로 해석해 -2dB를 적용하면 의도한 결과와 4dB 차이가 난다. **이번 검토에서 사용자의 EMM-6 파일 부호가 틀렸다고 확인한 것은 아니다.**

**개선:** response/correction을 명시적으로 선택·기록하고, 모호하거나 반대 증거가 있으면 자동 활성화를 보류한다. 제조사 형식은 문서로 확인한 preset으로 다룬다. 파일 hash·원본 헤더·선택 부호를 보존한다. 제조사가 제공하는 개별 CAL의 존재만으로 모든 열의 의미가 확인되지는 않는다. [Dayton EMM-6 공식 페이지](https://www.daytonaudio.com/product/911/emm-6-electret-measurement-microphone)

**검증:** response +2, correction +2, 무헤더, 모순 헤더, 잘못된 단위, 불완전한 주파수 범위에서 기대 부호와 보류 상태를 시험한다.

### R05 · P1 · MeasurementTap의 stop/drain/start 경계 경쟁 가능성 — 정적 분석, 앱 연결 전 해결

[MeasurementTap.kt][tap]는 오디오 스레드가 `collecting`을 확인한 뒤 counter/calibrator/queue를 갱신하고, 주 스레드는 stop·drain·clear·start를 수행하도록 작성되어 있다. 개별 필드의 volatile/atomic과 concurrent queue가 세션 전환 전체를 원자적으로 만들지는 않는다.

가능한 순서: 오디오 콜백이 collecting=true 확인 → 주 스레드 stop/drain 및 새 세션 시작 → 기존 콜백이 새 calibrator를 읽거나 drain 이후 프레임을 넣음. 그러면 이전 입력이 새 세션에 들어가거나 종료 결과에서 마지막 프레임이 빠질 수 있다. **동시 실행 재현 시험을 수행한 발견은 아니며, 현재 앱에 연결된 런타임 장애로 단정하지 않는다.**

**개선:** 시작·종료·drain·설정 전환을 기존 capture 명령 큐와 같은 단일 소유 스레드에서 직렬화하고, 완료 응답 후 결과를 읽는다. 세션 generation과 프레임 시각을 붙이고 실행 중 콜백의 종료를 보장한다. 오디오 콜백에서 오래 잠그는 방식은 피한다.

**검증:** latch 등으로 위 순서를 강제하는 시험, 빠른 reference/target 전환, 가득 찬 큐와 종료 동시 발생을 검사한다. 단순 반복 시험만으로 경쟁이 없다고 판정하지 않는다.

### R06 · P1 · 절대 SPL 교정 재사용 조건이 아날로그 gain 변경을 포괄하지 못함

[CalibrationKey/GlobalCalibration][scalar]에는 장치·source·일부 채널 구분과 레벨 offset은 있으나 표본율·인터페이스 gain 상태 등 전체 측정 체인이 기록되지 않는다. 새 MeasuredProfile의 환경 판정은 이보다 풍부하지만 기존 scalar 교정과는 별도다.

UMC404HD gain을 바꾸면 같은 마이크·채널이어도 기존 dBFS→SPL offset이 더 이상 맞지 않는다. gain 노브 값을 Android에서 항상 자동으로 읽을 수 있다는 가정을 두면 안 된다.

**개선:** 사용자 확인 가능한 gain 고정/변경 상태, 마이크 식별, 입력 설정, 교정 기준·시각을 기록하고 gain 변경 때 재교정을 안내한다. 자동 감지 불가 상태를 명시한다. 미교정 참고 표시를 유지한다.

**검증:** 알려진 기준 레벨에서 교정 후 gain을 바꾸고 잘못된 교정 유효 표시가 유지되지 않는지 확인한다. 기준 캘리브레이터의 적합한 커플러·주파수·레벨과 측정 체인 조건도 기록한다. [IEC 60942 공식 범위](https://webstore.iec.ch/en/publication/30045)

### R07 · P2 · FFT 4096 기반 저역 밴드 해상도와 경계 누설

[BandAnalyzer.kt][bands]는 한계를 숨기지 않고 내부 표본 순음의 손실로 분해 여부를 표시한다. 48kHz/4096에서 이번 재계산한 표본 최대 손실은 63Hz 2.491dB, 100Hz 1.024dB이며 첫 통과 밴드는 index 8, 즉 125Hz다. 이는 밴드 내부 세 위치의 결과이고 모든 위치의 최대 오차 보장은 아니다. 기존 문서도 경계 근처 누설을 별도로 설명한다.

**개선:** 저역을 위한 더 긴 분석창 또는 독립적으로 설계한 다중 해상도/필터뱅크를 검토한다. 긴 창의 지연을 표시한다. FFT 밴드 합산을 IEC 61260 적합 필터뱅크와 동일시하지 않는다. [IEC 61260-1 공식 범위](https://webstore.iec.ch/en/publication/5063)

**검증:** 중심뿐 아니라 밴드 경계 양쪽, 다중 tone·pink noise·과도 신호로 인접 밴드 누설/총전력/지연을 확인한다.

### R08 · P2 · EMA와 Peak Hold 속도가 프레임률에 의존

[BandAnalyzer.kt][bands] 및 [RtaEngine.kt][rta]의 평활 계수와 프레임당 0.4dB 감소값은 고정이다. 기본 hop 2048에서 hold 감소율은 44.1kHz 약 8.61dB/s, 48kHz 약 9.38dB/s다. FFT/hop를 바꾸면 더 크게 달라진다.

**개선:** 평활화는 초 단위 시간상수와 실제 frame 간격으로 계수를 산출하고, hold는 유지 시간·감소 dB/s를 분리한다. UI 명칭도 시간 평균과 주파수 평활화를 구분한다.

**검증:** 표본율·FFT·hop가 달라도 동일한 실시간 길이의 step 입력에서 같은 상승/하강 곡선이 나오는지 확인한다.

### R09 · P2 · rolling Leq는 버킷 단위의 근사 시간창

[TimeWeighting.kt][time]는 기본 100ms 버킷을 사용한다. 완성된 N개 버킷에 현재 부분 버킷을 더하므로 가득 찬 이후의 실제 누적 길이는 표시 창보다 최대 약 한 버킷 길어질 수 있다.

별도 축소 시험: 1kHz 표본율, 1초 창, 처음 1,000개 전력 1 이후 99개 전력 0. 결과는 -0.409977dBFS이고 정확한 최근 1,000표본 값은 -0.452752dBFS다. 이 0.042775dB 차이는 해당 신호의 예이며 일반적인 최대 오차가 아니다.

**개선:** 표시를 근사 창으로 명시하거나 정확한 sliding sum을 구현한다. 세션 전체 Leq, rolling Leq, Fast/Slow 값을 서로 구분한다.

**검증:** 경계 직전/직후의 큰 레벨 전환과 무음, 장시간 실행, chunk 크기 변화에서 독립적인 표본 단위 기준과 비교한다.

### R10 · P2/P3 · 기록·전문 측정 기능과 문서의 완성도

기록 UI는 미완성이므로 추적 가능한 현장 검증을 위해 원본 조건·보정 상태·신뢰도·시간을 가진 세션 저장/내보내기를 P2로 둔다. 라이브러리 기반이 있다는 것과 사용 가능한 전체 흐름은 구분한다. README의 단계 표는 현재 구현과 어긋나는 부분이 있어 P3로 갱신한다. 향후 기능을 현재 정확도 문제의 해결로 표현하지 않는다.

## 4. UMC404HD·EMM-6·S23 실제 지원 범위

저장소의 `docs/spec/2026-09-23-umc404hd-first-plug-result.md`는 S23 Ultra/Android 16에서 UMC404HD 입력 채널을 열어 본 기록이다. Input 1 신호와 비어 있는 Input 2/3의 차이를 관찰했지만 **Input 4 실측과 네 채널 동시 유효 신호는 확인되지 않았다.** 기록상 낮은 SNR 때문에 실제 주파수 교정도 완료되지 않았다.

- API가 광고하는 채널 수·표본율과 실제 열린 형식은 다르다. 현재 코드는 실제 route를 녹음 시작 후 확인하는 방향으로 구현되어 있다.
- `AudioRecord.getSampleRate()`는 설정된 sink 표본율이며 장치 native 표본율과 항상 같지는 않다. `setPreferredDevice()` 성공만으로 실제 route를 증명할 수 없다. [Android AudioRecord 공식 API](https://developer.android.com/reference/android/media/AudioRecord)
- 여러 채널을 열어 한 채널을 선택하는 기능과 동기식 두 채널 전달함수 분석은 다르다. 후자를 위해서는 같은 캡처 블록의 기준/측정 채널을 함께 보존해야 한다.
- S23 후면/하단의 논리 정보만으로 두 물리 마이크가 독립 입력임을 확정하지 않는다. 실제 채널 매핑·routing 및 반복 측정 증거가 필요하다.
- EMM-6 CAL 파일, 팬텀 전원, gain/clip, 신호 대 잡음비, 기준 마이크 전후 drift는 서로 다른 확인 항목이다. 하나를 통과했다고 나머지가 보장되지는 않는다.

## 5. Smaart 참고 기능과 독립 구현 로드맵

공식 기능 소개는 전문 분석기의 범주를 확인하는 데만 사용했다. 공개 문서 목록에는 LE 안내서 등이 연결되어 있으나 Suite/RT 전체 사용자 안내서는 준비 중으로 표시되어 있어, 모든 최신 설명서를 정독하거나 전체 기능 동등성을 확인했다고 주장하지 않는다. [공식 기능 소개](https://www.rationalacoustics.com/products/smaart-suite-v9-perpetual), [공식 문서 목록](https://support.rationalacoustics.com/support/solutions/articles/150000070760-smaart-platform-documentation)

| 기능 범주 | SELAH 상태 | 독립적인 다음 단계 | 우선순위 |
|---|---|---|---|
| Spectrum/RTA | 1/3옥타브·시간 평활·hold 존재 | 정확도 범위, 분석창 선택, 선 스펙트럼/대역 선택 | P1→P2 |
| SPL/Leq | 기반 존재 | R01/R02/R06 해결 및 추적 가능한 기록 | P1 |
| 시간/공간 평균 | 교정 평균 기반 존재 | live 평균 모드, 여러 위치의 전력 평균, 조건 저장 | P2 |
| 측정 trace 저장/비교 | 전체 흐름 미완성 | 보정·환경·단위가 다른 trace의 비교 제한 | P2 |
| Spectrogram | 이번 주요 경로 검토에서 완성된 기능 확인 못 함 | 시간축 스펙트럼과 일관된 레벨/색상척도 | P2 |
| 두 채널 전달함수 | 미구현 | 동기 입력, cross/auto spectrum, magnitude·phase·coherence | P2, 입력 구조 선행 |
| 지연·IR | 미구현 | 독립적인 지연 추정 및 impulse 분석, 신뢰도 판정 | P3 |
| THD | 미구현 | 발생기·입력 체인 자체 왜곡/노이즈를 포함한 측정 설계 | P3 |
| 신호 발생기 | 이미 존재 | 출력 레벨·sweep/pink 특성·입출력 경로 실측 | P2 |

평균의 종류는 목적에 맞게 구분한다. 시간 평균과 여러 위치를 합치는 공간 평균은 서로 대체되지 않는다. [Rational Acoustics 평균 설명](https://support.rationalacoustics.com/support/solutions/articles/150000214540-averaging)

전달함수의 독립 설계 예: 기준 x와 측정 y를 같은 클록으로 취득하고, `Pxy = average(conj(X) * Y)`라는 convention이면 `H1 = Pxy/Pxx`, `coherence = |Pxy|²/(Pxx*Pyy)`를 사용한다. 복소 교차스펙트럼을 먼저 평균하고, 무입력·클리핑·낮은 SNR·클록 불일치를 판정한다. 단일 FFT 한 장만으로 얻은 coherence를 신뢰도로 쓰지 않는다. Smaart 고유 다중창 구현을 추정하지 않고 요구 해상도와 지연에서 독립 설계한다. [SciPy CSD 공식 문서](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.csd.html), [coherence 공식 문서](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.coherence.html)

## 6. License / IP Compliance

### 판정 범위

**확인한 범위에서 Smaart 코드를 사용한다고 볼 직접적인 증거는 발견하지 않았다. 그러나 전체 제품의 비침해 또는 배포 적법성을 확정한 감사는 아니다.** 자체 Kotlin DSP 구현과 직접 의존성 선언, 주요 주석·자료 참조를 검토했으며 전체 이력, 이미지 유래, 모든 transitive dependency, 최종 APK, 특허·상표 검색은 완료하지 않았다.

### Smaart 자료와 제품의 구분

| 대상 | 공식 조건에서 확인한 점 | SELAH 작업에 적용할 조치 |
|---|---|---|
| Smaart 소프트웨어 | EULA 3.3은 역공학·소스 추출·파생물 등을 제한하고, 3.4는 소프트웨어 일부를 복사/변환해 유사 목적 소프트웨어를 만드는 행위를 제한 | 코드·바이너리·알고리즘 내부 추정·화면 복제 금지. 일반 DSP 요구를 독립 출처로 설계 |
| 문서·그림·UI 문구 | 공개 열람 가능하다고 재배포·번역·복제 허가가 생기는 것은 아님. 공식 support 페이지에는 AI training을 포함한 자료 사용에 사전 서면 동의를 요구하는 고지가 있음 | 문서/그림/번역을 앱이나 저장소에 넣지 않음. 본 검토는 제한적인 기능 사실 요약과 링크에 머무름. 추가 자료 재사용은 별도 허락 확인 |
| 상표/로고 | EULA는 상표 권한을 부여하지 않음 | Smaart 공식판·인증판·제휴 제품처럼 표시하지 않음. 비교 시 사실 관계만 명시 |
| API/SDK | 별도 조건이 있고 SDK 자체 재배포와 파생 정보 이용은 다르게 취급됨 | 현재 통합하지 않음. 향후 통합 시 당시 약관·배포 방식부터 별도 검토 |
| Smaart 내 오픈소스 | 해당 구성요소의 별도 라이선스가 적용될 수 있음 | Smaart 전체를 오픈소스로 취급하지 않음. 필요하면 원 프로젝트에서 해당 코드와 라이선스를 직접 확인 |

근거: [Smaart EULA](https://www.rationalacoustics.com/pages/smaart-end-user-license-agreement), [공식 문서 페이지의 고지](https://support.rationalacoustics.com/support/solutions/articles/150000070760-smaart-platform-documentation), [API/SDK 조건](https://www.rationalacoustics.com/pages/smaart-api-sdk-terms-and-conditions).

위 EULA를 ‘FFT나 유사 기능을 독립적으로 개발하는 모든 행위가 금지된다’는 뜻으로 확대하지 않는다. 동시에 일반 수학을 썼다는 이유만으로 복제된 코드·문구·자산 문제가 사라지는 것도 아니다. 계약 적용 여부와 상용 배포의 법적 판단이 필요한 경우 해당 자료와 배포 형태를 제시해 검토받아야 한다.

### 저장소·의존성·자산 확인

| 항목 | 관찰 | 배포 전 남은 작업 |
|---|---|---|
| 프로젝트 자체 라이선스 | 추적 파일에서 프로젝트 LICENSE/NOTICE/제3자 고지 목록을 찾지 못함 | 소유자 의도에 맞는 라이선스 또는 독점 사용 조건 결정. 공개 저장소라는 사실만으로 타인에게 재사용 권한이 부여된다고 보지 않음 |
| DSP 런타임 | DSP 모듈에 외부 FFT 런타임 의존성 선언 없음. 자체 구현 확인 | 작성 출처·참고 문헌 기록 유지. 이것만으로 전체 코드 기원 증명은 아님 |
| Kotlin·AndroidX·Compose·DataStore | 버전 선언을 확인했으나 모든 최종 해석 의존성의 LICENSE를 대조하지는 않음 | resolved dependency 기반 SBOM 및 개별 LICENSE/NOTICE, 최종 APK 포함 여부 확인 |
| 빌드·시험 도구 | Gradle 스크립트에 Apache 고지. JUnit은 시험 의존성 | 빌드/시험 전용과 배포 포함을 나눠 의무 판단 |
| Android 문서·예제 | 공식 content license는 예외를 포함한 조건을 명시 | API 사실 참조와 예제 코드 복사를 구분. 복사한 코드의 실제 원본 고지 유지 |
| 악기 가이드 | InstrumentCatalog에 iZotope/Shure 등 출처 링크가 있음 | 원문과의 표현 유사성·허용 범위 확인. 링크만으로 번역/재배포 허가를 대신하지 않음 |
| 그림·스크린샷 | `docs/spec/concept-screens.png` 등 자산의 기원 감사 미완료 | 제작자·원본·사용 허락 기록. 경쟁사 UI에서 가져온 자산인지 별도 확인 |
| 마이크 CAL | 사용자 개별 파일을 읽는 흐름 | 제조사 파일의 앱 번들/공개 재배포 권한은 별도 확인. 사용자 소유 파일 읽기와 묶음 배포 구분 |
| IEC 표준 | 공개 카탈로그의 적용 범위만 확인 | 유료 표준 전문·표·그림 재배포 금지 여부와 사용 조건 확인. 이번 검토는 전문 적합성 감사가 아님 |

Kotlin의 공개 라이선스는 Apache 2.0이지만 이것을 모든 transitive dependency의 확인으로 대체하지 않는다. Apache 2.0 구성요소를 배포할 때는 적용 대상의 라이선스 사본, 저작권·수정 표시 및 필요한 NOTICE 보존 의무를 확인해야 한다. [Kotlin 라이선스](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt), [Apache 2.0 원문](https://www.apache.org/licenses/LICENSE-2.0), [Android content license](https://developer.android.com/license)

**현재 배포 판단:** 라이선스 위반 확정도, ‘문제없음’ 인증도 아니다. P1 배포 전 확인 항목은 자체 라이선스 결정, 실제 배포 의존성 고지, 외부 문구/이미지 출처다. 새 의존성은 도입 전에 license·버전·사용 범위·배포 의무를 기록한다. Smaart 자료를 직접 재사용하는 변경은 독립 구현 작업과 분리해 검토한다.

## 7. 검증 결과와 다음 완료 조건

### 이번에 실행한 것

| 검증 | 결과 | 해석 |
|---|---|---|
| DSP 전체 소스/시험 컴파일 | 성공 | Kotlin 2.2.20, 기존 설치 JDK 21/캐시 사용 |
| JUnit DSP 시험 | **401 통과**, 72.052초 | 기존 시험 범위에서 회귀 미발견 |
| 독립 수치 프로브 | 완료 | R01 A 응답, R07 밴드 표본 손실, R08 hold, R09 Leq 근사 확인 |
| Android 앱 전체 빌드/실기기 | 미실행 | 오디오 routing·UI·지속 실행 결과를 이번 시험으로 보장하지 않음 |

컴파일에 `CalibrationSession.kt`의 항상 거짓인 null 비교 경고 하나가 있었다. 현재 정확도 우선순위보다 낮은 정리 항목이다. 시험은 Gradle 전체 빌드가 아니라 DSP 모듈의 main/test Kotlin을 직접 컴파일한 뒤 JUnit으로 실행했다. 환경 제약으로 처음 시도한 Java 보조 프로브 대신 동일한 Kotlin 경로로 수치 검증을 완료했다.

수치 프로브의 A 기준은 `Ra(f)=12194.217²·f⁴ / [(f²+20.598997²)·sqrt((f²+107.65265²)(f²+737.86223²))·(f²+12194.217²)]`, `A(f)=20log10(Ra(f)/Ra(1000))`이다. 필터 응답을 계산한 공학 비교이며 인증 계기로 측정한 IEC 적합성 시험은 아니다. 밴드 수치는 제품의 내부 진단 계산을 다시 실행한 것이므로 별도 독립 필터뱅크 검증과 구분한다.

### Claude Code에 전달할 작업 순서

1. **P1 정확도와 표시:** R01 전체 대역 가중 필터 검증, R02 보정 범위 구분, R04 부호 확정, R06 gain 변경 처리. 현재 표시/지원 범위를 먼저 사실에 맞춘다.
2. **P1 교정 완성:** R05 세션 경계를 직렬화한 뒤 R03 실제 측정·저장·적용을 연결한다. 이미 구현된 품질 판정은 유지한다.
3. **P1 배포 확인:** 라이선스·자산 출처·실제 배포 의존성 목록을 정리한다. 임의의 라이선스 선택은 구현 담당자가 대신 결정하지 않는다.
4. **P2 재현성:** 기록/내보내기, 정확한 시간 설정, 저역 해상도와 지연 선택, 하드웨어 교차 검증을 완료한다.
5. **P2/P3 확장:** 동기 두 채널 기반 확보 후 전달함수·coherence·지연/IR을 독립 구현한다. 기본 RTA/SPL의 검증 완료와 별도로 단계별 완료 기준을 둔다.

각 변경은 해당 SHA와 변경 이유, 실패했던 입력, 독립 기준에 대한 기대값, 실행 결과를 함께 남긴다. 다음 독립 검토는 변경된 커밋을 기준으로 회귀와 실제 앱 연결을 확인한다. 이 문서는 구현 변경을 승인하거나 실행한 기록이 아니다.

## 부록 · 고정 커밋 소스 링크

아래 링크는 모두 이번 검토 SHA에 고정되어 있다. 파일 내 함수명은 본문에 표시했다.

[fft]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/dsp/src/main/kotlin/kr/joa/selahrta/dsp/Fft.kt
[rta]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/dsp/src/main/kotlin/kr/joa/selahrta/dsp/RtaEngine.kt
[bands]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/dsp/src/main/kotlin/kr/joa/selahrta/dsp/BandAnalyzer.kt
[average]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/dsp/src/main/kotlin/kr/joa/selahrta/dsp/SpectrumAverage.kt
[spl]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/dsp/src/main/kotlin/kr/joa/selahrta/dsp/SplEngine.kt
[multi]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/dsp/src/main/kotlin/kr/joa/selahrta/dsp/MultiWeightEngine.kt
[time]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/dsp/src/main/kotlin/kr/joa/selahrta/dsp/TimeWeighting.kt
[weight]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/dsp/src/main/kotlin/kr/joa/selahrta/dsp/Weighting.kt
[curve]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationCurve.kt
[tap]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/dsp/src/main/kotlin/kr/joa/selahrta/dsp/MeasurementTap.kt
[session]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSession.kt
[capture]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt
[scalar]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/app/src/main/java/kr/joa/selahrta/calibration/CalibrationProfile.kt
[profile]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/app/src/main/java/kr/joa/selahrta/calibration/MeasuredProfile.kt
[wizard]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/app/src/main/java/kr/joa/selahrta/ui/screens/CalibrationWizardScreen.kt
[app]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/app/src/main/java/kr/joa/selahrta/ui/SelahApp.kt
[mic]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/app/src/main/java/kr/joa/selahrta/audio/MicSource.kt
[history]: https://github.com/janghorse-boop/SELAH-RTA/blob/181a5b6173a946ae826ad2c08348ca933e0e64d1/app/src/main/java/kr/joa/selahrta/ui/screens/HistorySettingsScreens.kt
