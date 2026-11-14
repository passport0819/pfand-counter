// Builds the app with the Android Gradle Plugin from src/, res/ and AndroidManifest.xml.
//
//   ./gradlew assembleRelease   → build-gradle/outputs/apk/release/pfand-counter-release.apk
//
// Signed with keys/debug.keystore when it exists (a local key); without it the APK stays
// unsigned, which is what F-Droid wants: it builds from source and signs with its own key.
//
//   ./gradlew assembleRelease -PwithLocal   also compiles src-local/ (optional local extra
//                                           source, see src/app/pfandcounter/ExtraSource.java).
// Without -PwithLocal src-local/ is never compiled in, even when the folder exists.
plugins {
    id("com.android.application") version "9.4.1"
}

// Gradle keeps its output in build-gradle/ (build/ belongs to an older build script).
layout.buildDirectory = file("build-gradle")

// The installed app's identity: app-id.local (one line, not in the
// repository) keeps an existing installation's id, otherwise the published one.
val appId = file("app-id.local").takeIf { it.isFile }
    ?.readLines()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    ?: "io.github.passport0819.pfandcounter"

val keystore = file("keys/debug.keystore")

// src-local/ only on explicit request: -PwithLocal (or -PwithLocal=true). Any other value stops.
val withLocal = providers.gradleProperty("withLocal").map {
    when (it.lowercase()) {
        "", "true" -> true
        "false" -> false
        else -> throw GradleException("-PwithLocal must be empty, true or false, not '$it'")
    }
}.getOrElse(false)
if (withLocal && !file("src-local").isDirectory) {
    throw GradleException("-PwithLocal given, but src-local/ does not exist")
}
logger.lifecycle(
    if (withLocal) "local source src-local/: included (-PwithLocal)"
    else "local source src-local/: not included (pass -PwithLocal to include it)"
)

android {
    namespace = "app.pfandcounter"
    compileSdk = 36
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = appId
        minSdk = 34
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.directories.clear()
            java.directories.add("src")
            if (withLocal) java.directories.add("src-local")
            res.directories.clear()
            res.directories.add("res")
        }
    }

    signingConfigs {
        if (keystore.isFile) {
            create("local") {
                storeFile = keystore
                storePassword = "android"
                keyAlias = "pfand"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("release") {
            // AppCompat and Material Components bring far more than the app uses: R8 drops the rest.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("local")
            // No commit id of the working copy inside the APK.
            vcsInfo { include = false }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        // Compressed dex, so the download stays small.
        dex { useLegacyPackaging = true }
    }

    dependenciesInfo {
        // No Google-encrypted dependency block in the APK (F-Droid asks for this).
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    // Material 3 look: the activity and dialogs come from AppCompat, the design from Material Components.
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
}
