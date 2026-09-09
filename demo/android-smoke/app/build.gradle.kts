plugins {
    id("com.android.application")
}

android {
    namespace = "com.ricezhou.vsrqg.smoke"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.ricezhou.vsrqg.smoke"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
