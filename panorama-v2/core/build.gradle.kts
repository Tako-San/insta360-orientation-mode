plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}
// kotlin-math 1.8.0 is published as Java 21 bytecode and exposes inline functions (fromAxisAngle,
// the q * v operator), so consumers MUST compile and run on JVM 21 — Kotlin refuses to inline
// Java-21 bytecode into a lower target. Hence the whole module is Java 21, not 17.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin { jvmToolchain(21) }
dependencies {
    api(libs.kotlin.math)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.assertions.core)
}
tasks.test { useJUnitPlatform() }   // Kotest runs on JUnit5 platform
kover {
    reports { verify { rule { minBound(90) } } }
}
