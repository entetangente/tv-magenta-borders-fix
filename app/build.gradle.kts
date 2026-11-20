plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "de.edgefix"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.edgefix"
        minSdk = 25
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // Bewusst ein fester, im Repo liegender Schlüssel: So lassen sich neue
    // Versionen aus jedem Build (lokal oder CI) über die alte installieren.
    // Für eine Verteilung über einen App-Store wäre ein geheimer Schlüssel nötig.
    signingConfigs {
        create("shared") {
            storeFile = file("edgefix-shared.keystore")
            storePassword = "edgefix"
            keyAlias = "edgefix"
            keyPassword = "edgefix"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/*.kotlin_module")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.tv.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.nanohttpd)
    implementation(libs.nanohttpd.websocket)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    // nur zum Vergleich des Schlüsselformats in AdbKeysTest
    testImplementation(libs.dadb)
    // echtes org.json für Tests (Android stellt es nur zur Laufzeit bereit)
    testImplementation(libs.orgjson)
}
