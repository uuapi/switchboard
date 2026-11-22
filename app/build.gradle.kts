plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.switchboard.gboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.switchboard.gboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 用 debug 签名，产物可直接安装；正式分发时替换为自己的 keystore
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
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    // libxposed 新 API 102：运行时由 LSPosed 提供。
    // 用本地 jar 而非 Maven AAR —— interface:102.0.0 的 AAR 元数据要求 compileSdk 37
    // （公共仓库无 android-37 平台），参照上游 libxposed 模块的做法直接对 jar 编译。
    compileOnly(files("libs/libxposed-api-102.0.0.jar"))
    // App 侧通过 XposedServiceHelper 读写 remote preferences，需要打入 APK
    implementation(libs.libxposed.service)
    // DexKit：运行期在 Gboard 进程内做特征定位
    implementation(libs.dexkit)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)

    testImplementation("junit:junit:4.13.2")
}
