plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/*
 * Build identity (docs/release-process.md). Releases are published from tags `vX.Y.Z`
 * (semantic versioning from 4.0.0; `vX.Y.Z-rc.N` for test builds, `-throughput.N` for the
 * 3.0.0 builds):
 *   versionName = the tag without "v"                    e.g. 4.0.0 (+ "-debug")
 *   versionCode = X*1_000_000 + Y*10_000 + Z*100 + N     e.g. 4_000_000 (> 3_000_005 of 3.0.0-throughput.5)
 * N is the -rc/-throughput number, 0 for a release. -PappVersionName / -PappVersionCode
 * override both; local builds without them are "4.0.0-dev" with versionCode 1, so a
 * published build always installs over them.
 */
val appVersionName: String = providers.gradleProperty("appVersionName").orNull ?: "4.0.0-dev"
val appVersionCode: Int = providers.gradleProperty("appVersionCode").orNull?.toInt()
    ?: Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(?:rc|throughput)\.(\d+))?$""").matchEntire(appVersionName)?.groupValues
        ?.let { parts ->
            val (major, minor, patch) = parts.subList(1, 4).map(String::toInt)
            val build = parts[4].ifEmpty { "0" }.toInt()
            require(minor < 100 && patch < 100 && build < 100) { "Version part out of range: $appVersionName" }
            major * 1_000_000 + minor * 10_000 + patch * 100 + build
        }
    ?: 1
/** Source commit shown in About; CI passes -PgitSha, local builds ask git. */
val gitSha: String = providers.gradleProperty("gitSha").orNull
    ?: runCatching {
        providers.exec { commandLine("git", "rev-parse", "HEAD"); isIgnoreExitValue = true }
            .standardOutput.asText.get().trim()
    }.getOrNull().orEmpty().ifEmpty { "unknown" }
val buildChannel: String = providers.gradleProperty("buildChannel").orNull ?: "local"

android {
    namespace = "com.mpedreira.nimwifi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mpedreira.nimwifi"
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "BUILD_CHANNEL", "\"$buildChannel\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Field-channel signing identity (docs/release-process.md). Without these variables the
    // debug build type keeps the machine's debug key, which is NOT a stable identity.
    val fieldKeystore = providers.environmentVariable("FIELD_KEYSTORE_FILE").orNull
    val fieldSigning = fieldKeystore?.let { path ->
        signingConfigs.create("field") {
            storeFile = file(path)
            storePassword = providers.environmentVariable("FIELD_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("FIELD_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("FIELD_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            fieldSigning?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // Opt-in isolated package for testing the same R8 rules on a device.
    if (providers.gradleProperty("r8Validation").isPresent) {
        buildTypes.create("r8Validation") {
            initWith(buildTypes.getByName("release"))
            applicationIdSuffix = ".r8validation"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            proguardFile("r8-validation-rules.pro")
        }
        testBuildType = "r8Validation"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Universal APK configuration - includes all ABIs
    splits {
        abi {
            isEnable = false // Disable split APKs to create universal APK
        }
    }

}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.activity:activity-ktx:1.11.0")
}
