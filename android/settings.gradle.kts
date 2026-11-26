pluginManagement {
    // Miuix 0.9.3 contains Kotlin 2.4 metadata; AGP 8.13.2 bundles R8 8.13.19.
    // Use R8's documented override without changing AGP or the Kotlin compiler.
    // https://r8.googlesource.com/r8/+/refs/tags/9.1.29/README.md#replacing-r8-in-android-gradle-plugin
    buildscript {
        repositories {
            // 9.1.29 is published in R8's official archive, not Google Maven.
            maven {
                url = uri("https://storage.googleapis.com/r8-releases/raw")
                content {
                    includeModule("com.android.tools", "r8")
                }
            }
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools:r8:9.1.29")
        }
    }

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
    }
}

rootProject.name = "KunKunIMEAndroid"
include(":app")
