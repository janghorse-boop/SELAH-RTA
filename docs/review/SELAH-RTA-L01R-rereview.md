# SELAH-RTA L01-R 독립 재검토

날짜: 2026-09-23
범위: `776d2c6..1881fd2` (요청서 기준 7파일 +356/−4)
검사 checkout: `64b0d46`. 대상 이후 변경은 이번 요청 문서 1개뿐이다.

## 판정

**L01-R 종료 가능. 이번 검토 범위에서 새 Critical/High/Medium/Low 이슈는 발견하지 않았다. F01-R 종료 판정도 유지한다.**

새 필드의 codec 보존, 원인 정보 없음의 명시적 표현, 잘못된 입력 거절, 기존 최종 승인 판정을 확인했다. Production 코드는 수정하지 않았고 검토 전후 작업 트리는 깨끗했다.

## 독립 확인

기존 톱니형 기준 세션을 실제 계산 경로로 처리한 뒤 encodeCurves/decodeCurves를 왕복했다.

```text
ROUNDTRIP flags=67->67 maskEqual=true
beforeLimit=true afterLimit=true
beforeVerdict=Fail afterVerdict=Fail
CODEC_CHECKS mask_and_reason_preserved=true
absent_is_unknown=true false_is_known=true malformed_rejected=3
ZERO_VALID generated=true norm=40 valid=0 final=Fail
```

- 상한 표시 개수뿐 아니라 BooleanArray 전체와 unsupportedReasonsKo 목록이 같은지 독립 check로 확인했다.
- `correction.limited`를 제거한 문자열은 null로 읽히고 「까닭 정보 없음」을 반환한다. 재인코딩해도 해당 필드는 생기지 않는다.
- 길이가 맞는 전부 0 배열은 null과 구분되는 알려진 false 배열로 복원된다.
- 빈 값, 한 글자 길이의 배열, 축 길이와 같지만 `2`로 채운 배열은 모두 Result.failure로 거절된다.
- 실제 상한 초과 결과의 마스크와 Fail 판정이 유지된다.
- CAL만 제한한 경우의 원인 안내, 정상 평탄 입력, 앞선 교집합/정규화/프레임/상한 반례의 시험이 통과했다.
- `maxCorrectionDb=0.000001`로 계산한 실제 세션 결과에서 정규화 점 40개/최종 유효점 0개가 생성되고 최종 Fail이다. 추가된 회귀 시험도 이 분기를 직접 단언한다.

검사한 핵심 위치는 ProfileCodec.kt의 correction.limited 인코딩·선택적 디코딩·길이 검사, ResponseCalibration.kt의 nullable limitedByMaxCorrection과 unknown 분류다. 누락된 필드를 전부 false로 간주하던 원인은 제거됐다.

## 실행 범위

Kotlin 2.2.20/JBR 직접 컴파일 후 JUnit 4.13.2로 **166개 대상 시험 모두 통과**했다.

클래스: BoundaryProbeTest, PostLimitProbeTest, ApprovalProbeTest, RecheckProbeTest, CalibrationSessionTest, CalibrationQualityTest, SpectrumAverageTest, ResponseCalibrationTest, MeasuredProfileTest, ProfileCodecTest, PipelineProbeTest.

추가 독립 단언은 `L01R-CodecVerification.kt`에 담았다. BoundaryProbeTest에서도 169 CAL 경계 조합/6·12·24점 해상도의 불일치 출력은 없었다.

구현자가 보고한 전체 682개 시험, Gradle build/lint, 변이 2건은 이번에 독립 재실행하지 않았다. 위 166개 대상 시험과 별도 probe 실행 결과를 그 전체 검증 결과로 확대하지 않는다.

## 남은 범위와 다음 단계

교정 마법사·캡처·저장 연결 작업을 진행해도 된다. 다만 다음은 이번 종료 판정과 별개의 필수 검증이다.

1. 실제 저장·자동 적용이 judgeCalibration의 최종 결과를 사용하는지 통합 시험.
2. referenceCalApplied 선언을 실제 bin 보정 실행의 증거로 대체하고 이중 적용/다른 CAL 혼입을 차단.
3. FFT bin 보간과 valid 경계가 supportedBands의 축 점 기반 정의와 일치하는지 검증.
4. 저장 형식 계약과 구형 데이터 처리 확정, 비교 화면·저장 데이터 일치 확인.
5. 실제 음향·USB·기기 UI 및 8프레임/2dB/60%·정규화 충분성 정책의 실측 검증.

이번에 확인한 것은 계산 및 순수 codec 경로다. 파일 I/O·실기기 캡처·UI가 완성되었거나 검증되었다는 뜻은 아니다. 요청서에 적힌 과거 SignalPlayer·알림 정지·TalkBack 잔여 항목도 이번에 재검토하거나 종료하지 않았다.
