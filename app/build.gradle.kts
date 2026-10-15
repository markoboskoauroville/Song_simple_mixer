import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersion = (project.findProperty("appVersion") as String).toInt()
// CI passes -PprojectVersionCode=1000+run_number: always rising, so every build installs over the last.
val versionCodeCI = (project.findProperty("projectVersionCode") as String?)?.toInt()
val keystore = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "community.auroville.mantra.mixer"
    compileSdk = 35
    defaultConfig {
        applicationId = "community.auroville.mantra.mixer"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeCI ?: appVersion
        versionName = if (versionCodeCI != null) "$appVersion.${versionCodeCI - 1000}" else appVersion.toString()
    }
    signingConfigs {
        create("release") {
            if (keystore.getProperty("storeFile") != null) {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storeType = "PKCS12"
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore.getProperty("storeFile") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
