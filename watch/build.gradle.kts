plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.serialization") }
android {
    namespace = "com.miniichat.watch"
    compileSdk = 34
    defaultConfig {
        applicationId="com.maidmanager.watch"; minSdk=26; targetSdk=34
        versionCode=providers.gradleProperty("BUILD_VERSION_CODE").orNull?.toInt() ?: 300000013
        versionName=providers.gradleProperty("BUILD_VERSION_NAME").orNull ?: "3.0.13"
    }
    signingConfigs {
        getByName("debug") {
            val existingDebugStore = file("${System.getenv("ANDROID_USER_HOME") ?: "${System.getProperty("user.home")}/.android"}/debug.keystore")
            // Preserve an existing local certificate. On a fresh machine, leave
            // AGP's default path intact so it can generate a disposable debug key.
            if (existingDebugStore.isFile) storeFile = existingDebugStore
        }
        val watchStore=providers.gradleProperty("WATCH_STORE_FILE").orNull ?: System.getenv("WATCH_STORE_FILE")
        if(!watchStore.isNullOrBlank()) create("watchUpgrade") {
            // Preserve the certificate of the previously installed watch test APK.
            // The private keystore stays outside the repository; CI use is optional.
            storeFile=file(watchStore);storePassword="android";keyAlias="androiddebugkey";keyPassword="android"
        }
    }
    buildTypes { release { signingConfigs.findByName("watchUpgrade")?.let { signingConfig=it } } }
    applicationVariants.all {
        val variant=this
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.outputFileName="maid-manager-watch-${variant.versionName}-${variant.buildType.name}.apk"
        }
    }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
    testOptions.unitTests.isIncludeAndroidResources=true
}
dependencies {
    implementation(project(":companion-core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
