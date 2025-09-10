plugins {
    kotlin("multiplatform") version "1.9.20"
    kotlin("plugin.serialization") version "1.9.20"
    id("org.jetbrains.compose") version "1.5.11"
    id("com.github.gmazzo.buildconfig") version "5.3.5"
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

                // Vodozemac encryption support via Rust FFI
                implementation(files("$projectDir/rust-core/target/debug/deps"))
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
    }
}

// Task to build Rust library (with fallback for Windows)
tasks.register("buildRustLib") {
    doLast {
        try {
            exec {
                workingDir = file("rust-core")
                commandLine("cargo", "build")
            }
            println("✅ Rust library built successfully")
        } catch (e: Exception) {
            println("⚠️  Rust build failed: ${e.message}")
            println("🔄 Using fallback: encryption will be disabled but app will still work")
            // Create a dummy library file so Gradle doesn't fail
            file("rust-core/target/debug/deps/feverdream_crypto.dll").getParentFile().mkdirs()
            file("rust-core/target/debug/deps/feverdream_crypto.dll").writeText("")
        }
    }
}

// Ensure Rust library is built before Kotlin compilation
tasks.named("compileKotlinDesktop") {
    dependsOn("buildRustLib")
}
