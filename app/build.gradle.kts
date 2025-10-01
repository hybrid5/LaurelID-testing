plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.parcelize)
  alias(libs.plugins.kotlin.kapt) // Added for Room
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
  }

  buildFeatures {
    viewBinding = true
    buildConfig = true // Added
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
}

dependencies {
  implementation(libs.androidx.core.ktx) // Added
  implementation(libs.androidx.appcompat) // Added
  implementation(libs.google.android.material) // Added

  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.mlkit.barcode)

  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  kapt(libs.room.compiler) // Requires kotlin-kapt plugin

  implementation(libs.retrofit.core)
  implementation(libs.retrofit.moshi)
  implementation(libs.okhttp)
  implementation(libs.okhttp.logging)
}
