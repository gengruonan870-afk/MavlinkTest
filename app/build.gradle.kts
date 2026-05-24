plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.mavlinktest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mavlinktest"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

        // 添加高德地图 3D 绘图 SDK (含定位和搜索功能)
    // 修改后的代码
    implementation("com.amap.api:3dmap:latest.integration")
    //implementation("com.amap.api:location:latest.integration")

    // 基础库 (Android Studio 自带的不要动)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // 1. 串口通信库 (咱们之前调通的)
    implementation("com.github.mik3y:usb-serial-for-android:3.4.6")

    // 2. 视频播放库 (ExoPlayer 2.18.7 版本)
    // 注意：exoplayer 这一行已经包含了 UI 界面功能，不需要再单独加 ui 了
    implementation("com.google.android.exoplayer:exoplayer:2.18.7")
    implementation("com.google.android.exoplayer:exoplayer-rtsp:2.18.7") // 必须加这个看 RTSP 视频流
    implementation(fileTree("libs") { include("*.jar", "*.aar") })
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

}