plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ch.fbc.krakenbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "ch.fbc.krakenbridge"
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
            isMinifyEnabled = true
            // No ndk.debugSymbolLevel: this project has no own NDK code. The only
            // .so in the AAB comes from androidx.graphics:graphics-path (transitive
            // Compose dep) and ships stripped, so AGP cannot extract symbols.
            // Play Console's "native debug symbols" warning is inherent here.
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
        buildConfig = true
    }
}

dependencies {
    // Core: ContextCompat, toUri, SharedPreferences.edit (used app-wide)
    implementation("androidx.core:core-ktx:1.18.0")
    // ComponentActivity + setContent for the Compose host
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose UI — versions resolved by the BOM
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    // ── Instrumented / BDD tests ─────────────────────────────────────────────
    // Cucumber-Android: Gherkin BDD runner. Features live in
    // src/androidTest/assets/features/; step definitions are auto-discovered
    // in the test APK via annotation scanning. Cucumber pulls junit transitively.
    androidTestImplementation("io.cucumber:cucumber-android:7.18.1")

    // InstrumentationRegistry — used by step definitions to obtain the target
    // context and uiAutomation handle.
    androidTestImplementation("androidx.test:runner:1.7.0")

    // UIAutomator — cross-app interaction (Google Camera, Google Photos).
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
