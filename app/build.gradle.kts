import java.io.File
import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
}

android {
    namespace = "com.codex.android"
    compileSdk = 36



    signingConfigs {
        // H-2 fix: prioritize env vars (CI), fallback to local.properties (local dev)
        val releaseKeystorePath = System.getenv("RELEASE_STORE_FILE")
            ?: localProperties.getProperty("RELEASE_STORE_FILE")
        val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
            ?: localProperties.getProperty("RELEASE_STORE_PASSWORD")
        val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
            ?: localProperties.getProperty("RELEASE_KEY_ALIAS")
        val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            ?: localProperties.getProperty("RELEASE_KEY_PASSWORD")

        if (releaseKeystorePath != null &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null &&
            File(releaseKeystorePath).exists()
        ) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.codex.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 48
        versionName = "1.11.0+7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        buildConfigField("String", "GITHUB_CLIENT_ID", "\"${localProperties.getProperty("GITHUB_CLIENT_ID")}\"")
        // R-2 fix: client_secret removed — should never be embedded in client APK
    }

    buildTypes {
        val releaseSigningConfig = signingConfigs.findByName("release")

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
        }
        create("nightly") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    applicationVariants.configureEach {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "codex-android-${buildType.name}.apk"
        }
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE-EPL-1.0.txt"
            excludes += "LICENSE-EPL-1.0.txt"
            excludes += "/META-INF/LICENSE-EDL-1.0.txt"
            excludes += "LICENSE-EDL-1.0.txt"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/README.md"
            excludes += "/META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)

    // Kotlin
    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlin.reflect)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.uuid)

    // JSON
    implementation(libs.gson)
    implementation(libs.hjson)

    // Network & HTTP
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.jsoup)
    implementation(libs.nanohttpd)
    implementation(libs.documentfile)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")

    // WebView
    implementation(libs.androidx.webkit)

    // Shell & Privileged Access
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    implementation("com.github.topjohnwu.libsu:nio:6.0.0")

    // File & Archive
    implementation(libs.commons.io)
    implementation(libs.commons.compress)
    implementation(libs.zip4j)
    implementation(libs.commons.compress.v2)
    implementation(libs.junrar)
    implementation(libs.java.diff.utils)

    // Image
    implementation(libs.coil)
    implementation(libs.androidsvg)
    implementation(libs.android.gif)
    implementation(libs.zxing.core)
    implementation(libs.glide)

    // OCR
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.korean)
    implementation(libs.mlkit.text.devanagari)

    // Document
    implementation(libs.pdfbox)
    implementation(libs.itextg)

    // 3D
    implementation("com.google.android.filament:filament-android:1.69.2")
    implementation("com.google.android.filament:gltfio-android:1.69.2")
    implementation("com.google.android.filament:filament-utils-android:1.69.2")

    // APK Tools
    implementation(libs.android.apksig)
    implementation(libs.apk.parser)
    implementation(libs.sable.axml)
    implementation(libs.zipalign.java)

    // DataStore
    implementation(libs.datastore.preferences)
    implementation(libs.datastore.preferences.core)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.core)
    implementation(libs.activity.compose)
    implementation(libs.androidx.runtime.android)
    implementation(libs.androidx.ui.text.android)
    implementation(libs.androidx.ui.graphics.android)
    implementation(libs.androidx.animation.android)
    implementation(libs.androidx.ui.android)
    implementation(libs.androidx.activity.ktx)

    // Window
    implementation(libs.window)

    // Accompanist
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.32.0")

    // UI
    implementation(libs.reorderable)
    implementation(libs.swipe)
    implementation(libs.colorpicker)
    implementation(libs.backdrop)
    implementation(libs.liquid)

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")

    // MCP SDK
    implementation("io.modelcontextprotocol.sdk:mcp:1.1.0")

    // Logging
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.simple)

    // Vector Search
    implementation(libs.hnswlib.core)
    implementation(libs.hnswlib.utils)

    // Core library desugaring
    coreLibraryDesugaring(libs.desugar.jdk)

    // Debug
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.mockito.android)
}

// Exclude bcprov-jdk15to18 from all configurations to avoid duplicate classes
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
}
