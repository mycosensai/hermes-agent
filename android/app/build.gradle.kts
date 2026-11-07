plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// The Hermes source tree to bundle: the repository root containing android/,
// overridable with -PhermesSource=/path/to/hermes-agent. It is staged into
// build/hermes-src first so pip never sees this Gradle project's own outputs.
val hermesRoot: File = (findProperty("hermesSource") as String?)?.let { file(it) }
    ?: rootProject.projectDir.parentFile
val hermesStage: File = layout.buildDirectory.dir("hermes-src").get().asFile
val hermesSource: String = hermesStage.absolutePath

val stageHermesSource by tasks.registering(Sync::class) {
    description = "Copies the Hermes Python package sources that pip bundles into the APK."
    from(hermesRoot) {
        include("pyproject.toml", "README.md", "LICENSE*", "*.py")
        listOf("agent", "tools", "hermes_cli", "gateway", "tui_gateway", "cron",
            "acp_adapter", "plugins", "providers").forEach { include("$it/**") }
        exclude("**/__pycache__/**", "**/node_modules/**", "**/*.pyc")
    }
    into(hermesStage)
}

android {
    namespace = "com.mycosensai.hermes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mycosensai.hermes"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.0-obsidian"

        // Python 3.12+ is 64-bit only on Android.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        abortOnError = false
        warningsAsErrors = false
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython("python3.13")
        pip {
            // Core networking / config used by the Android bridge and hermes auth.
            install("httpx==0.28.1")
            install("httpcore==1.0.9")
            // --no-deps below applies to EVERY pip requirement, not just Hermes.
            install("anyio==4.11.0")
            install("sniffio==1.3.1")
            install("h11==0.16.0")
            install("h2")
            install("hyperframe")
            install("hpack")
            install("certifi")
            install("charset-normalizer")
            install("urllib3")
            install("markupsafe")
            install("PyYAML==6.0.3")
            install("python-dotenv==1.2.2")
            install("tenacity==9.1.4")
            install("requests==2.33.0")
            install("jinja2==3.1.6")
            install("idna")
            install("typing-extensions")
            // Bundle the hermes-agent checkout this android/ folder lives in, without
            // desktop-only deps (openai/pydantic/rich/prompt_toolkit have no Chaquopy wheels).
            options("--no-deps")
            install(hermesSource)
        }
        // Extract at startup so imports of hermes_cli / agent work from disk.
        extractPackages(
            "hermes_cli",
            "agent",
            "tools",
            "gateway",
            "providers",
            "plugins",
            "run_agent",
            "model_tools",
            "toolsets",
            "hermes_constants",
            "hermes_state",
            "hermes_logging",
            "utils",
            "httpx",
            "yaml"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Chaquopy's pip task reads the staged Hermes tree.
tasks.matching { it.name.startsWith("install") && it.name.endsWith("PythonRequirements") }
    .configureEach { dependsOn(stageHermesSource) }
