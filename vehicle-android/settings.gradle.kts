pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The optional flavor is deliberately excluded from ordinary demo builds.
        if (providers.gradleProperty("includePleos").orNull == "true") {
            maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
        }
    }
}
rootProject.name = "PitInVehicle"
include(":app")
