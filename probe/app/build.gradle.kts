plugins { id("com.android.application") }
android {
    namespace = "io.github.local.esimh4probe"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.jabe1122.esimh4probe"
        minSdk = 33
        targetSdk = 35
        versionCode = 500
        versionName = "0.5.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += "BlockedPrivateApi"
    }
}
dependencies { compileOnly("io.github.libxposed:api:101.0.1") }
