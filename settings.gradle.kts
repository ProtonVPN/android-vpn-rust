val includeProtun = (providers.gradleProperty("include_protun").orNull ?: "false") == "true"
val libVersionName = getRepoVersionName()
// SDK version name is d.e.f-a.b.c where d.e.f is SDK version and d.e.f comes from this repo
val protunVersionName = getRepoVersionName(file("protun")) + "-" + libVersionName

gradle.allprojects {
    extensions.extraProperties["libVersionName"] = libVersionName
    extensions.extraProperties["protunSdkVersionName"] = protunVersionName
}

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
        if (includeProtun) {
            create("sdkLibs") {
                from(files("protun/sdk-android/gradle/libs.versions.toml"))
            }
        }
    }
}

rootProject.name = "android-vpn-rust"
include(":lib")
if (includeProtun) {
    include(":protun-sdk")
    project(":protun-sdk").projectDir = file("protun/sdk-android/sdk")
}

fun getRepoVersionName(workDir: File = file(".")): String {
    // Find last tag in the form M.m.D, D is optional. Add number of commits from that tag to D to form final
    // version name
    val tag = exec("git", "tag", "--merged", "HEAD", workDir = workDir)
        .trim()
        .split("\n")
        .reversed()
        .find { it.matches(Regex("\\d+(\\.\\d+){1,2}")) }

    if (tag == null) throw RuntimeException("Unable to obtain version tag", NullPointerException())

    val tagSplit = tag.split(".").map { it.toInt() }
    val (major, minor) = tagSplit
    var dev = tagSplit.getOrElse(2) { 0 }
    dev += exec("git", "log", "--first-parent", "${tag}..HEAD", "--oneline", workDir = workDir)
        .lineSequence()
        .filter { it.isNotBlank() }
        .count()
    return "${major}.${minor}.${dev}"
}

private fun exec(vararg cmd: String, workDir: File = file(".")): String {
    val proc = providers.exec {
        commandLine = cmd.toList()
        workingDir = workDir
    }
    if (proc.result.get().exitValue != 0)
        throw RuntimeException("Error executing: $cmd", RuntimeException(proc.standardError.asText.get()))

    return proc.standardOutput.asText.get()
}
