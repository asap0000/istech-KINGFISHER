plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.istech.privacycamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.istech.privacycamera"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // versionCode/versionName are injected by CI (see .github/workflows/release.yml)
        // so distributed builds auto-increment without manual edits. Local/CI debug builds
        // fall back to the defaults below.
        // istech バージョニング規約: リリースタグは vMAJOR.MINOR.PATCH の3桁 semver を用いる。
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.0-dev"

        vectorDrawables { useSupportLibrary = true }
    }

    // Release signing is driven entirely by environment variables (provided by CI
    // from GitHub Secrets, or set locally). The keystore is never committed — not even
    // in encrypted form, because this repository is public and a published ciphertext
    // can never be un-published. If no keystore is available, the release build is
    // simply left unsigned so the build never breaks.
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH") ?: "release.keystore"
    val releaseKeystoreFile = file(releaseKeystorePath)
    val hasReleaseSigning = releaseKeystoreFile.exists() &&
        !System.getenv("RELEASE_STORE_PASSWORD").isNullOrEmpty()

    // Kensa builds use their own identity and signing key so they can coexist with
    // the distributed app on the same device without risking the release key.
    val kensaKeystorePath =
        System.getenv("KENSA_KEYSTORE_PATH") ?: "kensa.keystore"
    val kensaKeystoreFile = file(kensaKeystorePath)
    val hasKensaSigning = kensaKeystoreFile.exists() &&
        !System.getenv("KENSA_STORE_PASSWORD").isNullOrEmpty()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                // The release keystore is PKCS12, which uses a single password: the key
                // password is always identical to the store password (a separate -keypass
                // is ignored at creation time). Use the store password directly so signing
                // can't be broken by a stale/incorrect RELEASE_KEY_PASSWORD secret.
                keyPassword = System.getenv("RELEASE_STORE_PASSWORD")
            }
        }
        if (hasKensaSigning) {
            create("kensa") {
                storeFile = kensaKeystoreFile
                storePassword = System.getenv("KENSA_STORE_PASSWORD")
                keyAlias = System.getenv("KENSA_KEY_ALIAS")
                // The kensa keystore is PKCS12, so its key password is necessarily
                // identical to its store password, just like the release keystore.
                keyPassword = System.getenv("KENSA_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("kensa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".kensa"
            versionNameSuffix = "-kensa"
            // initWith copies the release build type wholesale — including its signing
            // config. Left alone, a machine that has the release keystore but no kensa one
            // (CI, or a local shell missing KENSA_STORE_PASSWORD) signs the inspection build
            // with the distribution key: measured, `signingReport` reported proKensa as
            // Config: release. For a sideloaded product that is the worst kind of mix-up,
            // so the assignment below is unconditional. With no kensa key the build comes
            // out unsigned and refuses to install, which is the failure we want.
            signingConfig = if (hasKensaSigning) signingConfigs.getByName("kensa") else null
        }
    }

    // Run instrumentation against the non-debuggable build that is actually inspected.
    testBuildType = "kensa"

    // Two product tiers shipped from one codebase:
    //   lite -> free; capped local storage, encrypted one-way export, no masking
    //   pro  -> paid; unlimited storage, cumulative import, PII masking, advanced edit
    // The boolean BuildConfig.IS_PRO is the single source of truth for feature gating
    // (see com.istech.privacycamera.Tier). Lite ships under a distinct applicationId so it can
    // be installed alongside Pro; Pro uses the base id unsuffixed.
    // NOTE: the base id changed (com.privacycamera -> com.istech.privacycamera) when this app moved
    // to its own repository, so beta builds are a *different app* to Android — there is no upgrade
    // path from them. Data has to be carried over with the app's own export/restore.
    flavorDimensions += "tier"
    productFlavors {
        create("lite") {
            dimension = "tier"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
            buildConfigField("boolean", "IS_PRO", "false")
            resValue("string", "app_name", "プライバシーカメラ Lite")
        }
        create("pro") {
            dimension = "tier"
            versionNameSuffix = "-pro"
            buildConfigField("boolean", "IS_PRO", "true")
            resValue("string", "app_name", "プライバシーカメラ Pro")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // Robolectric がアプリのリソース/マニフェストを読めるようにする
            isIncludeAndroidResources = true
            all { test ->
                // ゴールデン画像の記録モード: ./gradlew ... -PupdateGoldens=true
                // (通常は record-goldens ワークフロー経由で実行する)
                test.systemProperty(
                    "updateGoldens",
                    project.findProperty("updateGoldens")?.toString() ?: "false"
                )
                // Bitmap を大量に扱うゴールデンテスト向け
                test.maxHeapSize = "2g"
            }
        }
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    debugImplementation(libs.compose.ui.tooling)
    // createComposeRule 用のホストアクティビティ(debugビルドのみ。releaseには入らない)
    debugImplementation(libs.compose.ui.test.manifest)

    // ---- 単体・ゴールデン・スクリーンショットテスト (JVM実行。APKには一切入らない) ----
    testImplementation(composeBom)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit.ktx)
    testImplementation(libs.truth)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)

    // Black-box instrumentation for the non-debuggable kensa build.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
