plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.alexkuklin.btbmsmonitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.alexkuklin.btbmsmonitor"
        minSdk = 26
        targetSdk = 36
        // The release workflow passes these in; a local build keeps the defaults. Play wants versionCode to grow with every upload.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1"
    }

    // The UPLOAD key (Play App Signing holds the real app signing key). Only the release workflow has it, as environment variables;
    // without them the release build is simply unsigned, which is what the ordinary CI builds.
    signingConfigs {
        System.getenv("UPLOAD_KEYSTORE_FILE")?.let { path ->
            create("upload") {
                storeFile = file(path)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // The BMS parameter dump is not yet verified on real hardware: debug builds only.
        debug {
            buildConfigField("boolean", "PARAM_DUMP", "true")
        }
        release {
            signingConfig = signingConfigs.findByName("upload")
            isMinifyEnabled = false
            buildConfigField("boolean", "PARAM_DUMP", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}
