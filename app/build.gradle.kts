plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// The release version, e.g. 6.1.0, passed by release-build.yml as -PmootmakerVersion. Local and PR
// builds have none. versionCode is major x 1,000,000 + minor x 1,000 + patch (design choice 7), so it
// always increases with the version; a pre-release suffix such as -rc1 is ignored.
val releaseVersion: String? = providers.gradleProperty("mootmakerVersion").orNull
val releaseVersionCode: Int? = releaseVersion?.let { version ->
    val parts = version.substringBefore('-').split('.').map { it.toInt() }
    require(parts.size == 3) { "mootmakerVersion must be major.minor.patch, not $version" }
    parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
}

// The release signing key, decoded to a file by release-build.yml. Never present in a cloud session,
// which builds debug variants only.
val keystoreFile: String? = providers.environmentVariable("MOOTMAKER_KEYSTORE_FILE").orNull

android {
    namespace = "com.mootmaker.app"
    compileSdk = 35

    defaultConfig {
        // Permanent: Android identifies the app by this on every device. See the design's choice 2.
        applicationId = "com.mootmaker.android"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode?.coerceAtLeast(1) ?: 1
        versionName = releaseVersion ?: "0.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = providers.environmentVariable("MOOTMAKER_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("MOOTMAKER_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("MOOTMAKER_KEY_PASSWORD").get()
            }
        }
    }

    // release-build.yml runs the acceptance suite against the release build itself, so the APK that
    // passed is the APK that ships. Everywhere else the instrumented tests use debug.
    testBuildType = providers.gradleProperty("mootmakerTestBuildType").getOrElse("debug")

    buildTypes {
        release {
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Lets a debug build sit beside the published app on one phone.
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":data"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(project(":testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit)

    androidTestImplementation(project(":testing"))
    androidTestImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
}
