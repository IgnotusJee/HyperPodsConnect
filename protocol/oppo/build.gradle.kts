// KGP is already on the root buildscript classpath, so it must be applied
// without a version here or Gradle refuses to resolve it.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Plain Kotlin/JVM like :core. The compiler cannot see Android, Xposed, Compose
// or the app's ConfigManager from here, so a parser physically cannot reach for
// them the way the current Packets.kt could.
dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}

sourceSets {
    test {
        // Same fixture tree the app tests read, so both sides are checked
        // against one set of protocol evidence.
        resources.srcDir(rootProject.file("testdata"))
    }
}

kotlin {
    jvmToolchain(JavaVersion.VERSION_22.majorVersion.toInt())
}

tasks.withType<Test> {
    useJUnit()
}
