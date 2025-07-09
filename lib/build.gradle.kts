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
}

android {
    namespace = "me.proton.vpn.androidvpnrust"
    compileSdk = 36
    ndkVersion = "28.1.13356709"

    defaultConfig {
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
        else -> null
    }
}
