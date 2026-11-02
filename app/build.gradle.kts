plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.batteryanalyzer"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int

    defaultConfig {
        applicationId = "com.batteryanalyzer"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "2.5.1"
    }

    signingConfigs {
        create("release") {
            // ===== 发布签名配置 =====
            // 使用 debug.keystore 作为默认签名（方便开发阶段分享）
            // 【发布正式版时请替换为正式密钥库】
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true  // 移除未使用资源
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // APK 输出命名：便于识别版本和构建类型
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "BatteryAnalyzer-v${defaultConfig.versionName}-${name}.apk"
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
        compose = true
    }

    // Room 数据库 schema 导出路径（用于数据库迁移）
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room Database (2.8.4+ supports Kotlin 2.x / KSP2)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Glance Widget (Android 12+)
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // WorkManager for background monitoring
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Core KTX
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Shizuku - 用于键值映射的按键注入
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ===== 测试依赖 =====

    // JUnit + Android测试基础
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    // Robolectric (在JVM上运行Android单元测试)
    testImplementation("org.robolectric:robolectric:4.14.1")

    // Mockito + MockK
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("io.mockk:mockk:1.13.13")

    // AndroidX Test - Core
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")

    // AndroidX Test - Rules (用于 Service/Activity 测试)
    androidTestImplementation("androidx.test:rules:1.6.1")

    // Notification 测试支持
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // Glance Widget 测试
    testImplementation("androidx.glance:glance-appwidget:1.1.1")
    testImplementation("androidx.glance:glance-material3:1.1.1")

    // DataStore 测试
    testImplementation("androidx.datastore:datastore-preferences:1.1.1")

    // Room 测试
    testImplementation("androidx.room:room-testing:2.8.4")
}
