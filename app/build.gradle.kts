import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ca.pinktv"
    compileSdk = 35
    defaultConfig {
        applicationId = "ca.pinktv"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "UPDATE_REPOSITORY", "\"kcote84/PinkTV\"")
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("production") {
            val local = Properties().apply { rootProject.file("local-signing/signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
            val path = System.getenv("PINKTV_KEYSTORE") ?: local.getProperty("storeFile")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("PINKTV_STORE_PASSWORD") ?: local.getProperty("storePassword")
                keyAlias = "pinktv"
                keyPassword = System.getenv("PINKTV_KEY_PASSWORD") ?: local.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("production")
            isMinifyEnabled = false
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
