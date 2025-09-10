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

**Note:** jOlm is deprecated and has been superseded by [vodozemac](https://github.com/matrix-org/vodozemac). The project plans to migrate to [matrix-sdk-crypto](https://github.com/matrix-org/matrix-rust-sdk/tree/main/crates/matrix-sdk-crypto) (which uses vodozemac internally) once official Kotlin bindings become available. Current blocker: matrix-sdk-crypto-ffi requires MSVC build tools for Windows compilation.

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
