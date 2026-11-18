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
    }
}

rootProject.name = "Spectra"
// The assistant's engine (llama.cpp over JNI) and chat logic, shared with LocalChat as on iOS.
include(":app", ":engine", ":kit")
