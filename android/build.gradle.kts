// NESTRA Remote Android 0.1.0 - same toolchain as the NESTRA Parent Android project that already builds on this laptop
// (AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9, compileSdk 35, JBR 21).
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
