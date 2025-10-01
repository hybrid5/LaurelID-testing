plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.parcelize)
  alias(libs.plugins.kotlin.kapt)
  alias(libs.plugins.hilt.android)
}

android {
  namespace = "com.laurelid" // Ensured
  compileSdk = 35

  defaultConfig {
    applicationId = "com.laurelid"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"
    testInstrumentationRunner = "com.google.dagger.hilt.android.testing.HiltTestRunner"
    buildConfigField("long", "PLAY_INTEGRITY_PROJECT_NUMBER", "0L")
  }

  flavorDimensions += "environment"

  productFlavors {
    create("staging") {
      dimension = "environment"
      buildConfigField("String", "TRUST_LIST_BASE_URL", "\"https://trustlist.staging.laurelid.dev/api/v1/\"")
      buildConfigField("boolean", "ALLOW_TRUST_LIST_OVERRIDE", "true")
      buildConfigField(
        "String",
        "TRUST_LIST_CERT_PINS",
        "\"sha256/53WBOQS+QL6Tw4VVKvh/Au9ua7Lif0cZ5mlzXRa37kY@1748736000000,sha256/Sq9GtEtHd5FcNhLBA7ZnWD9E/RE0KhwvhJ8apGLI1qI@1780272000000\"",
      )
      buildConfigField("long", "TRUST_LIST_CACHE_MAX_AGE_MILLIS", "43200000L")
      buildConfigField("long", "TRUST_LIST_CACHE_STALE_TTL_MILLIS", "259200000L")
      buildConfigField("long", "TRUST_LIST_PIN_EXPIRY_GRACE_MILLIS", "1209600000L")
    }
    create("production") {
      dimension = "environment"
      buildConfigField("String", "TRUST_LIST_BASE_URL", "\"https://trustlist.laurelid.gov/api/v1/\"")
      buildConfigField("boolean", "ALLOW_TRUST_LIST_OVERRIDE", "false")
      buildConfigField(
        "String",
        "TRUST_LIST_CERT_PINS",
        "\"sha256/53WBOQS+QL6Tw4VVKvh/Au9ua7Lif0cZ5mlzXRa37kY@1748736000000,sha256/Sq9GtEtHd5FcNhLBA7ZnWD9E/RE0KhwvhJ8apGLI1qI@1780272000000\"",
      )
      buildConfigField("long", "TRUST_LIST_CACHE_MAX_AGE_MILLIS", "43200000L")
      buildConfigField("long", "TRUST_LIST_CACHE_STALE_TTL_MILLIS", "259200000L")
      buildConfigField("long", "TRUST_LIST_PIN_EXPIRY_GRACE_MILLIS", "1209600000L")
    }
  }

  buildFeatures {
    viewBinding = true
    buildConfig = true
  }

  val releaseSigning = signingConfigs.create("release") {
    val keystorePath = System.getenv("LAURELID_RELEASE_KEYSTORE")
      ?: project.findProperty("laurelIdReleaseKeystore") as? String
    val keystorePassword = System.getenv("LAURELID_RELEASE_KEYSTORE_PASSWORD")
      ?: project.findProperty("laurelIdReleaseKeystorePassword") as? String
    val keyAliasValue = System.getenv("LAURELID_RELEASE_KEY_ALIAS")
      ?: project.findProperty("laurelIdReleaseKeyAlias") as? String
    val keyPasswordValue = System.getenv("LAURELID_RELEASE_KEY_PASSWORD")
      ?: project.findProperty("laurelIdReleaseKeyPassword") as? String

    if (!keystorePath.isNullOrBlank()) {
      storeFile = file(keystorePath)
    }
    storePassword = keystorePassword
    keyAlias = keyAliasValue
    keyPassword = keyPasswordValue
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
          getDefaultProguardFile("proguard-android-optimize.txt"),
          "proguard-rules.pro",
      )
      val hasReleaseSigning = releaseSigning.storeFile != null &&
        !releaseSigning.keyAlias.isNullOrBlank() &&
        !releaseSigning.storePassword.isNullOrBlank() &&
        !releaseSigning.keyPassword.isNullOrBlank()
      signingConfig = if (hasReleaseSigning) {
        releaseSigning
      } else {
        signingConfigs.debug
      }
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
}

kapt {
  correctErrorTypes = true
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)

  implementation(platform(libs.mlkit.bom))
  implementation(libs.mlkit.barcode)

  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)

  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  kapt(libs.room.compiler) // Requires kotlin-kapt plugin

  implementation(libs.retrofit.core)
  implementation(libs.retrofit.moshi)
  implementation(libs.okhttp)
  implementation(libs.okhttp.logging)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.cbor)
  implementation(libs.hilt.android)
  kapt(libs.hilt.compiler)
  implementation(libs.play.services.integrity)

  testImplementation(kotlin("test"))
  testImplementation(libs.cose)
  testImplementation(libs.bouncycastle.bcprov)
  testImplementation(libs.bouncycastle.bcpkix)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.robolectric)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.espresso.core)
  androidTestImplementation(libs.espresso.intents)
  androidTestImplementation(libs.hilt.android.testing)
  kaptAndroidTest(libs.hilt.compiler)
}
