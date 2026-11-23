plugins {
    id("com.android.application")
}

// Release signing comes from the environment (CI secrets or your shell):
//   IMEBAR_KEYSTORE (path), IMEBAR_KEYSTORE_PASSWORD, IMEBAR_KEY_ALIAS, IMEBAR_KEY_PASSWORD
// Without them the release APK is signed with the debug key, for local testing only.
val releaseKeystore: String? = System.getenv("IMEBAR_KEYSTORE")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.lazyexec.imebar"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lazyexec.imebar"
        minSdk = 30
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("IMEBAR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("IMEBAR_KEY_ALIAS")
                keyPassword = System.getenv("IMEBAR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Xposed loads classes by name (assets/xposed_init); keep the code as is
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

base {
    archivesName.set("ImeBar-${android.defaultConfig.versionName}")
}

dependencies {
    compileOnly(project(":xposed-stubs"))
}
