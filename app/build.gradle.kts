plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")
}

android {
    namespace = "com.arashivision.sdk.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arashivision.sdk.demo"
        minSdk = 29
        targetSdk = 35
        versionCode = 58
        versionName = libs.versions.insta.get()
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/rxjava.properties"
            )

            pickFirsts += listOf(
                "lib/arm64-v8a/libc++_shared.so"
            )
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("G:\\camerasdk\\sdkdemo2\\app\\sdk.jks")
            storePassword = "insta360"
            keyAlias = "insta360"
            keyPassword = "insta360"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }

    applicationVariants.configureEach {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                outputFileName = "insta_sdk_demo_${buildType.name}_${versionName}.apk"
            }
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
        viewBinding = true
    }

    ndkVersion = "25.2.9519653"
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.preference)
    implementation(libs.preference.ktx)
    implementation(libs.material)
    implementation(libs.androidx.viewbinding)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.swiperefreshlayout)

    implementation(libs.xx.permissions)
    implementation(libs.flowlayout)
    implementation(libs.lottie)
    implementation(libs.glide)
    kapt(libs.glide.compiler)
    implementation(libs.immersionbar)
    implementation(libs.xlog)
    implementation(libs.filepicker)

    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")

    implementation(libs.insta.camera)
    implementation(libs.insta.media)



    implementation(libs.glide.transformations)

    implementation(project(":lib"))

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
