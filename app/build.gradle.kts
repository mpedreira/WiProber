plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/*
 * Build identity (docs/release-process.md). Releases are built from tags `vX.Y.Z`, test builds
 * from `vX.Y.Z-rc.N`; the 3.0.0 builds were `v3.0.0-throughput.N`.
 *   versionName = the tag without "v"                       e.g. 4.0.0 (+ "-debug")
 *   versionCode = X*1_000_000 + Y*10_000 + Z*100 + slot
 *     slot = N for -rc.N (1..98), 99 for the stable release, N for the published -throughput.N
 * so every RC of a version sorts below its stable release and the stable release below the next
 * version's RCs: 4.0.0-rc.1 (4000001) < 4.0.0-rc.2 (4000002) < 4.0.0 (4000099) < 4.0.1-rc.1
 * (4000101). Published codes are kept: 3.0.0-throughput.5 was 3000005. Android's maximum
 * versionCode (2_100_000_000) bounds X at 2099. -PappVersionName / -PappVersionCode override;
 * local builds without them are "4.0.0-dev" with versionCode 1, below every published build.
 */
fun versionCodeOf(name: String): Int? {
    val parts = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(rc|throughput)\.(\d+))?$""").matchEntire(name)?.groupValues ?: return null
    val (major, minor, patch) = parts.subList(1, 4).map(String::toInt)
    val kind = parts[4]
    val n = parts[5].ifEmpty { "0" }.toInt()
    require(major <= 2099 && minor < 100 && patch < 100) { "Version part out of range: $name" }
    val slot = when (kind) {
        "rc" -> n.also { require(it in 1..98) { "RC number must be 1..98: $name" } }
        "throughput" -> n.also { require(major == 3 && minor == 0 && patch == 0 && it in 1..98) { "-throughput is only the published 3.0.0 series: $name" } }
        else -> 99
    }
    return major * 1_000_000 + minor * 10_000 + patch * 100 + slot
}

// Checked on every build (mejora 6): the scheme keeps RC < stable < next RC and published codes.
listOf("3.0.0-throughput.5", "4.0.0-rc.1", "4.0.0-rc.2", "4.0.0", "4.0.1-rc.1", "4.0.1", "4.1.0-rc.1").map { requireNotNull(versionCodeOf(it)) }
    .also { codes ->
        check(codes == codes.sorted() && codes.toSet().size == codes.size) { "versionCode scheme is not strictly increasing: $codes" }
        check(codes.first() == 3_000_005) { "Published versionCode of 3.0.0-throughput.5 changed" }
    }

val appVersionName: String = providers.gradleProperty("appVersionName").orNull ?: "4.0.0-dev"
val appVersionCode: Int = providers.gradleProperty("appVersionCode").orNull?.toInt()
    ?: versionCodeOf(appVersionName)
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

/** Prints the identity the field workflow checks the APK against. */
tasks.register("printVersionInfo") {
    val name = appVersionName
    val code = appVersionCode
    doLast { println("versionName=$name\nversionCode=$code") }
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
