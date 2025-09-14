plugins {
    kotlin("multiplatform") version "1.9.20"
    kotlin("plugin.serialization") version "1.9.20"
    id("org.jetbrains.compose") version "1.5.11"
}

kotlin {
    jvm("desktop") {
        // Use system Java instead of specific toolchain
        compilations.all {
            kotlinOptions {
                allWarningsAsErrors = false
            }
        }
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
                implementation("io.ktor:ktor-client-apache:2.3.0")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.0")
                // Add Kotlin reflection for JSON deserialization
                implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.20")
                // Add SLF4J logging implementation
                implementation("org.slf4j:slf4j-simple:2.0.9")
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
        val osName = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        
        val (platformDir, libName) = when {
            osName.contains("mac") && arch.contains("aarch64") -> "macos-arm64" to "libmatrix_sdk_crypto_ffi.dylib"
            osName.contains("mac") -> "macos-x86_64" to "libmatrix_sdk_crypto_ffi.dylib"
            osName.contains("win") -> "win32-x86-64" to "matrix_sdk_crypto_ffi.dll"
            osName.contains("linux") -> "linux-x86_64" to "libmatrix_sdk_crypto_ffi.so"
            else -> throw GradleException("Unsupported OS: $osName")
        }
        
        val libDir = file("$resourcesDir/$platformDir")
        val libFile = file("$libDir/$libName")
        
        if (!libFile.exists()) {
            throw GradleException("Native library not found: $libFile. Please ensure the matrix-sdk-crypto-ffi library is built and available.")
        }
    }
}

tasks.named("compileKotlinDesktop") {
    dependsOn("ensureNativeLibs")
}
