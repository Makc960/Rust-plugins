import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.makc.espcamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.makc.espcamera"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // One fixed key for every build, so a new APK installs over the old one.
    signingConfigs {
        create("shared") {
            storeFile = file("esp-camera.keystore")
            storePassword = "android"
            keyAlias = "espcamera"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress.add("tflite")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// EfficientDet-Lite0 (COCO, 80 classes) for MediaPipe Tasks, kept out of git.
val modelUrl =
    "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/latest/efficientdet_lite0.tflite"
val modelFile = file("src/main/assets/efficientdet_lite0.tflite")

val downloadModel by tasks.registering {
    description = "Downloads the object detection model into assets."
    outputs.file(modelFile)
    doLast {
        if (!modelFile.exists()) {
            modelFile.parentFile.mkdirs()
            val partial = File(modelFile.path + ".part")
            URI(modelUrl).toURL().openStream().use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            partial.renameTo(modelFile)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(downloadModel)
}

dependencies {
    val camerax = "1.3.4"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
