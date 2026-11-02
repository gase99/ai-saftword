pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    resolutionStrategy {
        eachPlugin {
            // Preserve the foundation's plugin ID; Hilt publishes no Gradle Plugin Portal marker.
            if (requested.id.id == "dagger.hilt.android.plugin") {
                useModule("com.google.dagger:hilt-android-gradle-plugin:${requested.version}")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "AiCodeGlass"
include(":app", ":core", ":terminal-emulator", ":terminal-view")
