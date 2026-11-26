plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/*
 * Build identity (docs/release-process.md). The field channel is the debug build type
 * (applicationId com.example.wiprober.debug) published from tags `vX.Y.Z-throughput.N`:
 *   versionName = the tag without "v"          e.g. 3.0.0-throughput.5 (+ "-debug")
 *   versionCode = X*1_000_000 + Y*10_000 + Z*100 + N   e.g. 3_000_005 (> 5 used by .1-.4)
 * -PappVersionName / -PappVersionCode override both; local builds without them are
 * "3.0.0-dev" with versionCode 1, so a field build always installs over them.
 */
val appVersionName: String = providers.gradleProperty("appVersionName").orNull ?: "3.0.0-dev"
val appVersionCode: Int = providers.gradleProperty("appVersionCode").orNull?.toInt()
    ?: Regex("""^(\d+)\.(\d+)\.(\d+)-throughput\.(\d+)$""").matchEntire(appVersionName)?.destructured
        ?.let { (major, minor, patch, build) ->
            require(minor.toInt() < 100 && patch.toInt() < 100 && build.toInt() < 100) { "Version part out of range: $appVersionName" }
            major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + build.toInt()
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
    namespace = "com.example.wiprober"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.wiprober"
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
