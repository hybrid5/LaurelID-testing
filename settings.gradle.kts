// settings.gradle.kts
import java.io.File
import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
  repositories {
    // Order matters: keep google() first for Android/AndroidX plugins
    google()
    mavenCentral()
    gradlePluginPortal()
  }
  // Defensive: map Android plugin IDs to their module coordinates
  resolutionStrategy {
    eachPlugin {
      val id = requested.id.id
      if (id == "com.android.application" || id == "com.android.library" || id.startsWith("com.android.")) {
        useModule("com.android.tools.build:gradle:${requested.version}")
      }
    }
  }
}

plugins {
  val foojayResolverVersion = File(rootDir, "gradle/catalogs/libs.versions.toml")
    .useLines { lines ->
      lines.firstOrNull { it.trim().startsWith("foojayResolver =") }
        ?.substringAfter("=")
        ?.trim()
        ?.trim('"')
    }
    ?: error("Version foojayResolver not found in version catalog")
  id("org.gradle.toolchains.foojay-resolver-convention") version foojayResolverVersion
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
  versionCatalogs {
    create("libs") {
      from(files("gradle/catalogs/libs.versions.toml"))
    }
  }
}

rootProject.name = "LaurelID"
include(":app")
