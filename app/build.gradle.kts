plugins {
    id("com.android.application")
}

android {
    namespace = "it.allard.rassh"
    compileSdk = 37

    defaultConfig {
        applicationId = "it.allard.rassh"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    /*
     * ssh and ssh-keygen are shipped as lib*.so so that the package
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
