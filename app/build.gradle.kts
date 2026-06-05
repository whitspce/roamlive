import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}

// Signing creds resolution order: env vars first, then keystore.properties file.
// Env vars are preferred — they live only in the shell session, never on disk.
fun signingCred(envVar: String, propKey: String): String? =
    System.getenv(envVar) ?: keystoreProperties.getProperty(propKey)

val resolvedStoreFile = signingCred("ROAM_STORE_FILE", "storeFile")
val resolvedStorePassword = signingCred("ROAM_STORE_PASSWORD", "storePassword")
val resolvedKeyAlias = signingCred("ROAM_KEY_ALIAS", "keyAlias")
val resolvedKeyPassword = signingCred("ROAM_KEY_PASSWORD", "keyPassword")
val hasSigningCreds = resolvedStoreFile != null && resolvedStorePassword != null

android {
    namespace = "dev.whitespc.roam"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.whitespc.roam"
        minSdk = 29
        targetSdk = 35
        versionCode = 9
        versionName = "0.4.0"
    }

    signingConfigs {
        create("release") {
            if (hasSigningCreds) {
                storeFile = file(resolvedStoreFile!!)
                storePassword = resolvedStorePassword
                keyAlias = resolvedKeyAlias
                keyPassword = resolvedKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigningCreds) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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
}
