plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val suppliedVersionCode = providers.gradleProperty("BUILD_VERSION_CODE").orNull
val buildVersionCode = when {
    suppliedVersionCode == null -> 300000006
    suppliedVersionCode.toIntOrNull()?.let { it in 1..2_100_000_000 } == true -> suppliedVersionCode.toInt()
    else -> error("BUILD_VERSION_CODE must be an integer from 1 to 2100000000")
}
val buildVersionName = providers.gradleProperty("BUILD_VERSION_NAME").orNull ?: "3.0.6"
require(buildVersionName.matches(Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}"))) {
    "BUILD_VERSION_NAME contains unsupported characters"
}

fun updateSlug(propertyName: String): String {
    val value = providers.gradleProperty(propertyName).orNull.orEmpty()
    require(value.isEmpty() || value.matches(Regex("[A-Za-z0-9_.-]+"))) {
        "$propertyName contains unsupported characters"
    }
    return value
}

android {
    namespace = "com.miniichat"
    compileSdk = 34

    defaultConfig {
        // The installed V2 debug builds already use this id. Keeping it for both
        // build types preserves local chats during the move to signed releases.
        applicationId = "com.maidmanager.debug"
        minSdk = 26
        targetSdk = 34
        versionCode = buildVersionCode
        versionName = buildVersionName
        vectorDrawables { useSupportLibrary = true }

        val updateOwner = updateSlug("UPDATE_GITHUB_OWNER")
        val updateRepo = updateSlug("UPDATE_GITHUB_REPO")
        buildConfigField("String", "UPDATE_GITHUB_OWNER", "\"$updateOwner\"")
        buildConfigField("String", "UPDATE_GITHUB_REPO", "\"$updateRepo\"")
    }

    val releaseStoreFile = providers.gradleProperty("RELEASE_STORE_FILE").orNull
        ?: System.getenv("RELEASE_STORE_FILE")
    val releaseStorePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
        ?: System.getenv("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
        ?: System.getenv("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
        ?: System.getenv("RELEASE_KEY_PASSWORD")
    val releaseSigningValues = listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword
    )
    require(releaseSigningValues.none { !it.isNullOrBlank() } || releaseSigningValues.all { !it.isNullOrBlank() }) {
        "Release signing requires store file, store password, key alias, and key password together"
    }

    signingConfigs {
        getByName("debug") {
            val androidUserHome = System.getenv("ANDROID_USER_HOME")
                ?: "${System.getProperty("user.home")}/.android"
            storeFile = file("$androidUserHome/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseSigningValues.all { !it.isNullOrBlank() }) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    splits {
        abi {
            // The app has no ABI-specific native payload. One APK avoids update
            // clients accidentally choosing an incompatible split.
            isEnable = false
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            versionNameSuffix = "-debug"
            // CI and hand-off builds can opt into the same long-lived signing
            // key as releases. Without signing properties, local development
            // keeps using the normal disposable debug key.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
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

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    // Output: maid-manager-<version>-<abi>-<buildType>.apk
    applicationVariants.all {
        val variant = this
        outputs.all outputLoop@{
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
                ?: return@outputLoop
            val cleanVersion = variant.versionName.removeSuffix("-debug")
            output.outputFileName =
                "maid-manager-$cleanVersion-universal-${variant.buildType.name}.apk"
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE",
                "/META-INF/LICENSE.txt",
                "/META-INF/NOTICE",
                "/META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.navigation:navigation-compose:2.8.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
