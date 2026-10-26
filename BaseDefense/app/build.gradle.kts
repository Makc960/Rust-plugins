import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

// The private upload key (Google Play, RuStore) never lives in the repository: CI writes it from a
// secret and points UPLOAD_KEYSTORE_FILE at it. Without it, builds use the shared key.
val uploadKey = System.getenv("UPLOAD_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

// Which store a build is for: "github" (the APK in the repository's releases), "rustore" or "play".
// RuStore builds sell VIP and the rest of the shop through RuStore's Pay SDK (src/rustore); with a
// shopServer below, RuStore and GitHub builds sell through the game's own server instead.
// Google Play allows no payments outside its own billing and rejects donation links, so its bundle has neither.
val store = (findProperty("store") as String?) ?: "github"

// The app's ID in the RuStore Console (the number in the console's address after /apps/).
val rustoreAppId = (findProperty("rustoreAppId") as String?) ?: "2063772062"
if (store == "rustore") require(rustoreAppId.isNotEmpty()) { "A RuStore build needs rustoreAppId in app/build.gradle.kts" }

// The rewarded video unit from the Yandex Advertising Network (partner.yandex.ru), like R-M-1234567-1;
// RuStore builds only. Empty, the default, shows no adverts; demo-rewarded-yandex plays Yandex's test videos.
val rewardedAdUnit = if (store == "rustore") (findProperty("rewardedAdUnit") as String?).orEmpty().trim() else ""

// The game's own shop server (BaseDefense/server), like https://shop.example.ru: set, the game sells
// through it, paid on Pally's page, instead of through RuStore Pay. Never in the Google Play bundle.
val shopServer = if (store == "play") "" else (findProperty("shopServer") as String?).orEmpty().trim().trimEnd('/')
require(shopServer.isEmpty() || shopServer.matches(Regex("https://[A-Za-z0-9.-]+(:\\d+)?"))) { "shopServer must be https://host, not $shopServer" }

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
        versionName = "3.1"
        buildConfigField("String", "SUPPORT_URL", "\"${if (store == "play") "" else supportUrl}\"")
        val shop = when {
            store == "rustore" || shopServer.isNotEmpty() -> "HERE"
            store == "play" -> "NONE"
            else -> "ELSEWHERE"
        }
        buildConfigField("String", "SHOP", "\"$shop\"")
        buildConfigField("String", "REWARDED_AD_UNIT", "\"$rewardedAdUnit\"")
        buildConfigField("String", "SHOP_SERVER", "\"$shopServer\"")
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
        implementation("ru.rustore.sdk:review")
        implementation("ru.rustore.sdk:appupdate")
        implementation("com.yandex.android:mobileads:8.5.0")
    }
}
