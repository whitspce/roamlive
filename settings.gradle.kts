pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven("https://jitpack.io")
            }
            filter {
                includeGroup("com.github.pedroSG94.RootEncoder")
            }
        }
    }
}

rootProject.name = "Roam"
include(":app")
// Vendored RootEncoder srt module with the cellular payload cap; the app
// substitutes it for the upstream srt artifact. See srt/README.md.
include(":srt")
// Vendored RootEncoder rtmp module with secure transport diagnostics and
// RTMPS hostname verification enabled by default. See rtmp/README.md.
include(":rtmp")
