plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // No kotlin.android: AGP 9.0+ has built-in Kotlin for Android modules (it applies KGP itself).
    alias(libs.plugins.kotlin.jvm) apply false           // :core (pure JVM) still needs it explicitly
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false // :core
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kover) apply false
}

// AGP 9.x carries a MINIMUM KGP (2.2.10) and will only upgrade older ones. Pin the whole build to
// our Kotlin line (2.3.21) + matching KSP (2.3.9) so :core (kotlin.jvm) and the Android modules
// (built-in Kotlin) compile on ONE Kotlin version. See plan "Version pin rationale".
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.9")
    }
}
