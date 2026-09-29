# 업로드 키 만들기와 보관

이 문서는 **사람이 직접 하는 일**을 적는다. 키는 비밀값이라 Claude 가
만들지 않는다.

> **왜 급한가**: 지금 앱은 **디버그 서명**만 있다
> (`app/build.gradle.kts` 에 릴리스 `signingConfig` 가 없다). 디버그
> 서명으로는 Play 에 올릴 수 없다.
>
> **키를 잃었을 때 무슨 일이 생기는지는 Play App Signing 을 켰는지에
> 달렸다**(독립 재검증 UISRFF-03).
>
> - **켠 경우**(권함): 여기서 만드는 것은 「업로드 키」이고, 잃으면
>   구글에 **재설정을 요청할 수 있다.** 배포 키는 구글이 들고 있다.
> - **안 켠 경우**: 그 키가 곧 배포 키다. 잃으면 **영영 갱신할 수
>   없고**, 같은 이름으로 새 앱을 올려야 하며 설치한 사람들은 갱신이
>   아니라 새로 깔아야 한다. 기존 데이터는 따라가지 않는다.
>
> 어느 쪽이든 급할 때 만들면 보관이 허술해지니 미리 만들어 둔다.

---

## 1. 키 만들기

JDK 의 `keytool` 로 만든다. Android Studio 가 깔려 있으면 그 안에 있다.

```bash
"C:/Program Files/Android/Android Studio/jbr/bin/keytool" -genkeypair -v \
  -keystore selah-rta-upload.jks \
  -alias selah-rta \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -storetype JKS
```

물어보는 것들:

| 물음 | 무엇을 적나 |
|---|---|
| 키 저장소 비밀번호 | **길게.** 비밀번호 관리자에 바로 넣는다 |
| 이름(CN) | `JOAWORKS` |
| 조직 단위(OU) | 비워도 된다 |
| 조직(O) | `JOAWORKS` |
| 시·도·국가 | 실제 값(`KR`) |
| 키 비밀번호 | 저장소 비밀번호와 **같게 해도 된다** — 다르게 하면 하나 더 잃을 것이 생긴다 |

`-validity 10000` 은 약 27년이다. Play 는 **2033-10-22 이후까지** 유효한
키를 요구하므로 넉넉히 잡는다.

---

## 2. 어디에 두나

**저장소에 넣지 않는다.** `.gitignore` 가 `*.jks` 를 막고 있고,
커밋 훅(`scripts/check-secrets.mjs`)도 비밀번호가 파일에 섞이는 것을
본다.

세 벌을 서로 **다른 곳**에 둔다:

1. 이 PC 의 저장소 **바깥** 폴더 (예: `D:/Keys/selah-rta/`)
2. 비밀번호 관리자의 첨부 파일 (비밀번호와 같은 자리)
3. 외장 매체나 다른 계정의 보관함

비밀번호는 **파일과 같은 곳에 두지 않는다** — 함께 잃으면 둘 다 잃는다.

**Play App Signing 을 켜 두면** 배포 키는 구글이 들고, 여기서 만든 것은
「업로드 키」가 된다. 업로드 키를 잃어도 구글에 재발급을 요청할 수
있으므로 **켜는 쪽이 안전하다.** 그렇다고 이 파일을 함부로 다뤄도
된다는 뜻은 아니다.

---

## 3. Gradle 에 걸기

비밀번호를 `build.gradle.kts` 에 적지 않는다. 저장소 바깥의
`~/.gradle/gradle.properties` 에 둔다:

```properties
SELAH_STORE_FILE=D:/Keys/selah-rta/selah-rta-upload.jks
SELAH_STORE_PASSWORD=...
SELAH_KEY_ALIAS=selah-rta
SELAH_KEY_PASSWORD=...
```

`app/build.gradle.kts` 에는 **이미 배선되어 있다.** 속성 넷을 모두
`orNull` 로 읽고, 넷이 다 있을 때만 `signingConfig` 를 채운다.

**release 패키징은 별도 검증 task 가 막는다** — `verifyReleaseSigning`.
`doFirst` 로는 안 된다(독립 재검증 2026-09-29): 그 task 가 **의존하는
작업들보다 먼저 돌지 않아**, 서명 task 가 이미 지나간 뒤에 실패한다.
그래서 선행 의존성으로 건다.

지금 이렇게 갈린다(실제로 돌려 확인함):

| 상황 | 결과 |
|---|---|
| 키 없음 · `assembleDebug` | **통과** — 개발에 지장 없다 |
| 키 없음 · `bundleRelease` | **실패** — 없는 속성 이름을 알려 준다 |
| 일부만 있음 | **실패** — 모자란 것만 짚어 준다 |
| 넷 다 있으나 파일 없음 | **실패** — 경로를 확인하라고 한다 |

**비밀번호는 어디에도 찍지 않는다.** 무엇이 없는지 이름으로만 말한다.

확인해 보려면:

```bash
./gradlew :app:verifyReleaseSigning
```

**이 task 가 통과했다고 「배포 가능한 올바른 키」인 것은 아니다**
(독립 재검증 2026-09-29). 여기서 보는 것은 **속성과 파일 존재**뿐이다.

| 무엇 | 어디서 확인되나 |
|---|---|
| 속성 넷이 있는가 · 파일이 있는가 | `verifyReleaseSigning` |
| 비밀번호·키가 유효한가 | 실제 서명 작업(`signReleaseBundle`)이 실패로 알려 준다 |
| **그 키가 Play 에 등록한 그 키인가** | 산출물 지문과 **업로드 인증서 대조**(4장) |

**키 없이도 되는 것**: debug 빌드, 그리고 **release 단위 시험**
(`:app:testReleaseUnitTest`). 막는 것은 최종 산출물과 서명뿐이다.

## 4. 만든 뒤 확인

**만든 것과 검사하는 것이 같아야 한다**(독립 재검증 UISRFF-03).
`bundleRelease` 가 내놓는 것은 **AAB** 인데 옛 판 이 문서는 `apksigner`
로 **APK** 를 검사하라고 적고 있었다 — APK 가 없으면 실패하고, 옛
APK 가 남아 있으면 **이번에 올릴 파일과 무관한 서명**을 보게 된다.

### 키 지문 보기

```bash
keytool -list -v -keystore selah-rta-upload.jks -alias selah-rta
```

### Play 에 올릴 번들(AAB)을 만들고 그것을 검사한다

```powershell
.\gradlew.bat :app:bundleRelease

& "$env:JAVA_HOME/bin/jarsigner.exe" -verify -verbose -certs `
  app/build/outputs/bundle/release/app-release.aab

& "$env:JAVA_HOME/bin/keytool.exe" -printcert -jarfile `
  app/build/outputs/bundle/release/app-release.aab
```

### APK 를 따로 나눠 줄 때만

```powershell
.\gradlew.bat :app:assembleRelease
& "$env:ANDROID_HOME/build-tools/<버전>/apksigner.bat" verify --print-certs `
  app/build/outputs/apk/release/app-release.apk
```

### 무엇을 보고 통과라 하나

**종료 코드 0 으로는 모자란다.** `jarsigner` 는 서명이 없어도 경고만
내고 0 으로 끝날 수 있다. 셋을 눈으로 본다:

1. `jar verified` 가 찍혔는가
2. 서명된 항목이 있는가(`sm` 표시)
3. 인증서 **SHA-256 지문**이 Play Console 의 **업로드 인증서**와 같은가

`unsigned` · `no manifest`, 서명 검증 실패, 서명되지 않은 payload,
등록된 업로드 인증서와 다른 지문은 거절한다.

다만 `certificate chain is not validated` 또는 `self signed` 경고만으로
거절하지 않는다. 이 문서의 `keytool -genkeypair`는 자체 서명 인증서를
만든다. JVM의 CA 신뢰 경로와 Play에 등록한 업로드 인증서의 일치는 다른
검사다. 경고를 무조건 무시하는 대신, 그 원인을 확인하고 같은 AAB의 서명
무결성·payload 서명 여부·인증서 유효기간·업로드 SHA-256 일치를 확인한다.

**새 AAB 만 남은 폴더에서 해 본다.** 옛 산출물이 섞여 있으면 무엇을
검사했는지 알 수 없다 — `app/build/outputs` 를 지우고 다시 만든다.

## 5. 이 일이 끝나야 다음이 열린다

| 남은 일 | 이 키가 있어야 하나 |
|---|---|
| Play Console 앱 등록 | 예 — 첫 업로드에 필요 |
| 내부 테스트 배포 | 예 |
| Data Safety 선언 | 아니오 (`docs/data-safety-mapping.md` 의 빈 칸을 콘솔에 옮겨 적는 일) |
