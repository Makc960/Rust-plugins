plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Адрес сервера вместо api.anthropic.com — для проверки на локальной заглушке:
// ./gradlew assembleRelease -PapiBaseUrl=http://10.0.2.2:8765
val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?).orEmpty()

// Постоянный ключ подписи. Файл в репозиторий не входит; без него сборка подписывается отладочным ключом.
val releaseKeystore = rootProject.file("signing/release.keystore")

android {
    namespace = "app.reshebnik"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.reshebnik"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        // Незашифрованный HTTP разрешён только в сборке для заглушки.
        manifestPlaceholders["cleartextTraffic"] = apiBaseUrl.startsWith("http://").toString()
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore.exists()) {
                storeFile = releaseKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // SDK Anthropic большой: без сжатия APK весит в разы больше. Правила для R8 SDK приносит сам.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Обновление ставится поверх старой версии, только если подпись та же.
            signingConfig = signingConfigs.getByName(if (releaseKeystore.exists()) "release" else "debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Roborazzi сохраняет снимки экранов в app/build/screenshots.
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "2g"
            }
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.anthropic:anthropic-java:3.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.78.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.78.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
