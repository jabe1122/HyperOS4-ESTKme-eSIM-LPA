plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "io.github.local.esimh4ui"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    defaultConfig {
        minSdk = 35
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        // Miuix navigation inline functions require JVM 21; AGP also uses this for Kotlin.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
dependencies {
    // `api`: the app screens are built with the same Miuix components.
    api(libs.androidx.activity.compose)
    api(libs.bundles.miuix)
}
