plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oms.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.oms.client"
        minSdk = 30
        targetSdk = 34
        versionCode = 37
        versionName = "1.6.1"
    }

    signingConfigs {
        create("release") {
            val envStoreFile = System.getenv("OMS_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
            val fallbackDebugStore = file("${System.getProperty("user.home")}/.android/debug.keystore")
            if (envStoreFile != null && file(envStoreFile).exists()) {
                storeFile = file(envStoreFile)
                storePassword = System.getenv("OMS_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("OMS_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("OMS_KEY_PASSWORD") ?: "android"
            } else if (fallbackDebugStore.exists()) {
                // Use local debug keystore when no custom release keystore is configured so OTA updates
                // remain signature-compatible with existing HyperOS installs while keeping debuggable=false.
                storeFile = fallbackDebugStore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile?.exists() == true }
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.0")
    implementation("androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.runtime:runtime:1.6.7")
    implementation("androidx.compose.foundation:foundation:1.6.7")
    implementation("androidx.compose.animation:animation:1.6.7")
    implementation("androidx.compose.ui:ui:1.6.7")
    implementation("androidx.compose.ui:ui-graphics:1.6.7")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.7")
    implementation("androidx.compose.material:material-icons-core:1.6.7")
    implementation("androidx.compose.material:material-ripple:1.6.7")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
