import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// local.properties 에서 REFLECT_BACKEND_URL / REFLECT_API_KEY 직접 로드
// (Android Gradle Plugin 은 sdk.dir 만 자동 처리하므로 명시적으로 읽어야 함)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String, default: String): String =
    localProps.getProperty(key)
        ?: System.getenv(key)
        ?: (project.findProperty(key) as String?)
        ?: default

android {
    namespace = "com.namhyun.reflect"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.namhyun.reflect"
        minSdk = 26
        targetSdk = 35
        versionCode = 128
        versionName = "0.1.28"

        val backendUrl = localProp("REFLECT_BACKEND_URL", "https://reflect-backend.hyun-752.workers.dev")
        val apiKey = localProp("REFLECT_API_KEY", "")
        if (apiKey.isEmpty()) {
            logger.warn("⚠️  REFLECT_API_KEY missing — set it in local.properties")
        }

        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        buildConfigField("String", "API_KEY", "\"$apiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
}
