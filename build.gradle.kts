plugins {
    kotlin("multiplatform") version "1.9.20"
    kotlin("plugin.serialization") version "1.9.20"
    id("org.jetbrains.compose") version "1.5.11"
}

kotlin {
    jvm("desktop") {
        jvmToolchain(17)
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
                implementation("io.ktor:ktor-client-core:2.3.0")
                implementation("io.ktor:ktor-client-cio:2.3.0")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.0")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                // JNA for native library loading
                implementation("net.java.dev.jna:jna:5.13.0")
                implementation("net.java.dev.jna:jna-platform:5.13.0")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
    }
}

// Ensure native library is available
tasks.register("ensureNativeLibs") {
    doLast {
        val resourcesDir = file("src/desktopMain/resources")
        val macosArm64Dir = file("$resourcesDir/macos-arm64")
        val dylibFile = file("$macosArm64Dir/libmatrix_sdk_crypto_ffi.dylib")
        
        if (!dylibFile.exists()) {
            throw GradleException("Native library not found: $dylibFile. Please ensure the matrix-sdk-crypto-ffi library is built and available.")
        }
    }
}

tasks.named("compileKotlinDesktop") {
    dependsOn("ensureNativeLibs")
}
