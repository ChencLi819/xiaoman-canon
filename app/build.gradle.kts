import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersion = "1.8.2"

android {
    /* 包名沿用 cloud.micoten.camlink 以免搬动 15 个源文件；
    applicationId 换成 xiaomancanon，与旧版 CAMLink 并存安装。 */
    namespace = "cloud.micoten.camlink"
    compileSdk = 34

    defaultConfig {
        applicationId = "cloud.micoten.xiaomancanon"
        minSdk = 26
        targetSdk = 34
        versionCode = 33
        versionName = appVersion
        vectorDrawables { useSupportLibrary = true }
    }

    /* 正式包签名：凭据在 keystore/keystore.properties，缺文件时退回 debug 签名以便日常构建。 */
    val keystoreProps =
        Properties().apply {
            val f = rootProject.file("keystore/keystore.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }

    signingConfigs {
        create("release") {
            if (keystoreProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name
        val taskSuffix = variantName.replaceFirstChar { it.uppercaseChar() }
        val apkDirectory = variant.artifacts.get(SingleArtifact.APK)
        val exportApk =
            tasks.register<Copy>("export${taskSuffix}Apk") {
                from(apkDirectory) {
                    include("*.apk")
                    rename { "xiaoman-canon-$appVersion-$variantName.apk" }
                }
                into(layout.buildDirectory.dir("distributions/$variantName"))
            }
        tasks.matching { it.name == "assemble$taskSuffix" }.configureEach { finalizedBy(exportApk) }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
