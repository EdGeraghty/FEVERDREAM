package crypto

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.MapSerializer
import network.client
import network.currentAccessToken
import network.currentHomeserver
import org.matrix.rustcomponents.sdk.crypto.BackupRecoveryKey
import org.matrix.rustcomponents.sdk.crypto.MegolmV1BackupKey
import org.matrix.rustcomponents.sdk.crypto.ProgressListener
import org.matrix.rustcomponents.sdk.crypto.Request

/**
 * Manages Matrix key backup operations including enabling backup,
 * creating backup versions, uploading keys, and restoring from backup.
 */
class KeyBackupManager {

    /**
     * Check if key backup is currently enabled.
     */
    fun isKeyBackupEnabled(): Boolean {
        val machine = OlmMachineManager.olmMachine ?: run {
            println("🔍 isKeyBackupEnabled: OlmMachine is null")
            return false
        }
        val enabled = machine.backupEnabled()
        println("🔍 isKeyBackupEnabled: backupEnabled = $enabled")
        return enabled
    }

    /**
     * Enable key backup and return the recovery key.
     */
    suspend fun enableKeyBackup(): String? {
        val machine = OlmMachineManager.olmMachine ?: return null
        val token = currentAccessToken ?: return null

        return try {
            // Generate backup recovery key
            val recoveryKey = BackupRecoveryKey()
            val publicKey = recoveryKey.megolmV1PublicKey()
            val recoveryKeyBase58 = recoveryKey.toBase58()

            // Create backup version on server
            val version = createBackupVersion(token, publicKey) ?: return null

            // Enable backup in OlmMachine
            machine.enableBackupV1(publicKey, version)

            // Save recovery key for later use
            machine.saveRecoveryKey(recoveryKey, version)

            println("🔐 Key backup enabled with version: $version")
            println("🔑 Recovery key: $recoveryKeyBase58")

            // Upload existing room keys
            uploadRoomKeys(token, version)

            recoveryKeyBase58
        } catch (e: Exception) {
            println("❌ Failed to enable key backup: ${e.message}")
            null
        }
    }

    /**
     * Create a backup version on the Matrix server.
     */
    private suspend fun createBackupVersion(token: String, publicKey: MegolmV1BackupKey): String? {
        return try {
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
                version
            } else {
                println("❌ Failed to create backup version: ${response.status}")
                null
            }
        } catch (e: Exception) {
            println("❌ Exception creating backup version: ${e.message}")
            null
        }
    }

    /**
     * Upload room keys to the backup.
     */
    private suspend fun uploadRoomKeys(token: String, version: String): Boolean {
        val machine = OlmMachineManager.olmMachine ?: return false

        return try {
            // Get room keys from OlmMachine
            val request = machine.backupRoomKeys()

            if (request == null) {
                println("ℹ️  No room keys to backup")
                return true
            }

            // The request should be a KeysBackup request with the encrypted keys
            if (request is Request.KeysBackup) {
                // rooms is already a JSON string, use it directly
                val roomsJson = request.rooms

                // Send the request to the server
                val response = client.put("$currentHomeserver/_matrix/client/v3/room_keys/keys") {
                    bearerAuth(token)
                    parameter("version", request.version)
                    contentType(ContentType.Application.Json)
                    setBody(roomsJson)
                }

                if (response.status == HttpStatusCode.OK) {
                    println("📤 Uploaded room keys to backup")
                    true
                } else {
                    println("❌ Failed to upload room keys: ${response.status}")
                    false
                }
            } else {
                println("❌ Unexpected request type for backup: ${request::class.simpleName}")
                false
            }
        } catch (e: Exception) {
            println("❌ Exception uploading room keys: ${e.message}")
            false
        }
    }

    /**
     * Restore keys from backup using the recovery key.
     */
    suspend fun restoreFromBackup(recoveryKeyBase58: String): Boolean {
        val machine = OlmMachineManager.olmMachine ?: return false
        val token = currentAccessToken ?: return false

        return try {
            // Parse recovery key
            val recoveryKey = BackupRecoveryKey.fromBase58(recoveryKeyBase58)

            // Get backup info from server
            val backupInfo = getBackupInfo(token) ?: return false

            val version = backupInfo["version"]?.jsonPrimitive?.content ?: return false
            val algorithm = (backupInfo["algorithm"] as? JsonPrimitive)?.content ?: return false

            if (algorithm != "m.megolm_backup.v1.curve25519-aes-sha2") {
                println("❌ Unsupported backup algorithm: $algorithm")
                return false
            }

            // Download and import keys
            val keysImported = downloadAndImportKeys(token, version, recoveryKey)

            if (keysImported > 0) {
                println("✅ Restored $keysImported keys from backup")
                true
            } else {
                println("ℹ️  No keys to restore from backup")
                true
            }
        } catch (e: Exception) {
            println("❌ Failed to restore from backup: ${e.message}")
            false
        }
    }

    /**
     * Get backup information from the server.
     */
    private suspend fun getBackupInfo(token: String): Map<String, JsonElement>? {
        return try {
            val response = client.get("$currentHomeserver/_matrix/client/v3/room_keys/version") {
                bearerAuth(token)
            }

            if (response.status == HttpStatusCode.OK) {
                val responseBody = response.body<JsonObject>()
                val mapSerializer = MapSerializer(String.serializer(), JsonElement.serializer())
                Json.decodeFromJsonElement(mapSerializer, responseBody)
            } else {
                println("❌ Failed to get backup info: ${response.status}")
                null
            }
        } catch (e: Exception) {
            println("❌ Exception getting backup info: ${e.message}")
            null
        }
    }

    /**
     * Download and import keys from backup.
     */
    private suspend fun downloadAndImportKeys(token: String, version: String, recoveryKey: BackupRecoveryKey): Int {
        return try {
            val response = client.get("$currentHomeserver/_matrix/client/v3/room_keys/keys") {
                bearerAuth(token)
                parameter("version", version)
            }

            if (response.status == HttpStatusCode.OK) {
                val responseBody = response.body<JsonObject>()
                val keysJson = Json.encodeToString(JsonObject.serializer(), responseBody)

                // Create a simple progress listener
                val progressListener = object : ProgressListener {
                    override fun onProgress(progress: Int, total: Int) {
                        println("🔄 Key import progress: $progress/$total")
                    }
                }

                // Import keys from backup
                val result = OlmMachineManager.olmMachine?.importRoomKeysFromBackup(keysJson, version, progressListener)
                result?.imported?.toInt() ?: 0
            } else {
                println("❌ Failed to download keys: ${response.status}")
                0
            }
        } catch (e: Exception) {
            println("❌ Exception downloading keys: ${e.message}")
            0
        }
    }
}
