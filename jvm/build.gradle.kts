plugins { kotlin("jvm") version "2.2.10" apply false }

// The Android root build already sets this. This JVM build is a separate
// Gradle invocation and would otherwise cache a changing SNAPSHOT for 24 hours.
allprojects {
    configurations.all {
        resolutionStrategy.cacheChangingModulesFor(0, "seconds")
    }
}
