import java.util.Properties

plugins { id("com.android.application") }

// Signing comes from the SIGNING_* environment variables (CI) or from signing.* in
// probe/local.properties. Without either, the build falls back to the debug key.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty("signing.$key")?.takeIf { it.isNotBlank() }
val signingStoreFile = signingValue("storeFile", "SIGNING_STORE_FILE")
    ?.replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home"))

android {
    namespace = "io.github.local.esimh4tester"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.local.esimh4tester"
        minSdk = 33
        targetSdk = 35
        versionCode = 601
        versionName = "0.6.1"
    }
    signingConfigs {
        if (signingStoreFile != null) {
            create("local") {
                storeFile = file(signingStoreFile)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        if (signingStoreFile != null) {
            getByName("debug") { signingConfig = signingConfigs.getByName("local") }
            getByName("release") { signingConfig = signingConfigs.getByName("local") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
