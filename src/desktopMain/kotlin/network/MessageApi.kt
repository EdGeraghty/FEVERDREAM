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

// Track recently requested session keys to avoid duplicate requests
// Use session_id + sender_key for better uniqueness and longer tracking
val recentlyRequestedKeys = mutableMapOf<String, Long>()

// Clean up old requests periodically (older than 5 minutes)
fun cleanupOldKeyRequests() {
    val cutoffTime = System.currentTimeMillis() - (5 * 60 * 1000) // 5 minutes ago
    recentlyRequestedKeys.entries.removeIf { it.value < cutoffTime }
}

private suspend fun fetchMessagesFromApi(roomId: String, token: String): List<Event> {
    val response = client.get("$currentHomeserver/_matrix/client/v3/rooms/$roomId/messages") {
        bearerAuth(token)
        parameter("limit", "50")
        parameter("dir", "b")
    }
    if (response.status == HttpStatusCode.OK) {
        val messagesResponse = response.body<RoomMessagesResponse>()
        val fetchedMessages = messagesResponse.chunk.reversed()
        println("📥 getRoomMessages: Fetched ${fetchedMessages.size} messages from API")
        return fetchedMessages
    } else {
        println("❌ getRoomMessages: Bad response status ${response.status}")
        return emptyList()
    }
}

private fun mergeAndUpdateCache(roomId: String, cachedMessages: List<Event>, fetchedMessages: List<Event>): List<Event> {
    val allMessages = (cachedMessages + fetchedMessages).distinctBy { it.event_id }
    println("🔀 getRoomMessages: Merged to ${allMessages.size} total messages")
    roomMessageCache[roomId] = allMessages.takeLast(100).toMutableList()
    return allMessages
}

/**
 * Message handling API functions for Matrix client
 */
suspend fun getRoomMessages(roomId: String, skipDecryption: Boolean = false): List<Event> {
    val token = currentAccessToken ?: return emptyList()
    println("🔍 getRoomMessages: Fetching messages for room $roomId")

    // Get cached messages first
    val cachedMessages = roomMessageCache[roomId] ?: emptyList()
    println("📋 getRoomMessages: Found ${cachedMessages.size} cached messages")

    try {
        val fetchedMessages = fetchMessagesFromApi(roomId, token)
        if (fetchedMessages.isEmpty()) {
            return cachedMessages
        }

        val allMessages = mergeAndUpdateCache(roomId, cachedMessages, fetchedMessages)

        // Skip decryption if requested
        if (skipDecryption) {
            println("⏭️ getRoomMessages: Skipping decryption as requested")
            return allMessages
        }

        // Decrypt encrypted messages from the merged list
        val machine = olmMachine
        if (machine != null) {
            println("🔐 getRoomMessages: Starting decryption process")

            val decryptedMessages = allMessages.map { event ->
                if (event.type == "m.room.encrypted") {
                    try {
                        // Use proper OlmMachine decryption
                        val decryptionSettings = uniffi.matrix_sdk_crypto.DecryptionSettings(
                            senderDeviceTrustRequirement = uniffi.matrix_sdk_crypto.TrustRequirement.UNTRUSTED
                        )
                        val decryptedEvent = machine.decryptRoomEvent(
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
                        // Parse the decrypted event from the clearEvent field
                        val clearEventJson = decryptedEvent.clearEvent
                        val clearEvent = json.parseToJsonElement(clearEventJson).jsonObject
                        // Return the decrypted event with proper type and content
                        event.copy(
                            type = clearEvent["type"]?.jsonPrimitive?.content ?: "m.room.message",
                            content = clearEvent["content"] ?: JsonObject(emptyMap())
                        )
                    } catch (e: Exception) {
                        println("⚠️  Failed to decrypt event ${event.event_id}: ${e.message}")
                        // Return undecryptable message with specific error
                        val errorMessage = when {
                            e.message?.contains("Session expired") == true -> "** Unable to decrypt: Session expired **"
                            e.message?.contains("Room key not available") == true -> "** Unable to decrypt: Room key not available **"
                            e.message?.contains("Can't find the room key") == true -> "** Unable to decrypt: Can't find the room key **"
                            else -> "** Unable to decrypt: ${e.message} **"
                        }
                        event.copy(
                            type = "m.room.message",
                            content = json.parseToJsonElement("""{"msgtype": "m.bad.encrypted", "body": "$errorMessage"}""")
                        )
                    }
                } else {
                    event
                }
            }
            return decryptedMessages
        }
        return allMessages
    } catch (e: Exception) {
        println("❌ getRoomMessages: Exception during message fetching: ${e.message}")
        return cachedMessages
    }
}

suspend fun sendMessage(roomId: String, message: String, skipEncryptionSetup: Boolean = false): Boolean {
    val token = currentAccessToken ?: return false
    try {
        val response = client.put("$currentHomeserver/_matrix/client/v3/rooms/$roomId/send/m.room.message/${System.currentTimeMillis()}") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("m.text", message))
        }
        return response.status == HttpStatusCode.OK
    } catch (e: Exception) {
        println("❌ sendMessage failed: ${e.message}")
        return false
    }
}
