plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

// 1. 引入必要的包
import java.util.Properties
        import java.io.FileInputStream

// 2. 加载 key.properties (Kotlin 写法)
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    // ⚠️ 注意：如果你修改过包名，请在这里确认 namespace 是否正确
    namespace = "com.example.anime_tracker"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        // ⚠️ 注意：这里也要确认 ID 是否正确
        applicationId = "com.example.anime_tracker"
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = 10
        versionName = flutter.versionName
    }

    // 3. 定义签名配置 (Kotlin 写法: create + 双引号 + as String)
    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
            storeFile = if (keystoreProperties["storeFile"] != null) {
                file(keystoreProperties["storeFile"] as String)
            } else {
                null
            }
            storePassword = keystoreProperties["storePassword"] as String
        }
    }

    // 4. 应用到 Release 构建 (Kotlin 写法: getByName + isMinifyEnabled)
    buildTypes {
        getByName("release") {
            // 引用上面定义的签名
            signingConfig = signingConfigs.getByName("release")

            // 开启混淆
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}