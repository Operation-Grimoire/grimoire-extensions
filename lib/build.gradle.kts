plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

// Publication version. The publish-lib workflow builds from a lib-vX.Y.Z tag
// and sets LIB_RELEASE_TAG to publish the immutable release X.Y.Z. Every other
// build (main, local) publishes a -SNAPSHOT of the next version for cross-repo
// development; bump the base below when a release is cut. See AGENTS.md.
val publishVersion: String =
    System.getenv("LIB_RELEASE_TAG")
        ?.trim()
        ?.removePrefix("lib-v")
        ?.removePrefix("v")
        ?.takeIf { it.isNotEmpty() }
        ?: "1.0.0-SNAPSHOT"

android {
    namespace = "io.grimoire.extensions.lib"
    compileSdk = 36
    buildFeatures { resValues = true }

    defaultConfig {
        minSdk = 26
        // Extensions using a newer API override this default compatibility floor.
        val minimumApi = "1.5"
        require(minimumApi.matches(Regex("""\d+\.\d+"""))) { "minimumApi must be <major.minor>, got $minimumApi" }
        resValue("string", "grimoire_extension_min_api", minimumApi)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Unit tests construct a subclassed theme source directly, which transitively
    // initializes the default OkHttp client (WebViewCookieJar → android.webkit
    // .CookieManager). Returning default values for unmocked Android calls lets
    // that initializer set its field to null rather than throw — the cookie jar
    // isn't used by the page-parsing tests.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(libs.grimoire.extensions.api)
    testImplementation(libs.junit.jupiter)
    // Gradle 9 no longer auto-resolves the JUnit Platform Launcher from
    // ServiceLoader — without this dep tests are silently discovered as zero.
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

afterEvaluate {
    publishing {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Operation-Grimoire/grimoire-extensions")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.grimoire"
                artifactId = "extensions-lib"
                version = publishVersion
            }
        }
    }
}
