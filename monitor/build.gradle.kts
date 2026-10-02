// v113: MANTRA MONITOR, the camera's sister app. Marko, 2.10.2026: "A sister app is just one NDI monitor which can
// connect to this camera when NDI is enabled ... It reads all the streams from the network ... this camera is just
// giving all the same interface to remote user, but nothing is recorded." Same repository, same release, same key,
// same version number as the camera; it borrows the camera's native bridge and NDI runtime rather than copying them.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appCpp = file("../app/src/main/cpp")
val ndiSdkPresent = File(appCpp, "ndi/include/Processing.NDI.Lib.h").exists()
val appVersion = (project.findProperty("appVersion") as String).toInt()
val keystoreFile = rootProject.file("signing/mantra-ndi.p12")
val keystorePassword: String? = System.getenv("SIGNING_PASSWORD")

android {
    namespace = "com.mantraproductions.ndi.monitor"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.mantraproductions.ndi.monitor"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersion
        versionName = appVersion.toString()
        buildConfigField("boolean", "NDI_SDK_PRESENT", ndiSdkPresent.toString())
        ndk { abiFilters += listOf("arm64-v8a") }
        if (ndiSdkPresent) externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "../shared/kotlin")
            jniLibs.srcDirs("../app/src/main/jniLibs")
        }
    }

    if (ndiSdkPresent) {
        externalNativeBuild {
            cmake {
                path = File(appCpp, "CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    signingConfigs {
        create("mantra") {
            if (keystoreFile.exists() && keystorePassword != null) {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "mantrandi"
                keyPassword = keystorePassword
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        val signing = if (keystoreFile.exists() && keystorePassword != null) signingConfigs.getByName("mantra") else null
        debug { signingConfig = signing }
        release { isMinifyEnabled = false; signingConfig = signing }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
