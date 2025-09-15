# FEVERDREAM

A multi-platform Matrix chat client built with Kotlin and Compose Multiplatform, featuring MSN Messenger-style separate windows and end-to-end encryption.

## Features

- **Multi-platform support**: Windows, Mac, Linux
- **MSN Messenger-style UI**: Separate windows for login, room list, chat windows, and settings
- **End-to-end encryption**: Olm/Megolm cryptographic ratchets with key backup
- **Modular architecture**: Clean separation of crypto components (OlmMachine, KeyBackup, RoomKey management)
- **Sync token persistence**: Efficient incremental synchronization
- **Room encryption detection**: Automatic detection and handling of encrypted rooms
- **Key backup and recovery**: Secure backup and restoration of encryption keys
- **Error handling**: Robust handling of missing keys, session expiration, and network issues

## Encryption

This client implements Matrix end-to-end encryption using the Olm and Megolm cryptographic ratchets via the Matrix Rust SDK.

**Current Implementation:**

- Uses vodozemac cryptographic library via Rust bindings (matrix-sdk-crypto)
- Supports room encryption detection and Megolm group encryption
- UI indicators for encrypted rooms and message status
- Key backup functionality with recovery key generation
- Automatic session renewal for message encryption
- Proper handling of undecryptable historical messages

**Implementation Details:** The project embeds the Matrix Rust SDK and uses Kotlin bindings for vodozemac, providing a native, high-performance cryptography implementation.

## Getting Started

### Prerequisites

- JDK 17 or higher
- Gradle (wrapper included)

### Building

On Unix-like systems:

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew build
```

On Windows:

```cmd
set JAVA_HOME=C:\path\to\jdk17
gradlew.bat build
```

### Running

On Unix-like systems:

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew run
```

On Windows:

```cmd
set JAVA_HOME=C:\path\to\jdk17
gradlew.bat run
```

## Project Structure

- `src/desktopMain/kotlin/` - Desktop-specific application code
- `matrix-rust-sdk/` - Embedded Matrix Rust SDK for crypto operations
- `kotlin-bindings/` - Kotlin bindings for Rust crypto libraries
- `crypto_store/` - Local storage for encryption keys and session data
- `build.gradle.kts` - Build configuration
- `settings.gradle.kts` - Project settings

## Architecture

The application follows a modular design with separate components for:

- **OlmMachineManager**: Handles OlmMachine lifecycle and initialization
- **KeyBackupManager**: Manages key backup operations and recovery
- **RoomKeyManager**: Handles room key requests and sharing
- **MessageCacheManager**: Manages message caching and retrieval
- **SyncManager**: Handles Matrix synchronization with token persistence

## Contributing

This project uses the Matrix Rust SDK for cryptographic operations. Ensure you have the necessary build tools for cross-compilation if modifying crypto components.

## License

[#YOLO Public License (YPL) v0.12.34-hunter.2](https://github.com/YOLOSecFW/YoloSec-Framework/blob/master/YOLO%20Public%20License)
