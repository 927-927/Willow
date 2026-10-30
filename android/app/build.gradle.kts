import java.util.Properties

plugins {
    id("com.android.application")
}

// The AI key is shared with the web prototype: prototype-web/.env (never committed).
// An environment variable with the same name wins, e.g. for a one-off build.
val dotEnv = Properties().apply {
    val f = rootDir.resolve("../prototype-web/.env")
    if (f.isFile) f.reader(Charsets.UTF_8).use { load(it) }
}
fun env(name: String, default: String = ""): String =
    (System.getenv(name) ?: dotEnv.getProperty(name) ?: default).trim().removeSurrounding("\"")
fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// The AI tier (v2.0). false builds v1.0 (rules + explanation cards only): no key goes into the APK.
val aiOn = true

// The AI: Qwen via 百炼 / DashScope (DASHSCOPE_API_KEY), OpenAI-compatible. Thinking is switched off in Ai.kt.
val aiKey = env("DASHSCOPE_API_KEY")
val aiModel = env("QWEN_MODEL", "qwen-plus")
val aiBaseUrl = env("QWEN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")

android {
    namespace = "com.willow"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.willow"
        minSdk = 30 // takeScreenshot() needs Android 11
        targetSdk = 35
        versionCode = 2
        versionName = "1.0"
        // ML Kit's OCR is native code; one ABI keeps the APK small (all current phones are arm64).
        ndk { abiFilters += "arm64-v8a" }

        buildConfigField("String", "AI_KEY", quoted(if (aiOn) aiKey else ""))
        buildConfigField("String", "AI_MODEL", quoted(aiModel))
        buildConfigField("String", "AI_BASE_URL", quoted(aiBaseUrl.trimEnd('/')))
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        // debug = the test phone (logs + adb test hooks). release = Grandpa's phone (no logs at all).
        // Both are signed with the standard debug key, so either can be installed over the other.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The same rule and card files as the web prototype, packed as assets.
androidComponents {
    onVariants { variant ->
        val shared = rootDir.resolve("../shared")
        listOf("rules", "debunks", "samples", "prompts").forEach {
            variant.sources.assets?.addStaticSourceDirectory(shared.resolve(it).path)
        }
    }
}

dependencies {
    // Google ML Kit text recognition, Chinese, with the model bundled in the APK:
    // works offline and without Google Play services (most phones in China have none).
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
