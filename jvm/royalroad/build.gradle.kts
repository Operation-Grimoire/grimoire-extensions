import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { kotlin("jvm") }

val sourceRoot = rootProject.file("../src/en/royalroad")
val metadata = Properties().apply { sourceRoot.resolve("extension.properties").inputStream().use(::load) }
val apiVersion = providers.gradleProperty("extensionsCoreVersion").orElse(libs.versions.extensionsApi)

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
    sourceSets.main { kotlin.srcDir(sourceRoot.resolve("src/main/java")) }
    sourceSets.test {
        kotlin.srcDir(sourceRoot.resolve("src/test/java"))
        kotlin.srcDir(rootProject.file("contract/src/main/kotlin"))
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
dependencies {
    compileOnly("io.grimoire:extensions-core:${apiVersion.get()}")
    compileOnly("org.json:json:20240303")
    testImplementation("io.grimoire:extensions-core:${apiVersion.get()}")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}
val extensionJar = tasks.named<Jar>("jar") {
    archiveFileName.set("${metadata.getProperty("package")}-${metadata.getProperty("versionName")}.jar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest.attributes(
        "Grimoire-Extension-Format" to "1",
        "Grimoire-Core-Api" to "1.3",
        "Grimoire-Source-Package" to metadata.getProperty("package"),
        "Grimoire-Source-Class" to metadata.getProperty("sourceClass"),
        "Grimoire-Version-Code" to metadata.getProperty("versionCode"),
        "Grimoire-Version-Name" to metadata.getProperty("versionName"),
        "Grimoire-Source-Version-Code" to metadata.getProperty("sourceVersionCode"),
        "Grimoire-Host-Libraries" to "org.json:json:20240303",
    )
}
tasks.test {
    useJUnitPlatform()
    dependsOn(extensionJar)
    inputs.file(extensionJar.flatMap { it.archiveFile })
    inputs.file(sourceRoot.resolve("extension.properties"))
    outputs.file(layout.buildDirectory.file("contract-jvm.json"))
    systemProperty("extension.jar", extensionJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("extension.metadata", sourceRoot.resolve("extension.properties").absolutePath)
    systemProperty("contract.result", layout.buildDirectory.file("contract-jvm.json").get().asFile.absolutePath)
}
