plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ch.fbc.krakenbridge"
    // 37 is required by androidx.core 1.19.0; targetSdk deliberately stays
    // on 36 so runtime behaviour is unchanged until explicitly retested.
    compileSdk = 37

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
        // CucumberRunner is annotated with @CucumberOptions; optionsAnnotationPackage points
        // the runner at the package that contains it (cucumber-android otherwise scans only
        // the testApplicationId package, which is `ch.fbc.krakenbridge.test`).
        testInstrumentationRunner = "ch.fbc.krakenbridge.bdd.CucumberRunner"
        testInstrumentationRunnerArguments["tags"] = "not @device-only and not @manual"
        testInstrumentationRunnerArguments["optionsAnnotationPackage"] = "ch.fbc.krakenbridge.bdd"
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
            // Re-verified 2026-06-05 on AGP 9.2.1: debugSymbolLevel=SYMBOL_TABLE
            // yields no BUNDLE-METADATA debugsymbols entry — the .so has no
            // .symtab to extract. Accept the warning; it is informational only.
            // See https://developer.android.com/build/include-native-symbols
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
    
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // ObsoleteSdkInt fires on res/mipmap-anydpi-v26/ because minSdk=26
        // makes the -v26 qualifier technically redundant. AAPT, however,
        // does NOT resolve adaptive icons placed in res/mipmap-anydpi/ (no
        // version qualifier) — the build fails with "resource mipmap/ic_launcher
        // not found". The -v26 qualifier is the documented and only working
        // location for adaptive-icon XMLs, so the check is wrong for our case.
        disable += "ObsoleteSdkInt"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Core: ContextCompat, toUri, SharedPreferences.edit (used app-wide).
    // core-ktx became an empty artifact in 1.19.0 (ktx APIs merged into core),
    // so we depend on core directly.
    implementation("androidx.core:core:1.19.0")
    // ComponentActivity + setContent for the Compose host
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose UI — versions resolved by the BOM
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    // ── JVM unit tests ───────────────────────────────────────────────────────
    // Pure-logic tests (ReconnectBackoff, ButtonDebouncer, state transitions,
    // payload parsing) — no emulator, run by the unit-tests CI job.
    testImplementation("junit:junit:4.13.2")

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
