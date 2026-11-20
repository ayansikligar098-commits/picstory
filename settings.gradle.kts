// Offline build: this VM blocks all direct TCP from Java processes, so
// Gradle cannot download dependencies itself. All artifacts were mirrored
// with curl into ../local-maven (see mirror.py). The local repo is listed
// FIRST; google()/mavenCentral()/gradlePluginPortal() stay as fallbacks
// for machines with normal network access.
pluginManagement {
    repositories {
        maven { url = uri("../local-maven") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("../local-maven") }
        google()
        mavenCentral()
    }
}
rootProject.name = "70a"
include(":app")
