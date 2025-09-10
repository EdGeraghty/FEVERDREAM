# FEVERDREAM

A multi-platform Matrix chat client built with Kotlin and Compose Multiplatform.

## Features

- Multi-platform support (Windows, Mac, Linux)
- One window per chat like MSN Messenger
- Built with Kotlin and Jetpack Compose
- End-to-end encryption support (Olm/Megolm)

## Encryption

This client implements Matrix end-to-end encryption using the Olm and Megolm cryptographic ratchets.

**Current Implementation:**

- Uses jOlm library (Java bindings for libolm)
- Supports room encryption detection
- Implements Megolm group encryption for messages
- Shows UI indicators for encrypted rooms

**Note:** jOlm is deprecated and has been superseded by [vodozemac](https://github.com/matrix-org/vodozemac). However, official Kotlin bindings for vodozemac are not yet available. Migration to vodozemac will be implemented when Kotlin bindings become available.

## Getting Started

### Prerequisites

- JDK 17 or higher
- Gradle

### Building

```bash
./gradlew build
```

### Running

```bash
./gradlew run
```

## Project Structure

- `src/desktopMain/kotlin/` - Desktop-specific code
- `build.gradle.kts` - Build configuration
- `settings.gradle.kts` - Project settings
