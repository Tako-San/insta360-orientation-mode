plugins {
    alias(libs.plugins.android.library)
    // No kotlin.android: AGP 9.0+ built-in Kotlin.
}
android {
    namespace = "com.panorama.android"
    compileSdk = 36          // androidx.core 1.19 + media3 1.10 require compileSdk >= 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17   // kotlin jvmTarget inherits from this
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true   // Robolectric
        unitTests.all { it.maxHeapSize = "1g" }      // Robolectric headroom over the 512m default
    }
}
kotlin { jvmToolchain(17) }   // built-in Kotlin: top-level kotlin {} block
dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
