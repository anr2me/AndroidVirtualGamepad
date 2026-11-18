plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The default build has no accessibility service. Google Play Protect can block the installation of a
// sideloaded APK that declares an accessibility service. Use -Pavg.accessibility=true to add the service
// (Accessibility mode). The files of the service are in app/src/accessibility.
val withAccessibility = providers.gradleProperty("avg.accessibility").map { it.toBoolean() }.getOrElse(false)

android {
    namespace = "com.avgamepad"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.avgamepad"
        minSdk = 24
        targetSdk = 34
        versionCode = System.getenv("AVG_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("avg.versionName").getOrElse("0.1.0")
        // Use -Pavg.abis=arm64-v8a to build one ABI only (faster CI and code scanning).
        val abis = providers.gradleProperty("avg.abis").getOrElse("arm64-v8a,armeabi-v7a,x86_64,x86").split(",")
        ndk { abiFilters += abis }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        // The UI uses this value to show or hide Accessibility mode.
        buildConfigField("boolean", "ACCESSIBILITY", withAccessibility.toString())
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The privileged helper process loads libagp.so from the app lib directory.
    // The library must exist as a real file on disk.
    packaging { jniLibs { useLegacyPackaging = true } }

    // Accessibility mode (optional). The manifest of a build type is merged into the main manifest.
    sourceSets {
        if (withAccessibility) {
            listOf("debug", "release").forEach { type ->
                getByName(type) {
                    manifest.srcFile("src/accessibility/AndroidManifest.xml")
                    res.srcDir("src/accessibility/res")
                    java.srcDir("src/accessibility/java")
                }
            }
        }
    }

    // Release signing comes from environment variables (set by the GitHub workflow).
    // Without a keystore, the release APK uses the debug key. It is installable but not a trusted release key.
    val keystorePath: String? = System.getenv("AVG_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("AVG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AVG_KEY_ALIAS")
                keyPassword = System.getenv("AVG_KEY_PASSWORD")
            }
        }
    }

    lint {
        // The release build runs the "vital" lint checks. A lint finding must not stop the build.
        abortOnError = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
}
