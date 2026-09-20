plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 안드로이드 의존성을 넣지 않는다. 이 모듈이 android SDK 를 알게 되는 순간
// 「에뮬레이터 없이 초 단위로 도는 측정 검증」이라는 이 분리의 목적이 사라진다.
dependencies {
    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}
