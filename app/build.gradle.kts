import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

// Release signing with the owner's key, which never goes into the repository: keystore.properties
// (storeFile, storePassword, keyAlias, keyPassword) next to this project, or on CI the
// DOPOMINE_KEYSTORE / DOPOMINE_KEYSTORE_PASSWORD variables. Without a key the release build is signed
// with this machine's debug key: it installs, but not over the official APK.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(property: String, env: String): String? =
    keystoreProps.getProperty(property) ?: System.getenv(env)?.takeIf { it.isNotBlank() }
val releaseKeystore = signingValue("storeFile", "DOPOMINE_KEYSTORE")

android {
    namespace = "com.dopomine.film"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dopomine.film"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "2.3.0"

        buildConfigField("String", "DEFAULT_PLAYER_HOST", "\"https://fz7-16gs81j.troutcdn.site\"")
        buildConfigField("String", "CATALOG_UPDATE_URL", "\"https://github.com/D0PXMINE/Dopomine-film/releases/download/catalog/catalog.db.gz\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = signingValue("storePassword", "DOPOMINE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "DOPOMINE_KEY_ALIAS") ?: "dopomine"
                keyPassword = signingValue("keyPassword", "DOPOMINE_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                System.getProperty("dpm.posters")?.let { dir -> it.systemProperty("dpm.posters", dir) }
                System.getProperty("dpm.online")?.let { v -> it.systemProperty("dpm.online", v) }
                it.testLogging { showStandardStreams = System.getProperty("dpm.online") == "true" }
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.coil.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
