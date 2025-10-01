import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import java.io.File
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

listOf(
  "android.builder.sdkDownload" to "true",
).forEach { (property, value) ->
  if (System.getProperty(property).isNullOrBlank()) {
    System.setProperty(property, value)
  }
}

val environmentSdk = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
if (environmentSdk.isNullOrBlank()) {
  val managedSdkDir = rootDir.resolve(".gradle/android-sdk").absoluteFile
  val localProperties = rootDir.resolve("local.properties")
  val sanitizedPath = managedSdkDir.absolutePath.replace("\\", "\\\\")
  val existingLines = if (localProperties.exists()) localProperties.readLines() else emptyList()
  val needsUpdate = existingLines.none { it.startsWith("sdk.dir=") && it.contains(sanitizedPath) }

  if (needsUpdate) {
    managedSdkDir.mkdirs()
    val retained = existingLines.filter { it.isBlank() || !it.startsWith("sdk.dir=") }
    val updatedContent = buildString {
      retained.forEach { appendLine(it) }
      appendLine("sdk.dir=$sanitizedPath")
    }
    localProperties.writeText(updatedContent)
  }
}

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
  dependsOn(
    ":app:testStagingDebugUnitTest",
    ":app:testProductionDebugUnitTest",
  )
}

tasks.register("ciRelease") {
  group = "build"
  description = "Runs verification tasks and assembles the release APK."
  dependsOn("ciStaticAnalysis", "ciUnitTest", ":app:assembleRelease")
}
