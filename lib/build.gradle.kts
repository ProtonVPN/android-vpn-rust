/*
 * Copyright (c) 2025. Proton AG
 *
 * This file is part of ProtonVPN.
 *
 * ProtonVPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonVPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonVPN.  If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.rustandroid)
    alias(libs.plugins.vanniktech.mavenpublish)
}

// Update this once we publish this project to GitHub.
private val githubRepo = "github.com/ProtonVPN/android-app"

android {
    namespace = "me.proton.vpn.androidvpnrust"
    compileSdk = 36
    ndkVersion = "28.1.13356709"

    defaultConfig {
        aarMetadata {
            minCompileSdk = 35 // Lower will probably also work.
        }
        minSdk = 25
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false // Let's leave it to library users to minify and optimize.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("jniLibs")
            // Java/Kotlin sources are added via registerJavaGeneratingTask below.
        }
    }
    testOptions {
        unitTests.all { it ->
            // Add path to the native library.
            // It would be better to add it to Java resources for the "test" source set, but I can't get it to work.
            val path = "rustJniLibs/desktop/${getHostTargetForRust()}"
            it.systemProperty("jna.library.path", layout.buildDirectory.file(path).get().asFile.path)
        }
    }

    mavenPublishing {
        publishToMavenCentral(automaticRelease = true)
        signAllPublications()

        val groupId = "me.proton.vpn"
        val artifactId = "android-vpn-rust"

        coordinates(groupId, artifactId, getFullVersionName())
        pom {
            name = "$groupId:$artifactId"
            description = "Rust library with code for Android Proton VPN client"
            url = "https://protonvpn.com"
            licenses {
                license {
                    name = "GNU GENERAL PUBLIC LICENSE, Version 3.0"
                    url = "https://www.gnu.org/licenses/gpl-3.0.en.html"
                }
            }
            developers {
                developer {
                    id = "opensource@proton.me"
                    name = "Open Source Proton"
                    email = "opensource@proton.me"
                }
            }
            scm {
                connection = "scm:git:git://${githubRepo}.git"
                developerConnection = "scm:git:ssh://${githubRepo}.git"
                url = "https://${githubRepo}"
            }
        }
    }
}

dependencies {
    implementation(libs.jna) {
        artifact { type = "aar" }
    }

    testImplementation(kotlin("test"))
    testImplementation(libs.jna) {
        artifact { type = "jar" }
    }
}

val rustCrateName = "androidvpnrust"
val rustCratePath = "../rust"
val generatedUniffiDirectory = layout.buildDirectory.file("generated/uniffi/java")
cargo {
    module = rustCratePath
    libname = rustCrateName
    targets = buildList {
        addAll(listOf("arm", "arm64", "x86", "x86_64"))
        // Host target is needed for running unit tests.
        getHostTargetForRust()?.let { add(it) }
    }
    prebuiltToolchains = true
    apiLevel = 25
    profile = "release"
}

val generateUniFFIBindingsTask = tasks.register<Exec>("generateUniFFIBindings") {
    dependsOn += "cargoBuild"
    workingDir = file(rustCratePath)
    commandLine = listOf("cargo", "run", "--release", "--bin", "uniffi-bindgen", "generate", "--library", "target/aarch64-linux-android/release/lib${rustCrateName}.so", "--language", "kotlin", "--config", "$rustCratePath/uniffi.toml", "--out-dir", generatedUniffiDirectory.get().asFile.path)
}

tasks.clean {
    delete("${rustCratePath}/target")
}

android.libraryVariants.configureEach {
    // generateUniFFIBindingsTask is an Exec task that doesn't define outputs, explicitly add the folder with generated
    // source files.
    registerJavaGeneratingTask(generateUniFFIBindingsTask, generatedUniffiDirectory.get().asFile)
}

private fun getHostTargetForRust(): String? {
    val os = org.gradle.internal.os.OperatingSystem.current()
    return when {
        os.isWindows -> "win32-x86-64-gnu"
        os.isLinux -> "linux-x86-64"
        os.isMacOsX -> "darwin-aarch64"
        else -> null
    }
}

private fun getFullVersionName(): String {
    // Find last tag in the form M.m.D, D is optional. Add number of commits from that tag to D to form final
    // version name
    val tag = exec("git", "tag", "--merged", "HEAD").trim().split("\n").reversed().find { it.matches(Regex("\\d+(\\.\\d+){1,2}")) }
    if (tag == null) throw GradleScriptException("Unable to obtain version tag", NullPointerException())

    val tagSplit = tag.split(".").map { it.toInt() }
    val (major, minor) = tagSplit
    var dev = tagSplit.getOrElse(2) { 0 }
    dev += exec("git", "log", "--first-parent", "${tag}..HEAD", "--oneline").lineSequence().count()
    return "${major}.${minor}.${dev}"
}

private fun exec(vararg cmd: String): String =
    // Exec doesn't return null with throwOnError.
    exec(*cmd, throwOnError = true)!!

private fun exec(vararg cmd: String, throwOnError: Boolean): String? {
    val proc = providers.exec {
        commandLine = cmd.toList()
    }
    if (proc.result.get().exitValue != 0) {
        if (throwOnError)
            throw GradleScriptException("Error executing: ${cmd.toString()}", RuntimeException(proc.standardError.asText.get()))
        else
            return null
    }
    return proc.standardOutput.asText.get()
}
