import com.google.protobuf.gradle.id
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.ksp)
  alias(libs.plugins.hilt.android)
  alias(libs.plugins.protobuf)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
  if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use(::load)
  }
}

android {
  namespace = "koharubiyori.starlight"
  ndkVersion = "27.0.12077973"
  compileSdk {
    version = release(37)
  }

  defaultConfig {
    applicationId = "koharubiyori.starlight"
    minSdk = 30
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    externalNativeBuild {
      ndkBuild {
        arguments += "PRODUCT_FLAVOR=nonRoot"
      }
    }
  }

  splits {
    abi {
      isEnable = true
      reset()
      include("arm64-v8a", "armeabi-v7a")
      isUniversalApk = false
    }
  }

  signingConfigs {
    if (keystorePropertiesFile.exists()) {
      create("release") {
        storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
        storePassword = keystoreProperties.getProperty("storePassword")
        keyAlias = keystoreProperties.getProperty("keyAlias")
        keyPassword = keystoreProperties.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    debug {
      applicationIdSuffix = ".debug"
      manifestPlaceholders["appLabel"] = "@string/app_name_debug"
    }

    release {
      manifestPlaceholders["appLabel"] = "@string/app_name"
      if (keystorePropertiesFile.exists()) {
        signingConfig = signingConfigs.getByName("release")
      }
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  packaging {
    resources {
      excludes += "/META-INF/LICENSE.md"
      excludes += "/META-INF/LICENSE-notice.md"
    }
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }

  externalNativeBuild {
    ndkBuild {
      path = file("src/main/jni/Android.mk")
    }
  }
}

protobuf {
  protoc {
    artifact = "com.google.protobuf:protoc:4.31.1"
  }
  generateProtoTasks {
    all().forEach { task ->
      task.builtins {
        id("java") { option("lite") }   // The generated Kotlin code based on the Java code.
        id("kotlin") { }
      }
    }
  }
}


dependencies {
  implementation(libs.androidx.appcompat.resources)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.pip)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.protobuf.kotlin.lite)
  implementation(libs.jcodec)
  implementation(libs.shieldcontrollerextensions)
  ksp(libs.hilt.android.compiler)

  implementation(libs.timber)
  implementation(libs.jmdns)
  implementation(libs.okhttp)
  implementation(libs.bcprov.jdk18on)
  implementation(libs.bcpkix.jdk18on)
  implementation(libs.androidx.datastore.preferences)

  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)


  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}