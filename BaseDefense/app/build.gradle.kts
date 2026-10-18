import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

// The private upload key (Google Play, RuStore) never lives in the repository: CI writes it from a
// secret and points UPLOAD_KEYSTORE_FILE at it. Without it, builds use the shared key.
val uploadKey = System.getenv("UPLOAD_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

// Which store a build is for: "github" (the APK in the repository's releases), "rustore" or "play".
// Only RuStore builds sell VIP: they carry RuStore's Pay SDK (src/rustore). Google Play allows no
// payments outside its own billing and rejects donation links, so its bundle has neither.
val store = (findProperty("store") as String?) ?: "github"

// The app's ID in the RuStore Console (the number in the console's address after /apps/).
val rustoreAppId = (findProperty("rustoreAppId") as String?) ?: "2063772062"
if (store == "rustore") require(rustoreAppId.isNotEmpty()) { "A RuStore build needs rustoreAppId in app/build.gradle.kts" }

// The page where players can support the author; empty hides the button in the settings.
val supportUrl = "https://pay.cloudtips.ru/p/5b9bb7e3"

android {
    namespace = "com.makc.basedefense"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.makc.basedefense"
        minSdk = 26
        targetSdk = 36
        // Every CI build counts up, so each one installs over the last and Play accepts it.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.plus(100) ?: 2
        versionName = "2.4"
        buildConfigField("String", "SUPPORT_URL", "\"${if (store == "play") "" else supportUrl}\"")
        val shop = when (store) {
            "rustore" -> "HERE"
            "play" -> "NONE"
            else -> "ELSEWHERE"
        }
        buildConfigField("String", "VIP_SHOP", "\"$shop\"")
        resValue("string", "rustore_app_id", rustoreAppId)
        // Payment apps open this scheme to come back to the game.
        manifestPlaceholders["payScheme"] = "com.makc.basedefense"
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    // Built-in Kotlin compiles extra folders only from the Kotlin source set, not the Java one.
    sourceSets.getByName("main").kotlin.directories.add(if (store == "rustore") "src/rustore/java" else "src/nostore/java")

    signingConfigs {
        // One public key for builds installed by hand, so a new APK installs over the old one.
        create("shared") {
            storeFile = file("base-defense.keystore")
            storePassword = "android"
            keyAlias = "basedefense"
            keyPassword = "android"
        }
        if (uploadKey != null) {
            create("upload") {
                storeFile = uploadKey
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (uploadKey != null) "upload" else "shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":engine"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    if (store == "rustore") {
        implementation(platform("ru.rustore.sdk:bom:2026.10.01"))
        implementation("ru.rustore.sdk:pay")
    }
}
