plugins { alias(libs.plugins.android.application) }

val fixtureCoreApi = Regex("""val minimumApi\s*=\s*"([^"]+)"""")
    .find(rootProject.file("lib/build.gradle.kts").readText())
    ?.groupValues?.get(1)
    ?: error("Could not read the fixture minimum API from lib/build.gradle.kts")

android {
    namespace = "io.grimoire.extensions.parity"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "io.grimoire.extensions.parity"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FIXTURE_CORE_API", "\"$fixtureCoreApi\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    for (contract in listOf("royalroad", "galaxynovels", "shared")) {
        sourceSets.getByName("androidTest").kotlin.srcDir(rootProject.file("contracts/$contract/src/main/kotlin"))
    }
}

dependencies {
    implementation(libs.grimoire.extensions.api)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
