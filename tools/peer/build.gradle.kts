import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

application {
    mainClass.set("com.vythera.relay.tools.peer.PeerKt")
}

dependencies {
    implementation(project(":core:node"))
}
