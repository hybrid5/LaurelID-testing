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
  }

  buildFeatures {
    viewBinding = true
    buildConfig = true
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
          getDefaultProguardFile("proguard-android-optimize.txt"),
          "proguard-rules.pro",
      )
      signingConfig = signingConfigs.debug
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
  implementation(libs.cbor)
  implementation(libs.hilt.android)
  kapt(libs.hilt.compiler)

  testImplementation(kotlin("test"))
  testImplementation(libs.cose)
  testImplementation(libs.bouncycastle.bcprov)
  testImplementation(libs.bouncycastle.bcpkix)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.espresso.core)
  androidTestImplementation(libs.espresso.intents)
  androidTestImplementation(libs.hilt.android.testing)
  kaptAndroidTest(libs.hilt.compiler)
}
