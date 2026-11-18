plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "com.smartpesa.student"
    compileSdk = 35
    defaultConfig {
        // A separate ID allows the native app and the legacy Flutter app to coexist.
        // Android test cleanup uninstalls its target. Use a separate target ID to protect user data.
        applicationId = if (providers.gradleProperty("ledgerDeviceTests").orNull == "true")
            "com.smartpesa.student.ledgertests" else "com.smartpesa.student.nativeapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = if (providers.gradleProperty("deviceRecoveryProbe").orNull == "true") {
            check(providers.gradleProperty("ledgerDeviceTests").orNull == "true") { "Recovery probe requires the disposable app ID." }
            "com.smartpesa.student.data.RecoveryProbe"
        } else "androidx.test.runner.AndroidJUnitRunner"
        javaCompileOptions { annotationProcessorOptions { arguments["room.schemaLocation"] = "$projectDir/schemas" } }
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    kapt("androidx.room:room-compiler:2.7.2")
    testImplementation("junit:junit:4.13.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
}

// Fixtures use isolated databases; the separate application ID also isolates install cleanup.
dependencies {
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.7.2")
}

// Fail before the runner installs or removes packages if the isolation flag was forgotten.
tasks.matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }.configureEach {
    doFirst {
        check(providers.gradleProperty("ledgerDeviceTests").orNull == "true") {
            "Device tests require -PledgerDeviceTests=true to protect the normal app and its data."
        }
        check(providers.gradleProperty("deviceRecoveryProbe").orNull != "true") {
            "The recovery probe is a separate shell acceptance check, not the connected JUnit suite."
        }
    }
}
