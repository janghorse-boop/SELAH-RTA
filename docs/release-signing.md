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

그다음 `app/build.gradle.kts` 에 이렇게 더한다(아직 안 되어 있다):

```kotlin
android {
    signingConfigs {
        create("release") {
            val f = providers.gradleProperty("SELAH_STORE_FILE").orNull
            if (f != null) {
                storeFile = file(f)
                storePassword = providers.gradleProperty("SELAH_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("SELAH_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("SELAH_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        release {
            // **없으면 조용히 디버그 서명으로 떨어지지 않게** 한다.
            // 서명 없이 만들어진 것을 모르고 올리는 쪽이 더 나쁘다.
            //
            // **「signingConfig 를 지정했다」는 사실만으로는 모자란다**
            // (독립 재검증 UISRFF-03). 속성이 없으면 storeFile 이 null 인
            // 채로 지정만 되어, release 패키징이 조용히 디버그로 떨어질
            // 수 있다. 그래서 아래에서 키 파일까지 보고 **명시적으로
            // 실패시킨다.**
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

`providers.gradleProperty(...).orNull` 로 두는 까닭: 키가 없는 기계
(CI·다른 사람의 PC)에서도 **디버그 빌드는 그대로 되어야** 한다.

그리고 **release 패키징은 명시적으로 막는다** — 조용히 디버그 서명으로
떨어지는 것이 가장 나쁘다:

```kotlin
tasks.matching { it.name.startsWith("bundleRelease") || it.name.startsWith("assembleRelease") }
    .configureEach {
        doFirst {
            val f = providers.gradleProperty("SELAH_STORE_FILE").orNull
            check(f != null && file(f).isFile) {
                "업로드 키가 없습니다. docs/release-signing.md 를 보고 " +
                    "~/.gradle/gradle.properties 에 SELAH_STORE_FILE 을 두십시오."
            }
        }
    }
```

---

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

`unsigned` · `no manifest` · `This jar contains entries whose certificate
chain is not validated` 같은 말이 보이면 통과가 아니다.

**새 AAB 만 남은 폴더에서 해 본다.** 옛 산출물이 섞여 있으면 무엇을
검사했는지 알 수 없다 — `app/build/outputs` 를 지우고 다시 만든다.

## 5. 이 일이 끝나야 다음이 열린다

| 남은 일 | 이 키가 있어야 하나 |
|---|---|
| Play Console 앱 등록 | 예 — 첫 업로드에 필요 |
| 내부 테스트 배포 | 예 |
| Data Safety 선언 | 아니오 (`docs/data-safety-mapping.md` 의 빈 칸을 콘솔에 옮겨 적는 일) |
