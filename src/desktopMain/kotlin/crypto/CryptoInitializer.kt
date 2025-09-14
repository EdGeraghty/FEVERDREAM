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


// Initialize Olm encryption
fun initializeEncryption(userId: String, deviceId: String) {
    OlmMachineManager.initializeEncryption(userId, deviceId)
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
    OlmMachineManager.clearOlmMachine()
    MessageCacheManager.clearAllMessages()
}

// Check if key backup is enabled
fun isKeyBackupEnabled(): Boolean {
    return KeyBackupManager().isKeyBackupEnabled()
}

// Generate a random backup recovery key
fun generateBackupRecoveryKey(): BackupRecoveryKey {
    return BackupRecoveryKey()
}

// Enable key backup
suspend fun enableKeyBackup(): String? {
    return KeyBackupManager().enableKeyBackup()
}

// Restore keys from backup
suspend fun restoreFromBackup(recoveryKeyBase58: String): Boolean {
    return KeyBackupManager().restoreFromBackup(recoveryKeyBase58)
}

// Get room key counts
fun getRoomKeyCount(): RoomKeyCounts {
    return RoomKeyManager().getRoomKeyCount()
}

// Get identity keys (Curve25519 and Ed25519)
fun getIdentityKeys(): Map<String, String>? {
    return RoomKeyManager().getIdentityKeys()
}