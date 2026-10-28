import java.util.Properties

// No `kotlin-android`: AGP 9 built-in Kotlin compiles the sources
// (applying it is a hard error). `kotlin-compose` is still required —
// it wires the Compose compiler against the embedded Kotlin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// ---------------------------------------------------------------------------
// Release signing — read keystore.properties ONLY if the file exists.
// The keystore itself is gitignored (*.keystore / *.jks); never commit secrets.
// ---------------------------------------------------------------------------
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
    Properties().apply {
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.inputStream().use { load(it) }
        }
    }
val hasReleaseSigning =
    keystorePropertiesFile.exists() &&
        keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.buzzbike.app"
    // compileSdk 37 is forced by the resolved AndroidX artifacts: Compose
    // 1.12.x, navigation-compose 2.10.2 and hilt-navigation-compose 1.4.0
    // publish AAR metadata requiring compileSdk >= 37 (checkDebugAarMetadata).
    // targetSdk stays at 36 so runtime behavior does not change.
    compileSdk = 37

    defaultConfig {
        // TODO(app-id): applicationId is the approved placeholder
        // `com.buzzbike.app` (AGENTS.md §11). Confirm final id before first release.
        applicationId = "com.buzzbike.app"
        // minSdk 26 = Android 8: FGS + modern BLE baseline (AGENTS.md §2).
        minSdk = 26
        // targetSdk 36 matches compileSdk; distribution is GitHub Releases APK.
        targetSdk = 36
        // Explicit versioning: GitHub Releases upgrades rely on a monotonic
        // versionCode, and versionName is surfaced to the user.
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // No minify/shrink in the scaffold — enable once proguard rules exist.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                if (hasReleaseSigning) {
                    signingConfigs.getByName("release")
                } else {
                    null
                }
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig is not used; prefer BuildConfig-free code paths.
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose — versions come from the BOM; individual libs are unversioned.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)

    // Hilt — KSP for the compiler (not kapt).
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Room — schema/migrations arrive in slice 2; deps are wired now.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.coroutines.android)

    // MapLibre — declared for slice 3+ (maps); not referenced in code yet.
    implementation(libs.maplibre.android.sdk)

    // Play Services Location
    implementation(libs.play.services.location)

    // Nordic BLE (no.nordicsemi.android:ble) intentionally NOT added yet:
    // it would be a dead dependency until the wearable/sensor slice.

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}

detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
    ignoreFailures = false
}

ktlint {
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}
