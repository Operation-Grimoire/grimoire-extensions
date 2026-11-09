pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Operation-Grimoire/grimoire-extensions-api")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "grimoire-extensions"

// Opt-in development against the unpublished shared runtime; release pins stay in the catalog.
providers.gradleProperty("grimoireApiDir").orNull?.let {
    includeBuild(it) {
        dependencySubstitution {
            substitute(module("io.grimoire:extensions-api")).using(project(":api"))
            substitute(module("io.grimoire:extensions-core")).using(project(":core"))
        }
    }
}

include(":lib")

// Auto-include all extensions from src/{lang}/{name}/
// Module names: :en-novelfull, :zh-xxx, etc.
File(rootDir, "src")
    .walkTopDown()
    .filter { it.isDirectory && File(it, "build.gradle.kts").exists() }
    .forEach { dir ->
        val moduleName = "${dir.parentFile.name}-${dir.name}"
        include(":$moduleName")
        project(":$moduleName").projectDir = dir
    }
