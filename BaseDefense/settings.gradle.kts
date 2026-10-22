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
        google()
        mavenCentral()
        // RuStore's SDKs, for RuStore builds only.
        maven("https://nexus-external.rustore.ru/repository/maven-rustore-exposed") {
            content {
                includeGroupByRegex("ru\\.rustore.*")
                includeGroupByRegex("ru\\.ok.*")
            }
        }
    }
}

rootProject.name = "BaseDefense"
include(":engine", ":app")
