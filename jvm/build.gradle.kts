import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { kotlin("jvm") version "2.2.10" }

// Separate Gradle invocation from the Android build, which already sets this.
// A changing core SNAPSHOT would otherwise stay cached for 24 hours.
configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val apiVersion = providers.gradleProperty("extensionsCoreVersion").orElse(libs.versions.extensionsApi)
val coreApi = apiVersion.map { version ->
    version.removeSuffix("-SNAPSHOT").split(".").take(2).joinToString(".")
}
val repo = rootProject.layout.projectDirectory.dir("..")
val libVersion = Regex("""\?: "([0-9][^"]*)"""")
    .find(repo.file("lib/build.gradle.kts").asFile.readText())
    ?.groupValues?.get(1)
    ?: error("Could not read the lib publish version from lib/build.gradle.kts")

fun hostDependencies(configuration: String) {
    dependencies {
        add(configuration, apiVersion.map { "io.grimoire:extensions-core:$it" })
        add(configuration, "org.json:json:20240303")
        add(configuration, "org.jsoup:jsoup:1.17.2")
        add(configuration, "com.squareup.okhttp3:okhttp:4.12.0")
        add(configuration, "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        add(configuration, kotlin("stdlib"))
    }
}

// The Android settings file includes these same directories. This build only
// compiles them, so android.jar is not on the classpath.
val extensionDirs = repo.asFile.resolve("src").walkTopDown()
    .filter { it.isDirectory && it.resolve("build.gradle.kts").isFile }
    .sortedBy { it.invariantSeparatorsPath }
    .toList()

data class ExtensionIdentity(
    val module: String,
    val directory: File,
    val packageName: String,
    val sourceClass: String,
    val versionCode: String,
    val versionName: String,
    val sourceVersionCode: String,
    val hostLibraries: String,
)

fun identityOf(dir: File): ExtensionIdentity {
    val module = "${dir.parentFile.name}-${dir.name}"
    val gradle = dir.resolve("build.gradle.kts").readText()
    fun pull(label: String, pattern: String) = Regex(pattern).find(gradle)?.groupValues?.get(1)
        ?: error("$module build.gradle.kts has no $label")
    val packageName = pull("applicationId", """applicationId\s*=\s*"([^"]+)"""")
    val versionCode = pull("versionCode", """versionCode\s*=\s*(\d+)""")
    val versionName = pull("versionName", """versionName\s*=\s*"([^"]+)"""")
    val manifest = dir.resolve("src/main/AndroidManifest.xml").readText()
    val sourceClass = Regex("""android:name="grimoire\.extension\.class"[\s\S]*?android:value="([^"]+)"""")
        .find(manifest)?.groupValues?.get(1)
        ?: error("$module manifest has no grimoire.extension.class")
    val sources = dir.resolve("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
    if (sources.isEmpty()) error("$module has no Kotlin sources")
    val joined = sources.joinToString("\n") { it.readText() }
    val at = joined.indexOf("@SourceInfo")
    if (at < 0) error("$module has no @SourceInfo")
    val window = joined.substring(at, minOf(at + 800, joined.length))
    val sourceVersionCode = Regex("""versionCode\s*=\s*(\d+)""").find(window)?.groupValues?.get(1)
        ?: error("$module @SourceInfo has no versionCode")
    sources.forEach { file ->
        val pkg = Regex("""(?m)^package\s+([\w.]+)""").find(file.readText())?.groupValues?.get(1)
            ?: error("${file.name} has no package")
        if (pkg != packageName && !pkg.startsWith("$packageName.")) {
            error("${file.name} package $pkg is outside $packageName")
        }
    }
    val libraries = mutableListOf("org.json:json:20240303")
    if (joined.contains("io.grimoire.extensions.lib")) libraries += "io.grimoire:extensions-lib:$libVersion"
    return ExtensionIdentity(
        module, dir, packageName, sourceClass, versionCode, versionName, sourceVersionCode,
        libraries.joinToString(","),
    )
}

fun jsonQuote(value: String) = buildString {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> append(ch)
        }
    }
    append('"')
}

fun String.sourceSetName() = split("-").joinToString("") { part ->
    part.replaceFirstChar { it.titlecase() }
}.replaceFirstChar { it.lowercase() }

val libSource = sourceSets.create("extensionsLib")
kotlin.sourceSets.getByName("extensionsLib").kotlin.setSrcDirs(listOf(repo.file("lib/src/main/java").asFile))
hostDependencies("extensionsLibImplementation")
val libJar = tasks.register<Jar>("libJar") {
    archiveFileName.set("extensions-lib-$libVersion.jar")
    destinationDirectory.set(layout.buildDirectory.dir("host"))
    from(libSource.output)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val extensionJars = extensionDirs.map { dir ->
    val identity = identityOf(dir)
    val setName = identity.module.sourceSetName()
    val extensionSource = sourceSets.create(setName) {
        compileClasspath += libSource.output
        runtimeClasspath += libSource.output
    }
    kotlin.sourceSets.getByName(setName).kotlin.setSrcDirs(listOf(dir.resolve("src/main/java")))
    hostDependencies("${setName}Implementation")
    val identityFile = layout.buildDirectory.file("extensions/${identity.module}/identity.json")
    val jar = tasks.register<Jar>("jar${setName.replaceFirstChar { it.titlecase() }}") {
        group = "build"
        description = "Thin Java 11 JAR for ${identity.module}"
        archiveFileName.set("${identity.packageName}-${identity.versionName}.jar")
        destinationDirectory.set(layout.buildDirectory.dir("extensions/${identity.module}"))
        from(extensionSource.output)
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        manifest.attributes(
            "Grimoire-Extension-Format" to "1",
            "Grimoire-Core-Api" to coreApi.get(),
            "Grimoire-Source-Package" to identity.packageName,
            "Grimoire-Source-Class" to identity.sourceClass,
            "Grimoire-Version-Code" to identity.versionCode,
            "Grimoire-Version-Name" to identity.versionName,
            "Grimoire-Source-Version-Code" to identity.sourceVersionCode,
            "Grimoire-Host-Libraries" to identity.hostLibraries,
        )
        doLast {
            val json = listOf(
                "module" to identity.module,
                "package" to identity.packageName,
                "sourceClass" to identity.sourceClass,
                "versionCode" to identity.versionCode,
                "versionName" to identity.versionName,
                "sourceVersionCode" to identity.sourceVersionCode,
                "hostLibraries" to identity.hostLibraries,
            ).joinToString(",\n", "{\n", "\n}\n") { (key, value) -> "  ${jsonQuote(key)}: ${jsonQuote(value)}" }
            identityFile.get().asFile.writeText(json)
        }
        outputs.file(identityFile)
    }
    identity to jar
}

tasks.register("jars") {
    group = "build"
    description = "Build a thin JAR for every extension under src/"
    dependsOn(libJar, extensionJars.map { it.second })
}

val royalroad = extensionJars.single { it.first.module == "en-royalroad" }
sourceSets.named("test").configure {
    compileClasspath += sourceSets.getByName(royalroad.first.module.sourceSetName()).output
    runtimeClasspath += sourceSets.getByName(royalroad.first.module.sourceSetName()).output
}
kotlin.sourceSets.getByName("test").kotlin.srcDir(repo.file("src/en/royalroad/src/test/java").asFile)
kotlin.target.compilations.getByName("test").associateWith(
    kotlin.target.compilations.getByName(royalroad.first.module.sourceSetName()),
)
dependencies {
    testImplementation("io.grimoire:extensions-core:${apiVersion.get()}")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jsoup:jsoup:1.17.2")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.named<Jar>("jar") { enabled = false }

tasks.named<Test>("test") {
    useJUnitPlatform()
    dependsOn(royalroad.second)
    val identity = royalroad.first
    val jarFile = layout.buildDirectory.file("extensions/${identity.module}/${identity.packageName}-${identity.versionName}.jar")
    val identityFile = layout.buildDirectory.file("extensions/${identity.module}/identity.json")
    inputs.file(jarFile)
    inputs.file(identityFile)
    outputs.file(layout.buildDirectory.file("extensions/${identity.module}/contract-jvm.json"))
    systemProperty("extension.jar", jarFile.map { it.asFile.absolutePath }.get())
    systemProperty("extension.identity", identityFile.map { it.asFile.absolutePath }.get())
    systemProperty(
        "contract.result",
        layout.buildDirectory.file("extensions/${identity.module}/contract-jvm.json").get().asFile.absolutePath,
    )
}
