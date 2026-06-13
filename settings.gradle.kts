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
        maven { url = uri("https://maven.aliyun.com/repository/public/") }
        maven { url = uri("https://jitpack.io") }
        // Insta360 SDK (sdkcamera/sdkmedia). Официальный публичный репозиторий из SDK-demo
        // V1.10.1. Старый nexus.arashivision.com:9999 более недоступен.
        maven {
            url = uri("https://androidsdk.insta360.com/repository/maven-public/")
            isAllowInsecureProtocol = true
            credentials {
                username = providers.gradleProperty("instaNexusUser").orNull ?: "insta360guest"
                password = providers.gradleProperty("instaNexusPassword").orNull ?: "EXMSjSo8OeOrjU7d"
            }
        }
    }
}

rootProject.name = "insta360-orientation-mode"
include(":app")
include(":lib")
