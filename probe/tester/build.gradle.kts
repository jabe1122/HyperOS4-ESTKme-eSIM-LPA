plugins { id("com.android.application") }
android {
    namespace = "io.github.local.esimh4tester"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.local.esimh4tester"
        minSdk = 33
        targetSdk = 35
        versionCode = 400
        versionName = "0.4.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
