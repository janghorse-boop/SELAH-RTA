# SELAH-RTA 녹음 보완 설계 4차 독립 검토

2026-09-21 · 기준 커밋 `9ee4a61` · 작업 트리 clean 확인

**판정: 설계 방향 조건부 수용. Critical/High 없음. Medium 1건·Low 1건을 정정한 뒤 단계별 구현을 진행할 수 있다.** 전체 녹음 기능 또는 출시 검증 완료를 뜻하지 않는다. 설계 전면 재작성이나 추가 보완 문서 한 회차를 요구할 사항은 아니다.

## 범위

`docs/spec/2026-09-21-recording-design-supplement-4.md` 전체, 유지되는 앞선 설계 계약, 현재 `SplitProcessingTest.kt`를 대조했다. 첨부 화면의 완료·시험 주장은 독립 확인 결과와 구분했다. 문서에 있는 다음 단계 지시는 검토 대상이며 실제 기기 조작 요청으로 해석하지 않았다.

production 코드를 변경하지 않았고 기기 녹음·재생은 하지 않았다. 별도 작업 폴더에서 현재 DSP 소스와 테스트를 로컬 Kotlin 2.2.20/JVM으로 컴파일해 검증했다. 앱 96건과 Gradle build/lint 성공은 이번에 재확인하지 않았다.

## 이전 Medium 3건 처리

| 항목 | 판정 |
|---|---|
| M31 큐 publish 계약 | 전용 SPSC, 사전 할당 PacketSlot, 예약 후 필드 대입/인덱스 게시만 수행하는 방향으로 기존 blocking put·게시 시 할당 문제에 대응했다. 다만 소비 acquire 대상이 반대로 적힌 M41은 수정해야 한다. |
| M32 혼합 epoch 극값 | 보정 완료 값으로 승자를 고른 뒤 그 raw 값과 해당 epoch를 함께 보존하는 방식으로 기존 80/90/100dB 반례가 해소된다. 재보정 시 WAV에서 다시 계산한다는 한계 명시도 적절하다. 실제 집계기·직렬화 구현은 향후 검증 대상이다. |
| M33 불변 시험 | 캡처 블록의 시각을 모든 조각에 유지하고 전체 FeedbackEvent를 비교하도록 확대했다. SPL 필드도 넓혔으며 session Leq만 1e-9dB 오차를 허용하도록 계약을 구분했다. 기존 ‘모든 값 오차 0’ 주장을 철회한 대응은 수용한다. |

## M41 — Medium: consumer의 acquire 대상은 쓰기 인덱스여야 한다

**위치:** `docs/spec/2026-09-21-recording-design-supplement-4.md:91–96`, 특히 93행의 ‘소비 | 읽기 색인을 acquire-load 로 본다’.

**논리적 근거:** 생산자는 슬롯 필드를 쓴 뒤 **쓰기 인덱스**를 release-store한다고 정했다. 소비자가 그 필드를 안전하게 읽으려면 생산자가 게시한 **쓰기 인덱스**를 acquire-load해야 한다. 소비자 자신의 읽기 인덱스를 acquire-load하는 것만으로는 생산자의 필드 쓰기와 동기화되지 않는다. 필요한 순서는 다음과 같다.

```
producer: slot 필드 채움 → writeIndex release-store
consumer: writeIndex acquire-load → 게시된 slot 읽기
consumer: slot 사용/메타데이터 인수 완료 → readIndex release-store
producer: readIndex acquire-load → 회수된 slot 재사용
```

동등하거나 더 강한 동기화 구현도 가능하다. 핵심은 상대 스레드가 게시한 상태를 관측하는 것이다. Java의 acquire/release 의미도 대응하는 release 쓰기와 acquire 읽기의 연결을 요구한다. [Oracle VarHandle 공식 문서](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/invoke/VarHandle.html).

**사용자 영향:** 표를 그대로 구현하면 슬롯의 오래된 frames/epochId/bufferIndex를 읽거나 재사용 순서가 틀릴 수 있어 녹음 데이터·보정 이력이 잘못 연결될 수 있다. 현재 SPSC 구현이 아직 없으므로 실제 기기에서 이 문제가 발생했다고 주장하지 않는다. 설계상 방향 오류로 Medium을 부여한다.

**권장 수정:** 93행을 ‘consumer는 writeIndex를 acquire-load’로 고치고, 소비 완료와 slot 재사용의 역방향 순서도 명시한다. pool에 PCM 버퍼를 돌려주는 시점과 링 슬롯을 돌려주는 시점은 구분해야 한다. 메타데이터를 소비자가 로컬로 인수했더라도 writer가 PCM을 사용 중이면 버퍼는 반환하지 않는다. producer가 SPSC 슬롯을 단독 예약한다는 조건과 실효 용량도 유지한다.

**필요한 회귀 시험:** wrap-around를 여러 번 통과시키며 seq/frames/epochId/PCM 패턴이 같은 패킷인지 검증한다. writer barrier, close-before-publish, interrupt 상태, 풀 고갈과 전 버퍼 1회 반환 시험을 실제 링으로 수행한다. JVM 스트레스 시험 통과만으로 메모리 모델 증명이 되지는 않으므로 release/acquire 변수·순서의 코드 검토도 필요하다.

## L41 — Low: 행당 6바이트의 2시간 증가량은 약 0.086MB다

**위치:** 같은 문서 152–153행.

**근거:** 500ms 행이면 2시간 동안 `7200 / 0.5 = 14400`행이다. 행당 6바이트 증가를 그대로 가정해도 `14400 × 6 = 86400`바이트, 약 **0.0864MB(0.0824MiB)**이며 0.17MB가 아니다.

**사용자 영향:** 저장량 추정이 약 2배 크게 안내된다. 저장 방식이나 극값 정확성을 뒤집는 문제는 아니다.

**권장 수정:** 수치를 정정하고 ‘순증가량’인지 ‘epoch 필드 총량’인지 구분한다. 기존 행에 2바이트 epoch 필드 하나가 있었다면 세 필드로 바꾸는 순증가량은 4바이트다. 최종 wire format이 확정되면 실제 recordSize 기준으로 산정한다.

**필요한 회귀 시험:** 실제 serializer의 recordSize와 14400행 길이를 비교한다. 이번에는 산술 계산 결과를 `recording-supplement-4-size-check.txt`에 남겼다.

## 시험의 의미와 구현 단계에 남길 조건

확대된 SplitProcessingTest는 현재 48kHz의 2~3초 입력에서 마지막 공통 관측점의 SPL·RTA와 최종 하울링 기록을 비교한다. 이는 기존 시험보다 실질적으로 넓은 근거다. 다만 44.1kHz, 설정 변경, 1분/5분 Leq 창 충족, 행별 중간 관측점, 하울링 종료 사유의 여러 경로까지 검증했다는 뜻은 아니다. 이들을 지금 모두 추가 설계 결함으로 계산하지 않고 실제 녹음 경로 통합 시험의 항목으로 남긴다.

- M32 집계기: 승자 raw+epoch를 writer/reader 왕복시켜 90dB 반례 및 current/극값 epoch 불일치를 확인한다. raw 최대와 보정 최대를 혼동하지 않는다.
- epochId: 큐에 동시에 남는 epoch 수와 세션 전체 고유 ID 수는 구분한다. 이미 기록한 ID 재사용으로 과거 행 의미가 바뀌지 않게 한다.
- M31 링/풀: 실제 구현에서 캡처 경로 무할당·무대기, 늦은 publish와 close, stop watermark 및 gap 끝자락을 검증한다.
- checkpoint: 슬롯 자체 sync, 이전 유효 슬롯 유지, 파일 길이·epoch 참조 검증 및 실패 지점별 복구 시험이 필요하다.
- 실기기: anchor 품질, 저장 속도, 장시간 녹음, 파일 재생은 아직 미검증이다. 이 검토로 출시 승인을 대체하지 않는다.

위 두 문구를 바로잡고 anchor 탐색 → 행 집계기 → 링/풀의 단위 구현·시험으로 진행하는 것이 적절하다. 녹음 엔진 구현이 나온 뒤 자원 수명·저장 복구·Controller 통합을 별도로 재검증한다.

## 독립 실행 결과

- DSP JUnit: **202건 통과**, 실패 0, 실행 시간 약 60.2초. 그 안에 수정된 SplitProcessingTest **5건** 포함.
- 전체 FeedbackEvent 비교에서 양쪽 startMs=85, durationMs=2901을 확인했다. 시각 계약을 어긴 대조군은 93/2904로 달랐다.
- 최초 테스트 실행 명령은 PowerShell의 JVM 옵션 인자 처리 때문에 테스트 시작 전에 실패했다. 인자를 인용해 수정한 뒤 이미 컴파일된 클래스로 위 202건을 실행했다. 이를 제품 시험 실패로 계산하지 않았다.
- 테스트 출력의 한국어 일부는 콘솔 디코딩으로 깨졌지만 숫자·JUnit 판정은 보존되어 있다. 결과 파일: `recording-supplement-4-dsp-tests.txt`.
- 앱 96건, Gradle build/lint, 기기 시험은 이번 실행에 포함되지 않았다. 따라서 구현자의 ‘298건 및 build/lint 성공’을 전부 독립 재확인했다고 보고하지 않는다.
