import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.cloakedcent.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.cloakedcent.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Tesseract ships native code: phones (arm64) and the x86_64 emulator only.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    androidResources {
        // The OCR model is copied out of the APK and checksummed; keep it uncompressed.
        noCompress += "traineddata"
    }

    // Instrumented tests run against a separate "qa" install (com.cloakedcent.app.qa), so
    // test runs never replace, clear or uninstall the developer's own debug installation.
    testBuildType = "qa"

    // Release signing comes only from the environment, so no key or password is ever in the
    // repository. Without CLOAKEDCENT_KEYSTORE the release build is simply left unsigned.
    signingConfigs {
        System.getenv("CLOAKEDCENT_KEYSTORE")?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("CLOAKEDCENT_KEYSTORE_PASSWORD")
                keyAlias = "cloakedcent"
                keyPassword = System.getenv("CLOAKEDCENT_KEYSTORE_PASSWORD")
            }
        }
    }

    // This block is encrypted to Google Play's key, so no one else could read it. F-Droid's scanner flags it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            matchingFallbacks += "debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
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

    buildFeatures {
        compose = true
    }
}

// JitPack builds artifacts on demand, so the downloaded Tesseract AAR is checked
// against the SHA-256 recorded when version 4.9.0 was reviewed.
val verifyTesseractArtifact = tasks.register("verifyTesseractArtifact") {
    val expected = "bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a"
    val aars = configurations.named("releaseRuntimeClasspath").map { classpath ->
        classpath.incoming.artifactView {
            attributes { attribute(Attribute.of("artifactType", String::class.java), "aar") }
            componentFilter { it is ModuleComponentIdentifier && it.module == "tesseract4android" }
        }.files
    }
    inputs.files(aars)
    doLast {
        val files = aars.get().files
        check(files.size == 1) { "Expected one Tesseract AAR, found $files" }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(files.single().readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == expected) { "Tesseract AAR checksum mismatch: $actual" }
    }
}
tasks.named("preBuild") { dependsOn(verifyTesseractArtifact) }

// The qa build reuses the debug-only sources (sample data) unchanged.
android.sourceSets.getByName("qa").kotlin.directories += "src/debug/java"

room {
    // Exported schemas are committed to Git; migration tests read them back.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.tesseract4android)
    implementation(libs.bouncycastle.prov)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)

    constraints {
        implementation(libs.kotlinx.serialization.core) {
            because("room-testing reads exported schemas with kotlinx-serialization 1.8.1+")
        }
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    "qaImplementation"(libs.androidx.compose.ui.tooling)
    "qaImplementation"(libs.androidx.compose.ui.test.manifest)
}
