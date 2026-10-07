// :core - the NESTRA Remote client logic (Parent login + MFA, Remote token, devices, pairing, session) in plain Kotlin:
// no Android API, no third-party library. Unit tests: gradlew :core:test. Real-backend gate: tools/run-core-integration.sh
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
