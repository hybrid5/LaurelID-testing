// build.gradle.kts (root)
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.gradle.kotlin.dsl.configure

plugins {
  id("com.android.application")              version "8.13.0" apply false
  id("com.android.library")                  version "8.13.0" apply false
  id("org.jetbrains.kotlin.android")         version "2.0.21" apply false
  id("org.jetbrains.kotlin.plugin.parcelize")version "2.0.21" apply false
  id("com.google.devtools.ksp")              version "2.0.21-1.0.27" apply false
  id("com.google.dagger.hilt.android")       version "2.51.1" apply false
  id("org.jlleitschuh.gradle.ktlint")        version "13.1.0" apply false
  id("io.gitlab.arturbosch.detekt")          version "1.23.8" apply false
  id("androidx.baselineprofile")             version "1.2.3" apply false
}

subprojects {
  // Compile with JDK 17 toolchain (stable for AGP 8.13); Gradle may run on Java 21.
  plugins.withId("org.jetbrains.kotlin.android") {
    extensions.findByType(KotlinAndroidProjectExtension::class.java)?.apply {
      jvmToolchain(17)
      compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
  }
  plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.findByType(KotlinJvmProjectExtension::class.java)?.apply {
      jvmToolchain(17)
      compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
  }

  // Static analysis
  pluginManager.apply("org.jlleitschuh.gradle.ktlint")
  pluginManager.apply("io.gitlab.arturbosch.detekt")

  extensions.configure<KtlintExtension> {
    android.set(true)
    ignoreFailures.set(false)
    filter { exclude("**/build/**") }
  }

  extensions.configure<DetektExtension> {
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = false
    source.setFrom(files("src/main/java", "src/main/kotlin"))
  }

  dependencies {
    val catalog = rootProject.extensions
      .getByType(VersionCatalogsExtension::class.java).named("libs")
    val detektVersion = catalog.findVersion("detekt")
      .orElseThrow { IllegalStateException("Detekt version missing from catalog") }
      .requiredVersion
    add("detektPlugins", "io.gitlab.arturbosch.detekt:detekt-formatting:$detektVersion")
  }
}

// --- CI Tasks ---
tasks.register("ciStaticAnalysis") {
  group = "ci"
  description = "Runs Android lint, ktlint, and detekt checks."
  dependsOn(":app:lint", ":app:ktlintCheck", ":app:detekt")
}

tasks.register("ciRelease") {
  group = "ci"
  description = "Assembles the release APK."
  dependsOn(":app:assembleRelease")
}

tasks.register("ciFast") {
  group = "ci"
  description = "Runs lint, unit tests, and assemble tasks with configuration cache enabled."
  dependsOn(
    ":app:lint",
    ":app:testStagingDebugUnitTest",
    ":app:assembleStagingDebug"
  )
  doFirst {
    check(gradle.startParameter.isConfigurationCacheRequested) {
      "Run ciFast with --configuration-cache (or org.gradle.configuration-cache=true) to keep CI fast."
    }
  }
}
