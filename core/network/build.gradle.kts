import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

// livetiming App 级共享密钥：从 local.properties(gitignored) 注入，缺值用占位符。
// 源码/git 不含明文 token（design Decision 5 / tasks §9）。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.blazepush.core.network"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "LIVETIMING_TOKEN",
            "\"${localProps.getProperty("LIVETIMING_TOKEN", "PLACEHOLDER_TOKEN")}\"",
        )
        buildConfigField("String", "LIVETIMING_BASE_URL", "\"http://111.229.149.252:8080/\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = true
    }
    lint {
        abortOnError = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Retrofit + OkHttp + Gson（首个网络栈，core/network 隔离）
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.gson)

    testImplementation(libs.junit)
    // coroutines-test 在本 android module 离线 transform 失败；诊断上传单测无真实挂起点
    // （OkHttp execute 同步阻塞），改用 stdlib startCoroutine 跑 suspend（见 DiagnosticLogUploaderTest）
    // testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // mockwebserver 离线无缓存且无测试实际使用；诊断上传单测改用 JDK 内置 HttpServer（见 DiagnosticLogUploaderTest）
    // testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
