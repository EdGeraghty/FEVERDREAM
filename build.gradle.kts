plugins {
    kotlin("multiplatform") version "1.9.20"
    kotlin("plugin.serialization") version "1.9.20"
    id("org.jetbrains.compose") version "1.5.11"
}

repositories {
    mavenCentral()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/") }
    maven { url = uri("https://maven.matrix.org/") } // Matrix repository
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
                implementation("io.ktor:ktor-client-apache:2.3.0")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.0")

                // TODO: Replace with matrix-sdk-crypto when Kotlin bindings are available
                // Current blocker: matrix-sdk-crypto-ffi requires MSVC build tools for Windows
                // See: https://github.com/matrix-org/matrix-rust-sdk/tree/main/bindings/matrix-sdk-crypto-ffi
                implementation("io.github.brevilo:jolm:1.1.1")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                // JNA dependency for matrix-sdk-crypto-ffi
                implementation("net.java.dev.jna:jna:5.13.0")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
    }
}
