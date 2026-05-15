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
        versionCode = 200
        versionName = "0.2.0"

        val backendUrl = localProp("REFLECT_BACKEND_URL", "https://reflect-backend.hyun-752.workers.dev")
        val apiKey = localProp("REFLECT_API_KEY", "")
        if (apiKey.isEmpty()) {
            logger.warn("⚠️  REFLECT_API_KEY missing — set it in local.properties")
        }

        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        buildConfigField("String", "API_KEY", "\"$apiKey\"")
    }

    // 고정 keystore — 기존 keystore 있으면 그걸로 서명, 없으면 안드로이드 기본 debug keystore.
    // CI 에서 REFLECT_KEYSTORE_PATH 등 env 가 채워지면 그걸로 서명 (모든 빌드 일관 서명).
    signingConfigs {
        create("reflect") {
            val ksPath = System.getenv("REFLECT_KEYSTORE_PATH") ?: ""
            val ksPass = System.getenv("REFLECT_KEYSTORE_PASSWORD") ?: ""
            val kAlias = System.getenv("REFLECT_KEY_ALIAS") ?: ""
            val kPass = System.getenv("REFLECT_KEY_PASSWORD") ?: ""
            if (ksPath.isNotEmpty() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = kAlias
                keyPassword = kPass
            }
            // 한국 OEM(삼성/LG) 폰들이 v1 서명 누락 시 "앱이 설치되지 않았습니다" 띄움.
            // v1 + v2 + v3 모두 활성화해서 모든 폰 호환.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // env 있으면 고정 키, 없으면 debug 키
            signingConfig = if (System.getenv("REFLECT_KEYSTORE_PATH").isNullOrEmpty())
                signingConfigs.getByName("debug")
            else signingConfigs.getByName("reflect")
        }
        debug {
            // .debug suffix 제거 — release 패키지(com.namhyun.reflect)와 동일.
            // 옛 .debug suffix 앱과 충돌 회피.
            signingConfig = if (System.getenv("REFLECT_KEYSTORE_PATH").isNullOrEmpty())
                signingConfigs.getByName("debug")
            else signingConfigs.getByName("reflect")
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
