import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}

// Signing creds resolution order: env vars first, then keystore.properties file.
// Env vars are preferred: they live only in the shell session, never on disk.
fun signingCred(envVar: String, propKey: String): String? =
    System.getenv(envVar) ?: keystoreProperties.getProperty(propKey)

val resolvedStoreFile = signingCred("ROAM_STORE_FILE", "storeFile")
val resolvedStorePassword = signingCred("ROAM_STORE_PASSWORD", "storePassword")
val resolvedKeyAlias = signingCred("ROAM_KEY_ALIAS", "keyAlias")
val resolvedKeyPassword = signingCred("ROAM_KEY_PASSWORD", "keyPassword")
val signingValues = listOf(
    resolvedStoreFile,
    resolvedStorePassword,
    resolvedKeyAlias,
    resolvedKeyPassword,
)
val hasAnySigningCreds = signingValues.any { !it.isNullOrBlank() }
val hasSigningCreds = signingValues.all { !it.isNullOrBlank() }

if (hasAnySigningCreds && !hasSigningCreds) {
    throw GradleException("Release signing configuration is incomplete")
}

android {
    namespace = "dev.whitespc.roam"
    // RootEncoder 2.8 is built against API 37. Target API 36 meets the current
    // Play requirement while keeping compile and runtime policy changes explicit.
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.whitespc.roam"
        minSdk = 29
        targetSdk = 36
        versionCode = 19
        versionName = "0.10.1"
    }

    signingConfigs {
        if (hasSigningCreds) {
            create("release") {
                storeFile = file(resolvedStoreFile!!)
                storePassword = resolvedStorePassword!!
                keyAlias = resolvedKeyAlias!!
                keyPassword = resolvedKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            // R8: dead-code and resource shrinking roughly halves the APK.
            // proguard-rules.pro carries the one reflection keep-rule we need.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // A production release is never signed with the debug key. Without
            // all four credentials, Gradle produces an unsigned release APK.
            if (hasSigningCreds) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// The SRT and RTMP layers are vendored copies of RootEncoder's matching
// modules (see srt/README.md and rtmp/README.md), with Roam's transport-safety
// patches. Substitution catches the transitive edges from the prebuilt
// library artifact. Bumping rootEncoder requires re-vendoring both modules.
configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("com.github.pedroSG94.RootEncoder:srt"))
            .using(project(":srt"))
        substitute(module("com.github.pedroSG94.RootEncoder:rtmp"))
            .using(project(":rtmp"))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.root.encoder)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)

    // RootEncoder's WHIP transport brings Bouncy Castle transitively. Keep the
    // complete family on the security-fixed patch level rather than mixing it
    // with the older versions declared by RootEncoder 2.8.0.
    constraints {
        implementation(libs.bouncycastle.bcpkix)
        implementation(libs.bouncycastle.bcprov)
        implementation(libs.bouncycastle.bctls)
        implementation(libs.bouncycastle.bcutil)
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
