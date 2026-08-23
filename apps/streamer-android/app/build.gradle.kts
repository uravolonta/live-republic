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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    testImplementation("junit:junit:4.13.2")
}
