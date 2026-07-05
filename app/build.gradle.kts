import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
}

android {
    namespace = "me.mavenried.Ryde"
    compileSdk = 35

    val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

    defaultConfig {
        applicationId = "me.mavenried.Ryde"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber ?: 1
        versionName = if (runNumber != null) "1.0.$runNumber" else "1.0"
        manifestPlaceholders["MAPS_API_KEY"] = localProps.getProperty("MAPS_API_KEY", "")
    }

    val ciKeystorePath = System.getenv("KEYSTORE_PATH")
    val ciKeystorePass = System.getenv("KEYSTORE_PASS")
    val ciKeyAlias = System.getenv("KEY_ALIAS")

    // Local keystore — credentials read from local.properties (gitignored)
    val localKeystore = rootProject.file("ryde-release.jks")
    val localKeystorePass = localProps.getProperty("KEYSTORE_PASS")
    val localKeyAlias = localProps.getProperty("KEY_ALIAS") ?: "ryde"

    val sharedStore = when {
        ciKeystorePath != null -> file(ciKeystorePath)
        localKeystore.exists() && localKeystorePass != null -> localKeystore
        else -> null
    }
    val sharedPass = ciKeystorePass ?: localKeystorePass
    val sharedAlias = ciKeyAlias ?: localKeyAlias

    if (sharedStore != null && sharedPass != null) {
        signingConfigs {
            create("shared") {
                storeFile = sharedStore
                storePassword = sharedPass
                keyAlias = sharedAlias
                keyPassword = sharedPass
            }
            getByName("debug") {
                storeFile = sharedStore
                storePassword = sharedPass
                keyAlias = sharedAlias
                keyPassword = sharedPass
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (signingConfigs.findByName("shared") != null)
                signingConfigs.getByName("shared")
            else
                signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.play.services.location)
    implementation(libs.play.services.maps)
    implementation(libs.maps.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidsvg)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime.ktx)
    debugImplementation(libs.androidx.ui.tooling)
}
