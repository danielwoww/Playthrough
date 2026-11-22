plugins {
    id("com.android.application")
}

android {
    namespace = "com.playthrough.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.playthrough.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // Release signing comes from environment variables (set by the release workflow).
    // Without them, the release APK is built unsigned.
    val keystoreFile = System.getenv("PLAYTHROUGH_KEYSTORE")
    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("PLAYTHROUGH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PLAYTHROUGH_KEY_ALIAS")
                keyPassword = System.getenv("PLAYTHROUGH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
