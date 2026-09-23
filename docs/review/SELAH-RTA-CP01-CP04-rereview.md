# SELAH-RTA CP01–CP04 독립 재검토

검토일: 2026-09-23

## 판정

**종료 승인 보류. Critical 없음. High 2건(같은 CP02의 정규화 결함과 기준 SNR 미완료), Medium 1건(CP01 최소 표본 관문)이 남는다.** CP03은 이번 입력 검증 범위에서 종료 가능하다. CP04의 잘못된 중심값 차감 구현 제거는 확인했으며, 실제 캡처 경로의 CAL 적용 증명은 별도 통합 검증 대상이다.

검토 대상은 `5f33ea4..aa6a29b`의 9파일(+938/−152), 읽은 checkout HEAD는 `47ef620`. aa6a29b 이후 HEAD까지 차이는 검토 문서 3개뿐이다. 작업 트리는 검토 전후 깨끗하며 production 코드는 수정하지 않았다. 요청서의 완료·시험 주장은 검증 대상으로 취급했다.

## RCP01 — High: 정규화의 공통 유효 지지구간과 실패 관문이 없다

위치: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/ResponseCalibration.kt:173–181, 318–327`; `CalibrationSession.kt:280–301`.

두 곡선은 같은 300–3000Hz 명목 대역을 쓰지만, 각자 다른 valid 점으로 평균한다. CAL 범위는 기준에만 적용되므로 두 평균의 주파수 지지구간이 달라진다. 또 정규화할 점이 0개여도 offset=0으로 계속 진행하고, quality는 이 사실을 모른다.

독립 probe로 두 경로 모두 재현했다(각 단계 동일 프레임 8개, referenceCalApplied=true, dspVerifiedBySignal=true).

1. 기준과 대상 입력을 **완전히 동일한** `50 + 4*log2(f/1000)` 곡선으로 하고 CAL 범위만 1000–16000Hz로 설정: 품질 `Pass`, 유효 보정 점 48개, 보정값 **−3.500000000000007dB**. 동일 입력의 상대 주파수 보정은 0이어야 한다. 유효 마스크 전파 자체는 성공했지만, 마스크가 서로 다른 평균에 들어가 인공 오프셋을 만든다.
2. 기준은 평탄 60dB, 대상은 평탄 50dB. 대상 배경은 250–3500Hz에서 50dB, 나머지는 0dB. CAL 범위 20–20000Hz: usable **19/31**, 품질 **Pass**, 정규화 `pointsUsed=0`, 유효 보정 점 **64개**, 보정값 **+10.0dB**. 상대 응답 정규화가 불가능한데도 단순 녹음 게인 차이가 보정으로 남는다.

사용자 영향: 잘못된 상대 응답 곡선이 Pass 품질과 함께 반환된다. 현재 파일/마법사 연결 전이므로 실제 저장·적용을 관찰한 것은 아니지만, 그대로 연결하면 측정 레벨에 인공 보정이 들어간다.

권장 수정: 보간한 두 곡선의 **공통 valid 교집합**과 동일 가중치로 정규화 기준을 계산한다. 공통 정규화 지지구간이 없으면 진단 곡선과 별개로 승인 불가로 처리한다. 충분성은 보간 점 개수만이 아니라 원래 유효 대역 수·주파수 범위/분포로 정의하고, 정규화 결과를 품질 판정 및 저장 승인에 전달한다. 내부/기준 곡선의 진단 표시 범위는 유지해도 된다.

필요 회귀 시험: 위 두 반례, 서로 다른 마스크의 동일 곡선→0dB, 정규화 유효점 0개→승인 불가, 한쪽 가장자리에만 잔존하는 점, 마스크 밖 값을 크게 바꿔도 정규화 결과 불변, 평활·codec 왕복 후 승인 상태 유지.

## RCP02 — High: 기준 경로 SNR을 판정할 수 없어 CP02가 미완료다

위치: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSession.kt:280–286, 312–327`.

qualityFromSession은 대상 신호와 단일 noiseDb만으로 BandNoise를 만든다. calibrateFromSession은 그 대상 usable을 기준에도 복사하고 CAL 범위만 추가한다. 기준 경로의 배경 또는 SNR을 받을 자리가 없다. 따라서 대상 경로가 충분히 조용하면, 기준 경로의 신호가 배경과 구별되지 않는 경우에도 이를 기준 valid에서 제외할 수 없다. 앞뒤 기준이 안정적인 배경이면 drift 검사도 대체 수단이 되지 않는다.

이는 새로 숨은 결함이 아니라 요청서 §3·§7에서도 인정한 **이전 CP02의 미해결 부분**이다. 실제 소리로 재현했다고 주장하지 않는다. 동일한 현재 API 입력에 대해 기준 배경만 좋은 경우/나쁜 경우를 표현할 수 없다는 데이터 흐름상의 근거다.

사용자 영향: 기준 마이크의 잡음 바닥이 대상 마이크 주파수 특성으로 기록될 수 있다. 대상 SNR 통과와 CAL 파일 범위는 기준 측정의 신뢰도 증명이 아니다.

권장 수정: 기준 전후와 대상의 경로별 배경/SNR을 받는다. 기준 전후의 신뢰도, 대상 신뢰도, CAL 범위를 승인용 교집합에 반영하고, 기준 SNR 미측정이면 검증 완료 Pass를 부여하지 않는다. 실제 측정 UI가 나중에 생기더라도 현재 API에서 unknown을 나타낼 수 있어야 한다.

필요 회귀 시험: 대상 SNR 충분/기준만 미달, 기준 전후 중 한쪽만 미달, 기준 배경 미측정, 양쪽 충분, 기준 저신뢰 구간이 정규화 대역에 걸리는 경우. 기준 배경 변화가 mask·정규화·최종 verdict까지 도달하는지 확인한다.

## RCP03 — Medium: 최소 프레임 관문이 버린 프레임까지 센다

위치: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationSession.kt:188`; `CalibrationQuality.kt:241–248`.

세 단계마다 평탄한 0dB 프레임 3개, 50dB 프레임 2개, 100dB 프레임 3개를 기록했다. 중앙값 필터는 50dB 2개만 유지한다. 그러나 SessionResult.minFramesPerStep은 totalFrames에서 계산되어 8이다. 대상 배경 0dB, DSP 검증 true 조건에서 **kept=2 / total=8 / minimum=8 / Pass**가 재현됐다. noStableFrames=false이고 남은 두 프레임의 spread=0이라 다른 검사도 막지 못한다.

사용자 영향: 평균과 반복성 판단에 실제 사용된 표본이 정책상 최소 8개에 못 미치는데도 충분한 것으로 표시된다. 문턱을 정책 객체로 옮긴 것만으로는 해결되지 않는다.

권장 수정: 승인용 최소 수는 각 단계의 keptFrames로 계산하고, total/dropped는 진단 통계로 따로 보존한다. 향후 시간 기준을 추가하더라도 버려진 구간을 유효 지속 시간에 포함하지 않는다.

필요 회귀 시험: 위 반례→Pass 금지, 총 8개 중 유지 2개와 유지 8개 구별, 세 단계 중 한 단계만 부족, 7/8개 경계, 필터 폴백(noStableFrames) 경로 유지.

## 이전 수정 확인

- CP01: 세 단계 최악 spread 전달, noStableFrames 거절, 광대역 상쇄를 막는 대역별 drift를 확인했다. 기존 probe는 불안정 기준과 스펙트럼 이동 모두 Fail이다. 다만 RCP03 때문에 최소 표본 충분성은 미완료다.
- CP02: 대상 저SNR/CAL 범위 밖이 correction.valid=false가 되는 것은 확인했다. 정규화와 기준 SNR까지 이어지지 않아 전체 종료는 불가하다.
- CP03: NaN 입력이 실패로 돌아오고, finite/축/메타데이터 검증 및 encode 축 일치를 확인했다. 이번 대상 테스트에서 재발은 발견하지 않았다.
- CP04: applyMicCalibration의 잘못된 밴드 중심 차감이 제거되고, referenceCalApplied=false면 실패하는 것을 확인했다. **Boolean은 실제 bin 보정 실행이나 이중 적용 방지의 증거가 아니다.** 실제 bin 보정 경로→세션까지 검증하기 전에는 이 부분의 end-to-end 완료를 주장할 수 없다.

## 요청서 질문 답변

1. **Fail인데 correctionMax=6.73이 진단으로 남는 것**: 허용 가능하다. 진단 성공 Result와 저장/자동 적용 승인은 분리하고, 실패 사유를 표시해야 한다. 현재 파일 바인딩이 없으므로 실제 저장 차단까지 시험한 것은 아니다.
2. **최소 8프레임/대역 drift 2dB**: 코드 정책으로 분리한 것은 적절하지만, 보정 신뢰도의 검증된 기준으로 승인할 근거는 없다. 유지된 표본 수, 유효 시간, FFT/hop에 따른 표본 상관, 반복 측정 분산을 함께 고려해 실측으로 정해야 한다. 근거 없이 다른 숫자를 제안하지 않는다.
3. **정규화에 몇 점이 남아야 하는가**: 최소한 0개는 반드시 거절해야 한다. 여러 보간 점이 한 원래 대역에서 생길 수 있으므로 pointsUsed의 수만으로 충분성을 증명할 수 없다. RCP01의 공통 지지구간 계약부터 필요하다.
4. **referenceCalApplied의 더 나은 경계**: 실제 bin 보정 함수가 생성하는 제한된 생성자의 결과 타입을 세션에 전달하고, CAL 식별자·샘플레이트·FFT 설정·적용 횟수/세대를 함께 보존하는 방식이 낫다. 다른 CAL이나 raw 입력의 혼입과 이중 적용을 테스트한다. 이름만 바꾸거나 호출자가 true를 넣는 것으로는 증명이 되지 않는다.

## 독립 실행 근거와 한계

Kotlin 2.2.20/JBR의 직접 컴파일 후 JUnit 4.13.2로 아래 **136개 시험 통과**를 확인했다. Gradle 전체 빌드나 lint를 독립 재실행한 결과는 아니다.

- CalibrationSessionTest, CalibrationQualityTest, SpectrumAverageTest, ResponseCalibrationTest
- MeasuredProfileTest, ProfileCodecTest, PipelineProbeTest

별도 `CP01-CP04-RecheckProbe.kt`의 관측:

```text
KEPT_GATE kept=2 total=8 minimum=8 verdict=Pass
ZERO_NORMALIZATION verdict=Pass usable=19/31 pointsUsed=0 validCount=64 correction=10.0
UNEQUAL_SUPPORT verdict=Pass identicalInputs=true correction=-3.500000000000007 valid=48
```

기존 probe의 변경 후 관측도 확인했다: 불안정 기준 Fail, 스펙트럼 이동 Fail(진단 6.73374204940048 유지), 저신뢰/CAL 밖 마스크 false, NaN decode 실패, CAL 선언 false 거절.

구현자의 651개 전체 시험 및 변이 11건 실행 주장은 이번에 그대로 재검증하지 않았다. 실제 음향 측정, USB 장치, 저장 파일 바인딩, 교정 마법사 및 비교 카드 실기기 UI는 이번 검증 범위에 포함하지 않았다. 통과한 기존 시험 136개가 위 세 반례를 막는다는 뜻은 아니다.
