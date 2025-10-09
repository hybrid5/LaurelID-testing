// Root build.gradle.kts
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.kotlin.dsl.the
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

val libs = the<LibrariesForLibs>()

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.parcelize) apply false
  alias(libs.plugins.kotlin.kapt) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.hilt.android.plugin) apply false
  alias(libs.plugins.ktlint) apply false
  alias(libs.plugins.detekt) apply false
  alias(libs.plugins.androidx.baselineprofile) apply false
}

subprojects {
  // JVM toolchain for Kotlin projects
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
    add("detektPlugins", "io.gitlab.arturbosch.detekt:detekt-formatting:${libs.versions.detekt.get()}")
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
