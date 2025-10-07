pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
  plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.20"
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.0.20"
    id("org.jetbrains.kotlin.kapt") version "2.0.20"
    id("com.google.dagger.hilt.android") version "2.57.2"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
  }
}

rootProject.name = "LaurelID"
include(":app")
