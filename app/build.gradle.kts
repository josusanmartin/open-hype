plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val releaseStoreFile =
    providers
        .gradleProperty("HYPE_RELEASE_STORE_FILE")
        .orElse(providers.environmentVariable("HYPE_RELEASE_STORE_FILE"))
val releaseStorePassword =
    providers
        .gradleProperty("HYPE_RELEASE_STORE_PASSWORD")
        .orElse(providers.environmentVariable("HYPE_RELEASE_STORE_PASSWORD"))
val releaseKeyAlias =
    providers
        .gradleProperty("HYPE_RELEASE_KEY_ALIAS")
        .orElse(providers.environmentVariable("HYPE_RELEASE_KEY_ALIAS"))
val releaseKeyPassword =
    providers
        .gradleProperty("HYPE_RELEASE_KEY_PASSWORD")
        .orElse(providers.environmentVariable("HYPE_RELEASE_KEY_PASSWORD"))
val hasReleaseSigning =
    !releaseStoreFile.orNull.isNullOrBlank() &&
        !releaseStorePassword.orNull.isNullOrBlank() &&
        !releaseKeyAlias.orNull.isNullOrBlank() &&
        !releaseKeyPassword.orNull.isNullOrBlank()

val releaseSigningInputs =
    mapOf(
        "HYPE_RELEASE_STORE_FILE" to releaseStoreFile.orNull,
        "HYPE_RELEASE_STORE_PASSWORD" to releaseStorePassword.orNull,
        "HYPE_RELEASE_KEY_ALIAS" to releaseKeyAlias.orNull,
        "HYPE_RELEASE_KEY_PASSWORD" to releaseKeyPassword.orNull,
    )

android {
    namespace = providers.gradleProperty("app.namespace").get()
    compileSdk = providers.gradleProperty("android.compileSdk").get().toInt()

    defaultConfig {
        applicationId = providers.gradleProperty("app.id").get()
        minSdk = providers.gradleProperty("android.minSdk").get().toInt()
        targetSdk = providers.gradleProperty("android.targetSdk").get().toInt()
        versionCode = 31
        versionName = "0.31.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:playback"))
    implementation(project(":core:ui"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:catalog"))
    implementation(project(":feature:library"))
    implementation(project(":feature:search"))
    implementation(project(":feature:details"))
    implementation(project(":feature:player"))
    implementation(project(":auto"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.coil.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}

tasks.register("validateReleaseSigning") {
    group = "verification"
    description = "Warns (or fails with -PrequireReleaseSigning=true) when upload-key signing credentials are missing."
    val requireSigning = providers.gradleProperty("requireReleaseSigning").orNull == "true"
    doLast {
        val missing =
            releaseSigningInputs
                .filterValues { it.isNullOrBlank() }
                .keys
                .joinToString()
        if (missing.isNotBlank()) {
            // The default `build`/`assemble` lifecycle reaches assembleRelease,
            // so failing here broke every machine without release credentials.
            // Local builds produce an unsigned release; CI's signed packaging
            // passes the flag to keep the hard guarantee.
            if (requireSigning) {
                throw GradleException(
                    "Release signing is required for Play-ready artifacts. Missing: $missing. " +
                        "Set these as Gradle properties or environment variables.",
                )
            }
            logger.warn(
                "Release signing credentials missing ($missing); producing an UNSIGNED release build. " +
                    "Pass -PrequireReleaseSigning=true to fail instead.",
            )
        }
    }
}

tasks
    .matching { task ->
        task.name == "assembleRelease" || task.name == "bundleRelease"
    }.configureEach {
        dependsOn("validateReleaseSigning")
    }
