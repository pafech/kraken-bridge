plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.krakenbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.krakenbridge"
        minSdk = 26
        targetSdk = 36
        // In CI the release workflow injects VERSION_CODE (github.run_number, monotonically
        // increasing) and VERSION_NAME (git tag minus the leading 'v'). Local builds fall
        // back to the literals below so gradle sync still works without env vars.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0"

        // Cucumber-Android discovers features from assets/features/ and step definitions
        // by scanning the test APK. Tags filter which scenarios run in CI vs. on device.
        testInstrumentationRunner = "io.cucumber.android.runner.CucumberAndroidJUnitRunner"
        testInstrumentationRunnerArguments["tags"] = "not @device-only and not @manual"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // Signing config reads from environment variables injected by the release workflow.
    // Local debug builds are unaffected — the release signing config is only applied
    // when KEYSTORE_PATH is set.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Only attach signing config when the env vars are present (i.e. in CI)
            if (!System.getenv("KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ── Unit tests ───────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")

    // ── Instrumented / BDD tests ─────────────────────────────────────────────
    // Cucumber-Android: Gherkin BDD runner for instrumented tests.
    // Features live in src/androidTest/assets/features/
    // Step definitions are auto-discovered in the test APK via annotation scanning.
    androidTestImplementation("io.cucumber:cucumber-android:7.14.0")

    // UIAutomator – cross-app interaction (Google Camera, Google Photos)
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")

    // AndroidX test infrastructure
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    // Espresso – in-app UI assertions for the Kraken Bridge activity itself
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
