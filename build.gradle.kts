// Root build.gradle.kts

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
  id("com.android.application") version "8.13.0" apply false
  id("com.android.library")     version "8.13.0" apply false

  // Use a KSP build that exists; keep Kotlin and KSP in the same minor line
  id("org.jetbrains.kotlin.android")          version "2.0.21" apply false
  id("org.jetbrains.kotlin.plugin.parcelize") version "2.0.21" apply false
  id("org.jetbrains.kotlin.kapt")             version "2.0.21" apply false
  id("com.google.devtools.ksp")               version "2.0.21-1.0.24" apply false

  id("com.google.dagger.hilt.android")        version "2.57.2" apply false
  id("org.jlleitschuh.gradle.ktlint")         version "13.1.0" apply false
  id("io.gitlab.arturbosch.detekt")           version "1.23.8" apply false
}

// Do NOT force Kotlin artifacts globally. Let processors (Room) pick newer stdlib if they need it.

subprojects {
  // JVM toolchain for all Kotlin projects
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
    add("detektPlugins", "io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
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
