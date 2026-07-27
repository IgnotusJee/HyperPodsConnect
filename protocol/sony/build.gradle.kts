plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

sourceSets {
    test {
        resources.srcDir(rootProject.file("testdata"))
    }
}

kotlin {
    jvmToolchain(JavaVersion.VERSION_22.majorVersion.toInt())
}

tasks.withType<Test> {
    useJUnit()
}
