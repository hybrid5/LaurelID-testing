// settings.gradle.kts
pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
  resolutionStrategy {
    eachPlugin {
      if (requested.id.id == "com.google.devtools.ksp") {
        useModule("com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:${requested.version}")
      }
    }
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()

    // Signal’s GCS mirror (secondary)
    maven("https://storage.googleapis.com/maven.signal.org") {
      content { includeGroup("org.signal") }
    }
  }
  versionCatalogs {
    create("libs") {
      from(files("gradle/catalogs/libs.versions.toml"))
    }
  }
}

rootProject.name = "LaurelID"
include(":app")
