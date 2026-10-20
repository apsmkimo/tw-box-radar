plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.twboxradar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.twboxradar"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0"
        // 廣告 ID 只改這裡。目前是 Google 官方測試 ID，上架前換成 AdMob 的正式 ID。
        // 說明見 README「廣告與移除廣告」。
        val admobAppId = "ca-app-pub-3940256099942544~3347511713"
        val admobBanner = "ca-app-pub-3940256099942544/9214589741"
        val admobNative = "ca-app-pub-3940256099942544/2247696110"
        val admobAppOpen = "ca-app-pub-3940256099942544/9257395921"
        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "DATA_BASE_URL", "\"https://raw.githubusercontent.com/apsmkimo/tw-box-radar/main/docs\"")
        buildConfigField("String", "ADMOB_BANNER_UNIT", "\"$admobBanner\"")
        buildConfigField("String", "ADMOB_NATIVE_UNIT", "\"$admobNative\"")
        buildConfigField("String", "ADMOB_APP_OPEN_UNIT", "\"$admobAppOpen\"")
        buildConfigField("String", "REMOVE_ADS_PRODUCT_ID", "\"remove_ads\"")
        buildConfigField("String", "ADMOB_TEST_DEVICE", "\"\"")
        buildConfigField("boolean", "ADMOB_DEBUG_EEA", "false")
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("org.json:json:20240303")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")
    implementation("com.android.billingclient:billing-ktx:7.0.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
