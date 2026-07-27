plugins {
    alias(libs.plugins.agp.lib)
}

android {
    namespace = "moe.chenxy.headphones.transport.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_22
        targetCompatibility = JavaVersion.VERSION_22
    }
}

// Android only enters through the socket wrapper. Everything that decides when
// to read, write, time out or close is plain Kotlin working against SppSocket,
// so the whole state machine is unit-testable without a device or Robolectric.
dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

kotlin {
    jvmToolchain(JavaVersion.VERSION_22.majorVersion.toInt())
}
