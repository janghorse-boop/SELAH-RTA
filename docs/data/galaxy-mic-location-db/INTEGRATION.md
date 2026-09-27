# 갤럭시 마이크 위치 DB — 이 저장소에서 어떻게 쓰나

들인 날: 2026-09-27 · 자료 확인 기준일: 2026-09-26 · 자료 판: `2026-09-26.v0.1`

원본 지시서는 [README.md](README.md) 다. **그 문서가 규칙이고, 이 문서는
그것을 코드 어디에 옮겨 놓았는지 적는다.**

> **앱 이름은 「SELAH RTA」다.** 원본 파일들이 `ToneVista_*` 로 되어 있는
> 것은 이름을 바꿀지 검토하던 때의 흔적이고, 바꾸지 않기로 했다 —
> [2026-09-27-app-name-stays-selah-rta.md](../../decisions/2026-09-27-app-name-stays-selah-rta.md).

## 어디에 있나

| 무엇 | 어디 |
|---|---|
| **원본 데이터**(앱이 읽는 것) | `app/src/main/assets/galaxy-mic-locations.json` |
| 구조 정의 | [schema.json](schema.json) |
| 검증기 | [validate_db.py](validate_db.py) |
| 사람이 보는 표 | [galaxy-mic-locations-v0.1.xlsx](galaxy-mic-locations-v0.1.xlsx) |
| 읽는 코드 | `app/.../micdb/MicLocationDbParser.kt` |
| 모델·조회 | `app/.../micdb/MicLocationDb.kt` |
| 화면 | `app/.../ui/components/MicLocationCard.kt` (도구 화면) |
| 시험 | `app/src/test/.../micdb/MicLocationDbTest.kt` |

**JSON 을 Kotlin 으로 옮겨 적지 않았다.** 표를 코드로 굳히면 자료를 고칠
때마다 코드를 다시 만들어야 한다. 원본이 정한 개정 절차(JSON 을 갈고
검증기를 돌린다)가 그대로 살아 있어야 한다.

## 자료를 개정할 때

```bash
# 1. 새 JSON 을 자산 자리에 덮어쓴다
cp <새파일> app/src/main/assets/galaxy-mic-locations.json

# 2. 원본 검증기를 돌린다 (PASS 가 나와야 한다)
python docs/data/galaxy-mic-location-db/validate_db.py \
    app/src/main/assets/galaxy-mic-locations.json

# 3. 앱 시험을 돌린다 — 실린 자료를 그대로 읽어 본다
./gradlew :app:testDebugUnitTest --tests "*MicLocationDbTest*"
```

3번이 **표본이 아니라 실제 자산 파일**을 읽는다. 자료가 바뀌면 여기서
깨진다 — 그러라고 그렇게 적었다. 셈이 바뀌었으면 시험의 숫자도 함께
고치고, **왜 바뀌었는지 커밋 메시지에 적는다.**

`schema_version` 이 `1.0.0` 이 아니면 앱은 **읽지 않는다.** 뜻이 바뀐
자리를 옛 뜻으로 읽으면 틀린 안내가 확인된 자료의 얼굴로 나간다.
그때는 `MicLocationDbParser.SUPPORTED_SCHEMA` 와 파서를 함께 고친다.

## 경계 — 세 가지를 섞지 않는다

원본 5장이 못박은 자리이고, 코드가 그 경계를 지킨다.

| | 무엇인가 | 어디서 오나 | 어디에 뜨나 |
|---|---|---|---|
| **위치 안내** | 도면에 적힌 **외관상 구멍 자리** | 이 DB | 도구 화면 카드 |
| 실행 중 진단 | 지금 녹음에 쓰이는 마이크 | `getActiveMicrophones()` | 캡처 진단 「활성 마이크」 |
| 음압 교정 | 이 경로의 감도 | 사람이 재서 얻은 값 | 보정 카드 |

- **`location_key` 는 화면용 이름이지 `MicrophoneInfo.getId()` 가
  아니다.** 둘을 잇는 대응표는 검증된 적이 없다. `MicLocation` 에 그
  자리를 아예 만들지 않았다 — 자리를 만들어 두면 언젠가 누가 채운다.
- **`documented_mic_location_count` 는 「이 기기의 마이크 개수」가
  아니다.** 화면 문구는 「공식 도면에서 확인된 마이크 위치: N곳」으로
  고정했다.
- **이 DB 로 음압 보정값을 만들지 않는다.** `FactoryCalibration` 과
  `micdb` 는 서로를 부르지 않는다. 시험이 `MicModel` 에 `spl`·`offset`·
  `calib` 이 든 이름이 생기는지 본다.
- **하단/상단/후면 개별 선택 단추를 만들지 않는다.** 안드로이드가 물리
  마이크의 독립 선택을 보장하지 않는다(`MicSeparation` 이 이미 같은
  규칙을 지킨다).

## 자동 조회 규칙

`Build.MANUFACTURER` 가 Samsung 이고, `Build.MODEL` 을 **앞뒤 공백 제거 +
대문자 변환**한 것이 `documented_model_codes` 와 **정확히** 같을 때만
문서 안내를 건다.

접미사(`N`·`B`·`U1`·`E`·`/DS`·`UD`)를 떼거나, `SM-S9` 같은 앞머리로 여러
세대를 묶거나, Plus·Ultra·FE·연식이 비슷하다고 다른 자료를 쓰지 않는다.

**왜 이렇게까지 좁히나** — 틀린 자리를 알려 주면 사람은 그 자리에 대고
잰다. 모른다고 말하면 사람이 직접 찾아본다. 뒤엣것이 낫다.

이 판에서 그 규칙이 실제로 갈라내는 것들:

- `SM-X710` → Tab S9 (문서 기반 참고)
- `SM-S921B` → S24 · **`SM-S921N` 은 모름**
- `SM-S918N` → **모름.** S23 세 기종은 국내 제품명별 자리는 확인됐지만
  모델코드를 대조하지 못해 `documented_model_codes` 가 비어 있다.
  **제품명 수동 선택으로만** 참고할 수 있고, 그것은 확인이 아니다.
- S22 계열을 비롯한 조사대기 16종은 자동 조회에 들어오지 않는다.

## 아직 안 한 것

- **도면 오버레이.** `normalized_diagram_coordinates` 가 전부 `null` 이라
  픽셀 좌표를 만들 근거가 없다. 텍스트 설명만 보고 좌표를 지어내지
  않는다 — 원본 그림 검토가 먼저다.
- **원본 도면 이미지.** 자료 묶음에 재배포되지 않았고, 앱에 넣으려면
  사용 조건을 따로 봐야 한다.
- **실기기 확인.** 「확인」은 도면의 마이크 라벨·지시선을 읽었다는 뜻이지
  기기에서 구멍을 짚어 본 것이 아니다.
