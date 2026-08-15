plugins {
    // Versionless on purpose: AGP is already on the build classpath via the
    // app module's android-application plugin, and Gradle refuses a
    // versioned re-request of the same artifact.
    id("com.android.library")
}

android {
    namespace = "com.pedro.srt"
    compileSdk = 37

    defaultConfig {
        // Roam itself requires Android 10. Matching that floor keeps lint
        // focused on the actual supported runtime instead of legacy APIs.
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    // Exactly what the upstream srt artifact's POM declares.
    api(libs.root.encoder.common)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.kotlin)
}
