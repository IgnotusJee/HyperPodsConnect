// KGP is already on the root buildscript classpath, so it must be applied
// without a version here or Gradle refuses to resolve it.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlinSerialization)
}

// Deliberately a plain Kotlin/JVM module: the compiler cannot resolve Android,
// Xposed, Compose or HyperOS types here, so the dependency rule that keeps
// domain logic testable is enforced by the build rather than by review.
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(JavaVersion.VERSION_22.majorVersion.toInt())
    compilerOptions {
        allWarningsAsErrors.set(false)
    }
}

tasks.withType<Test> {
    useJUnit()
}
