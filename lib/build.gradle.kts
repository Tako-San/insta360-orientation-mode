plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// :lib is the blocking CI gate; enforce a coverage floor so the pure-JVM logic
// (orientation math, panorama geometry, smoothing, detection) can't silently regress.
// Current coverage is ~97%; 80% leaves headroom without being a rubber stamp.
kover {
    reports {
        verify {
            rule {
                minBound(80)
            }
        }
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation("junit:junit:4.13.2")
}
