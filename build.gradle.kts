import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.parcelize) apply false
  alias(libs.plugins.kotlin.kapt) apply false
  alias(libs.plugins.hilt.android) apply false
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
    add("detektPlugins", "io.gitlab.arturbosch.detekt:detekt-formatting:1.23.6")
  }
}

tasks.register("ciStaticAnalysis") {
  group = "ci"
  description = "Runs Android lint, ktlint, and detekt checks."
  dependsOn(":app:lint", ":app:ktlintCheck", ":app:detekt")
}

tasks.register("ciUnitTest") {
  group = "ci"
  description = "Executes JVM unit tests for staging and production builds."
  dependsOn(":app:testStagingUnitTest", ":app:testProductionUnitTest")
}

tasks.register("ciRelease") {
  group = "ci"
  description = "Assembles the release APK."
  dependsOn(":app:assembleRelease")
}
