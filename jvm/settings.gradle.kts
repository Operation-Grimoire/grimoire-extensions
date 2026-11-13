pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/Operation-Grimoire/grimoire-extensions-api")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
rootProject.name = "grimoire-extensions-jvm"
providers.gradleProperty("grimoireApiDir").orNull?.let {
    includeBuild(it) {
        dependencySubstitution {
            substitute(module("io.grimoire:extensions-core")).using(project(":core"))
        }
    }
}
