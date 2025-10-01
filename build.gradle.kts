import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.parcelize) apply false
  alias(libs.plugins.kotlin.kapt) apply false // Added for kapt
  alias(libs.plugins.ktlint) apply false
  alias(libs.plugins.detekt) apply false
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

subprojects {
  pluginManager.apply("org.jlleitschuh.gradle.ktlint")
  pluginManager.apply("io.gitlab.arturbosch.detekt")

  extensions.configure<KtlintExtension> {
    android.set(true)
    ignoreFailures.set(false)
    filter {
      exclude("**/build/**")
    }
  }

  extensions.configure<DetektExtension> {
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = false
    source.setFrom(
      files(
        "src/main/java",
        "src/main/kotlin",
        "src/test/java",
        "src/test/kotlin",
        "src/androidTest/java",
        "src/androidTest/kotlin"
      )
    )
  }

  dependencies {
    add("detektPlugins", libs.findLibrary("detekt-formatting").get())
  }
}

tasks.register("ciStaticAnalysis") {
  group = "verification"
  description = "Runs Android lint, ktlint, and detekt checks."
  dependsOn(
    ":app:lint",
    ":app:ktlintCheck",
    ":app:detekt"
  )
}

tasks.register("ciUnitTest") {
  group = "verification"
  description = "Executes JVM unit tests for debug builds."
  dependsOn(":app:testDebugUnitTest")
}

tasks.register("ciRelease") {
  group = "build"
  description = "Runs verification tasks and assembles the release APK."
  dependsOn("ciStaticAnalysis", "ciUnitTest", ":app:assembleRelease")
}
