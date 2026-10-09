plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nestra.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nestra.remote"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "0.3.9"       // ETAP 10 RC2: preserve PC local disconnect over transport-close race
        buildConfigField("String", "PARENT_BASE_URL", "\"https://panel.nestraparent.com\"")
        buildConfigField("String", "REMOTE_BASE_URL", "\"https://remote.nestraparent.com\"")
        // AGPL-3.0 (the APK contains the RustDesk core): public Corresponding Source. Set before distributing any APK.
        buildConfigField("String", "SOURCE_URL", "\"${providers.gradleProperty("nestraSourceUrl").getOrElse("https://github.com/<owner>/nestra-remote-engine")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // QR: Google code scanner (Play services shows its own scanner UI; this app needs NO camera permission)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
}
