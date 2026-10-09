import com.android.build.api.variant.impl.VariantOutputImpl
import java.io.FileInputStream
import java.util.Properties

// Single source of truth for the app version. Keep these in sync with the git
// release tag (e.g. tag v0.1.22-preview -> versionName "0.1.22-preview").
val appVersionCode = 22
val appVersionName = "0.1.22-preview"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is opt-in via a properties file kept OUTSIDE the repo
// (storeFile / storePassword / keyAlias / keyPassword). Debug builds need none.
val releaseProps = Properties().apply {
    val path = System.getenv("GOUTOU_KEYSTORE_PROPS")
    if (!path.isNullOrBlank()) {
        val source = file(path)
        if (source.isFile) FileInputStream(source).use { load(it) }
    }
}

android {
    namespace = "com.jev.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.goutoujunshi.chat"
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // ML Kit's bundled Chinese recognizer ships native libs for every ABI.
        // The target phone (and every phone this can run on: minSdk 30) is
        // arm64, so keep only that one — the other three are dead weight.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Uncompressed, page-aligned .so files: required for the 16 KB page-size
    // devices Android 15+ ships, and it lets the loader mmap the ML Kit natives
    // instead of unpacking them at install time.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Name every APK with its version so builds from different releases never
// overwrite or get confused with one another.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? VariantOutputImpl)?.outputFileName?.set(
                "goutoujunshi-jev-chat-$appVersionName-${variant.name}.apk"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // On-device OCR. The *bundled* Chinese model (not the play-services variant):
    // it works on phones with no Google Play services and needs no model download.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
