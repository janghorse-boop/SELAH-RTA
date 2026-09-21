# 녹음 행 집계기 독립 검토

2026-09-21 · HEAD `b191cc4` · 구현 범위 `9d38151..d38bdfb` (4파일 +714줄)

**판정: M32의 정상 입력 90dB 반례는 해결. 단계 2 완료 승인은 보류 — Medium 3건 / Low 1건. 현재 검토 범위에서 Critical/High는 발견하지 못했다.** 아직 Controller·사용자 녹음 경로에 연결되지 않은 순수 컴포넌트이므로 아래를 실사용 녹음 장애가 이미 발생한 것처럼 해석하지 않는다. SPSC의 독립 구현은 병행 가능하나 이 집계기를 연결하기 전에 Medium 항목을 수정해야 한다.

요청서의 구현 지시는 검토 대상으로 취급했다. production 수정·기기 조작은 하지 않았다. 작업 트리는 clean이었다.

## 독립 실행

현재 소스의 `TimelineRoundTripTest` **11건 모두 통과**. 별도 Kotlin/JVM probe로 아래 누락 조건을 재현했다. 이 11건 통과는 보고된 baseline 314건 또는 Gradle build/lint 전체를 재확인한 것이 아니다.

```
EPOCH_CROSS accepted=true calibrated=80dB
REVERSE current=-20.0 epoch=0
FINISH first=1 second=2
PARTIAL_GAP missing=false
ID_TRUNCATE expected=90 actual=70dB epoch=0
BAD_HEADER accepted=true
```

로그 `row-eq-independent-jvm.txt`, 재현 소스 `RowEqIndependentProbe.kt`. 최초 probe 컴파일의 로컬 문법 오류를 수정한 뒤 실행했으며 제품 시험 실패로 계산하지 않았다.

## RA01 / Medium — epoch 경계와 행 내부 순서를 검사하지 않는다

**위치:** `app/src/main/java/kr/joa/selahrta/recording/RowAggregator.kt:113–145`; `RecordingEpoch.kt:61–62`의 at()은 add 경로에서 사용되지 않는다.

**재현:** epoch 0은 frame 0/offset 100, epoch 1은 frame 12000/offset 120이다. `add(0,24000,epochId=0,...)`은 epoch 경계를 가로질러도 성공하고 전체를 epoch 0으로 처리한다. 또한 같은 행에서 `add(12000,12000,1,-30)` 다음 `add(0,12000,0,-20)`을 넣어도 성공하며 current는 나중에 호출한 과거 조각 -20/epoch 0이 된다.

**근거:** 검사하는 것은 첫/끝 행 번호와 `index >= openIndex`뿐이다. 한 행 안의 시간 역행·중복·overlap, 지정한 epoch가 구간에 실제 적용되는지를 확인하지 않는다. 클래스 설명의 ‘한 행·한 epoch 계약을 여기서 확인한다’와 다르다.

**사용자 영향:** 향후 분할기나 큐 연결 실수가 조용한 오보정·과거 current 덮어쓰기로 바뀐다.

**권장 수정:** 마지막 수락 exclusive end를 추적하여 역행/중복/overlap을 거부한다. 시작과 끝 프레임이 모두 동일한 지정 epoch에 속하는지, 해당 구간의 epoch 정보가 이미 확정돼 있는지 검증한다. frameStart 음수·범위 overflow도 입력 단계에서 막는다. 유효성 검사 전에 open row를 닫는 등의 상태 변경을 하지 않도록 순서를 정리한다.

**회귀 시험:** 정상 epoch 경계 분할, 경계를 걸친 조각, 틀린 epochId, 같은 행에서 역순/중복/겹침, 거절 후 정상 입력을 이어도 결과가 오염되지 않음.

## RA02 / Medium — finish를 반복하면 같은 행이 중복된다

**위치:** `RowAggregator.kt:149–151,163–178`.

**재현:** 한 조각을 추가한 뒤 finish를 두 번 호출하면 반환 행 수가 1 → 2로 늘고 rowIndex=0이 두 번 들어간다. closeOpen이 done에 추가한 뒤 anyData/open 상태를 소비하거나 닫힘으로 바꾸지 않는다. finish 후 add도 허용돼 이미 반환한 행이 다시 닫힐 수 있다.

**사용자 영향:** stop·오류·정리 경로가 중복 finalize하면 파일 레코드 위치와 rowIndex가 어긋난다.

**권장 수정:** finish를 멱등하게 만들고 이후 add를 거절하거나, 반복 finish 자체를 명시적으로 거절한다. 어느 선택이든 중복 레코드를 조용히 생성하면 안 된다. 반환 배열의 소유권도 확정한다.

**회귀 시험:** finish 두 번, 빈 집계기 finish 두 번, finish 후 add, 부분 행 finish 후 재정리. 행 번호와 레코드 수가 일치해야 한다.

## RA03 / Medium — epoch 개수 제한이 ID 범위 제한을 대신하지 못한다

**위치:** `RecordingEpoch.kt:45–52`, `TimelineIo.kt:74–76` 및 reader의 signed Short 해석.

**재현:** EpochTable은 ID 0과 65536 두 개를 허용한다. ID 65536/offset 120의 raw -30을 저장하면 `.toShort()`가 0으로 줄인다. 재생은 epoch 0/offset 100을 찾아 **90dB 대신 70dB**를 반환한다. 현재 외부 생성자가 ID를 순차 발급한다는 보장도 없다.

**사용자 영향:** write 이전까지 유효하던 보정 이력이 파일 왕복 후 다른 epoch로 조용히 연결된다. 현재 두 epoch만으로도 재현되므로 상한 256으로 방지되지 않는다.

**권장 수정:** 지원하는 ID 도메인을 명시적으로 제한한다(예: 0..255, -1은 missing 전용). 테이블뿐 아니라 writer 입력에서도 범위와 참조를 검사한다. reader는 missing 이외 행의 sentinel/없는 epoch를 구조화된 invalid로 처리한다. 단순 truncation을 하지 않는다.

**회귀 시험:** -1/음수, 255/256, 32768, 65536, 존재하지 않는 참조, 정상 missing 왕복. 허용 범위 밖은 저장 전에 거절하거나 명시적인 오류로 읽어야 한다.

## RA04 / Low — 파일 시간 단위의 유효성 검사가 없다

**위치:** `TimelineIo.kt:51–62,108–117`, `RowAggregator.kt:89`.

**재현:** writer에 `TimelineHeader(0,0)`을 주어 만든 파일을 reader가 정상적으로 연다. rowMillis는 Short로 자르므로 범위 초과도 조용히 바뀐다. 집계기도 fs/rowMillis를 검증하지 않아 framesPerRow=0이면 add에서 나눗셈 오류가 난다.

**사용자 영향:** 손상된 헤더나 잘못된 호출을 정상 시간축으로 받아들일 수 있다. 아직 소비 UI가 없어 확정 사용자 오표시로 부풀리지는 않는다.

**권장 수정:** v1이 500ms 전용이면 writer/reader에서 이를 강제한다. 가변 간격을 지원할 계획이면 양수·표현 범위·지원 sampleRate를 검증하고 모든 소비자가 header를 쓰도록 한다. 레코드 연속성, 유한 숫자와 참조 검사도 파일 검증 경계에서 설계한다.

**회귀 시험:** fs=0/음수, rowMillis=0/음수/표현 범위 초과, 지원하지 않는 간격. 정상 44.1/48kHz 왕복과 함께 시험한다.

## 요청서 질문에 대한 판단

1. **보정한 값으로 승자를 고른 뒤 raw+epoch 저장은 맞다.** 정상 입력 M32 반례는 실제 왕복에서 90dB가 나온다. Peak도 별도 반례를 넣고, RA01/03 때문에 잘못된 epoch가 들어오는 길을 막아야 한다.
2. **3단계 연결 전:** RA01~03과 소유권 경계를 닫는다. split producer, final watermark, 부분 손실 표현은 연결 설계에서 명시해야 한다. SPSC 컴포넌트 자체의 독립 개발까지 멈출 필요는 없다.
3. **148바이트·little-endian 자체는 문제없다.** 다만 현재 행에는 요구된 Leq/A·C·Z 표현, epoch에는 timeWeight·curve hash 등 이전 설계의 메타데이터가 아직 없다. 지금 포맷을 ‘전체 녹음 v1 완성’으로 동결하지 않는다. 필드 추가 시 명시적 버전 변경/호환 처리가 필요하다.
4. **11건은 실제 확인한 범위에서 정직하게 통과한다.** ‘행 경계 거절’은 검사하나 epoch 경계 거절은 시험하지 않는다. ‘마지막 부분 행’은 행 존재를 확인할 뿐 실제 끝 프레임 보존을 확인하지 않는다. ‘끊긴 꼬리’는 메모리 바이트 배열 절단 시험이지 crash/fsync 복구 보장이 아니다. ‘상한’은 max=3 fixture로 개수 제한을 시험하며 ID 표현 범위는 시험하지 않는다.

## 구현자 빈 곳 1~6 및 추가 통합 조건

- **단일 소유자:** writer/recording worker가 집계기·EpochTable·TimelineWriter를 함께 소유하고 producer는 불변 조각/epoch 명령을 순서 있게 넘기는 방식이 자연스럽다. 각 세션마다 별도 인스턴스를 만들고 늦은 세대는 worker 입구에서도 거부한다. 캡처와 writer가 같은 ArrayList를 읽고 쓰는 방식은 피한다. 분할 시 필요한 epoch 메타데이터는 producer에게 불변 스냅샷으로 준다.
- **missing=-1:** v1 내부 센티넬로 유지해도 된다. 정상 행에 허용되지 않도록 하고 공개 조회가 missing을 안전하게 처리하면 nullable로 전면 재작성할 필요는 없다. bands의 0은 ‘없음’일 때 절대 0dB 그래프로 그리지 않는다.
- **256 상한:** overflow를 명시적으로 처리한다면 잠정 정책으로 허용 가능하다. 경험적으로 보장된 상한이라고 주장하지 않는다.
- **flush/복구:** flush만으로 마지막 꼬리 행만 잃는다고 보장할 수 없다. header/이전 행까지 지속되지 않았을 수 있다. checkpoint 단계 전에는 crash-recovery 완료로 표시하지 않는다. epoch 테이블도 현재는 외부에서 주입하므로 파일 단독 재시작 복구는 아직 구현되지 않았다.
- **부분 gap·끝자락:** `[0,100)`과 `[1000,1100)`만 입력해도 현재 행은 missing=false이며 900-frame gap 정보가 없다. 행이 아예 비었는지만 뜻하는 flag라면 그 정의를 명시하고 gap 사건/coverage를 별도로 연결해야 한다. 끝에 입력이 전부 없으면 finish()는 최종 frame을 받지 않아 끝자락 missing 행을 만들 수 없다. 마지막 부분 행의 유효 끝도 별도 메타데이터가 필요하다. 이 단계에서 gap 사건 저장이 미구현임을 인정하고 통합 단계의 완료 조건에 넣는다.
- **메모리:** done에 모든 행을 쌓고 finish에서 전체를 돌려주는 현재 API는 시험에는 편하지만 writer streaming API가 아니다. 캡처 스레드에서 copy/ArrayList 증가를 수행하지 않도록 하고, 장시간 기록에서는 완결 행을 순차 배출해 해제하는 경계를 만든다.

현재 단계의 범위를 존중해 미구현 WAV·체크포인트 자체를 추가 결함 수로 세지 않았다. 그러나 위 범위를 숨긴 채 ‘단계 2의 저장 형식은 최종 완성’이라고 승인하지는 않는다.
