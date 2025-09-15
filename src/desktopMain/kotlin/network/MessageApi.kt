package network

import io.ktor.client.request.*
import io.ktor.client.call.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import models.*
import network.*
import crypto.*
import uniffi.matrix_sdk_crypto.*
import org.matrix.rustcomponents.sdk.crypto.*

/**
 * Tracks recently requested session keys to avoid duplicate requests
 */
class KeyRequestTracker {
    private val recentlyRequestedKeys = mutableMapOf<String, Long>()

    /**
     * Check if a key has been recently requested
     */
    fun hasRecentlyRequested(sessionId: String, senderKey: String): Boolean {
        val key = "$sessionId:$senderKey"
        val lastRequestTime = recentlyRequestedKeys[key] ?: return false
        val cutoffTime = System.currentTimeMillis() - (5 * 60 * 1000) // 5 minutes ago
        return lastRequestTime > cutoffTime
    }

    /**
     * Mark a key as recently requested
     */
    fun markRequested(sessionId: String, senderKey: String) {
        val key = "$sessionId:$senderKey"
        recentlyRequestedKeys[key] = System.currentTimeMillis()
    }

    /**
     * Clean up old requests periodically (older than 5 minutes)
     */
    fun cleanupOldRequests() {
        val cutoffTime = System.currentTimeMillis() - (5 * 60 * 1000) // 5 minutes ago
        recentlyRequestedKeys.entries.removeIf { it.value < cutoffTime }
    }
}

/**
 * Handles fetching messages from the Matrix API
 */
class MessageFetcher {
    suspend fun fetchMessagesFromApi(roomId: String, token: String): List<Event> {
        val response = withTimeout(15000L) { // 15 second timeout
            client.get("$currentHomeserver/_matrix/client/v3/rooms/$roomId/messages") {
                bearerAuth(token)
                parameter("limit", "50")
                parameter("dir", "b")
            }
        }

        return if (response.status == HttpStatusCode.OK) {
            val messagesResponse = response.body<RoomMessagesResponse>()
            val fetchedMessages = messagesResponse.chunk.reversed()
            println("📥 MessageFetcher: Fetched ${fetchedMessages.size} messages from API")
            fetchedMessages
        } else {
            println("❌ MessageFetcher: Bad response status ${response.status}")
            emptyList()
        }
    }
}

/**
 * Manages message caching operations
 */
class MessageCacheManager {
    fun getCachedMessages(roomId: String): List<Event> {
        return crypto.MessageCacheManager.getRoomMessages(roomId)
    }

    fun mergeAndUpdateCache(roomId: String, cachedMessages: List<Event>, fetchedMessages: List<Event>): List<Event> {
        val allMessages = (cachedMessages + fetchedMessages).distinctBy { it.event_id }
        println("🔀 MessageCacheManager: Merged to ${allMessages.size} total messages")
        crypto.MessageCacheManager.setRoomMessages(roomId, allMessages.takeLast(100))
        return allMessages
    }
}

/**
 * Handles decryption of encrypted messages
 */
class MessageDecryptor {
    suspend fun decryptMessages(roomId: String, messages: List<Event>): List<Event> {
        if (crypto.OlmMachineManager.olmMachine == null) return messages

        println("🔐 MessageDecryptor: Starting decryption process")

        // Decrypt messages asynchronously on IO dispatcher to avoid blocking UI
        return withContext(Dispatchers.IO) {
            coroutineScope {
                val decryptedMessages = messages.map { event ->
                    async {
                        if (event.type == "m.room.encrypted") {
                            decryptSingleMessage(roomId, event)
                        } else {
                            event
                        }
                    }
                }.awaitAll()
                decryptedMessages
            }
        }
    }

    private suspend fun decryptSingleMessage(roomId: String, event: Event): Event {
        return try {
            // Validate that the encrypted event has required fields
            val content = event.content
            if (content !is JsonObject) {
                return createUndecryptableEvent(event, "Malformed encrypted event (invalid content format)")
            }

            val jsonContent = content
            val algorithm = jsonContent["algorithm"]?.jsonPrimitive?.content
            val ciphertext = jsonContent["ciphertext"]?.jsonPrimitive?.content

            if (algorithm.isNullOrEmpty() || ciphertext.isNullOrEmpty()) {
                return createUndecryptableEvent(event, "Malformed encrypted event (missing algorithm or ciphertext)")
            }

            // Add timeout to decryption to prevent hanging
            val decryptedEvent = withTimeout(5000L) { // 5 second timeout per message
                // Use proper OlmMachine decryption
                val decryptionSettings = uniffi.matrix_sdk_crypto.DecryptionSettings(
                    senderDeviceTrustRequirement = uniffi.matrix_sdk_crypto.TrustRequirement.UNTRUSTED
                )

                crypto.OlmMachineManager.olmMachine!!.decryptRoomEvent(
                    roomId = roomId,
                    event = JsonObject(mapOf(
                        "type" to JsonPrimitive(event.type),
                        "event_id" to JsonPrimitive(event.event_id),
                        "sender" to JsonPrimitive(event.sender),
                        "origin_server_ts" to JsonPrimitive(event.origin_server_ts),
                        "content" to event.content
                    )).toString(),
                    decryptionSettings = decryptionSettings,
                    handleVerificationEvents = false,
                    strictShields = false
                )
            }

            // Parse the decrypted event from the clearEvent field
            val clearEventJson = decryptedEvent.clearEvent
            val clearEvent = json.parseToJsonElement(clearEventJson).jsonObject

            // Return the decrypted event with proper type and content
            event.copy(
                type = clearEvent["type"]?.jsonPrimitive?.content ?: "m.room.message",
                content = clearEvent["content"] ?: JsonObject(emptyMap())
            )
        } catch (e: TimeoutCancellationException) {
            println("⚠️  Decryption timed out for event ${event.event_id}")
            createUndecryptableEvent(event, "Decryption timed out")
        } catch (e: Exception) {
            println("⚠️  Failed to decrypt event ${event.event_id}: ${e.message}")
            createUndecryptableEvent(event, getDecryptionErrorMessage(e))
        }
    }

    private fun createUndecryptableEvent(originalEvent: Event, errorMessage: String): Event {
        return originalEvent.copy(
            type = "m.room.message",
            content = json.parseToJsonElement("""{"msgtype": "m.bad.encrypted", "body": "** $errorMessage **"}""")
        )
    }

    private fun getDecryptionErrorMessage(e: Exception): String {
        return when {
            e.message?.contains("Session expired") == true -> "Unable to decrypt: Session expired"
            e.message?.contains("Room key not available") == true -> "Unable to decrypt: Room key not available"
            e.message?.contains("Can't find the room key") == true -> "Unable to decrypt: Can't find the room key"
            else -> "Unable to decrypt: ${e.message}"
        }
    }
}

/**
 * Handles sending messages (both encrypted and unencrypted)
 */
class MessageSender {
    suspend fun sendMessage(roomId: String, message: String, skipEncryptionSetup: Boolean = false): Boolean {
        val token = currentAccessToken ?: return false

        return try {
            val isEncrypted = isRoomEncrypted(roomId)

            if (isEncrypted) {
                sendEncryptedMessage(roomId, message, token, skipEncryptionSetup)
            } else {
                sendUnencryptedMessage(roomId, message, token)
            }
        } catch (e: Exception) {
            println("❌ MessageSender: sendMessage failed: ${e.message}")
            false
        }
    }

    private suspend fun sendEncryptedMessage(roomId: String, message: String, token: String, skipEncryptionSetup: Boolean): Boolean {
        val machine = crypto.OlmMachineManager.olmMachine
        if (machine == null) {
            println("⚠️  MessageSender: OlmMachine not available, sending unencrypted message")
            return sendUnencryptedMessage(roomId, message, token)
        }

        // Ensure encryption is set up for this room (unless explicitly skipped)
        if (!skipEncryptionSetup) {
            val encryptionSetup = ensureRoomEncryption(roomId)
            if (!encryptionSetup) {
                println("⚠️  MessageSender: Failed to set up encryption for room $roomId, sending unencrypted message")
                return sendUnencryptedMessage(roomId, message, token)
            }

            // Check if we can actually encrypt messages (important for single-device setups)
            if (!crypto.canEncryptRoom(roomId)) {
                println("⚠️  MessageSender: Cannot encrypt messages in this room (likely single-device setup), refusing to send")
                return false
            }
        }

        // Try to encrypt the message
        return try {
            // Properly construct message content as JSON
            val messageContent = """{"msgtype":"m.text","body":${JsonPrimitive(message)}}"""
            val encryptedContent = machine.encrypt(roomId, "m.room.message", messageContent)

            // Send as encrypted message
            val encryptedRequest = JsonObject(mapOf(
                "type" to JsonPrimitive("m.room.encrypted"),
                "content" to json.parseToJsonElement(encryptedContent)
            ))

            val response = client.put("$currentHomeserver/_matrix/client/v3/rooms/$roomId/send/m.room.encrypted/${System.currentTimeMillis()}") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(encryptedRequest)
            }

            val success = response.status == HttpStatusCode.OK
            if (success) {
                println("🔐 MessageSender: Encrypted message sent successfully")
            } else {
                println("❌ MessageSender: Failed to send encrypted message: ${response.status}")
            }
            success
        } catch (encryptException: Exception) {
            println("⚠️  MessageSender: Encryption failed: ${encryptException.message}")

            // If encryption failed due to session issues, try to renew the session once
            if (!skipEncryptionSetup && encryptException.message?.contains("session") == true) {
                println("🔄 Attempting session renewal before fallback...")
                val retryEncryptionSetup = ensureRoomEncryption(roomId)
                if (retryEncryptionSetup) {
                    // Retry encryption with renewed session
                    return try {
                        // Properly construct message content as JSON
                        val messageContent = """{"msgtype":"m.text","body":${JsonPrimitive(message)}}"""
                        val encryptedContent = machine.encrypt(roomId, "m.room.message", messageContent)

                        val encryptedRequest = JsonObject(mapOf(
                            "type" to JsonPrimitive("m.room.encrypted"),
                            "content" to json.parseToJsonElement(encryptedContent)
                        ))

                        val response = client.put("$currentHomeserver/_matrix/client/v3/rooms/$roomId/send/m.room.encrypted/${System.currentTimeMillis()}") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(encryptedRequest)
                        }

                        val success = response.status == HttpStatusCode.OK
                        if (success) {
                            println("🔐 MessageSender: Encrypted message sent successfully after session renewal")
                        } else {
                            println("❌ MessageSender: Failed to send encrypted message after renewal: ${response.status}")
                        }
                        success
                    } catch (retryException: Exception) {
                        println("⚠️  MessageSender: Encryption still failed after session renewal: ${retryException.message}")
                        // Don't fall back to unencrypted - return failure instead
                        println("🚫 MessageSender: Refusing to send unencrypted message in encrypted room")
                        false
                    }
                }
            }

            // Don't fall back to unencrypted message - return failure for security
            println("🚫 MessageSender: Refusing to send unencrypted message in encrypted room")
            false
        }
    }

    private suspend fun sendUnencryptedMessage(roomId: String, message: String, token: String): Boolean {
        val response = client.put("$currentHomeserver/_matrix/client/v3/rooms/$roomId/send/m.room.message/${System.currentTimeMillis()}") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("m.text", message))
        }
        return response.status == HttpStatusCode.OK
    }
}

// Global instance for backward compatibility
val keyRequestTracker = KeyRequestTracker()

// Backward compatibility function
fun cleanupOldKeyRequests() {
    keyRequestTracker.cleanupOldRequests()
}

/**
 * Message handling API functions for Matrix client
 */
suspend fun getRoomMessages(roomId: String, skipDecryption: Boolean = false): List<Event> {
    val token = currentAccessToken ?: return emptyList()
    println("� getRoomMessages: Fetching messages for room $roomId")

    val cacheManager = MessageCacheManager()
    val messageFetcher = MessageFetcher()
    val messageDecryptor = MessageDecryptor()

    // Get cached messages first
    val cachedMessages = cacheManager.getCachedMessages(roomId)
    println("📋 getRoomMessages: Found ${cachedMessages.size} cached messages")

    return try {
        val fetchedMessages = messageFetcher.fetchMessagesFromApi(roomId, token)
        if (fetchedMessages.isEmpty()) {
            return cachedMessages
        }

        val allMessages = cacheManager.mergeAndUpdateCache(roomId, cachedMessages, fetchedMessages)

        // Skip decryption if requested
        if (skipDecryption) {
            println("⏭️ getRoomMessages: Skipping decryption as requested")
            return allMessages
        }

        // Decrypt encrypted messages from the merged list
        messageDecryptor.decryptMessages(roomId, allMessages)
    } catch (e: Exception) {
        println("❌ getRoomMessages: Exception during message fetching: ${e.message}")
        cachedMessages
    }
}

suspend fun sendMessage(roomId: String, message: String, skipEncryptionSetup: Boolean = false): Boolean {
    val messageSender = MessageSender()
    return messageSender.sendMessage(roomId, message, skipEncryptionSetup)
}
