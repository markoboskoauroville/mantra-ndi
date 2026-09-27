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

// THE VERSION IN THE ICON (v93). *"Under the camera, write the version number,
// so in the icon I can already see what my version is."* A vector drawable has
// no text, so the number is drawn: segment strokes for the digits and a v,
// under the camera, inside the 42-unit symbol box (modules/app-icon.md). The
// template is src/main/icon; the icon the app ships is generated from it here.
val versionIconDir = layout.buildDirectory.dir("generated/versionIcon").get().asFile
run {
    val segments = mapOf(
        'a' to "M0,0 H5", 'b' to "M5,0 V5", 'c' to "M5,5 V10", 'd' to "M0,10 H5",
        'e' to "M0,5 V10", 'f' to "M0,0 V5", 'g' to "M0,5 H5"
    )
    val digits = mapOf(
        '0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc",
        '5' to "afgcd", '6' to "afgedc", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg"
    )
    val text = "v$appVersion"
    val cell = 5.0
    val gap = 2.2
    val width = text.length * cell + (text.length - 1) * gap
    val left = 54.0 - width / 2
    val top = 64.0
    val paths = StringBuilder()
    text.forEachIndexed { i, ch ->
        val x = left + i * (cell + gap)
        val data = if (ch == 'v') "M0,4 L2.5,10 L5,4"
            else digits[ch].orEmpty().map { segments.getValue(it) }.joinToString(" ")
        // Each glyph's path is written in its 5 x 10 cell and moved into place.
        val moved = Regex("([MLHV])([-0-9.]+)(,([-0-9.]+))?").replace(data) { m ->
            val cmd = m.groupValues[1]
            val a = m.groupValues[2].toDouble()
            val b = m.groupValues[4].takeIf { it.isNotEmpty() }?.toDouble()
            // Rounded by hand: a Double prints with a dot in every locale.
            fun n(v: Double) = (Math.round(v * 100) / 100.0).toString()
            when (cmd) {
                "H" -> "H" + n(a + x)
                "V" -> "V" + n(a + top)
                else -> cmd + n(a + x) + "," + n((b ?: 0.0) + top)
            }
        }
        paths.append("    <path android:strokeColor=\"#E6E8EA\" android:strokeWidth=\"1.5\" ")
            .append("android:strokeLineCap=\"round\" android:strokeLineJoin=\"round\" ")
            .append("android:pathData=\"").append(moved).append("\" />\n")
    }
    val template = file("src/main/icon/ic_launcher_foreground.xml").readText()
    val out = File(versionIconDir, "drawable/ic_launcher_foreground.xml")
    out.parentFile.mkdirs()
    out.writeText(template.replace("    <!--VERSION-->\n", "    <!-- v$appVersion, generated -->\n$paths"))
}

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
            res.srcDirs("src/main/res", versionIconDir)
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
