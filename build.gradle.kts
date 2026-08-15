// AGP's built-in Kotlin support currently ships an older compiler than the
// Compose plugin below. Loading the matching KGP keeps both on Kotlin 2.4.10.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
