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

// versioning.md: the version is written once, in gradle.properties.
val appVersion = (project.findProperty("appVersion") as String).toInt()

// android-app.md §3: one permanent key for the life of the app. A different key
// is a different app to Android, and every install after it is an uninstall
// first. CI decodes it from secrets; without them a build is unsigned and the
// workflow says so rather than quietly producing an uninstallable APK.
val keystoreFile = rootProject.file("signing/mantra-ndi.p12")
val keystorePassword: String? = System.getenv("SIGNING_PASSWORD")

android {
    namespace = "com.mantraproductions.ndi"
    compileSdk = 35

    // Pinned so AGP stops reaching for whatever its default NDK happens to be;
    // CI installs exactly this version.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.mantraproductions.ndi"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersion
        versionName = appVersion.toString()

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
        val signing = if (keystoreFile.exists() && keystorePassword != null) {
            signingConfigs.getByName("mantra")
        } else null

        debug {
            signingConfig = signing
        }
        release {
            isMinifyEnabled = false
            signingConfig = signing
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
    testImplementation("junit:junit:4.13.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
