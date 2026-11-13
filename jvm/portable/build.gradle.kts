import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { kotlin("jvm") }

val apiVersion = providers.gradleProperty("extensionsCoreVersion").orElse(libs.versions.extensionsApi)

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
    sourceSets.main {
        // Sources that used to call Android APIs directly. Compiling them here
        // is the gate that they stay on the shared host interfaces.
        listOf(
            "../lib/src/main/java",
            "../src/en/webnovel/src/main/java",
            "../src/en/azurechronicles/src/main/java",
            "../src/all/zlibrary/src/main/java",
            "../src/ar/galaxynovels/src/main/java",
        ).forEach { kotlin.srcDir(rootProject.file(it)) }
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    compileOnly("io.grimoire:extensions-core:${apiVersion.get()}")
    compileOnly("org.json:json:20240303")
    compileOnly("org.jsoup:jsoup:1.17.2")
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
