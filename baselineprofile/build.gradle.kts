plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "dev.josu.hypecar.baselineprofile"
    compileSdk = providers.gradleProperty("android.compileSdk").get().toInt()

    defaultConfig {
        // Baseline Profiles apply to API 28+, while ProfileInstaller covers
        // this app's API 26-27 installs.
        minSdk = 28
        targetSdk = providers.gradleProperty("android.targetSdk").get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

baselineProfile {
    // API 33+ connected devices can collect profiles without root. This keeps
    // the harness lightweight and avoids a multi-gigabyte GMD download in CI.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.test.uiautomator)
}
