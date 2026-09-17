import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProps = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
fun localValue(name: String) = localProps.getProperty(name, "")

android {
    namespace = "com.arhan.frugalcctv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.arhan.frugalcctv"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3.0"
        buildConfigField("String", "DEFAULT_SUPABASE_URL", "\"${localValue("FRUGAL_SUPABASE_URL")}\"")
        buildConfigField("String", "DEFAULT_SUPABASE_KEY", "\"${localValue("FRUGAL_SUPABASE_KEY")}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("io.github.webrtc-sdk:android:144.7559.09")
    implementation(platform("io.github.jan-tennert.supabase:bom:3.0.2"))
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.ktor:ktor-client-android:3.0.1")
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
