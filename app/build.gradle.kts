plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 이 앱에는 빌드 때 넣는 비밀 값이 없다.
// Claude API 키는 앱 설정 화면에서 입력하고, 휴대폰 안에만 암호화해 저장한다.

android {
    namespace = "com.dot.doctor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dot.doctor"
        minSdk = 28
        targetSdk = 35
        // GitHub Actions 실행 번호를 빌드 번호로 쓴다 (덮어쓰기 설치가 되도록 항상 증가)
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1"
    }

    signingConfigs {
        // GitHub Actions: 저장소 Secret의 키(dotDoctor 전용 키)를 DOT_KEYSTORE 경로로 풀어 두면 그 키로 서명한다.
        getByName("debug") {
            System.getenv("DOT_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { path ->
                storeFile = file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    // 삼성헬스 데이터는 헬스 커넥트를 거쳐 읽는다 (읽기 전용)
    implementation(libs.androidx.health.connect)
}
