plugins {
    alias(libs.plugins.android.application)
    // No kotlin.android: AGP 9.0+ built-in Kotlin.
    alias(libs.plugins.kotlin.compose)   // version == Kotlin version (2.3.21)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.panorama.app"
    compileSdk = 36          // androidx.core 1.19 + media3 1.10 require compileSdk >= 36
    defaultConfig {
        applicationId = "com.panorama.app"
        minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "2.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }   // built-in Kotlin: top-level kotlin {} block
dependencies {
    implementation(project(":core"))
    implementation(project(":android"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.media3.exoplayer)
}
