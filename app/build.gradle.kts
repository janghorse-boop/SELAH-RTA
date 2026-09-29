plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "kr.joa.selahrta"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.joa.selahrta"
        // USB 오디오 기기 열거(AudioDeviceInfo)와 UNPROCESSED 입력이 모두
        // 안정적으로 되는 선. 명세 2장의 USB-C 측정 마이크 지원이 여기에 걸린다.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // **업로드 키는 저장소 밖에 둔다.** 비밀값이라 여기 적지 않고,
    // `~/.gradle/gradle.properties` 에서 읽는다(docs/release-signing.md).
    //
    // 넷 다 `orNull` 로 읽는다 — 키가 없는 기계(CI·다른 사람의 PC)에서도
    // **구성 단계가 실패하면 안 된다.** `.get()` 을 쓰면 debug 빌드조차
    // 못 한다.
    val signStore = providers.gradleProperty("SELAH_STORE_FILE").orNull
    val signStorePw = providers.gradleProperty("SELAH_STORE_PASSWORD").orNull
    val signAlias = providers.gradleProperty("SELAH_KEY_ALIAS").orNull
    val signKeyPw = providers.gradleProperty("SELAH_KEY_PASSWORD").orNull

    signingConfigs {
        create("release") {
            if (signStore != null && signStorePw != null &&
                signAlias != null && signKeyPw != null
            ) {
                storeFile = file(signStore)
                storePassword = signStorePw
                keyAlias = signAlias
                keyPassword = signKeyPw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    // **`unitTests.isReturnDefaultValues` 를 켜지 않는다.**
    //
    // 한때 켰었다. android.jar 의 빈 구현이 예외를 던져 내보내기 시험의
    // 스레드가 그 자리에서 죽었기 때문이다. 그런데 그 옵션은 로그뿐 아니라
    // **모든** 안드로이드 호출을 0/null 로 돌려주어, 다른 시험이 실제
    // 프레임워크에 잘못 기대고 있어도 그 실패를 숨긴다(독립 검증 답변 2번).
    // 안드로이드 문서도 최후 수단으로 쓰라고 적는다.
    //
    // 그래서 경계를 좁혔다 — `SignalPlayer` 가 로그를 주입받는다.
}

// 시험이 몇 개 돌았는지 보이게 한다. 조용히 0개가 도는 것을 못 알아채면
// 「통과했다」는 말이 아무 뜻도 없어진다.
tasks.withType<Test>().configureEach {
    testLogging { events("passed", "failed", "skipped") }
}

dependencies {
    implementation(project(":dsp"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // **시험에서는 진짜 org.json 을 쓴다.** android.jar 의 빈 구현은 예외를
    // 던지므로, 그것만으로는 마이크 위치 DB 를 한 줄도 읽어 볼 수 없다.
    // 기기에서는 안드로이드가 제 구현을 쓴다 — 앱에는 들어가지 않는다.
    testImplementation(libs.json)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

/**
 * **서명 설정이 갖춰졌는지 release 패키징 *전에* 본다.**
 *
 * ## 왜 `doFirst` 가 아닌가 (독립 재검증 2026-09-29, 6장)
 *
 * 문서에 `assembleRelease.doFirst` 로 막으라고 적었는데, **그것은 그
 * task 가 의존하는 작업들보다 먼저 돌지 않는다.** 서명 task 가 이미
 * 지나간 뒤에 실패하면 막았다고 할 수 없다.
 *
 * 그래서 검증을 **별도 task 로 두고 선행 의존성으로 건다.**
 *
 * ## 무엇을 막나
 *
 * 키가 없는 기계에서 **debug 는 그대로 된다.** 막는 것은 release
 * 패키징뿐이다 — 서명 없이 만들어진 것을 모르고 올리는 쪽이 가장 나쁘다.
 *
 * **비밀값은 찍지 않는다.** 무엇이 없는지만 이름으로 말한다.
 */
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "release 패키징 전에 업로드 키 설정이 갖춰졌는지 본다"

    val store = providers.gradleProperty("SELAH_STORE_FILE")
    val missing = listOf(
        "SELAH_STORE_FILE" to providers.gradleProperty("SELAH_STORE_FILE"),
        "SELAH_STORE_PASSWORD" to providers.gradleProperty("SELAH_STORE_PASSWORD"),
        "SELAH_KEY_ALIAS" to providers.gradleProperty("SELAH_KEY_ALIAS"),
        "SELAH_KEY_PASSWORD" to providers.gradleProperty("SELAH_KEY_PASSWORD"),
    )
    val absent = missing.filter { !it.second.isPresent }.map { it.first }
    val storePath = store.orNull
    // **signingConfig 와 같은 기준으로 푼다**(독립 재검증 UIS6-01).
    // `File(...)` 은 실행 디렉터리 기준이라, `file(...)` 이 app 프로젝트
    // 기준으로 제대로 찾은 키를 여기서는 「없다」고 했다. 문서의 절대
    // 경로 예제는 멀쩡했고 **상대 경로를 쓰는 사람만** 막혔다.
    val fileMissing = storePath != null && !file(storePath).isFile

    doLast {
        check(absent.isEmpty()) {
            "업로드 키 설정이 없습니다: ${absent.joinToString(", ")}. " +
                "docs/release-signing.md 를 보고 ~/.gradle/gradle.properties 에 " +
                "넣으십시오. debug 빌드는 이것 없이도 됩니다."
        }
        check(!fileMissing) {
            "SELAH_STORE_FILE 이 가리키는 키 파일이 없습니다. " +
                "경로를 확인하십시오(비밀번호는 여기 찍지 않습니다)."
        }
    }
}

// release 로 묶거나 서명하는 일 **앞에** 세운다.
//
// **이름을 못박는다**(독립 재검증 UIS6-02, 2026-09-29). 예전에는
// `startsWith("bundleRelease")` 같은 접두사로 걸었는데, 그것이
// `bundleReleaseClassesToCompileJar`·`packageReleaseResources` 까지
// 잡았다. 그것들은 **서명과 무관한 release 단위 시험의 선행 작업**이라,
// 키가 없으면 `:app:testReleaseUnitTest` 조차 못 돌았다.
//
// 막으려던 것은 **최종 산출물과 서명**뿐이다.
//
// AGP 8.13.0 기준 이름이다. **AGP 나 variant 를 올리면 이 목록을 다시
// 확인한다** — 이름이 바뀌면 조용히 안 막게 된다(`-x` 로 일부러 빼는
// 것까지 막는 보안 경계는 아니다).
val guardedReleaseTasks = setOf(
    "bundleRelease", "assembleRelease", "packageRelease", "packageReleaseBundle",
    "packageReleaseUniversalApk", "signReleaseBundle", "validateSigningRelease",
    "signingConfigWriterRelease",
)
tasks.matching { it.name in guardedReleaseTasks }
    .configureEach { dependsOn(verifyReleaseSigning) }
