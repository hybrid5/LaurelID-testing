import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

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

  // ✅ Correct extensions per plugin
  plugins.withId("org.jetbrains.kotlin.android") {
    extensions.configure<KotlinAndroidProjectExtension> {
      jvmToolchain(17)
    }
  }
  plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<KotlinJvmProjectExtension> {
      jvmToolchain(17)
    }
  }

  extensions.configure<KtlintExtension> {
    android.set(true)
    ignoreFailures.set(false)
    filter { exclude("**/build/**") }
  }

  // Do NOT force kotlin-stdlib globally; let the toolchain handle it.
  extensions.configure<DetektExtension> {
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = false
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
