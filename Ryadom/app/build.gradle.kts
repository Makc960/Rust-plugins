import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

// The private upload key (RuStore, Google Play) never lives in the repository: CI writes it from a
// secret and points UPLOAD_KEYSTORE_FILE at it. Without it, builds use the shared public key.
val uploadKey = System.getenv("UPLOAD_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

// Which store a build is for: "github" (the APK on the releases page) or "rustore".
val store = (findProperty("store") as String?) ?: "github"

// The app's server (server/ in the project): accounts, friends, positions.
val server = ((findProperty("server") as String?)?.trim()?.takeIf { it.isNotEmpty() } ?: "https://ice-rust.store/ryadom").trimEnd('/')
require(server.matches(Regex("https://[A-Za-z0-9.-]+(:\\d+)?(/[A-Za-z0-9_-]+)*"))) { "server must be https://host or https://host/path, not $server" }

android {
    namespace = "com.makc.ryadom"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.makc.ryadom"
        minSdk = 26
        targetSdk = 36
        // Every CI build counts up, so each one installs over the last and the stores accept it.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0"
        buildConfigField("String", "STORE", "\"$store\"")
        buildConfigField("String", "SERVER", "\"$server\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // One public key for builds installed by hand, so a new APK installs over the old one.
        create("shared") {
            storeFile = file("ryadom.keystore")
            storePassword = "android"
            keyAlias = "ryadom"
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

    androidResources {
        // The map styles and MapLibre's scripts are read as they are.
        noCompress += listOf("mjs", "json")
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

/** The app's whole interface is a web page: Node puts it together from ../web into the assets. */
abstract class BuildWeb : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val webDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile.resolve("web")
        exec.exec {
            workingDir(webDir.get().asFile)
            commandLine("node", "tools/build.mjs", out.absolutePath)
        }
    }
}

val buildWeb = tasks.register<BuildWeb>("buildWeb") {
    val web = rootProject.file("web")
    webDir.set(web)
    sources.from(fileTree(web) { include("src/**", "tools/**", "vendor/**", "package-lock.json") })
    outputDir.set(layout.buildDirectory.dir("generated/web"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(buildWeb, BuildWeb::outputDir)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
