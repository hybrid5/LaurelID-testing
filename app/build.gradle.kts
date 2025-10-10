// app/build.gradle.kts
import java.util.Properties
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.parcelize)
  alias(libs.plugins.ksp)
  alias(libs.plugins.hilt.android.plugin)
}

val playIntegrityProjectNumber: Long = run {
  val props = Properties()
  val local = rootProject.file("local.properties")
  if (local.exists()) {
    local.inputStream().use(props::load)
  }
  props.getProperty("playIntegrityProjectNumber")?.toLongOrNull()
    ?: props.getProperty("PLAY_INTEGRITY_PROJECT_NUMBER")?.toLongOrNull()
    ?: 0L
}

android {
  namespace = "com.laurelid"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.laurelid"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    buildConfigField("long", "PLAY_INTEGRITY_PROJECT_NUMBER", "${playIntegrityProjectNumber}L")
    buildConfigField("boolean", "USE_OFFLINE_TEST_VECTORS", "false")
    buildConfigField("boolean", "DEVPROFILE_MODE", "false")
    buildConfigField("boolean", "TRANSPORT_QR_ENABLED", "true")
    buildConfigField("boolean", "TRANSPORT_NFC_ENABLED", "true")
    buildConfigField("boolean", "TRANSPORT_BLE_ENABLED", "false")
    buildConfigField("boolean", "DEV_MODE", "false")
  }

  flavorDimensions += listOf("environment")
  productFlavors {
    create("staging") {
      dimension = "environment"
      buildConfigField("String","TRUST_LIST_BASE_URL","\"https://trustlist.staging.laurelid.dev/api/v1/\"")
      buildConfigField("boolean","ALLOW_TRUST_LIST_OVERRIDE","true")
      buildConfigField("String","TRUST_LIST_CERT_PINS","\"sha256/53WBOQS+QL6Tw4VVKvh/Au9ua7Lif0cZ5mlzXRa37kY@1748736000000,sha256/Sq9GtEtHd5FcNhLBA7ZnWD9E/RE0KhwvhJ8apGLI1qI@1780272000000\"")
      buildConfigField("long","TRUST_LIST_CACHE_MAX_AGE_MILLIS","43200000L")
      buildConfigField("long","TRUST_LIST_CACHE_STALE_TTL_MILLIS","259200000L")
      buildConfigField("long","TRUST_LIST_PIN_EXPIRY_GRACE_MILLIS","1209600000L")
      buildConfigField("boolean","DEV_MODE","true")
      buildConfigField("String","TRUST_LIST_MANIFEST_ROOT_CERT","\"MIIBmzCCAUGgAwIBAgIULLaYvR7QSKLfmVPR5XFwG8lyFsowCgYIKoZIzj0EAwIwIzEhMB8GA1UEAwwYTGF1cmVsSUQgVGVzdCBUcnVzdCBSb290MB4XDTI1MTAwMjAwMDQzMloXDTM1MDkzMDAwMDQzMlowIzEhMB8GA1UEAwwYTGF1cmVsSUQgVGVzdCBUcnVzdCBSb290MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEyrjywG4CQfVfu7CtKnWRmTQUR9OX/aNoWV6kJDiLjDOzywT8Q+0K3kALe/ia4u2VBOjjKYMS2jcqcs5TJZwrsqNTMFEwHQYDVR0OBBYEFKIg2A8F65q0WEuYC9We9JIxIloHMB8GA1UdIwQYMBaAFKIg2A8F65q0WEuYC9We9JIxIloHMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIhAPHz3+yopBOVPO6QBvlhHkC9iUNp4Hw6K2zUJOr9MEyVAiA7/885IjIOYQod4TL7qKqu4pDchuhJVvzd+NK/BQz3EQ==\"")
      buildConfigField("boolean","USE_APPLE_EXTERNAL_VERIFIER","true")
    }
    create("production") {
      dimension = "environment"
      buildConfigField("String","TRUST_LIST_BASE_URL","\"https://trustlist.laurelid.gov/api/v1/\"")
      buildConfigField("boolean","ALLOW_TRUST_LIST_OVERRIDE","false")
      buildConfigField("String","TRUST_LIST_CERT_PINS","\"sha256/53WBOQS+QL6Tw4VVKvh/Au9ua7Lif0cZ5mlzXRa37kY@1748736000000,sha256/Sq9GtEtHd5FcNhLBA7ZnWD9E/RE0KhwvhJ8apGLI1qI@1780272000000\"")
      buildConfigField("long","TRUST_LIST_CACHE_MAX_AGE_MILLIS","43200000L")
      buildConfigField("long","TRUST_LIST_CACHE_STALE_TTL_MILLIS","259200000L")
      buildConfigField("long","TRUST_LIST_PIN_EXPIRY_GRACE_MILLIS","1209600000L")
      buildConfigField("boolean","DEV_MODE","false")
      buildConfigField("String","TRUST_LIST_MANIFEST_ROOT_CERT","\"MIIBmzCCAUGgAwIBAgIULLaYvR7QSKLfmVPR5XFwG8lyFsowCgYIKoZIzj0EAwIwIzEhMB8GA1UEAwwYTGF1cmVsSUQgVGVzdCBUcnVzdCBSb290MB4XDTI1MTAwMjAwMDQzMloXDTM1MDkzMDAwMDQzMlowIzEhMB8GA1UEAwwYTGF1cmVsSUQgVGVzdCBUcnVzdCBSb290MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEyrjywG4CQfVfu7CtKnWRmTQUR9OX/aNoWV6kJDiLjDOzywT8Q+0K3kALe/ia4u2VBOjjKYMS2jcqcs5TJZwrsqNTMFEwHQYDVR0OBBYEFKIg2A8F65q0WEuYC9We9JIxIloHMB8GA1UdIwQYMBaAFKIg2A8F65q0WEuYC9We9JIxIloHMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIhAPHz3+yopBOVPO6QBvlhHkC9iUNp4Hw6K2zUJOr9MEyVAiA7/885IjIOYQod4TL7qKqu4pDchuhJVvzd+NK/BQz3EQ==\"")
      buildConfigField("boolean","USE_APPLE_EXTERNAL_VERIFIER","false")
    }
  }

  buildFeatures {
    viewBinding = true
    buildConfig = true
  }

  lint {
    warningsAsErrors = true
    abortOnError = true
  }

  testOptions {
    unitTests.isIncludeAndroidResources = true
    unitTests.isReturnDefaultValues = true
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("LAURELID_RELEASE_KEYSTORE")
        ?: project.findProperty("laurelIdReleaseKeystore") as? String
      val keystorePassword = System.getenv("LAURELID_RELEASE_KEYSTORE_PASSWORD")
        ?: project.findProperty("laurelIdReleaseKeystorePassword") as? String
      val keyAliasValue = System.getenv("LAURELID_RELEASE_KEY_ALIAS")
        ?: project.findProperty("laurelIdReleaseKeyAlias") as? String
      val keyPasswordValue = System.getenv("LAURELID_RELEASE_KEY_PASSWORD")
        ?: project.findProperty("laurelIdReleaseKeyPassword") as? String

      if (!keystorePath.isNullOrBlank()) storeFile = file(keystorePath)
      storePassword = keystorePassword
      keyAlias = keyAliasValue
      keyPassword = keyPasswordValue
    }
  }

  buildTypes {
    getByName("release") {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      val releaseSig = signingConfigs.getByName("release")
      val hasReleaseSigning =
        (releaseSig.storeFile != null) &&
                !releaseSig.keyAlias.isNullOrBlank() &&
                !releaseSig.storePassword.isNullOrBlank() &&
                !releaseSig.keyPassword.isNullOrBlank()
      signingConfig = if (hasReleaseSigning) releaseSig else signingConfigs.getByName("debug")
      buildConfigField("boolean","INTEGRITY_GATE_ENABLED","true")
    }
    getByName("debug") {
      isMinifyEnabled = false
      buildConfigField("boolean","INTEGRITY_GATE_ENABLED","false")
    }
  }

  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions { jvmTarget = "17" }
  packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}") } }
}

kotlin {
  jvmToolchain(17)
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val releaseRuntimeClasspath by configurations.creating

androidComponents {
  onVariants { variant ->
    if (variant.name == "productionRelease") {
      releaseRuntimeClasspath.extendsFrom(configurations.getByName("${variant.name}RuntimeClasspath"))
    }
  }
}

ksp { arg("room.generateKotlin", "true") }

dependencies {
  implementation(platform(libs.kotlin.bom))

  implementation(libs.androidx.activity.ktx)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.fragment.ktx)
  implementation(libs.material)
  implementation(libs.bundles.androidx.lifecycle)

  implementation(libs.androidx.security.crypto)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.play.services)

  implementation(libs.zxing.core)
  implementation(libs.bundles.camera)

  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  ksp(libs.room.compiler)

  implementation(libs.retrofit.core)
  implementation(libs.retrofit.moshi)
  implementation(libs.okhttp.core)
  implementation(libs.okhttp.logging)

  implementation(libs.cose)
  implementation(libs.cbor)
  implementation(libs.bouncycastle.bcprov)
  implementation(libs.bouncycastle.bcpkix)
  implementation(libs.bouncycastle.bcutil)

  // 🔁 Switched to Maven Central artifact (no custom repo required)
  implementation(libs.libsignal.android)

  implementation(libs.play.integrity)
  implementation(libs.mlkit.barcode)

  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)

  testImplementation(kotlin("test"))
  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.mockk)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.arch.core.testing)
  testImplementation(libs.androidx.test.core)

  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.espresso.core)
  androidTestImplementation(libs.androidx.test.core)

  // Core library desugaring (needed by libsignal-android on minSdk < 26; safe in any case)
  coreLibraryDesugaring(libs.desugarJdkLibs)
}

val anchorsDir = file("src/main/assets/trust/iaca")

tasks.register("printAnchors") {
  group = "trust"
  description = "Prints bundled IACA trust anchors"
  doLast {
    if (!anchorsDir.exists()) {
      println("No anchors found at ${anchorsDir.absolutePath}")
      return@doLast
    }
    val factory = CertificateFactory.getInstance("X.509")
    val files = anchorsDir.walkTopDown().filter { it.isFile && it.extension.equals("cer", true) }.toList()
    if (files.isEmpty()) {
      println("No anchors found at ${anchorsDir.absolutePath}")
      return@doLast
    }
    println("Found ${files.size} trust anchor(s)")
    files.sortedBy { it.name }.forEach { file ->
      file.inputStream().use { input ->
        val cert = factory.generateCertificate(input) as X509Certificate
        println("- ${cert.subjectX500Principal.name} (expires ${cert.notAfter})")
      }
    }
  }
}

// --- added for COSE/CBOR/crypto ---
dependencies {
  implementation("com.augustcellars.cose:cose-java:1.1.0")
  implementation("com.upokecenter:cbor:4.5.2")
  implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
  implementation("com.squareup.okio:okio:3.7.0")
  // HPKE (needed for KEM_X25519_HKDF_SHA256/KDF_HKDF_SHA256/AEAD_AES_GCM_256)
}
// --- end added ---
