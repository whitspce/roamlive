plugins {
    // Versionless on purpose: AGP is already on the build classpath via the
    // app module's android-application plugin.
    id("com.android.library")
}

android {
    namespace = "com.pedro.rtmp"
    compileSdk = 37

    defaultConfig {
        // Roam supports Android 10 and newer.
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    // Matches the upstream rtmp artifact's runtime dependencies.
    api(libs.root.encoder.common)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.bouncycastle.bcpkix)
}
