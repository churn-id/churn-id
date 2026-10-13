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

    packaging {
        // Each Bouncy Castle jar carries this file.
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
}

dependencies {
    implementation("com.hierynomus:sshj:0.41.1")
    // Comes with sshj; ChurnApp registers its provider.
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only a stub in unit tests.
    testImplementation("org.json:json:20250517")
}
