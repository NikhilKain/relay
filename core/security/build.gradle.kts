import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

dependencies {
    api(project(":core:protocol"))
    // Only used to build the X.509 structure of the self-signed device certificate.
    // Signing and all TLS cryptography use the platform JCA provider.
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
