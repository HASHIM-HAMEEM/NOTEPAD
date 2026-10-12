import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hashim.notepad"
    compileSdk = 37

    defaultConfig {
        val props = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
        fun cfg(key: String, env: String) = (props.getProperty(key) ?: System.getenv(env) ?: "").replace("\"", "")
        buildConfigField("String", "SUPABASE_URL", "\"${cfg("supabase.url", "SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cfg("supabase.anonKey", "anonPublic")}\"")
        applicationId = "com.hashim.notepad"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-spike"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Test builds only: signed with the debug key until an upload keystore exists.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.text.google.fonts)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.ink.authoring)
    implementation(libs.ink.brush)
    implementation(libs.ink.geometry)
    implementation(libs.ink.rendering)
    implementation(libs.ink.strokes)
    implementation(libs.ink.storage)
    implementation(libs.ink.nativeloader)
    implementation(libs.input.motionprediction)
    implementation(libs.pdfium.android)
    implementation(libs.mlkit.digital.ink)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime)
}
