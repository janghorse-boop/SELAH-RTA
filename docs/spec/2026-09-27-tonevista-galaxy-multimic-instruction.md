# ToneVista 개발지시서 — Galaxy 다중 내장 마이크 식별 및 캘리브레이션

> 받은 날: 2026-09-27 · 원본: `ToneVista_Galaxy_다중내장마이크_캘리브레이션_개발지시서.docx`
>
> `inbox/` 는 저장소에 올리지 않으므로(CLAUDE.md §2) 본문을 그대로 옮겨 적는다.
> **문구는 원본 그대로이고 손대지 않았다** — 목차만 뺐다(마크다운 제목이 그 일을 한다).
> 이 지시서를 지금 코드와 맞춰 본 결과는
> [2026-09-27-tonevista-multimic-review.md](2026-09-27-tonevista-multimic-review.md) 에 있다.

## 1. 목적

ToneVista에서 Galaxy S23/S23+/S23 Ultra와 같이 여러 개의 내장 마이크를 가진 Android 기기를 사용할 때, 물리적인 마이크 개수만 기준으로 임의의 캘리브레이션 값을 적용하지 않는다.
대신 Android가 실제 녹음 과정에서 사용하는 마이크와 입력 경로를 확인하고, 실제 ToneVista 측정에 사용되는 AudioRecord 입력 경로 전체를 하나의 캘리브레이션 프로파일로 관리하도록 구현한다.
핵심 원칙은 다음과 같다.
물리 마이크 각각을 무조건 독립 캘리브레이션하지 말고, ToneVista가 실제 사용하는 활성 마이크 및 녹음 경로를 식별한 뒤 해당 측정 경로 전체에 SPL Calibration Offset을 적용한다.

## 2. 기본 처리 흐름

ToneVista가 내장 마이크를 이용해 측정을 시작할 때 다음 순서로 처리한다.
1. Android에서 사용 가능한 내장 마이크 목록 조회
2. AudioRecord 생성 및 측정 시작
3. 실제 활성화된 마이크 확인
4. 활성 마이크 및 채널 매핑 정보 저장
5. 현재 녹음 경로와 Audio Source 정보 저장
6. 해당 입력 경로에 대응하는 Calibration Profile 로드
7. SPL Calibration Offset 적용
8. RTA, SPL, Peak, Recording 등 모든 관련 측정 결과에 동일한 캘리브레이션 적용

## 3. 마이크 목록 조회

Android API를 이용하여 기기에서 제공하는 마이크 정보를 확인한다.
우선 다음 정보를 사용한다.
AudioManager.getMicrophones()
각 MicrophoneInfo에 대해 가능한 범위에서 다음 정보를 수집한다.
Microphone ID
Device ID
Type
Address
Location
Directionality
Group
Index in Group
Channel Mapping
Frequency Response
Sensitivity
SPL 관련 제공 정보
기타 Android가 제공하는 MicrophoneInfo
단, Galaxy 및 제조사에 따라 일부 정보가 UNKNOWN, 빈 값 또는 제한적으로 제공될 수 있으므로 해당 값에 의존해 하단/상단/후면 마이크를 단정하지 않는다.

## 4. 실제 활성 마이크 확인

ToneVista에서 AudioRecord를 시작한 이후 반드시 다음 API를 이용해 실제 녹음에 사용되는 마이크를 확인한다.
audioRecord.activeMicrophones
또는 대응되는 공식 Android API를 사용한다.
확인 항목:
활성 Microphone ID
Group
Index in Group
Channel Mapping
현재 입력 채널 수
실제 녹음 중 활성화된 마이크 개수
예:
Available Internal Microphones: 3

Active Microphones:
Mic ID: 5
Group: 0
Index: 0

Mic ID: 7
Group: 0
Index: 2
물리적인 마이크가 3개 존재하더라도 실제 ToneVista 녹음 시 1개 또는 여러 개가 동시에 활성화될 수 있음을 전제로 한다.

## 5. 중요한 설계 원칙

다음과 같은 UI 또는 로직을 임의로 만들지 않는다.
상단 마이크 선택
하단 마이크 선택
후면 마이크 선택
Android API가 실제로 해당 물리 마이크의 독립적인 선택을 보장하지 않는다면 위와 같은 선택 기능을 제공하지 않는다.
또한 아래와 같이 임의의 개별 보정값도 생성하지 않는다.
하단 마이크 +1.5 dB
상단 마이크 -0.8 dB
후면 마이크 +2.1 dB
대신 다음처럼 처리한다.
Galaxy S23 Ultra
Built-in Microphone Measurement Path

Active Mic Combination:
Mic 5 + Mic 7

Audio Source:
UNPROCESSED

SPL Calibration Offset:
+2.6 dB
즉, 현재 사용되는 측정 경로 전체를 하나의 Calibration Profile로 관리한다.

## 6. Audio Source 확인

측정 시 현재 사용 중인 Audio Source를 명확히 관리한다.
가능하다면 측정용 입력에서는 다음 우선순위를 검토한다.
MediaRecorder.AudioSource.UNPROCESSED
단, 기기에서 UNPROCESSED가 지원되지 않을 경우 안전하게 fallback 한다.
Fallback 후보:
VOICE_RECOGNITION
DEFAULT
MIC
실제 선택된 Audio Source를 Calibration Profile에 반드시 저장한다.
중요:
Audio Source가 변경되면 동일한 내장 마이크라도 제조사 DSP, AGC, Noise Suppression 등의 동작이 달라질 수 있으므로 동일 캘리브레이션으로 간주하지 않는다.

## 7. Calibration Profile 구조

다음과 같은 구조로 프로파일을 설계한다.
예시:
data class CalibrationProfile(
    val profileId: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val inputDeviceType: String,
    val audioSource: Int,

    val activeMicrophoneIds: List<String>,
    val microphoneGroups: List<Int>,
    val channelCount: Int,

    val sampleRate: Int,
    val encoding: Int,

    val splOffsetDb: Double,

    val createdAt: Long,
    val updatedAt: Long
)
실제 프로젝트 구조와 Architecture에 맞게 수정 가능하다.

## 8. 프로파일 식별 조건

내장 마이크 Calibration Profile은 최소 다음 조건을 기반으로 식별한다.
Device Manufacturer
Device Model
Input Device
Audio Source
Active Microphone Combination
Channel Count
Sample Rate
가능하면 AudioRecord 설정 역시 비교한다.
예:
Samsung
SM-S918N
Built-in Mic
UNPROCESSED
Mic IDs: [5, 7]
Mono
48 kHz
이 조합에 대해 하나의 SPL Calibration Offset을 저장한다.

## 9. SPL 캘리브레이션 화면

ToneVista 설정에 다음 기능을 추가한다.
설정
 └ 마이크 / 입력
    └ 캘리브레이션
       └ 내장 마이크 캘리브레이션
화면 예시:
내장 마이크 캘리브레이션

기기
Galaxy S23 Ultra

입력
Built-in Microphone

Audio Source
UNPROCESSED

활성 마이크
2개

현재 측정값
82.4 dB SPL

기준값
85.0 dB SPL

보정값
+2.6 dB

[현재 보정값 저장]

## 10. 캘리브레이션 방식

내장 스마트폰 마이크에는 일반적인 Sound Level Calibrator를 직접 결합하지 않는다.
내장 마이크 캘리브레이션은 기준 측정 시스템과 비교하는 방식을 사용한다.
권장 기준 시스템:
EMM-6
+
오디오 인터페이스
+
Sound Level Calibrator
먼저 EMM-6 시스템을 절대 SPL 기준으로 캘리브레이션한다.
예:
Sound Level Calibrator
94 dB SPL
1 kHz
EMM-6 측정 시스템을 94.0 dB SPL 기준으로 설정한다.
그 후 동일한 음장을 다음 두 시스템으로 측정한다.
Reference:
EMM-6 = 85.0 dB SPL

Phone:
Galaxy S23 Ultra = 82.4 dB SPL
자동 계산:
Calibration Offset
= Reference SPL - Phone SPL

= 85.0 - 82.4

= +2.6 dB
ToneVista 내부에 다음 값을 저장한다.
SPL Offset = +2.6 dB

## 11. Calibration Wizard 구현

가능하면 캘리브레이션 화면을 Wizard 형식으로 구현한다.
STEP 1. 기준 측정
기준 측정기의 값을 입력하세요.

Reference SPL
[ 85.0 ] dB
STEP 2. 스마트폰 측정
ToneVista가 일정 시간 측정한다.
권장 측정 시간:
3~5초
측정 결과:
Average SPL
82.4 dB
순간값 하나가 아니라 일정 시간 평균값을 사용한다.
STEP 3. 자동 계산
Reference SPL     85.0 dB
Measured SPL      82.4 dB
Calibration       +2.6 dB
STEP 4. 저장
[캘리브레이션 저장]

## 12. SPL Offset 적용 위치

Calibration Offset은 SPL 계산의 최종 표시 단계에서 임의로 더하는 방식이 아니라, DSP pipeline 내에서 적용 위치를 명확히 정의한다.
개념적으로:
Audio Input
↓
PCM
↓
RMS / SPL Calculation
↓
Base SPL
↓
Calibration Offset
↓
Calibrated SPL
↓
UI / Recording / Analysis
예:
Base SPL = 82.4 dB

Calibration Offset = +2.6 dB

Final SPL = 85.0 dB

## 13. 적용 범위

SPL Calibration Offset은 동일 입력 경로를 사용하는 다음 기능에 일관되게 적용한다.
SPL Meter
RTA의 SPL 축
Peak SPL
Average SPL
Min / Max SPL
Recording 분석 결과
저장된 측정 데이터
Measurement History
Report 또는 Export 기능
어떤 화면에서는 보정되고 어떤 화면에서는 보정되지 않는 문제가 생기지 않도록 공통 DSP 또는 Calibration Layer에서 처리한다.

## 14. FFT / RTA 주파수응답 보정과 분리

SPL Calibration과 Frequency Response Calibration은 반드시 별도로 관리한다.
SPL Calibration
목적:
절대 음압값 보정
예:
+2.6 dB
Frequency Response Calibration
목적:
주파수별 마이크 응답 보정
예:
100 Hz   +1.2 dB
1 kHz     0.0 dB
10 kHz   -2.8 dB
두 개를 하나의 개념으로 처리하지 않는다.

## 15. 마이크 진단 정보

개발 및 검증을 위해 Diagnostic 화면 또는 Debug Log에 다음 정보를 제공한다.
예:
=== Audio Input Diagnostic ===

Device:
Samsung SM-S918N

Input Device:
Built-in Microphone

Audio Source:
UNPROCESSED

Sample Rate:
48000 Hz

Channels:
1

Available Microphones:
3

Active Microphones:
2

Mic ID:
5

Group:
0

Index:
0

Mic ID:
7

Group:
0

Index:
2

Calibration Profile:
Galaxy_S23Ultra_Internal_UNPROCESSED

SPL Offset:
+2.6 dB
릴리즈 버전에서는 사용자에게 필요한 정보만 보여주고 전체 상세값은 Developer/Diagnostic 화면에서 확인 가능하게 한다.

## 16. 마이크 경로 변경 감지

다음 조건이 변경될 경우 기존 Calibration Profile을 무조건 적용하지 않는다.
Built-in Mic → USB Mic
Built-in Mic → iMM-6C
USB Audio Interface 연결
Audio Source 변경
실제 Active Microphone 조합 변경
Input Channel 변경
변경 시:
현재 입력 설정에 맞는 캘리브레이션 프로파일이 없습니다.
표시 후 다음 선택지를 제공한다.
[캘리브레이션 진행]
[보정 없이 사용]

## 17. 기존 프로파일 자동 불러오기

ToneVista 시작 또는 입력 장치 변경 시 현재 AudioRecord 설정과 일치하는 기존 Calibration Profile이 있으면 자동 적용한다.
예:
Galaxy S23 Ultra
+
Built-in Mic
+
UNPROCESSED
+
Active Mic IDs [5,7]
↓
Saved Profile Found

SPL Calibration
+2.6 dB
자동 적용한다.

## 18. 활성 마이크가 변경되는 경우

측정 도중 getActiveMicrophones() 결과가 변경될 가능성도 고려한다.
예:
처음
Mic 5 + Mic 7

↓

변경 후
Mic 5
이런 변화가 실제 측정 도중 발생할 경우 로그를 남긴다.
필요하면 Calibration 상태를 다음과 같이 표시한다.
Calibration Profile mismatch
단, 매 프레임마다 MicrophoneInfo를 조회하여 성능을 저하시키지 않는다.
적절한 시점에만 확인한다.
권장:
AudioRecord 시작 직후
Input 변경 직후
AudioRecord 재생성 직후
앱 Resume 후 입력 재초기화 시

## 19. UI 권장 명칭

일반 사용자 화면에서는 복잡한 Android 용어를 최소화한다.
권장:
내장 마이크

캘리브레이션 상태
보정 완료

보정값
+2.6 dB
Advanced 또는 Diagnostic 화면:
활성 마이크
Mic 5, Mic 7

Audio Source
UNPROCESSED

Sample Rate
48 kHz

## 20. 절대 하지 말아야 할 처리

다음 방식은 구현하지 않는다.
① 물리 위치만 보고 마이크 번호 결정
Mic 0 = Bottom
Mic 1 = Top
Mic 2 = Rear
기기별로 보장되지 않는다.
② Galaxy 모델 전체에 하나의 고정값 적용
예:
Galaxy S23 Ultra = +2.6 dB
개별 기기, Audio Source, OS, 입력 처리 상태에 따라 차이가 있을 수 있으므로 사용자별 캘리브레이션 값을 저장한다.
③ Sound Level Calibrator를 스마트폰 구멍에 직접 대고 94 dB로 간주
정식 측정 마이크와 같은 밀폐 coupling이 보장되지 않으므로 공식 캘리브레이션 방식으로 사용하지 않는다.
④ SPL 보정값으로 Frequency Response까지 보정
SPL Offset과 FR Calibration은 별도로 처리한다.

## 21. 테스트 항목

다음 테스트를 반드시 수행한다.
Test 1
Galaxy 내장 마이크를 이용하여 AudioRecord 시작
확인:
getMicrophones()
getActiveMicrophones()
결과가 정상적으로 조회되는지 확인한다.
Test 2
AudioRecord 재시작 후 활성 마이크 정보가 안정적으로 동일하게 반환되는지 확인한다.
Test 3
앱 종료 후 재실행하여 기존 Calibration Profile이 정상적으로 자동 로드되는지 확인한다.
Test 4
USB Audio Interface 연결 시 기존 Built-in Mic Calibration Profile이 적용되지 않는지 확인한다.
Test 5
USB Audio Interface 제거 후 Built-in Mic으로 돌아왔을 때 해당 프로파일이 다시 정상 로드되는지 확인한다.
Test 6
Reference SPL:
85.0 dB
ToneVista raw measurement:
82.4 dB
Calibration:
+2.6 dB
Final:
85.0 dB
가 정확하게 출력되는지 단위 테스트한다.
Test 7
Calibration 적용 후 다음 값들이 모두 동일한 보정 기준을 사용하는지 확인한다.
SPL
Peak
Average
Min
Max
RTA SPL
Recording Analysis
Saved Measurement

## 22. 로그 추가

개발 중 다음 형태의 로그를 남긴다.
[ToneVista][AudioInput]

Device = SM-S918N
Input = Built-in Mic
AudioSource = UNPROCESSED
SampleRate = 48000
Channels = 1

AvailableMicCount = 3
ActiveMicCount = 2

ActiveMicIds = [5,7]

CalibrationProfile =
Galaxy_S23Ultra_Internal_UNPROCESSED

SPLCalibrationOffset =
+2.6 dB

## 23. 최종 사용자 동작

최종 사용자는 복잡한 마이크 구조를 알 필요 없이 다음 정도의 흐름만 경험하도록 한다.
내장 마이크 선택
        ↓
ToneVista가 실제 마이크 경로 자동 확인
        ↓
기존 Calibration Profile 확인
        ↓
있음 → 자동 적용
없음 → 캘리브레이션 안내
        ↓
Reference SPL 입력
        ↓
자동 측정
        ↓
Offset 계산
        ↓
저장

## 24. 최종 목표

Galaxy에 여러 개의 물리 마이크가 존재한다는 이유만으로 각각 임의의 보정값을 적용하지 않는다.
ToneVista는 Android가 실제로 사용하는 마이크와 녹음 경로를 확인하고,
Device
+
Input Device
+
Audio Source
+
Active Microphones
+
Audio Configuration
의 조합을 하나의 측정 입력 시스템으로 간주한다.
이 측정 시스템에 대해 EMM-6 등의 기준 측정 시스템과 비교하여 SPL Calibration Offset을 생성하고 저장한다.
이를 통해 Galaxy 내장 마이크뿐 아니라 향후 다른 Android 스마트폰에서도 동일한 구조를 재사용할 수 있도록 구현한다.
