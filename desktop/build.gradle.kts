import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Relay for Windows, Linux and macOS. Same engine (:core:node) and same design system
 * (:core:designsystem) as Android; desktop conventions for everything around them:
 * a window with a navigation rail, a system tray, drag and drop, native file dialogs.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

dependencies {
    implementation(project(":core:node"))
    implementation(project(":core:designsystem"))
    implementation(compose.desktop.currentOs)
    implementation(libs.cmp.resources)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Swing dispatcher for Dispatchers.Main on desktop.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.coroutines.get()}")

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

compose.resources {
    publicResClass = false
    packageOfResClass = "com.vythera.relay.desktop.resources"
}

compose.desktop {
    application {
        mainClass = "com.vythera.relay.desktop.MainKt"

        // jpackage lives in a full JDK. Point `relay.packagingJdk` (gradle.properties or
        // -P) at one; otherwise the JDK running Gradle is used.
        (findProperty("relay.packagingJdk") as String?)?.let { javaHome = it }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg)
            packageName = "Relay"
            packageVersion = "1.0.0"
            description = "Your devices, together. Files, clipboard and links between phone and computer."
            vendor = "Vythera"
            // Only the parts of the Java runtime Relay actually uses, which roughly halves
            // the download. Detected with :desktop:suggestRuntimeModules.
            modules("java.instrument", "java.naming", "java.sql", "jdk.unsupported", "jdk.crypto.ec")

            windows {
                menuGroup = "Relay"
                upgradeUuid = "8d6d2a1e-4f5a-4b53-9d0f-6f3b9a2c7e11"
                shortcut = true
                dirChooser = true
            }
            macOS {
                bundleID = "com.vythera.relay"
            }
            linux {
                packageName = "relay"
            }
        }
    }
}
