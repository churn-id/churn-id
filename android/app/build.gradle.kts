plugins {
    id("com.android.application")
}

android {
    namespace = "id.churn"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.churn"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    // The upload key is supplied by CI (or a local environment) and never
    // committed. Without it, bundleRelease produces an unsigned bundle.
    val keystorePath: String? = System.getenv("CHURN_KEYSTORE_PATH")

    signingConfigs {
        if (keystorePath != null) {
            create("upload") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("CHURN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CHURN_KEY_ALIAS")
                keyPassword = System.getenv("CHURN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("upload")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
