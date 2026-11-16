plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.grimoire.extension.ar.novltime"
    compileSdk = 36
    buildFeatures { resValues = true }

    defaultConfig {
        applicationId = "io.grimoire.extension.ar.novltime"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"
        val minimumApi = "1.6"
        require(minimumApi.matches(Regex("""\d+\.\d+"""))) { "minimumApi must be <major.minor>, got $minimumApi" }
        resValue("string", "grimoire_extension_min_api", minimumApi)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
