pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SELAH RTA"

include(":app")

// DSP 는 안드로이드에 의존하지 않는 순수 JVM 모듈이다.
// 명세 0장 「DSP 는 UI 와 독립시키고 synthetic signal 테스트를 갖는다」.
// 이렇게 두면 에뮬레이터 없이 초 단위로 합성 신호 검증이 돌고,
// 독립 검증자도 측정 수학만 따로 볼 수 있다.
include(":dsp")
