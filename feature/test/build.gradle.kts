plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.ksp)
    id("kotlin-parcelize")
}

android {
    namespace = "com.blazepush.feature.test"
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
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.0"
    }

    testOptions {
        // JVM 单测里 android.os.SystemClock / android.util.Log 默认会抛 "not mocked"；
        // 对应 fix-laptime-clock-source-integrity change，TestSessionViewModel 的 elapsedRealtime()
        // 需要返回默认值 0L 而不是抛异常
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:bluetooth"))
    // camera-preview-in-laplivescreen round（Decision 2）：拿 PreviewView / ProcessCameraProvider
    // （core:camera 把 camera-view/camera-lifecycle 提为 api）+ CameraAvailability（hasCamera 降级）。
    // camera-recording-and-gps-sync round：core:camera 已将 camera-video 改为 api（VideoCapture/Recorder 透出）。
    // fps hint 实现（Camera2Interop）在 CameraX 1.3.4 不适用 Recorder.Builder（不实现 ExtendableBuilder），
    // 暂不引入 camera-camera2 直接依赖，fps 由设备决定（design.md Decision 3 risks 已声明）。
    implementation(project(":core:camera"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.material3)
    implementation("androidx.compose.foundation:foundation:1.5.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.runtime.livedata)

    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation("com.patrykandpatrick.vico:compose:2.0.0-alpha.28")
    implementation("com.patrykandpatrick.vico:compose-m3:2.0.0-alpha.28")

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.gson)

    implementation("androidx.datastore:datastore-preferences:1.0.0")

    testImplementation(libs.junit)
    testImplementation(project(":simulator"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.mockito:mockito-core:5.2.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}