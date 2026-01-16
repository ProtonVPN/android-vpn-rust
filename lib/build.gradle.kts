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

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.rustandroid)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.mavenpublish)
}

// Update this once we publish this project to GitHub.
private val githubRepo = "github.com/ProtonVPN/android-app"
private val rustCrateName = "androidvpnrust"
private val rustCratePath = "../rust"
private val generatedUniffiDirectory = layout.buildDirectory.file("generated/uniffi/java")
private val versionName = ext["libVersionName"] as String

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
        debug {
            packaging.jniLibs.keepDebugSymbols.add("**/*.so")
        }
    }
    // Java 11 is used because of dokka issue with publishing with Java 17:
    // https://github.com/Kotlin/dokka/issues/2956
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    sourceSets {
        getByName("main") {
            // Note: JNI libraries for desktop platforms (for unit testing) are being added
            // after the AAR has been built, see below.
            jniLibs.srcDir("rustJniLibs")

            // Note: Java/Kotlin sources are added via registerJavaGeneratingTask below.
        }
    }
    testOptions {
        unitTests.all { it ->
            // Add path to the native library.
            // It would be better to add it to Java resources for the "test" source set, but AGP
            // filters out .so files from Java resources.
            val path = "rustJniLibs/desktop/${getHostTargetForRust()}"
            it.systemProperty(
                "jna.library.path",
                layout.buildDirectory.file(path).get().asFile.path
            )
        }
    }

    mavenPublishing {
        publishToMavenCentral(automaticRelease = true)
        signAllPublications()

        val groupId = "me.proton.vpn"
        val artifactId = "android-vpn-rust"

        coordinates(groupId, artifactId, versionName)
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
    implementation(libs.androidx.annotation)
    implementation(libs.jna) {
        artifact { type = "aar" }
    }

    testImplementation(kotlin("test"))
    testImplementation(libs.jna) {
        artifact { type = "jar" }
    }
}

val rustProfile = "release"
cargo {
    module = rustCratePath
    libname = rustCrateName
    targets = buildList {
        addAll(listOf("arm", "arm64", "x86", "x86_64"))

        // Host target is needed for running unit tests locally.
        getHostTargetForRust()?.let { add(it) }
    }
    prebuiltToolchains = true
    apiLevel = 25
    profile = rustProfile
    features {
        if (findProperty("include_protun") == "true")
            defaultAnd(arrayOf("protun"))
    }
}

val generateUniFFIBindingsTask = tasks.register<Exec>("generateUniFFIBindings") {
    dependsOn += "cargoBuild"
    workingDir = file(rustCratePath)
    commandLine = listOf("cargo", "run", "--bin", "uniffi-bindgen", "generate", "--library", "target/aarch64-linux-android/$rustProfile/lib${rustCrateName}.so", "--language", "kotlin", "--config", "uniffi.toml", "--out-dir", generatedUniffiDirectory.get().asFile.path)
}

// Ensure that the Rust library is built before merging JNI libs into the AAR.
// issue: https://github.com/mozilla/rust-android-gradle/issues/85
tasks.whenTaskAdded {
    if (name == "mergeDebugJniLibFolders" || name == "mergeReleaseJniLibFolders") {
        dependsOn("cargoBuild")
    }
}

fun zipDirectory(sourceDir: File, zipFile: File, excludeDirs: Set<String> = emptySet()) {
    ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
        sourceDir.walkTopDown()
            .filter { it.isFile }
            .filter { file -> excludeDirs.none { file.path.contains("/$it/") } }
            .forEach { file ->
                val entryName = file.relativeTo(sourceDir).path
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().buffered().use { it.copyTo(zos) }
                zos.closeEntry()
            }
    }
}

// Inject desktop native libraries directly into the classes JAR to allow running unit tests by the
// users of the library.
// AGP filters .so files from resources in MergeJavaResourceDelegate, so we add them after the JAR
// is created.
val desktopLibsDir = layout.buildDirectory.dir("rustJniLibs/desktop")
afterEvaluate {
    listOf("Release", "Debug").forEach { variant ->
        tasks.findByName("bundle${variant}Aar")?.doLast {
            val aarFile = outputs.files.singleFile
            val libsDir = desktopLibsDir.get().asFile

            if (!libsDir.exists()) {
                logger.warn("Desktop libs dir not found: $libsDir")
                return@doLast
            }

            val nativeLibs = libsDir.walkTopDown().filter {
                it.isFile && it.extension in listOf("so", "dylib", "dll")
            }.toList()

            if (nativeLibs.isEmpty()) {
                logger.warn("No desktop native libs to inject")
                return@doLast
            }

            val tempDir = temporaryDir.resolve("aar-repack")
            tempDir.deleteRecursively()
            tempDir.mkdirs()

            // Extract AAR
            copy {
                from(zipTree(aarFile))
                into(tempDir)
            }

            // Extract classes.jar
            val classesJar = tempDir.resolve("classes.jar")
            val classesContent = tempDir.resolve("classes-content")
            copy {
                from(zipTree(classesJar))
                into(classesContent)
            }

            // Copy desktop native libs
            copy {
                from(libsDir)
                into(classesContent)
                include("**/*.so", "**/*.dylib", "**/*.dll")
            }

            // Repackage classes.jar
            classesJar.delete()
            zipDirectory(classesContent, classesJar)

            // Repackage AAR
            aarFile.delete()
            zipDirectory(tempDir, aarFile, excludeDirs = setOf("classes-content"))

            logger.lifecycle("Injected ${nativeLibs.size} desktop native lib(s) into ${aarFile.name}")
        }
    }
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
