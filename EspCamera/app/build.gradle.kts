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
        versionCode = 2
        versionName = "2.0"

        // Phones only: MediaPipe's x86 libraries would add ~20 MB.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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
        noCompress.add("task")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// MediaPipe models, kept out of git: EfficientDet (COCO, 80 classes) in int8 for the CPU
// and float16 for the GPU, plus the pose model for skeletons.
val modelBase = "https://storage.googleapis.com/mediapipe-models"
val models = mapOf(
    "efficientdet_lite0_int8.tflite" to "$modelBase/object_detector/efficientdet_lite0/int8/latest/efficientdet_lite0.tflite",
    "efficientdet_lite2_int8.tflite" to "$modelBase/object_detector/efficientdet_lite2/int8/latest/efficientdet_lite2.tflite",
    "efficientdet_lite0_fp16.tflite" to "$modelBase/object_detector/efficientdet_lite0/float16/latest/efficientdet_lite0.tflite",
    "efficientdet_lite2_fp16.tflite" to "$modelBase/object_detector/efficientdet_lite2/float16/latest/efficientdet_lite2.tflite",
    "pose_landmarker_lite.task" to "$modelBase/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task",
)
val assetsDir = file("src/main/assets")

val downloadModels by tasks.registering {
    description = "Downloads the MediaPipe models into assets."
    outputs.files(models.keys.map { File(assetsDir, it) })
    doLast {
        assetsDir.mkdirs()
        for ((name, url) in models) {
            val target = File(assetsDir, name)
            if (target.exists()) continue
            val partial = File(assetsDir, "$name.part")
            URI(url).toURL().openStream().use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            partial.renameTo(target)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(downloadModels)
}

dependencies {
    val camerax = "1.4.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-effects:$camerax")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
