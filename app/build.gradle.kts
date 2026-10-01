plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.lyretest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.lyretest"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"

        // 正常版始终不内置 MIDI；仅 bundled 临时构建会覆盖为 true。
        buildConfigField("boolean", "BUNDLED_MIDI", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("bundled") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".songs172"
            versionNameSuffix = "-songs172"
            isDebuggable = true
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "BUNDLED_MIDI", "true")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // 只有 bundled 构建能看到这 172 首歌；debug/release 不会打包该目录。
    sourceSets.getByName("bundled").assets.srcDir(
        rootProject.file("ref_python/GenshinMusic/songs")
    )
    buildFeatures {
        buildConfig = true
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
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
