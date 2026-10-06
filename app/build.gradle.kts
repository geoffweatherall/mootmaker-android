import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.apollo)
    alias(libs.plugins.roborazzi)
}

// Pinned version of the schema published by mootmaker-api (npm: @mootmaker/schema).
val schemaVersion = "6.2.0"

android {
    namespace = "com.mootmaker.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mootmaker.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.apollo.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
}

// Downloads the pinned schema from the public npm registry (no token needed) and extracts
// mootmaker.graphql for Apollo's code generator.
val fetchSchema by tasks.registering {
    val outDir = layout.buildDirectory.dir("schema")
    val version = schemaVersion
    inputs.property("version", version)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.apply { mkdirs() }
        val url = "https://registry.npmjs.org/@mootmaker/schema/-/schema-$version.tgz"
        val tgz = File(dir, "schema.tgz")
        URI(url).toURL().openStream().use { input -> tgz.outputStream().use { input.copyTo(it) } }
        exec {
            commandLine("tar", "xzf", tgz.absolutePath, "-C", dir.absolutePath, "--strip-components=1", "package/mootmaker.graphql")
        }
        tgz.delete()
    }
}

apollo {
    service("mootmaker") {
        packageName.set("com.mootmaker.app.graphql")
        schemaFiles.from(files(layout.buildDirectory.file("schema/mootmaker.graphql")).builtBy(fetchSchema))
    }
}
