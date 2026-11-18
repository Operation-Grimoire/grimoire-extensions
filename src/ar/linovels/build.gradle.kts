plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.grimoire.extension.ar.linovels"
    compileSdk = 36
    buildFeatures { resValues = true }

    defaultConfig {
        applicationId = "io.grimoire.extension.ar.linovels"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        val minimumApi = "1.6"
        resValue("string", "grimoire_extension_min_api", minimumApi)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":lib"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.org.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
