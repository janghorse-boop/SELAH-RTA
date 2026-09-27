# CFRC-R01·CFRC-R02 수정분 독립 재검토

검토일: 2026-09-27 · 검토자: Codex

**판정: 이번 수정 범위 승인. CFRC-R01(Medium)·CFRC-R02(Low) 해결 확인. 이번 재검토에서 새 Critical / High / Medium / Low 지적은 없다.**

이전 검토의 부분 보류를 해소한다. 이 승인은 CAL 파일 판정과 저장소 검증 경계의 해당 수정에 대한 것이다. 전체 앱 출시, 실제 입력 전환 UI, 마이크 음향 성능이나 절대 SPL 정확도까지 검증했다는 뜻은 아니다.

## 1. 범위와 첨부 자료 확인

- 수정 커밋: **bd1b72e72e65264bccf3fe9c749a294840d17555**.
- 현재 병합 HEAD: **2b1eba60347ef09564dc3d9c3f6dcf54df2ffec9**, PR #19. 이 HEAD와 수정 커밋의 트리 차이는 없었다.
- 이전 고정 대상: `c7755e2`. 그 뒤의 `ae755ce` 가져오기 안내 수정도 연결 경로로 검토했다.
- 수정 커밋을 `git archive`로 별도 작업 폴더에 고정했다. 사용자 저장소의 production 코드, 브랜치, 설정은 변경하지 않았다.
- 시작 작업 트리는 깨끗했다. 종료 무렵에는 SessionCsv·SessionMeta·CaptureViewModel 변경과 MeasurementConditions·MeasurementReport 관련 새 파일이 있었다. 이 별도 진행 작업은 검토하거나 수정하지 않았다.

첨부된 네 파일은 지난 검토에서 전달한 검토서·회귀 시험·probe·제안 패치와 **줄바꿈을 정규화한 본문이 모두 일치**했다. 새 요청서 대신 이전 결과와 수정 커밋을 대조했다. 이전 검토서의 ‘부분 보류’는 당시 `c7755e2`의 이력으로 보존하고, 현재 판정은 이 문서로 갱신한다.

## 2. 해결 여부

| 지적 | 수정 위치 | 확인 결과 |
|---|---|---|
| CFRC-R01 — 부호 충돌이 단위·수량 오류를 가림 | `dsp/.../CalibrationSign.kt:158, 208–230, 268–271` | 부호로 풀 수 없는 사유를 우선하고 모든 괄호 검사 뒤에만 부호 충돌을 반환한다. 부분 문자열 대신 정확히 일치하는 규약만 인정한다. **해결** |
| CFRC-R02 — wrapper 재생성을 앱 재시작으로 과장 | `app/.../calibration/CurveStore.kt:162–165` 및 `CurveStoreConfirmationTest.kt:166–236` | DataStore 주입을 추가했다. 실제 주입 저장소에 기록했는지 확인하고 writer scope 종료·join 후 같은 파일을 새 DataStore로 읽는다. 시험 이름도 디스크 재읽기로 좁혔다. **해결** |

### CFRC-R01 재현 확인

원본 CurveStore + 실제 DataStore로 다음 결과를 확인했다.

- `(correction) (Pa)`와 `(Pa) (correction)` 모두 Unit으로 거절.
- 부호 충돌 선언과 kHz 선언의 줄 순서를 뒤집어도 모두 거절.
- 부호 충돌 선언과 Phase 열 선언의 줄 순서를 뒤집어도 모두 거절.
- `(correction Pa)`, `(Pa correction)`, `(response linear)`, `(response Pa)` 거절.
- 서로 다른 선언이 여럿인 경우 Ambiguous로 거절.
- save/watch, Response·Correction 확인, 일반 켜기를 통한 우회도 차단.

반대로 `Response (dB) (correction)`와 `Corr (dB) (response)`처럼 **지원되는 수량·단위 안에서 방향만 어긋나는 경우는 계속 확인할 수 있다.** Correction을 고르면 원본 +3dB 점이 응답 표현 -3dB로 한 번만 변환된다.

이전 토큰 문제도 되돌아보았다. A 표시 후 B 저장 → A 토큰 확인은 여전히 거절되며, 다른 채널에 확인이 복사되지 않는다. 일반 켜기 우회, 파일 바이트 교체, 규칙 버전 불일치, 여덟 번째 머리글 손실도 기존 차단을 유지했다.

### CFRC-R02 재현 확인

`CurveStore`의 watch/save/setEnabled/confirmReading/setMicName/clear가 모두 주입된 `store`를 사용한다. 주입하지 않은 앱 호출은 기존 delegate를 기본값으로 사용한다.

새 시험은 주입한 DataStore의 실제 preferences를 직접 읽어 기록을 확인한다. 따라서 Store가 주입을 무시하고 공용 delegate를 쓰면 통과할 수 없다. 첫 writer의 Job을 종료·join한 뒤 새 reader로 확인 규약·활성 상태·부호 변환을 복원하는 시험도 통과했다.

또한 별도 독립 probe를 **다른 JVM 프로세스에서 다시 실행**해 다음을 확인했다:

```text
REOPEN confirmed=true enabled=true gain100=-3.0
```

정규 시험은 새 DataStore의 디스크 재읽기를, 독립 probe는 새 JVM에서의 복원을 검증했다. 두 경계를 구분한다.

## 3. 독립 실행 결과

| 실행 | 결과 |
|---|---|
| 수정 커밋 DSP JUnit | **564건 통과**, 80.452초 |
| 앱 교정 관련 선택 JUnit, 18개 클래스 | **277건 통과**, 9.598초 |
| 실제 CurveStoreConfirmationTest 23건 + 지난 독립 회귀 5건 | **28건 통과**, 10.308초 |
| 실제 Store/DataStore 독립 probe | 모든 기존·조합 반례 차단, 정상 대조군 유지 |
| 별도 JVM 재실행 | 확인 규약·활성 상태·부호 변환 복원 통과 |
| 판정 수정을 되돌린 별도 사본 | **28건 중 8건 실패** — 저장소 정규 시험 4건 + 독립 시험 4건 |
| 주입 DataStore를 무시하도록 바꾼 별도 사본 | **28건 중 2건 실패** — 디스크 재읽기 및 저장소 격리 시험 |

마지막 두 행의 실패는 의도적으로 결함을 되살린 사본의 결과다. 수정 코드의 실패가 아니다. 8건은 서로 다른 결함 유형 8개가 아니라 동일한 네 조합 계약을 두 시험 집합에서 검증한 결과다.

Kotlin 2.2.20 / JBR 21 / JVM target 17, 실제 DataStore 1.1.1을 사용했다. DSP harness의 coroutines는 1.8.0, 앱/저장소는 1.9.0이다. Application의 filesDir/applicationContext만 대역이며 저장 데이터는 작업 폴더 안에만 만들었다. 앱 선택 시험에서는 현재 원본 ActiveCurve 선언을 추출해 컴파일했다.

구현자의 ‘697건 및 assembleDebug 성공’은 커밋에 기재된 결과다. 이번 독립 실행의 시험 집합과 같다고 합산하지 않는다. 전체 Gradle assemble/lint 및 Android 화면·기기 실행은 이번에 다시 수행하지 않았다.

## 4. 가져오기 안내와 남은 경계

`ae755ce`는 가져오기 안내를 `curveImportNoticeKo`로 모으고, 지원하지 않는 형식 / 확인 필요 / 적용됨을 구분한다. 이 함수에 실제 Store 결과를 전달하는 네 정규 시험도 위 23건에 포함돼 통과했다. ViewModel이 실제 `readingUnsupportedKo`를 전달하는 연결은 정적으로 확인했다. 기기에서 그 문구를 다시 띄워 본 것은 아니다.

남은 검증 경계는 다음과 같다. 이번 두 지적을 다시 보류하는 사유로 추가하지 않는다.

- 입력을 B로 바꾼 뒤 이전 A 화면의 버튼을 누르는 ViewModel/UI 실행 시험은 아직 없다. 현재 키와 토큰 키를 비교하는 코드는 유지된다.
- 기준 소음계·94dB 교정기·실제 마이크를 통한 절대 SPL와 음향 검증은 별도다. 이번 코드 승인과 구분한다.
- 자유 서술 괄호나 모든 제조사 형식의 지원을 보장하지 않는다. 현재 패치는 명시한 형식과 회귀 사례에서 보수적으로 거절하는 최소안이다.
- 일반 저장소 시험의 `store()` helper는 여전히 기본 delegate를 공유한다. 이번에 추가한 디스크 재읽기·격리 시험은 별도로 주입된 DataStore를 사용하므로 R02의 증거는 유효하다. 후속 시험 정리 때 일반 helper에도 scope 소유권을 통일하고, writer/reader의 종료를 실패 경로까지 finally + cancel/join으로 정리하면 된다. 현재 승인 조건에 새 항목으로 추가하지 않는다.

## 5. 전달 및 재실행 안내

**이번 수정에는 추가 production 패치를 제안할 필요가 없다.** 지난 제안의 핵심이 반영됐고, 정상 대조군과 결함 되돌림 시험까지 확인했다.

첨부된 `2026-09-27-cfrc-independent-store-probe.kt`는 **이전 결함을 관찰하는 기대값(true)**을 일부 가지고 있다. 그 원본을 수정 코드의 합격 시험으로 그대로 쓰면 기대값 때문에 실패한다. 이번 실행은 작업 사본의 해당 조합 기대값만 false로 바꿨다. 안전 계약을 직접 단언하는 독립 회귀 5건은 그대로 사용했다.

재현 자료는 `C:\Users\jangh\Documents\Codex\2026-09-21\bash-selah-rta-independent-reviewer-verification\work\cfrcr-20260927`에 있다:

- `run.ps1` / `dsp-test-output.txt`
- `app-calibration-tests.ps1` / `app-calibration-test-output.txt`
- `store-probe.ps1` / `store-junit-output.txt` / `store-output.txt` / `store-reopen-output.txt`
- `mutation-sign.ps1` / `mutation-sign-output.txt`
- `mutation-injection.ps1` / `mutation-injection-output.txt`

mutations는 별도 디렉터리·클래스 출력에서 실행했다. 고정한 제품 원본과 사용자 저장소는 수정하지 않았다.