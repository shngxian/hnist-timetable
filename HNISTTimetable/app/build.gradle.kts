import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息从仓库外的 keystore.properties 读取（该文件不入库），
// 格式：storeFile=keystore.jks / storePassword=... / keyAlias=... / keyPassword=...
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "cn.hnist.timetable"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.hnist.timetable"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "1.7.2"
        resourceConfigurations += listOf("zh-rCN")
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile", "keystore.jks"))
            storePassword = keystoreProps.getProperty("storePassword", "")
            keyAlias = keystoreProps.getProperty("keyAlias", "hnist")
            keyPassword = keystoreProps.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.3.6")

    testImplementation("junit:junit:4.13.2")
    // 单元测试里 android.jar 的 org.json 只是桩，需要真实实现
    testImplementation("org.json:json:20240303")
}
