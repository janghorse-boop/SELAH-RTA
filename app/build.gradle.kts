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

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    testOptions {
        unitTests {
            // android.jar 의 빈 구현은 기본값을 돌려주게 한다.
            //
            // 이것이 꺼져 있으면 `Log.w` 같은 호출이 예외를 던져, 시험이
            // 돌리는 스레드가 그 자리에서 죽는다 — 실기기에서는 멀줦한 경로가
            // 시험에서만 깨져, **없는 결함을 있다고 읽게 된다.** 실제로
            // 내보내기 시험을 쓸 때 그런 일이 있었다.
            isReturnDefaultValues = true
        }
    }
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

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
