plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.blazepush.core.camera"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // CameraX (Phase 2 视频管线)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.video)
    // camera-preview-in-laplivescreen round（Decision 2）：camera-lifecycle / camera-view 提为 api，
    // 让 ProcessCameraProvider / PreviewView 类透出给依赖方 feature:test（CameraPreview Composable 消费）。
    // camera-core/camera2/video 保持 implementation（feature:test 不直接 import 它们）。
    api(libs.androidx.camera.lifecycle)
    api(libs.androidx.camera.view)

    testImplementation(libs.junit)
}
