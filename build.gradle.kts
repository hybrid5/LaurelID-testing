import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
  id("com.android.application") apply false
  id("org.jetbrains.kotlin.android") apply false
  id("org.jetbrains.kotlin.plugin.parcelize") apply false
  id("org.jetbrains.kotlin.kapt") apply false
  id("com.google.dagger.hilt.android") apply false
  id("org.jlleitschuh.gradle.ktlint") apply false
  id("io.gitlab.arturbosch.detekt") apply false
}

subprojects {
  pluginManager.apply("org.jlleitschuh.gradle.ktlint")
  pluginManager.apply("io.gitlab.arturbosch.detekt")

  extensions.configure<KtlintExtension> {
    android.set(true)
    ignoreFailures.set(false)
    filter { exclude("**/build/**") }
  }
    configurations.configureEach {
      resolutionStrategy {
        force(
          "org.jetbrains.kotlin:kotlin-stdlib:2.2.0",
          "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.2.0",
          "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.0",
          "org.jetbrains.kotlin:kotlin-reflect:2.2.0"
        )
      }
    }

  extensions.configure<DetektExtension> {
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = false
    // production-only: don’t scan test sources
    source.setFrom(files("src/main/java", "src/main/kotlin"))
  }

  dependencies {
    add("detektPlugins", "io.gitlab.arturbosch.detekt:detekt-formatting:1.23.6")
  }
}

tasks.register("ciStaticAnalysis") {
  group = "ci"; description = "Runs Android lint, ktlint, and detekt checks."
  dependsOn(":app:lint", ":app:ktlintCheck", ":app:detekt")
}

tasks.register("ciRelease") {
  group = "ci"; description = "Assembles the release APK."
  dependsOn(":app:assembleRelease")
}
