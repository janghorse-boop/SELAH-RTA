# SELAH RTA --- UMC404HD + Dayton EMM-6 외장 측정 입력 개발 지시서

## 1. 목적

SELAH RTA Android 앱에서 스마트폰 내장 마이크 외에 USB Audio Class 기반
오디오 인터페이스와 측정 마이크를 사용할 수 있도록 확장한다.

### 기준 장비

-   측정 마이크: Dayton Audio EMM-6
-   오디오 인터페이스: Behringer U-PHORIA UMC404HD
-   연결: EMM-6 → XLR → UMC404HD → USB 2.0 Type-B to Type-C 데이터
    케이블(3 m) → Android 스마트폰
-   UMC404HD: 전용 외부 전원 어댑터 사용
-   EMM-6: 팬텀전원 필요. UMC404HD의 +48 V PHANTOM을 사용자가 물리적으로
    ON
-   앱은 팬텀전원 및 아날로그 GAIN을 제어하지 않는다.
-   UMC404HD 전용으로 하드코딩하지 말고 다른 표준 USB Audio Class 장치로
    확장 가능한 구조로 구현한다.

## 2. 신호 경로

``` text
스피커/공간
  ↓
Dayton EMM-6
  ↓ XLR
UMC404HD MIC INPUT 1~4
  ├─ +48 V PHANTOM: 하드웨어에서 ON
  ├─ GAIN: 하드웨어에서 조절
  └─ ADC
  ↓
USB-B → USB-C 데이터 케이블
  ↓
Android
  ↓
SELAH RTA
  ↓
USB Audio Input → Channel Selector → PCM → FFT/RTA
  → EMM-6 Calibration → Smoothing/Analysis/Display
```

## 3. 필수 기능

1.  USB 오디오 장치 연결/해제 감지
2.  Android USB 사용 권한 처리
3.  내장 마이크 / USB 오디오 입력 선택
4.  USB 장치명 및 실제 스트림 정보 표시
5.  접근 가능한 입력 채널 탐색 및 채널 선택
6.  선택 채널 실시간 Peak/RMS dBFS 표시
7.  클리핑 감지
8.  실제 sample rate/channel count/PCM format 기반 DSP 처리
9.  EMM-6 캘리브레이션 파일 불러오기
10. 캘리브레이션 ON/OFF
11. 주파수별 보정값 보간 및 FFT 결과에 적용
12. 마지막 장치/채널/캘리브레이션 설정 저장
13. 측정 중 장치 분리 시 안전하게 측정 종료

## 4. 입력 장치 UI

``` text
입력 장치

● 스마트폰 내장 마이크
○ USB 오디오 인터페이스
   BEHRINGER UMC404HD 192k

상태: 연결됨
입력 채널: Input 1
샘플레이트: 48,000 Hz
캘리브레이션: EMM-6_xxxxxx.txt
```

USB 장치가 없으면 `연결된 USB 오디오 장치 없음`을 표시한다.

측정 중 USB가 분리되면 다른 입력으로 임의 전환하지 말고: \> USB 오디오
장치 연결이 해제되어 측정을 종료했습니다.

## 5. 입력 채널 선택

UMC404HD가 멀티 입력 장치이므로 사용자가 측정 채널을 선택할 수 있어야
한다.

``` text
측정 입력 채널
● Input 1
○ Input 2
○ Input 3
○ Input 4
```

단, UMC404HD라는 이유로 4채널을 하드코딩하지 않는다. 실제 Android/USB
오디오 스트림에서 접근 가능한 채널 수를 기준으로 UI를 만든다.

각 채널의 레벨 확인 기능 권장:

``` text
Input 1  ███████████░   -18.2 dBFS
Input 2  ░░░░░░░░░░░   -∞
...
```

선택된 채널만 현재 RTA 분석에 사용하되 향후 2채널 비교 측정이 가능하도록
내부 구조는 멀티채널 확장을 고려한다.

## 6. 레벨 및 클리핑

최소 표시: - Peak dBFS - RMS dBFS - CLIP

클리핑 시: \> 입력 신호가 클리핑되고 있습니다. UMC404HD의 GAIN을
낮춰주세요.

GAIN은 앱에서 제어하지 않는다.

## 7. EMM-6 팬텀전원 안내

EMM-6는 팬텀전원이 필요한 측정 마이크다. 앱에서 EMM-6 프로파일을
선택했을 때 다음 안내를 제공한다.

``` text
Dayton EMM-6 사용 안내

1. EMM-6를 XLR로 UMC404HD MIC 입력에 연결하세요.
2. UMC404HD 전원을 켜세요.
3. UMC404HD의 +48 V PHANTOM을 켜세요.
4. GAIN을 조절하여 클리핑을 피하세요.
```

중요: - 앱이 USB 신호만으로 팬텀전원 ON/OFF를 확정할 수 있다고 가정하지
않는다. - 팬텀전원을 앱에서 제어하지 않는다. - 신호가 없거나 지나치게
작으면 `마이크 연결, +48 V 팬텀전원, GAIN을 확인하세요`라고 안내한다.

## 8. 샘플레이트

기본 측정값은 우선 48 kHz를 권장하지만 DSP에 48 kHz를 하드코딩하지
않는다. 실제 열린 스트림의 sampleRate를 FFT 주파수 축 계산에 사용한다.

금지: - 실제 44.1 kHz를 48 kHz로 가정 - 리샘플링 여부를 무시 - 지원하지
않는 포맷을 성공으로 간주

진단 화면:

``` text
USB AUDIO DIAGNOSTICS
Device       BEHRINGER UMC404HD 192k
Input        Channel 1
Sample Rate  48000 Hz
Channels     (실제 스트림 값)
Encoding     PCM ...
Buffer       ...
Calibration  EMM-6_xxxxxx.txt
```

## 9. EMM-6 캘리브레이션 파일

### 9.1 파일 불러오기

Android Storage Access Framework를 이용해 사용자가 자신의 EMM-6 개별
calibration 파일을 선택한다. 불필요한 전체 저장소 권한을 요구하지
않는다.

``` text
마이크 캘리브레이션

마이크: Dayton Audio EMM-6
파일: EMM-6_xxxxxx.txt

[캘리브레이션 파일 불러오기]
적용 [ON]
상태: ✓ 정상 적용 중

[파일 정보] [파일 제거]
```

필요하면 persistable URI permission을 사용하여 앱 재실행 후에도 접근
가능하게 한다.

### 9.2 데이터 모델

오디오 인터페이스와 측정 마이크를 분리한다.

``` text
InputDeviceProfile
- deviceId
- deviceName
- vendorId
- productId
- selectedInputChannel
- preferredSampleRate

CalibrationProfile
- id
- displayName
- microphoneManufacturer
- microphoneModel
- serialNumber (optional)
- sourceFileName
- sourceUri
- frequencyPoints[]
- correctionValuesDb[]
- importedAt
- enabled
```

즉 `UMC404HD = EMM-6 calibration`으로 묶지 말고,
`UMC404HD / Input 1 / Dayton EMM-6 / Serial XXXXX / calibration file`로
각각 관리한다.

### 9.3 파서

Dayton EMM-6 실제 파일을 우선 지원하되 확장 가능한 parser 구조로 만든다.

``` text
Frequency(Hz)    Value(dB)
20               ...
25               ...
31.5             ...
...
20000            ...
```

처리: - 공백/Tab 구분 - 빈 줄/헤더/주석 - CRLF/LF - 소수점 - 주파수 정렬
및 중복 검증 - NaN/Infinity/비정상 값 차단 - 최저/최고 주파수 확인 -
인코딩 오류 처리

불러온 뒤 파일명, 데이터 포인트 수, 최저/최고 주파수, 정상 여부를
표시한다.

### 9.4 보정부호 검증 --- 중요

두 번째 열을 무조건 `더할 correction`이라고 가정하지 않는다.

Dayton EMM-6 원본 파일 형식을 확인하여 값이 correction인지
response/deviation인지, 보정할 때 더해야 하는지 빼야 하는지 검증 후
구현한다. 불명확하면 임의 부호로 측정값을 변경하지 않는다.

권장 구조:

``` text
CalibrationFileParser
 ├─ DaytonEmm6Parser
 └─ GenericFrequencyCorrectionParser
```

### 9.5 보간

FFT bin과 calibration point가 일치하지 않으므로 주파수의 log10 축에서
인접 포인트 사이 선형 보간을 우선 검토한다.

``` text
rawSpectrumDb(f)
  → calibrationCorrection(f)
  → correctedSpectrumDb(f)
```

실제 +/- 부호는 파일 형식 검증 결과를 따른다. 파일 범위 밖에서는 무리한
외삽을 하지 않는다.

## 10. DSP 순서

``` text
AudioInputSource
 → PCM Buffer
 → Channel Extraction
 → Window Function
 → FFT
 → Magnitude
 → dB Conversion
 → Microphone Calibration
 → Smoothing / Display Processing
 → RTA / Analysis UI
```

Raw spectrum / Calibrated spectrum / Smoothed display spectrum을 가능한
한 분리한다.

## 11. 캘리브레이션 ON/OFF

파일을 삭제하지 않고 적용만 즉시 켜고 끌 수 있어야 한다.

OFF = 원본 측정값 ON = 동일 입력에 캘리브레이션 적용

향후 보정 전/후 오버레이 비교가 가능하도록 설계한다.

## 12. 입력 추상화

기존 코드가 스마트폰 마이크에 직접 결합되어 있다면 리팩터링한다.

``` text
AudioInputSource
 ├─ BuiltInMicrophoneSource
 └─ UsbAudioInputSource
```

공통 AudioFrame 예:

``` text
- PCM samples
- sampleRate
- channelCount
- selectedChannel
- timestamp
- sourceMetadata
```

MeasurementEngine은 입력 장치 종류를 몰라도 되게 한다.

## 13. 측정 시작 전 체크

``` text
외부 측정 준비

✓ UMC404HD 연결됨
✓ Input 1 선택됨
✓ 오디오 신호 감지됨
✓ EMM-6 캘리브레이션 적용됨

사용자 확인:
• UMC404HD +48 V PHANTOM ON
• GAIN 클리핑 없음

[측정 시작]
```

팬텀전원은 앱이 실제 확인한 것처럼 ✓ 표시하지 말고 `사용자 확인`
항목으로 둔다.

## 14. 오류 처리

-   권한 거부: `USB 오디오 장치를 사용하려면 연결 권한이 필요합니다.`
-   신호 없음:
    `입력 신호가 감지되지 않습니다. 마이크 연결, UMC404HD의 +48 V 팬텀전원 및 GAIN을 확인해주세요.`
-   클리핑: `입력 신호가 너무 큽니다. UMC404HD의 GAIN을 낮춰주세요.`
-   파일 오류:
    `캘리브레이션 파일을 읽을 수 없습니다. 파일 형식과 데이터를 확인해주세요.`
-   장치 분리: `USB 오디오 장치 연결이 해제되어 측정을 종료했습니다.`

## 15. 실기기 테스트

실제 다음 구성으로 검증한다.

``` text
Dayton EMM-6
→ XLR
→ UMC404HD
→ 전용 전원 어댑터
→ +48 V ON
→ USB-B to USB-C 2.0 데이터 케이블 3 m
→ Android 스마트폰
→ SELAH RTA
```

필수 테스트: 1. 앱 실행 전/후 USB 연결 2. USB 권한 승인/거부 3. 실제
접근 가능한 모든 입력 채널 매핑 4. 선택 채널별 신호 확인 5. 측정 중
분리/재연결 6. +48 V OFF/ON 시 EMM-6 신호 확인 7. 낮은/적정/과도한 GAIN
및 클리핑 확인 8. 정상/비정상 calibration 파일 import 9. Calibration
ON/OFF 비교 10. 앱 재실행 후 설정 복원 11. 백그라운드/포그라운드 전환
12. 장시간 측정 시 오디오 드롭, 메모리 누수, ANR 확인

## 16. CalibrationEngine 단위 테스트

테스트용 보정 데이터를 만들어 다음을 검증한다. - calibration point에서
정확한 값 - 중간 주파수 로그 보간 - 범위 밖 정책 - ON/OFF - 잘못된 파일
차단 - 보정 외 FFT 결과 불변 - sampleRate 변경 시 주파수 bin과 보정 매핑
정확성

## 17. 완료 조건(Definition of Done)

다음 조건을 모두 충족해야 완료로 본다.

-   UMC404HD가 Android에서 정상 입력 장치로 인식됨
-   EMM-6 +48 V 사용 시 실시간 RTA 측정 가능
-   실제 접근 가능한 입력 채널 선택 가능
-   선택 채널이 FFT에 정확히 매핑됨
-   Peak/RMS/CLIP 확인 가능
-   EMM-6 calibration 파일 import/검증/저장 가능
-   calibration ON/OFF 가능
-   보간 및 보정 방향에 대한 단위 테스트 통과
-   USB 분리/권한 거부/파일 오류에서 크래시 없음
-   내장 마이크 기존 기능 회귀 없음
-   특정 UMC404HD 제품명에 의존하지 않는 확장 가능한 입력 구조

## 18. 구현 우선순위

### P0

USB 장치 감지 → 권한 → PCM 입력 → 채널 선택 → 기존 RTA 연결

### P1

레벨/클리핑 → EMM-6 파일 import → 파싱 → 보정 → ON/OFF

### P2

진단 화면 → 프로파일 저장 → UX 개선 → 다른 USB 인터페이스 호환성 테스트

## 19. Claude Code 작업 원칙

1.  기존 SELAH RTA 구조를 먼저 분석한 후 최소 침습적으로 변경한다.
2.  기존 내장 마이크 측정 기능을 깨뜨리지 않는다.
3.  USB 장치명이나 UMC404HD 채널 수를 하드코딩하지 않는다.
4.  Android에서 실제 확보한 스트림 포맷을 기준으로 DSP를 수행한다.
5.  calibration 보정부호는 Dayton 파일 형식을 확인하기 전 추측하지
    않는다.
6.  가능한 로직에는 단위 테스트를 추가한다.
7.  구현 후 실제 UMC404HD + EMM-6에서 검증할 수 있는 진단 로그/화면을
    남긴다.
8.  변경사항은 기능 단위로 커밋 가능하도록 분리한다.
