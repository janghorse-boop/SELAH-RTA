# SELAH-RTA F01-R 독립 재검토

날짜: 2026-09-23
범위: `563c3ed..69cf550` (6파일 +352/−1)
검사 checkout: `c28608d`. 대상 이후 변경은 이번 요청 문서 1개뿐이다.

## 판정

**F01-R 종료 가능. 이번 변경 범위에서 Critical/High 이슈는 발견하지 않았다.** 이전의 내부 결손 반례를 최종 승인에서 차단하며, 정상 평탄 입력은 통과한다. 차단 항목이 아닌 **Low 1건(원인 안내 오류)**이 남는다.

이는 계산·판정 함수에 대한 범위 한정 결론이다. 아직 없는 마법사/저장 경로와 실제 CAL 적용, 실기기 음향 검증까지 승인한 것은 아니다. Production 코드는 수정하지 않았고 작업 트리는 검토 전후 깨끗하다.

## 확인한 수정

`ResponseCalibration.kt:349`의 supportedBands는 각 원래 1/3옥타브 경계 안에 축 점이 존재하고 그 점이 전부 유효할 때 대역을 센다. `CalibrationQuality.kt:418`에서 그 비율을 기존 minUsableBandRatio와 비교한다. 입력 보고의 유효 비율과 별도로 계산 후 결손을 검사하므로, 앞선 양끝만 검사하던 누락은 수정됐다.

독립 실행 결과:

```text
INTERSECTION approved=8 verdict=Fail auto=false
NARROW verdict=Fail auto=false
POST_LIMIT approved=30/31 pre=Pass final=Fail auto=false
valid=49/120 centers=3/31
span=25.19842099789747..19330.54592372118 norm=40
```

보정 곡선 관측값은 이전과 같고, 최종 승인이 Pass에서 Fail로 바뀌었다. 정상 평탄 입력의 지원 31대역/최종 Pass 시험도 통과했다. CalibrationSessionTest가 이제 judgeCalibration의 판정을 직접 단언한다.

## L01 — Low: 모든 지원 부족을 보정 상한 초과로 단정한다

**위치:** `dsp/src/main/kotlin/kr/joa/selahrta/dsp/CalibrationQuality.kt:420–426`.

지원 대역 비율이 부족할 때 항상 「보정량이 상한(12dB)을 넘는 자리가 많다는 뜻입니다」라고 안내한다. 하지만 CAL 범위, SNR 마스크, 보간 경계도 지원 대역을 줄인다.

**재현:** 기준·대상은 모든 대역 70dB, 양쪽 배경 0dB, 단계별 8프레임, DSP 확인 true, CAL 적용 선언 true. CAL 범위만 20–1300Hz로 제한한다.

```text
CAL_ONLY approved=18 supported=16 maxCorrection=0.0 pre=Fail final=Fail
```

모든 보정값이 0dB여서 상한을 넘은 점이 없는데도 상한 초과 문구가 나온다. 실패 판정 자체는 맞다. 참고로 실제 세션의 정확 중심 주파수에서는 최저 대역이 20Hz 미만이어서 이 CAL 범위의 입력 유효 수는 19가 아니라 18이다.

**사용자 영향:** CAL 범위가 원인인데 레벨이나 보정 상한 문제로 오해하고 잘못된 재측정을 할 수 있다. 현재 화면 연결 전이므로 실제 UI에서 관찰한 것은 아니며, 판정 함수가 반환한 문구를 확인했다.

**권장 수정:** 일반적인 「계산 후 유효 지원이 부족합니다」로 안내하거나, CAL/SNR/상한 초과 등의 원인 정보를 보존해 실제 해당 원인만 표시한다. 수치 관문을 바꿀 필요는 없다.

**필요 회귀 테스트:** 보정값 전부 0인 CAL 제한 입력에서 상한 초과로 단정하지 않는지, 실제 상한 초과 입력에서는 해당 원인 안내가 나오는지 검사한다.

## 축 해상도와 시험 충분성

저장소의 해상도 시험은 모든 점이 유효한 평탄 입력에서 6점/옥타브와 24점/옥타브를 비교한다. 이를 보완해 독립 probe에서 동일한 평탄 입력에 **CAL 경계 169조합**을 주고 6·12·24점/옥타브의 supportedBands 수를 비교했다.

- 하한 대역 index 0–12, 상한 index 18–30의 모든 조합.
- 각 범위는 정확 중심 주파수에 하한 0.999, 상한 1.001을 곱해 정했다.
- 이 범위에서는 지원 대역 수 차이를 발견하지 않았다.

이 결과는 검사한 CAL 경계 조합의 일관성이다. 임의의 응답 모양·축 배치·상한 교차점에 대한 수학적 불변성 증명은 아니다. 현재 supportedBands는 **축 점에서 평가한 지원**이라는 정의이며, 나중에 실제 FFT bin 적용과 연결할 때에는 대역 경계/축 점 사이의 보간과 마스크 처리도 통합 검증해야 한다.

요청서 §4의 유효점 0개 시험은 세션 입구의 거절을 검사하므로 최종 함수의 validCount==0 분기 회귀와 정확히 같지는 않다. 저장 통합 단계에서 최종 outcome의 전부 무효/부분 무효/정상 결과를 직접 단언하는 시험을 보강하면 좋다. 현재는 코드 분기에 해당 거절이 명시돼 있고, 별도 차단 이슈로 보지는 않는다.

## 남은 통합·정책 위험

- judgeCalibration을 production 저장·자동 적용 경로가 아직 사용하지 않는다. 향후 이 최종 결과를 반드시 사용한다는 통합 시험이 필요하다.
- referenceCalApplied는 호출자가 선언한 Boolean이며 실제 bin 보정 실행이나 이중 적용 방지 증명이 아니다.
- 8프레임/2dB 및 60% 정책이 이번 수정으로 실측 검증된 것은 아니다.
- 정규화 충분성은 0점 거절 이외의 폭·분포·반복성 기준이 미확정이다. 최종 지원 비율 검사는 그와 다른 조건이다.
- 실제 음향·USB·CAL 적용·마법사·저장 파일·비교 화면은 미검증 상태다.

## 독립 실행 범위

Kotlin 2.2.20/JBR 직접 컴파일 후 JUnit 4.13.2로 **154개 시험 모두 통과**했다:

ApprovalProbeTest, RecheckProbeTest, CalibrationSessionTest, CalibrationQualityTest, SpectrumAverageTest, ResponseCalibrationTest, MeasuredProfileTest, ProfileCodecTest, PipelineProbeTest.

기존 RCPF-PostLimitProbe를 현재 소스로 실행했고, 별도 `F01R-BoundaryProbe.kt`로 CAL 경계와 안내 문구를 확인했다. 추가 probe를 재컴파일할 때 scratch Kotlin 모듈 메타데이터 충돌이 한 번 발생했으며, 별도 출력 디렉터리에 DSP 소스와 함께 다시 컴파일한 성공 실행만 결과에 사용했다.

구현자가 보고한 670개 전체 시험, Gradle build/lint, 변이 2건은 이번에 독립 재실행하지 않았다. 위 154개 결과와 독립 probe 결과를 그 전체 검증 결과로 확대하지 않는다.
