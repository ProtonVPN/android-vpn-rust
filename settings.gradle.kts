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
    versionCatalogs {
        create("sdkLibs") {
            from(files("protun/sdk-android/gradle/libs.versions.toml"))
        }
    }
}

val includeProtun = (gradle.startParameter.projectProperties["include_protun"] ?: "false") == "true"

rootProject.name = "android-vpn-rust"
include(":lib")
if (includeProtun) {
    include(":protun-sdk")
    project(":protun-sdk").projectDir = file("protun/sdk-android/sdk")
}

