plugins {
    id("com.android.application")
}

android {
    namespace = "com.liverepublic.streamer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.liverepublic.streamer"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SERVER_URL", "\"https://live-republic-server.fly.dev\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // AWS IVS 실송출 (Issue #5)
    implementation("com.amazonaws:ivs-broadcast:1.45.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
