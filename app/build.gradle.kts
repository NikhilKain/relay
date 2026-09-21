import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Analytics are optional, and off unless the user turns them on. The Google plugin is
// applied only when a Firebase project file is present, so the app still builds, runs and
// can be released from a clean checkout without one.
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) apply(plugin = libs.plugins.google.services.get().pluginId)

// Release signing: the keystore lives outside the repository and is named in
// keystore.properties, which is not committed. Without that file the release build is
// still produced, unsigned, so anyone can build Relay from source.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.vythera.relay"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vythera.relay"
        minSdk = 29
        // Android 17 requires a runtime permission for local-network access when targeting
        // API 37. Relay adopts it together with the permission flow; see docs/roadmap.md.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProperties.getProperty("storeFile")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = if (keystoreProperties.getProperty("storeFile") != null) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Ship the native symbols with the bundle so Play can make sense of the
            // crashes and ANRs that come out of the libraries Compose brings with it.
            ndk { debugSymbolLevel = "FULL" }
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

    defaultConfig {
        buildConfigField("boolean", "ANALYTICS_AVAILABLE", firebaseConfigured.toString())
        // Instant clipboard needs READ_LOGS and an overlay window, which Google Play
        // reviews harshly and which only a person with adb can grant anyway. It is off in
        // the store build; a build for GitHub can flip this to true and restore the two
        // permissions in the manifest.
        buildConfigField("boolean", "INSTANT_CLIPBOARD", "false")
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    // Glance pulls an old fragment; the newer one is what registerForActivityResult needs.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    if (firebaseConfigured) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.analytics)
    }
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
