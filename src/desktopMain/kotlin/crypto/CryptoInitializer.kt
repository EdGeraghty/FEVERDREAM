package crypto

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.*
import network.*
import network.client
import models.*
import org.matrix.rustcomponents.sdk.crypto.*
import uniffi.matrix_sdk_crypto.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.modules.*
import kotlinx.serialization.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement


// Global OlmMachine instance for encryption operations
var olmMachine: OlmMachine? = null

// Message cache for real-time updates
val roomMessageCache = mutableMapOf<String, MutableList<Event>>()

// Initialize Olm encryption
fun initializeEncryption(userId: String, deviceId: String) {
    if (olmMachine == null) {
        // Create crypto store directory
        val cryptoStorePath = "crypto_store"
        java.io.File(cryptoStorePath).mkdirs()

        try {
            // Create OlmMachine with persistent storage
            olmMachine = OlmMachine(userId, deviceId, cryptoStorePath, null)

            val identityKeys = olmMachine!!.identityKeys()
            println("🔑 Matrix SDK Crypto initialized")
            println("Curve25519 key: ${identityKeys["curve25519"]}")
            println("Ed25519 key: ${identityKeys["ed25519"]}")

            // Debug: Check if we're loading from existing store
            val existingKeyCounts = olmMachine!!.roomKeyCounts()
            println("🔑 Existing room key counts on initialization - Total: ${existingKeyCounts.total}, Backed up: ${existingKeyCounts.backedUp}")

            if (existingKeyCounts.total > 0) {
                println("✅ OlmMachine loaded from existing crypto store with ${existingKeyCounts.total} room keys")
            } else {
                println("🆕 OlmMachine created with new crypto store")
            }
        } catch (e: Exception) {
            println("❌ Failed to initialize OlmMachine: ${e.message}")
            e.printStackTrace()
        }
    } else {
        println("ℹ️  OlmMachine already initialized")
    }
}

// Set current session information
fun setSessionInfo(userId: String, deviceId: String, accessToken: String, homeserver: String) {
    currentUserId = userId
    currentDeviceId = deviceId
    currentAccessToken = accessToken
    currentHomeserver = homeserver
}

// Clear session information
fun clearSessionInfo() {
    currentUserId = null
    currentDeviceId = null
    currentAccessToken = null
    currentHomeserver = "https://matrix.org"  // Reset to default instead of null
    currentSyncToken = ""
    olmMachine = null
}