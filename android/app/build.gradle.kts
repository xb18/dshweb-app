plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xb18.dshweb"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xb18.dshweb"
        minSdk = 21
        targetSdk = 35

        val shellVersionName: String by project
        val shellVersionCode: String by project
        val webUrl: String by project
        val updateRepo: String by project
        versionName = shellVersionName
        versionCode = shellVersionCode.toInt()

        // 网址写进 BuildConfig，Kotlin 侧直接引用，避免散落硬编码。
        buildConfigField("String", "WEB_URL", "\"${webUrl}\"")
        buildConfigField("String", "UPDATE_REPO", "\"${updateRepo}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    buildFeatures {
        buildConfig = true
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
    // 仅用于 FileProvider 安全分享 APK 给系统安装器，避免 file:// Uri 暴露异常。
    implementation("androidx.core:core-ktx:1.13.1")
}
