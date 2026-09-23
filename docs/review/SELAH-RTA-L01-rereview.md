# SELAH-RTA L01 독립 재검토

날짜: 2026-09-23
범위: `c28608d..77201b7`. 확인 checkout `776d2c6`; 대상 이후 변경은 요청 문서뿐이다.

## 판정

**기존 L01의 직접 계산 경로 수정은 확인했다. F01-R 종료 판정 유지. 이번 범위에서 Critical/High는 발견하지 않았다.** 새 진단 필드의 codec 왕복 누락으로 Low 1건이 남아, 원인 안내까지 완전히 종료됐다고 보기는 어렵다. 수치 승인 관문을 막는 결함은 아니다.

Production 코드는 수정하지 않았으며 검토 전후 작업 트리는 깨끗했다.

## 확인된 수정

- CAL 20–1300Hz, 평탄한 기준·대상 70dB, 배경 0dB, 각 단계 8프레임에서 `approved=18`, `supported=16`, `maxCorrection=0.0`, `pre=Fail`, `final=Fail` 유지.
- 최종 대역 부족 문구는 상한 초과 단정 대신 `SNR·CAL 범위 밖 15대역`으로 바뀌었다.
- 실제 상한 초과 입력에서 상한 원인을 기록하는 시험이 통과했다.
- CalibrationQualityTest가 제품과 같은 ThirdOctave.exactCenter를 사용하도록 바뀌었다. 19–1300Hz의 19/31 입력을 이용해 Degraded 범위 안내를 검사하도록 조정한 것은 적절하다. 기존 수치 관문을 느슨하게 하지 않았다.
- 이전 교집합 부족, 좁은 CAL, 상한 처리 내부 결손 반례는 모두 Fail·자동 적용 불가를 유지한다.

## L01-R — Low: codec 왕복이 상한 초과 원인 정보를 잃는다

**위치:**

- `app/src/main/java/kr/joa/selahrta/calibration/ProfileCodec.kt:352` encodeCurves
- 같은 파일 `:473` decodeCurves의 CalibrationOutcome 생성
- `dsp/src/main/kotlin/kr/joa/selahrta/dsp/ResponseCalibration.kt:321, 404` 기본값과 원인 분류

새 `limitedByMaxCorrection`은 encodeCurves에 기록되지 않고 decodeCurves에서도 복구하지 않는다. 디코딩 결과는 기본값인 전부 false 배열을 받는다. unsupportedReasonsKo는 false를 「상한 이전에 무효」로 해석해 SNR·CAL로 분류한다. 따라서 아직 파일 I/O가 없어도 현재 제공되는 순수 codec 함수만으로 원인 정보가 손실된다.

**독립 재현:** 기존 F01-R의 톱니형 기준 입력을 그대로 계산한 다음 `decodeCurves(encodeCurves(outcome))` 수행.

```text
ROUNDTRIP flags=67->0 maskEqual=true
beforeLimit=true afterLimit=false
beforeVerdict=Fail afterVerdict=Fail
```

실제 상한 초과 표시 67개가 사라지고, 상한 원인이 안내에서 없어져 SNR·CAL 원인으로 분류된다. 보정 valid 마스크와 Fail 판정은 유지되므로 승인 우회나 수치 손상으로 분류하지 않는다.

**사용자 영향:** 결과를 직렬화했다가 다시 읽어 진단/비교할 때 같은 결과의 원인 안내가 달라진다. 아직 파일 바인딩이 없으므로 실사용 저장 파일에서 발생했다고 주장하지 않는다.

**권장 수정:** 진단 필드를 codec에 저장·복원하고 배열 길이를 검증한다. 과거 포맷이 원인 정보를 갖지 않는 경우에는 전부 false로 「상한 아님」을 선언하지 말고 「원인 정보 없음」으로 구분한다. 복구 가능한 정보로 재계산하는 방식도 가능하지만 원래 적용 설정과 마스크에 근거해야 한다.

**필요 회귀 테스트:** 실제 상한 초과 결과의 codec 왕복에서 원인 마스크·안내 보존, CAL만 제한된 결과의 왕복에서 상한 원인 없음 유지, 원인 필드가 없는 구형 문자열의 명시적 처리, 길이가 다른 원인 배열의 거절. verdict 보존도 함께 확인한다.

## 요청서 §6의 사실 정정 — 최종 유효점 0개는 세션 경로에서 생성 가능하다

「calibrateFromSession이 먼저 거절하므로 그런 outcome을 만들 수 없다」는 설명은 정확하지 않다. 이 함수가 검사하는 정규화 지원 점은 보정 상한 적용 전의 지원이다. 상한이 최종 보정을 전부 무효화해도 정규화 점은 남을 수 있다.

같은 톱니형 기준 세션에서 허용되는 설정 `CalibrationSettings(maxCorrectionDb=0.000001)`로 계산했다. outcome을 손으로 조립하지 않았다.

```text
ZERO_VALID generated=true norm=40 valid=0 final=Fail
```

이 작은 상한은 현장 권장값이 아니라 분기 도달 가능성을 보여 주는 시험 설정이다. 현재 최종 함수가 이 경우를 **정상적으로 Fail 처리**하므로 새 production 결함은 아니다. 저장 통합을 기다리지 않고 실제 계산 경로를 이용한 회귀 단언을 추가할 수 있다. 시험에서 `calibrateFromSession 성공`, `normalizeSupportPoints > 0`, `correction.validCount == 0`을 전제로 확인한 뒤 최종 Fail을 단언하면 된다.

## 검증 범위

Kotlin 2.2.20/JBR 직접 컴파일, JUnit 4.13.2로 **160개 대상 시험 모두 통과**했다.

실행 클래스: BoundaryProbeTest, PostLimitProbeTest, ApprovalProbeTest, RecheckProbeTest, CalibrationSessionTest, CalibrationQualityTest, SpectrumAverageTest, ResponseCalibrationTest, MeasuredProfileTest, ProfileCodecTest, PipelineProbeTest.

BoundaryProbeTest의 CAL 경계 169조합에서 6·12·24점/옥타브 지원 대역 수 불일치 출력은 없었다. 별도 `L01-RoundTripProbe.kt`로 위 두 관측을 확인했다.

Gradle 전체 build/lint, 구현자 보고 675개 전체 시험 및 변이 시험은 독립 재실행하지 않았다. 실제 음향·USB·기기 화면·파일 저장 검증은 하지 않았다.

## 다음 단계에 대한 의견

교정 마법사와 실제 캡처·저장 연결 작업을 진행하는 것은 합리적이다. 다만 다음 사항은 이번 종료 판단에 포함되지 않는다:

- 실제 저장·자동 적용이 judgeCalibration의 최종 판정을 사용하는지.
- referenceCalApplied 선언을 실제 bin 보정 실행의 증거로 대체하고 이중 적용을 막는지.
- 실제 FFT bin 적용의 보간·valid 경계가 지원 대역 정의와 일치하는지.
- 8프레임/2dB/60% 및 정규화 충분성 정책의 실측 검증.
- 저장 형식 계약, 왕복 일관성, 비교 화면과 저장 데이터의 일치.

이번 Low는 그 작업을 시작하지 못하게 할 차단 사유는 아니지만, codec을 영속화 경로에 연결하기 전에는 해결하는 편이 좋다.
