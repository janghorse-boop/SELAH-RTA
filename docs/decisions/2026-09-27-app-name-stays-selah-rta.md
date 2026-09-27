# 앱 이름은 「SELAH RTA」 그대로 간다

날짜: 2026-09-27 · 정한 사람: 장훈(개발 담당)

## 무엇을 정했나

**앱 이름은 「SELAH RTA」다.** 「ToneVista」로 바꿀지 검토했고, **원래
이름을 유지하기로 했다.**

## 왜 적어 두나

2026-09-26·27 에 받은 자료 두 묶음이 **제목과 파일 이름에 「ToneVista」를
달고 있다.**

| 저장소의 것 | 받은 이름 |
|---|---|
| [docs/spec/2026-09-27-tonevista-galaxy-multimic-instruction.md](../spec/2026-09-27-tonevista-galaxy-multimic-instruction.md) | `ToneVista_Galaxy_다중내장마이크_캘리브레이션_개발지시서.docx` |
| [docs/data/galaxy-mic-location-db/](../data/galaxy-mic-location-db/) · `app/src/main/assets/galaxy-mic-locations.json` | `ToneVista_Galaxy_Microphone_DB_v0_1.*` |

그 자료를 읽는 다음 세션이 **「이 앱의 진짜 이름은 ToneVista 인가」**
또는 **「이름을 바꾸다 만 것인가」**로 읽을 수 있다. 둘 다 아니다 —
검토만 했고 쓰지 않기로 했다.

## 무엇을 하고, 무엇을 하지 않나

- `app_name` 은 「SELAH RTA」다. **고치지 않는다.** 패키지
  (`kr.joa.selahrta`)·저장소 이름·문서의 제목도 그대로다.
- **받은 자료의 본문은 손대지 않는다.** 원본이 쓴 말이고, 고쳐 적으면
  나중에 원본과 대조할 수 없다. `ActiveMicCombo.kt` 의 인용문에 그
  이름이 그대로 남아 있는 것도 같은 까닭이다.
- **저장소 안의 파일 이름은 중립으로 둔다.** 자산은
  `galaxy-mic-locations.json`, 폴더는 `docs/data/galaxy-mic-location-db/`
  다. 자료를 개정할 때 그 이름으로 갈아 끼우면 된다.
- 화면에 나가는 문구에는 그 이름을 쓰지 않는다.

## 되돌린다면

이름을 정말 바꾸기로 하면 `app_name` 한 곳이 아니다 — 패키지·스토어
등록·안드로이드 앱 내보내기 문서([docs/android-app-release.md](../android-app-release.md))가
함께 걸린다. 그때는 별도 결정으로 적는다.
