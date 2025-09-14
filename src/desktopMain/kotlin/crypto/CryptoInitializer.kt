package crypto

import network.currentUserId
import network.currentDeviceId
import network.currentAccessToken
import network.currentHomeserver
import network.currentSyncToken

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
import kotlin.random.Random
import java.security.SecureRandom


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
            // Check if the error is due to account mismatch (device ID change)
            if (e.message?.contains("the account in the store doesn't match the account in the constructor") == true) {
                println("⚠️  Crypto store mismatch detected (device ID changed). Clearing old crypto store and reinitializing...")
                try {
                    // Delete the crypto store directory
                    val cryptoStoreDir = java.io.File(cryptoStorePath)
                    if (cryptoStoreDir.exists()) {
                        cryptoStoreDir.deleteRecursively()
                        println("🗑️  Old crypto store cleared")
                    }
                    // Recreate the directory
                    cryptoStoreDir.mkdirs()
                    // Retry initialization
                    olmMachine = OlmMachine(userId, deviceId, cryptoStorePath, null)

                    val identityKeys = olmMachine!!.identityKeys()
                    println("🔑 Matrix SDK Crypto reinitialized after store reset")
                    println("Curve25519 key: ${identityKeys["curve25519"]}")
                    println("Ed25519 key: ${identityKeys["ed25519"]}")

                    val existingKeyCounts = olmMachine!!.roomKeyCounts()
                    println("🔑 Room key counts after reset - Total: ${existingKeyCounts.total}, Backed up: ${existingKeyCounts.backedUp}")
                    println("🆕 New crypto store created")
                } catch (retryException: Exception) {
                    println("❌ Failed to reinitialize OlmMachine after store reset: ${retryException.message}")
                    retryException.printStackTrace()
                }
            } else {
                println("❌ Failed to initialize OlmMachine: ${e.message}")
                e.printStackTrace()
            }
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

// Check if key backup is enabled
fun isKeyBackupEnabled(): Boolean {
    val machine = olmMachine ?: run {
        println("🔍 isKeyBackupEnabled: OlmMachine is null")
        return false
    }
    val enabled = machine.backupEnabled()
    println("🔍 isKeyBackupEnabled: backupEnabled = $enabled")
    return enabled
}

// Generate a random backup recovery key
fun generateBackupRecoveryKey(): BackupRecoveryKey {
    return BackupRecoveryKey()
}

// Enable key backup
suspend fun enableKeyBackup(): String? {
    val machine = olmMachine ?: return null
    val token = currentAccessToken ?: return null

    try {
        // Generate backup recovery key
        val recoveryKey = generateBackupRecoveryKey()
        val publicKey = recoveryKey.megolmV1PublicKey()
        val recoveryKeyBase58 = recoveryKey.toBase58()

        // Create backup version on server
        val version = createBackupVersion(token, publicKey)
        if (version == null) return null

        // Enable backup in OlmMachine
        machine.enableBackupV1(publicKey, version)

        // Save recovery key for later use
        machine.saveRecoveryKey(recoveryKey, version)

        println("🔐 Key backup enabled with version: $version")
        println("🔑 Recovery key: $recoveryKeyBase58")

        // Upload existing room keys
        uploadRoomKeys(token, version)

        return recoveryKeyBase58
    } catch (e: Exception) {
        println("❌ Failed to enable key backup: ${e.message}")
        return null
    }
}

// Create backup version on server
suspend fun createBackupVersion(token: String, publicKey: MegolmV1BackupKey): String? {
    try {
        val backupInfoJson = """
            {
                "algorithm": "m.megolm_backup.v1.curve25519-aes-sha2",
                "auth_data": {
                    "public_key": "${publicKey.publicKey}",
                    "signatures": {}
                }
            }
        """.trimIndent()

        val response = client.post("$currentHomeserver/_matrix/client/v3/room_keys/version") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(backupInfoJson)
        }

        if (response.status == HttpStatusCode.OK) {
            val responseBody = response.body<JsonObject>()
            val version = responseBody["version"]?.jsonPrimitive?.content
            println("📤 Backup version created: $version")
            return version
        } else {
            println("❌ Failed to create backup version: ${response.status}")
            return null
        }
    } catch (e: Exception) {
        println("❌ Exception creating backup version: ${e.message}")
        return null
    }
}

// Upload room keys to backup
suspend fun uploadRoomKeys(token: String, version: String): Boolean {
    val machine = olmMachine ?: return false

    try {
        // Get room keys from OlmMachine
        val request = machine.backupRoomKeys()

        if (request == null) {
            println("ℹ️  No room keys to backup")
            return true
        }

        // The request should be a KeysBackup request with the encrypted keys
        if (request is Request.KeysBackup) {
            // Convert rooms to JSON string for proper serialization
            val roomsJson = Json.encodeToString(request.rooms)

            // Send the request to the server
            val response = client.put("$currentHomeserver/_matrix/client/v3/room_keys/keys") {
                bearerAuth(token)
                parameter("version", request.version)
                contentType(ContentType.Application.Json)
                setBody(roomsJson)
            }

            if (response.status == HttpStatusCode.OK) {
                println("📤 Uploaded room keys to backup")
                return true
            } else {
                println("❌ Failed to upload room keys: ${response.status}")
                return false
            }
        } else {
            println("❌ Unexpected request type for backup: ${request::class.simpleName}")
            return false
        }
    } catch (e: Exception) {
        println("❌ Exception uploading room keys: ${e.message}")
        return false
    }
}

// Restore keys from backup
suspend fun restoreFromBackup(recoveryKeyBase58: String): Boolean {
    val machine = olmMachine ?: return false
    val token = currentAccessToken ?: return false

    try {
        // Parse recovery key
        val recoveryKey = BackupRecoveryKey.fromBase58(recoveryKeyBase58)

        // Get backup info from server
        val backupInfo = getBackupInfo(token)
        if (backupInfo == null) return false

        val version = backupInfo["version"] as? String ?: return false
        val algorithm = (backupInfo["algorithm"] as? JsonPrimitive)?.content ?: return false

        if (algorithm != "m.megolm_backup.v1.curve25519-aes-sha2") {
            println("❌ Unsupported backup algorithm: $algorithm")
            return false
        }

        // Verify backup
        // val verification = machine.verifyBackup(Json.encodeToString(backupInfo))
        // if (verification != SignatureVerification.Valid) {
        //     println("❌ Backup verification failed: $verification")
        //     return false
        // }

        // Download and import keys
        val keysImported = downloadAndImportKeys(token, version, recoveryKey)

        if (keysImported > 0) {
            println("✅ Restored $keysImported keys from backup")
            return true
        } else {
            println("ℹ️  No keys to restore from backup")
            return true
        }
    } catch (e: Exception) {
        println("❌ Failed to restore from backup: ${e.message}")
        return false
    }
}

// Get backup info from server
suspend fun getBackupInfo(token: String): Map<String, Any>? {
    try {
        val response = client.get("$currentHomeserver/_matrix/client/v3/room_keys/version") {
            bearerAuth(token)
        }

        if (response.status == HttpStatusCode.OK) {
            val responseBody = response.body<JsonObject>()
            return Json.decodeFromJsonElement<Map<String, Any>>(responseBody)
        } else {
            println("❌ Failed to get backup info: ${response.status}")
            return null
        }
    } catch (e: Exception) {
        println("❌ Exception getting backup info: ${e.message}")
        return null
    }
}

// Download and import keys from backup
suspend fun downloadAndImportKeys(token: String, version: String, recoveryKey: BackupRecoveryKey): Int {
    try {
        val response = client.get("$currentHomeserver/_matrix/client/v3/room_keys/keys") {
            bearerAuth(token)
            parameter("version", version)
        }

        if (response.status == HttpStatusCode.OK) {
            val responseBody = response.body<JsonObject>()
            val keysJson = Json.encodeToString(responseBody)

            // Create a simple progress listener
            val progressListener = object : ProgressListener {
                override fun onProgress(progress: Int, total: Int) {
                    println("🔄 Key import progress: $progress/$total")
                }
            }

            // Import keys from backup
            val result = olmMachine?.importRoomKeysFromBackup(keysJson, version, progressListener)
            return result?.keys?.size ?: 0
        } else {
            println("❌ Failed to download keys: ${response.status}")
            return 0
        }
    } catch (e: Exception) {
        println("❌ Exception downloading keys: ${e.message}")
        return 0
    }
}

// Get room key counts
fun getRoomKeyCount(): RoomKeyCounts {
    val machine = olmMachine ?: return RoomKeyCounts(0L, 0L)
    return try {
        machine.roomKeyCounts()
    } catch (e: Exception) {
        println("❌ Failed to get room key counts: ${e.message}")
        RoomKeyCounts(0L, 0L)
    }
}