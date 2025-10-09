// app/build.gradle.kts
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.parcelize")
  id("com.google.devtools.ksp")           // Room on KSP
  id("org.jetbrains.kotlin.kapt")         // Hilt on KAPT
  id("com.google.dagger.hilt.android")
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

    buildConfigField("long", "PLAY_INTEGRITY_PROJECT_NUMBER", "0L")
    buildConfigField("boolean", "USE_OFFLINE_TEST_VECTORS", "false")
    buildConfigField("boolean", "DEVPROFILE_MODE", "false")
    buildConfigField("boolean", "TRANSPORT_QR_ENABLED", "true")
    buildConfigField("boolean", "TRANSPORT_NFC_ENABLED", "true")
    buildConfigField("boolean", "TRANSPORT_BLE_ENABLED", "false")
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
      buildConfigField("String","TRUST_LIST_MANIFEST_ROOT_CERT","\"MIIBmzCCAUGgAwIBAgIULLaYvR7QSKLfmVPR5XFwG8lyFsowCgYIKoZIzj0EAwIwIzEhMB8GA1UEAwwYTGF1cmVsSUQgVGVzdCBUcnVzdCBSb290MB4XDTI1MTAwMjAwMDQzMloXDTM1MDkzMDAwMDQzMlowIzEhMB8GA1UEAwwYTGF1cmVsSUQgVGVzdCBUcnVzdCBSb290MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEyrjywG4CQfVfu7CtKnWRmTQUR9OX/aNoWV6kJDiLjDOzywT8Q+0K3kALe/ia4u2VBOjjKYMS2jcqcs5TJZwrsqNTMFEwHQYDVR0OBBYEFKIg2A8F65q0WEuYC9We9JIxIloHMB8GA1UdIwQYMBaAFKIg2A8F65q0WEuYC9We9JIxIloHMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIhAPHz3+yopBOVPO6QBvlhHkC9iUNp4Hw6K2zUJOr9MEyVAiA7/885IjIOYQod4TL7qKqu4pDchuhJVvzd+NK/BQz3EQ==\"")
      buildConfigField("boolean","USE_APPLE_EXTERNAL_VERIFIER","false")
    }
  }

  buildFeatures {
    viewBinding = true
    buildConfig = true
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

kapt { correctErrorTypes = true }
ksp { arg("room.generateKotlin", "true") }

dependencies {
  // Kotlin BOM (align with plugin 2.0.21) + coroutines BOM
  implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.0.21"))
  implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.10.2"))
  implementation(kotlin("stdlib"))
  implementation(kotlin("reflect"))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)

  // viewModels() and lifecycle ViewModel
  implementation("androidx.activity:activity-ktx:1.9.3")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")

  // ZXing (if used elsewhere)
  implementation(libs.zxing.core)

  // CameraX
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)

  // Room via KSP
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  ksp(libs.room.compiler)

  // Networking
  implementation(libs.retrofit.core)
  implementation(libs.retrofit.moshi)
  implementation(libs.okhttp.core)
  implementation(libs.okhttp.logging)

  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.lifecycle.runtime.ktx)

  // CBOR / COSE
  implementation(libs.cbor)
  implementation(libs.cose)
  implementation(libs.bouncycastle.bcprov)
  implementation(libs.bouncycastle.bcpkix)

  // Hilt
  implementation(libs.hilt.android)
  kapt(libs.hilt.compiler)

  // ML Kit + coroutines bridge
  implementation("com.google.mlkit:barcode-scanning:17.3.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
  implementation("com.google.android.gms:play-services-tasks:18.4.0")

  // Play Integrity (use your version catalog)
  implementation(libs.play.integrity)

  // COSE/CBOR (explicit — also in versions catalog above)
  implementation("com.augustcellars.cose:cose-java:1.1.0")
  implementation("com.upokecenter:cbor:4.5.6")

  // HPKE (Signal) — Android target; requires Signal repo in settings.gradle.kts
  implementation("org.signal:hpke-android:0.0.4")

  testImplementation(libs.kotlinx.coroutines.test)
}
