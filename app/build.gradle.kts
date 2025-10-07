// app/build.gradle.kts
plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.parcelize")
  id("org.jetbrains.kotlin.kapt")
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
  }

  // Flavor dimension
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
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

      // Use release signing if fully configured; else fall back to debug signing.
      val releaseSig = signingConfigs.getByName("release")
      val hasReleaseSigning =
        (releaseSig.storeFile != null) &&
                !releaseSig.keyAlias.isNullOrBlank() &&
                !releaseSig.storePassword.isNullOrBlank() &&
                !releaseSig.keyPassword.isNullOrBlank()
      signingConfig = if (hasReleaseSigning) releaseSig else signingConfigs.getByName("debug")

      // Integrity gate ON for release
      buildConfigField("boolean","INTEGRITY_GATE_ENABLED","true")
      // (Keep USE_APPLE_EXTERNAL_VERIFIER defined in flavors)
    }
    getByName("debug") {
      isMinifyEnabled = false
      // Integrity gate OFF for debug to allow local testing
      buildConfigField("boolean","INTEGRITY_GATE_ENABLED","false")
      // (Do not redeclare USE_APPLE_EXTERNAL_VERIFIER here)
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  // Optional: common packaging excludes (COSE/CBOR deps sometimes ship extra notices)
  packaging {
    resources {
      excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
  }
}

kotlin {
  jvmToolchain(17)
  compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

kapt { correctErrorTypes = true }

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)

  // CameraX
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)

  // Room
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  kapt(libs.room.compiler)

  // Networking
  implementation(libs.retrofit.core)
  implementation(libs.retrofit.moshi)
  implementation(libs.okhttp.core)
  implementation(libs.okhttp.logging)

  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.lifecycle.runtime.ktx)

  // CBOR / COSE
  implementation(libs.cbor)  // upokecenter CBOR
  implementation(libs.cose)  // augustcellars COSE

  // Hilt
  implementation(libs.hilt.android)
  kapt(libs.hilt.compiler)

  // ML Kit
  implementation("com.google.mlkit:barcode-scanning:17.3.0")

  // Play Integrity
  implementation(libs.play.integrity)
  implementation("com.google.android.gms:play-services-tasks:18.4.0")
}
