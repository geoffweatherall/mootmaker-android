import java.net.URI

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.apollo)
}

// Pinned version of the schema published by mootmaker-api (npm: @mootmaker/schema).
val schemaVersion = "6.2.0"

android {
    namespace = "com.mootmaker.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    api(libs.apollo.runtime)
    api(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(project(":testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
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
        packageName.set("com.mootmaker.data.graphql")
        schemaFiles.from(files(layout.buildDirectory.file("schema/mootmaker.graphql")).builtBy(fetchSchema))
    }
}
