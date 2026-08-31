plugins {
    id("com.android.application")
}

android {
    namespace = "ai.sinergy.messenger"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.sinergy.messenger"
        minSdk = 26
        targetSdk = 37
        versionCode = 200
        versionName = "0.2.0-native"
        vectorDrawables.useSupportLibrary = true
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.core:core:1.17.0")
    implementation("io.github.tdlibx:td-android:1.8.56-RC9")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
}
