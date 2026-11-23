import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateUpdateNetworkResources : DefaultTask() {
    @get:Input abstract val configuration: Property<String>
    @get:OutputDirectory abstract val resourceDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val target = resourceDirectory.get().file("xml/update_network_security.xml").asFile
        target.parentFile.mkdirs()
        target.writeText(configuration.get())
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localConfig(name: String): String =
    providers.gradleProperty(name).orNull
        ?: System.getenv(name)
        ?: localProperties.getProperty(name).orEmpty()

fun quotedBuildConfig(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val cloudApiBase = localConfig("CLOUD_API_BASE")
val clientApiToken = localConfig("API_TOKEN")

// Public API endpoints must use HTTPS; cleartext HTTP is only allowed for local loopback
// or private LAN addresses during development.
val allowCleartextTraffic = run {
    if (!cloudApiBase.startsWith("http://", ignoreCase = true)) {
        false
    } else {
        val host = cloudApiBase.substringAfter("://", "").substringBefore("/").substringBefore(":").lowercase()
        val privateHost = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            (host.startsWith("172.") && host.substringAfter("172.", "").substringBefore(".").toIntOrNull()?.let { it in 16..31 } == true)
        if (!privateHost) {
            throw org.gradle.api.GradleException(
                "CLOUD_API_BASE 必须使用 HTTPS（当前为公网 http 地址）。" +
                    "开发调试请改用 localhost / 127.0.0.1 / 10.0.2.2 或私有网段地址。"
            )
        }
        true
    }
}
val appNamespace = "com.animeow.app"

val updateNetworkResources = layout.buildDirectory.dir("generated/res/updateNetwork")
val updateNetworkXml = """
    <?xml version="1.0" encoding="utf-8"?>
    <network-security-config>
        <base-config cleartextTrafficPermitted="$allowCleartextTraffic" />
    </network-security-config>
""".trimIndent()
val generateUpdateNetworkResources = tasks.register<GenerateUpdateNetworkResources>("generateUpdateNetworkResources") {
    configuration.set(updateNetworkXml)
    resourceDirectory.set(updateNetworkResources)
}

android {
    namespace = appNamespace
    compileSdk = 36
    sourceSets.getByName("main").res.srcDir(updateNetworkResources)
    sourceSets.getByName("test").resources.srcDirs("schemas", "../test-fixtures/database")

    defaultConfig {
        applicationId = "com.animeow.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 50
        versionName = "2.2.5"

        buildConfigField("String", "CLOUD_API_BASE", quotedBuildConfig(cloudApiBase))
        buildConfigField("String", "API_TOKEN", quotedBuildConfig(clientApiToken))
        buildConfigField("String", "APP_NAMESPACE", quotedBuildConfig(appNamespace))
        manifestPlaceholders["allowCleartextTraffic"] = allowCleartextTraffic.toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Copy the completed release configuration so test APKs exercise the same R8/resource shrinking.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort").forEach { key ->
                providers.systemProperty(key).orNull?.let { value -> it.systemProperty(key, value) }
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(generateUpdateNetworkResources) }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.inspector.frame)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.espresso.core)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
