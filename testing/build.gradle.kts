// Test fakes shared by the unit, Robolectric and instrumented suites. Plain JVM: no Android needed.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
