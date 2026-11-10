plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.openstream.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.openstream.tv"
        minSdk = 23
        targetSdk = 36
        versionCode = 22
        versionName = "0.15.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    val releaseStoreFile = System.getenv("OPENSTREAM_RELEASE_STORE_FILE")
    val releaseStorePassword = System.getenv("OPENSTREAM_RELEASE_STORE_PASSWORD")
    val releaseKeyPassword = System.getenv("OPENSTREAM_RELEASE_KEY_PASSWORD")
    val releaseKeyAlias = System.getenv("OPENSTREAM_RELEASE_KEY_ALIAS") ?: "openstreamtv"
    if (
        !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank() &&
        file(releaseStoreFile).isFile
    ) {
        signingConfigs.create("production") {
            storeFile = file(releaseStoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
        buildTypes.named("release") {
            signingConfig = signingConfigs.getByName("production")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
