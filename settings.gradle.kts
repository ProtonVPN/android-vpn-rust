val includeProtun = (providers.gradleProperty("include_protun").orNull ?: "false") == "true"
val libVersionName = getRepoVersionName()
// VPN core version name is d.e.f-a.b.c where d.e.f is protun version and a.b.c is this repo's version.
val protunVersionName = getCargoVersionName(file("protun/Cargo.toml")) + "-" + libVersionName

gradle.allprojects {
    extensions.extraProperties["libVersionName"] = libVersionName
    extensions.extraProperties["protunCoreVersionName"] = protunVersionName
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
            create("coreLibs") {
                from(files("protun/vpn-core-android/gradle/libs.versions.toml"))
            }
        }
    }
}

rootProject.name = "android-vpn-rust"
include(":lib")
if (includeProtun) {
    include(":vpn-core")
    project(":vpn-core").projectDir = file("protun/vpn-core-android/vpn-core")
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

// Read the crate version from protun's Cargo.toml ([package] version).
fun getCargoVersionName(cargoToml: File): String {
    if (!cargoToml.exists()) {
        throw RuntimeException("Unable to find crate manifest at $cargoToml")
    }
    val lines = cargoToml.readLines()
    val packageStart = lines.indexOfFirst { it.trim() == "[package]" }
    if (packageStart < 0) {
        throw RuntimeException("No [package] section in $cargoToml")
    }

    // First `version = "..."` within the [package] table
    val versionRegex = Regex("""^\s*version\s*=\s*"([^"]+)""")
    for (line in lines.drop(packageStart + 1)) {
        if (line.trimStart().startsWith("[")) break
        versionRegex.find(line)?.let { return it.groupValues[1] }
    }
    throw RuntimeException("No version found in [package] of $cargoToml")
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
