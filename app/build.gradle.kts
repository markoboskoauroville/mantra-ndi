plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The NDI Advanced SDK is licensed and gitignored, so it won't be present on a
// fresh clone or in CI. Rather than failing the build, we compile without the
// native bridge: camera, preview, profiles and manual controls all still work,
// you just can't send. Drop the SDK in (see README) and the native half builds
// automatically on the next run.
val ndiSdkPresent = file("src/main/cpp/ndi/include/Processing.NDI.Lib.h").exists()

android {
    namespace = "com.mantraproductions.ndi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mantraproductions.ndi"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("boolean", "NDI_SDK_PRESENT", ndiSdkPresent.toString())

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        if (ndiSdkPresent) {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                }
            }
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    if (ndiSdkPresent) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// Kotlin 2.x removed kotlinOptions; jvmTarget lives here now.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // RootEncoder (Apache-2.0) — Camera2 + hardware MediaCodec pipeline.
    // We subclass its StreamBase to add NDI as an output alongside RTMP/RTSP/SRT.
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
}
