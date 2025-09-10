plugins {
    kotlin("multiplatform") version "1.9.20"
    kotlin("plugin.serialization") version "1.9.20"
    id("org.jetbrains.compose") version "1.5.11"
}

repositories {
    mavenCentral()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
    maven { url = uri("https://jitpack.io") }
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
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
    }
}
