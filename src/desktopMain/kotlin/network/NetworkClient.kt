package network

import com.sun.jna.Native
import com.sun.jna.Platform
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.*
import models.*

val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    serializersModule = SerializersModule {
        // Simplified serializers module
    }
}

val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(json)
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 1000
    }
}

// Extract native library from JAR to temporary directory for JNA
fun extractNativeLibrary() {
    try {
        val libName = when {
            Platform.isMac() && Platform.isARM() -> "macos-arm64/libmatrix_sdk_crypto_ffi.dylib"
            Platform.isMac() -> "macos-x86_64/libmatrix_sdk_crypto_ffi.dylib"
            Platform.isWindows() -> "win32-x86-64/matrix_sdk_crypto_ffi.dll"
            Platform.isLinux() -> "linux-x86_64/libmatrix_sdk_crypto_ffi.so"
            else -> {
                println("Unsupported platform for native library extraction")
                return
            }
        }

        // Get the resource from the JAR
        val resourceStream = object {}.javaClass.classLoader.getResourceAsStream(libName)
            ?: run {
                println("Native library resource not found: $libName")
                return
            }

        // Create temporary directory
        val tempDir = Files.createTempDirectory("matrix-sdk-libs").toFile()
        tempDir.deleteOnExit()

        val libFile = File(tempDir, "libmatrix_sdk_crypto_ffi${if (Platform.isWindows()) ".dll" else if (Platform.isMac()) ".dylib" else ".so"}")

        // Extract the library
        resourceStream.use { input ->
            FileOutputStream(libFile).use { output ->
                input.copyTo(output)
            }
        }

        // Add the directory to JNA search path
        val currentPath = System.getProperty("jna.library.path", "")
        val newPath = if (currentPath.isEmpty()) tempDir.absolutePath else "$currentPath:${tempDir.absolutePath}"
        System.setProperty("jna.library.path", newPath)

        println("✅ Extracted native library to: ${libFile.absolutePath}")
        println("✅ Updated jna.library.path to include: ${tempDir.absolutePath}")
    } catch (e: Exception) {
        println("❌ Failed to extract native library: ${e.message}")
        e.printStackTrace()
    }
}

// Initialize native library extraction
private val initializeNativeLib = extractNativeLibrary()

// Function to properly close the HTTP client
fun closeHttpClient() {
    client.close()
}

// Utility function to convert Maps to HashMap recursively to avoid serialization issues with SingletonMap
@Suppress("UNCHECKED_CAST")
fun convertMapToHashMap(map: Any?): Any? {
    return when (map) {
        is Map<*, *> -> {
            if (map.isEmpty()) {
                mutableMapOf<String, Any>()
            } else {
                map.mapValues { convertMapToHashMap(it.value) }.toMutableMap()
            }
        }
        is List<*> -> map.map { convertMapToHashMap(it) }
        else -> map
    }
}

// Utility function to convert Any to JsonElement for serialization
fun anyToJsonElement(value: Any?): JsonElement {
    return when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.map { it.key.toString() to anyToJsonElement(it.value) }.toMap())
        is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
        else -> JsonPrimitive(value.toString()) // fallback for other types
    }
}
