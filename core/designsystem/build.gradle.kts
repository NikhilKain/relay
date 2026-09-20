import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Relay's Material 3 Expressive design system, shared by every client that draws
 * Compose UI: the Android app and the desktop app (Windows, Linux, macOS).
 *
 * Almost everything lives in commonMain. Platform source sets only supply what the
 * platform decides: wallpaper colour and haptics on Android, their desktop equivalents.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    androidLibrary {
        namespace = "com.vythera.relay.designsystem"
        compileSdk = 37
        minSdk = 29
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        // Compose resources (the design system's strings) ship as Android assets; the KMP
        // library plugin only packages them when Android resources are enabled.
        androidResources.enable = true
    }

    jvm("desktop") {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }

    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-Xexpect-actual-classes",
        )
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.cmp.runtime)
            api(libs.cmp.foundation)
            api(libs.cmp.animation)
            api(libs.cmp.ui)
            api(libs.cmp.material3)
            api(libs.cmp.icons.extended)
            api(libs.androidx.graphics.shapes.kmp)
            implementation(libs.cmp.resources)
        }
        androidMain.dependencies {
            implementation(libs.androidx.compose.ui.tooling.preview)
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "com.vythera.relay.designsystem.resources"
}
