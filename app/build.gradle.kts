plugins {
    id("com.android.application")
}

android {
    namespace = "it.allard.rassh"
    compileSdk = 37

    defaultConfig {
        applicationId = "it.allard.rassh"
        minSdk = 33
        targetSdk = 37
        versionCode = 14
        versionName = "0.1.13"
    }

    /* Release builds are signed only when the CI provides the keystore. */
    val keystore = System.getenv("RASSH_KEYSTORE_FILE")
    if (keystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("RASSH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RASSH_KEY_ALIAS")
                keyPassword = System.getenv("RASSH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    /*
     * The OpenSSH programs are shipped as lib*.so so that the package
     * manager extracts them to nativeLibraryDir, the only app location
     * the system allows to execute.
     */
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core"))
}
