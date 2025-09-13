package network

import crypto.initializeEncryption
import crypto.syncAndProcessToDevice
import crypto.roomMessageCache
import crypto.isRoomEncrypted
import crypto.hasRoomKey
import crypto.ensureRoomEncryption
import crypto.olmMachine
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import models.*
import org.matrix.rustcomponents.sdk.crypto.*
import uniffi.matrix_sdk_crypto.CollectStrategy
import uniffi.matrix_sdk_crypto.DecryptionSettings
import uniffi.matrix_sdk_crypto.TrustRequirement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.*

// Track recently requested session keys to avoid duplicate requests
// Use session_id + sender_key for better uniqueness and longer tracking
val recentlyRequestedKeys = mutableMapOf<String, Long>()

// Clean up old requests periodically (older than 5 minutes)
fun cleanupOldKeyRequests() {
    val cutoffTime = System.currentTimeMillis() - (5 * 60 * 1000) // 5 minutes ago
    recentlyRequestedKeys.entries.removeIf { it.value < cutoffTime }
}

/**
 * Message handling API functions for Matrix client
 */
suspend fun getRoomMessages(roomId: String): List<Event> {
    val token = currentAccessToken ?: return emptyList()
    println("🔍 getRoomMessages: Fetching messages for room $roomId")

    // Get cached messages first
    val cachedMessages = roomMessageCache[roomId] ?: emptyList()
    println("📋 getRoomMessages: Found ${cachedMessages.size} cached messages")

    try {
        val response = client.get("$currentHomeserver/_matrix/client/v3/rooms/$roomId/messages") {
            bearerAuth(token)
            parameter("limit", "50")
            parameter("dir", "b")
        }
        if (response.status == HttpStatusCode.OK) {
            val messagesResponse = response.body<RoomMessagesResponse>()
            val fetchedMessages = messagesResponse.chunk.reversed()
            println("📥 getRoomMessages: Fetched ${fetchedMessages.size} messages from API")

            // Merge cached and fetched messages, avoiding duplicates
            val allMessages = (cachedMessages + fetchedMessages).distinctBy { it.event_id }
            println("🔀 getRoomMessages: Merged to ${allMessages.size} total messages")

            // Update cache with merged messages
            roomMessageCache[roomId] = allMessages.takeLast(100).toMutableList()

            // Decrypt encrypted messages from the merged list
            val machine = olmMachine
            if (machine != null) {
                println("🔐 getRoomMessages: Starting decryption process")

                // Check if OlmMachine is still valid
                try {
                    // Quick test to see if OlmMachine is still functional
                    machine.identityKeys()
                    println("✅ OlmMachine is functional")
                } catch (e: Exception) {
                    println("❌ OlmMachine is not functional: ${e.message}")
                    // Return all messages as undecryptable
                    return allMessages.map { event ->
                        if (event.type == "m.room.encrypted") {
                            event.copy(
                                type = "m.room.message",
                                content = json.parseToJsonElement("""{"msgtype": "m.bad.encrypted", "body": "** Unable to decrypt: OlmMachine not available **"}""")
                            )
                        } else {
                            event
                        }
                    }
                }

                // Sync once at the beginning to get latest keys for all messages
                println("🔄 Syncing once before decryption...")
                val initialSyncResult = syncAndProcessToDevice(10000UL) // 10 second timeout
                if (!initialSyncResult) {
                    println("⚠️  Initial sync failed - this may affect decryption of recent messages")
                } else {
                    println("✅ Initial sync completed successfully")
                }

                val decryptedMessages = allMessages.map { event ->
                    if (event.type == "m.room.encrypted") {
                        try {
                            // Clean up old requests before checking
                            cleanupOldKeyRequests()

                            // Check if we've already requested this key recently using session info
                            val keyRequestId = "${roomId}:${event.sender}:${event.event_id}"
                            val lastRequestTime = recentlyRequestedKeys[keyRequestId]
                            val currentTime = System.currentTimeMillis()

                            if (lastRequestTime != null && (currentTime - lastRequestTime) < (30 * 1000)) { // 30 seconds
                                println("⚠️  Already requested key for this event recently (${(currentTime - lastRequestTime)/1000}s ago), skipping duplicate request")
                                // Fall through to return undecryptable event
                            } else {
                                recentlyRequestedKeys[keyRequestId] = currentTime
                            }

                            // More defensive approach: Check session validity before attempting decryption
                            val hasValidKey = try {
                                hasRoomKey(roomId)
                            } catch (e: Exception) {
                                println("⚠️  Error checking room key validity: ${e.message}")
                                false
                            }

                            if (!hasValidKey) {
                                println("⚠️  No valid room key available, skipping decryption attempt")
                                // Return event with key missing marker without attempting decryption
                                return@map event.copy(
                                    type = "m.room.message",
                                    content = json.parseToJsonElement("""{"msgtype": "m.bad.encrypted", "body": "** Unable to decrypt: Room key not available **"}""")
                                )
                            }

                            val eventJson = json.encodeToString(event)
                            val decryptionSettings = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)

                            // Use a safer decryption approach that avoids panics
                            val decrypted = try {
                                // First, try to validate the event structure to avoid malformed event panics
                                val eventObj = json.parseToJsonElement(eventJson)
                                if (eventObj !is JsonObject) {
                                    throw Exception("Invalid event structure")
                                }

                                val content = eventObj["content"]
                                if (content !is JsonObject) {
                                    throw Exception("Invalid event content structure")
                                }

                                val algorithm = content["algorithm"]?.toString()?.trim('"')
                                val ciphertext = content["ciphertext"]

                                if (algorithm.isNullOrBlank() || ciphertext == null) {
                                    throw Exception("Missing required encryption fields")
                                }

                                // Only attempt decryption if we have valid structure
                                machine.decryptRoomEvent(
                                    roomId = roomId,
                                    event = eventJson,
                                    decryptionSettings = decryptionSettings,
                                    handleVerificationEvents = false,
                                    strictShields = false
                                )
                            } catch (e: Exception) {
                                // Handle all decryption failures gracefully
                                if (e.message?.contains("Session expired") == true ||
                                    e.message?.contains("panicked") == true ||
                                    e.message?.contains("Invalid event structure") == true ||
                                    e.message?.contains("Missing required encryption fields") == true ||
                                    e.message?.contains("OlmMachine object has already been destroyed") == true) {
                                    println("⚠️  Decryption not possible: ${e.message}")
                                    // Return event with appropriate error marker
                                    return@map event.copy(
                                        type = "m.room.message",
                                        content = json.parseToJsonElement("""{"msgtype": "m.bad.encrypted", "body": "** Unable to decrypt: ${e.message} **"}""")
                                    )
                                } else {
                                    throw e
                                }
                            }

                            // Parse the decrypted content and merge with original event metadata
                            val decryptedContent = try {
                                json.parseToJsonElement(decrypted.clearEvent)
                            } catch (e: Exception) {
                                // If decrypted content is not valid JSON, treat it as plain text
                                println("⚠️  Decrypted content is not valid JSON: ${decrypted.clearEvent}")
                                JsonPrimitive(decrypted.clearEvent)
                            }

                            // Try to decode as MessageContent, but handle cases where it might not match
                            val messageContent = try {
                                json.decodeFromString<MessageContent>(json.encodeToString(decryptedContent))
                            } catch (e: Exception) {
                                // If it doesn't match MessageContent structure, create a fallback
                                println("⚠️  Decrypted content doesn't match expected format: ${decrypted.clearEvent}")
                                MessageContent("m.text", decrypted.clearEvent.trim('"'))
                            }

                            // Ensure we have a body field
                            val finalContent = if (messageContent.body.isNullOrBlank()) {
                                messageContent.copy(body = decrypted.clearEvent.trim('"'))
                            } else {
                                messageContent
                            }

                            // Create a new event with the decrypted content but preserve original metadata
                            event.copy(
                                type = "m.room.message",
                                content = json.parseToJsonElement(json.encodeToString(finalContent))
                            )
                        } catch (e: Exception) {
                            println("❌ Decryption failed: ${e.message}")

                            // Handle different types of decryption failures
                            when {
                                e.message?.contains("Session expired") == true -> {
                                    println("⚠️  Session expired during decryption")

                                    // Try session renewal one more time
                                    val renewed = hasRoomKey(roomId)
                                    if (renewed) {
                                        println("✅ Session renewed, attempting final decryption...")

                                        // Final attempt at decryption
                                        try {
                                            val finalEventJson = json.encodeToString(event)
                                            val finalDecryptionSettings = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)
                                            val finalDecrypted = machine.decryptRoomEvent(
                                                roomId = roomId,
                                                event = finalEventJson,
                                                decryptionSettings = finalDecryptionSettings,
                                                handleVerificationEvents = false,
                                                strictShields = false
                                            )

                                            // If successful, return the decrypted event
                                            val finalDecryptedContent = try {
                                                json.parseToJsonElement(finalDecrypted.clearEvent)
                                            } catch (e: Exception) {
                                                JsonPrimitive(finalDecrypted.clearEvent)
                                            }

                                            val finalMessageContent = try {
                                                json.decodeFromString<MessageContent>(json.encodeToString(finalDecryptedContent))
                                            } catch (e: Exception) {
                                                MessageContent("m.text", finalDecrypted.clearEvent.trim('"'))
                                            }

                                            val finalContent = if (finalMessageContent.body.isNullOrBlank()) {
                                                finalMessageContent.copy(body = finalDecrypted.clearEvent.trim('"'))
                                            } else {
                                                finalMessageContent
                                            }

                                            return@map event.copy(
                                                type = "m.room.message",
                                                content = json.parseToJsonElement(json.encodeToString(finalContent))
                                            )
                                        } catch (finalException: Exception) {
                                            println("❌ Final decryption attempt failed: ${finalException.message}")
                                        }
                                    }

                                    // Return event with session expired marker
                                    return@map event.copy(
                                        type = "m.room.message",
                                        content = json.parseToJsonElement("""{"msgtype": "m.bad.encrypted", "body": "** Unable to decrypt: Session expired - please refresh the room **"}""")
                                    )
                                }
                                e.message?.contains("Can't find the room key") == true -> {
                                    println("🔑 Room key missing, attempting to request keys...")
                                    // Try to request missing keys from other devices
                                    try {
                                        val eventJsonForKeyRequest = json.encodeToString(event)
                                        val keyRequestPair = machine.requestRoomKey(eventJsonForKeyRequest, roomId)

                                        // Send the key request (skip cancellation to avoid 400 errors)
                                        val keyRequest = keyRequestPair.keyRequest
                                        when (keyRequest) {
                                            is Request.ToDevice -> {
                                                println("📤 Sending room key request")
                                                try {
                                                    val keysQueryResponse = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${keyRequest.eventType}/${System.currentTimeMillis()}") {
                                                        bearerAuth(token)
                                                        contentType(ContentType.Application.Json)
                                                        // Debug: Log the raw request body
                                                        println("🔍 Key request body: ${keyRequest.body}")

                                                        // Parse the string body as JSON
                                                        val body = convertMapToHashMap(keyRequest.body)
                                                        if (body is Map<*, *>) {
                                                            @Suppress("UNCHECKED_CAST")
                                                            val mapBody = body as Map<String, Any>
                                                            val jsonBody = JsonObject(mapBody.mapValues { anyToJsonElement(it.value) })
                                                            // Matrix API requires messages wrapper for to-device requests
                                                            val messagesWrapper = JsonObject(mapOf("messages" to jsonBody))
                                                            println("🔍 Wrapped request body: $messagesWrapper")
                                                            setBody(messagesWrapper)
                                                        } else if (body is String) {
                                                            val parsedElement = json.parseToJsonElement(body)
                                                            if (parsedElement is JsonObject) {
                                                                // Matrix API requires messages wrapper for to-device requests
                                                                val messagesWrapper = JsonObject(mapOf("messages" to parsedElement))
                                                                println("🔍 Wrapped parsed body: $messagesWrapper")
                                                                setBody(messagesWrapper)
                                                            } else {
                                                                println("⚠️  Unexpected body format, using empty object")
                                                                setBody(JsonObject(mapOf("messages" to JsonObject(mapOf()))))
                                                            }
                                                        } else {
                                                            println("⚠️  Unknown body type: ${body?.javaClass?.simpleName}")
                                                            setBody(JsonObject(mapOf("messages" to JsonObject(mapOf()))))
                                                        }
                                                    }
                                                    if (keysQueryResponse.status == HttpStatusCode.OK) {
                                                        println("✅ Room key request sent successfully")
                                                    } else {
                                                        println("❌ Failed to send room key request: ${keysQueryResponse.status}")
                                                        // Log response body for debugging
                                                        try {
                                                            val responseBody = keysQueryResponse.body<String>()
                                                            println("❌ Response body: $responseBody")
                                                        } catch (e: Exception) {
                                                            println("❌ Could not read response body: ${e.message}")
                                                        }
                                                        // Don't throw exception here - continue with decryption attempt
                                                    }
                                                } catch (e: Exception) {
                                                    println("❌ Exception sending key request: ${e.message}")
                                                    e.printStackTrace()
                                                    // Don't throw exception here - continue with decryption attempt
                                                }
                                            }
                                            else -> {
                                                println("⚠️  Unexpected key request type: ${keyRequest::class.simpleName}")
                                            }
                                        }

                                        // Sync multiple times with delays to allow key responses to arrive
                                        println("🔄 Syncing to process incoming key responses...")
                                        for (i in 1..3) {
                                            println("🔄 Sync attempt ${i}/3...")
                                            val syncResult = syncAndProcessToDevice(5000UL) // 5 second timeout per sync
                                            if (syncResult) {
                                                println("✅ Sync ${i} successful")
                                            } else {
                                                println("⚠️  Sync ${i} failed or no new events")
                                            }

                                            // Small delay between syncs
                                            if (i < 3) {
                                                kotlinx.coroutines.delay(2000)
                                            }
                                        }

                                        // Try decryption again with the newly received keys
                                        try {
                                            val retryEventJson = json.encodeToString(event)
                                            val retryDecryptionSettings = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)
                                            val retryDecrypted = machine.decryptRoomEvent(
                                                roomId = roomId,
                                                event = retryEventJson,
                                                decryptionSettings = retryDecryptionSettings,
                                                handleVerificationEvents = false,
                                                strictShields = false
                                            )

                                            // If retry succeeds, return the decrypted event
                                            val retryDecryptedContent = try {
                                                json.parseToJsonElement(retryDecrypted.clearEvent)
                                            } catch (e: Exception) {
                                                JsonPrimitive(retryDecrypted.clearEvent)
                                            }

                                            val retryMessageContent = try {
                                                json.decodeFromString<MessageContent>(json.encodeToString(retryDecryptedContent))
                                            } catch (e: Exception) {
                                                MessageContent("m.text", retryDecrypted.clearEvent.trim('"'))
                                            }

                                            val retryFinalContent = if (retryMessageContent.body.isNullOrBlank()) {
                                                retryMessageContent.copy(body = retryDecrypted.clearEvent.trim('"'))
                                            } else {
                                                retryMessageContent
                                            }

                                            return@map event.copy(
                                                type = "m.room.message",
                                                content = json.parseToJsonElement(json.encodeToString(retryFinalContent))
                                            )
                                        } catch (retryException: Exception) {
                                            println("❌ Retry decryption also failed: ${retryException.message}")
                                            // Fall through to return undecryptable event with better error message
                                        }
                                    } catch (keyRequestException: Exception) {
                                        println("❌ Key request failed: ${keyRequestException.message}")
                                        // Fall through to return undecryptable event with better error message
                                    }

                                    // Return event with improved error message for missing keys
                                    return@map event.copy(
                                        type = "m.room.message",
                                        content = json.parseToJsonElement("""{"msgtype": "m.bad.encrypted", "body": "** Unable to decrypt: Room key not available. This message was sent before you joined or from another device. **"}""")
                                    )
                                }
                                else -> {
                                    // Default case for other decryption failures
                                    println("❌ Other decryption failure: ${e.message}")
                                }
                            }

                            // Return the event as-is with a bad encrypted marker for any decryption failure
                            return@map event.copy(
                                type = "m.room.message",
                                content = json.parseToJsonElement("""{"msgtype": "m.bad.encrypted", "body": "** Unable to decrypt: ${e.message} **"}""")
                            )
                        }
                    } else {
                        // Not an encrypted event, return as-is
                        event
                    }
                }
                println("🔐 getRoomMessages: Decryption process completed")
                return decryptedMessages
            } else {
                println("⚠️ getRoomMessages: No OlmMachine available, returning raw messages")
                return allMessages
            }
        } else {
            println("❌ getRoomMessages: Bad response status ${response.status}")
            return cachedMessages
        }
    } catch (e: Exception) {
        println("❌ getRoomMessages: Exception: ${e.message}")
        return cachedMessages
    }
}

suspend fun sendMessage(roomId: String, message: String, skipEncryptionSetup: Boolean = false): Boolean {
    println("📤 sendMessage called with roomId: $roomId, message: $message, skipEncryptionSetup: $skipEncryptionSetup")
    try {
        // Check if room is encrypted
        val isEncrypted = isRoomEncrypted(roomId)
        val machine = olmMachine

        var finalContent: String
        var eventType: String

        if (isEncrypted && machine != null) {
            // Only ensure encryption setup if not already verified
            if (!skipEncryptionSetup) {
                println("🔐 Ensuring encryption setup for room $roomId before sending...")
                val encryptionSetup = ensureRoomEncryption(roomId)
                if (!encryptionSetup) {
                    println("⚠️  Failed to set up encryption, cannot send encrypted message to encrypted room")
                    println("❌ Message not sent - encryption setup failed for encrypted room")
                    return false // Don't send the message if encryption setup fails
                }
            }

            // Validate that encryption is working before sending
            println("� Validating encryption capability for room $roomId...")
            try {
                // Test encryption with a dummy message to ensure it works
                machine.encrypt(roomId, "m.room.message", """{"body": "encryption_test", "msgtype": "m.text"}""")
                println("✅ Encryption validation successful")
            } catch (validationError: Exception) {
                println("❌ Encryption validation failed: ${validationError.message}")
                println("❌ Cannot send message - encryption not working for room")
                return false
            }

            // Encrypt the actual message
            println("🔐 Encrypting message for room $roomId")
            try {
                val encryptedContent = machine.encrypt(roomId, "m.room.message", """{"body": "$message", "msgtype": "m.text"}""")
                finalContent = encryptedContent // Don't double-encode - OlmMachine already returns JSON string
                eventType = "m.room.encrypted"
                println("✅ Message encrypted successfully")
                println("🔐 Encrypted content: $finalContent")
            } catch (encryptError: Exception) {
                println("❌ Message encryption failed: ${encryptError.message}")
                println("❌ Cannot send message - encryption failed for encrypted room")
                return false // Don't send the message if encryption fails
            }
        } else {
            // Send as plain text
            val requestBody = SendMessageRequest(body = message)
            finalContent = json.encodeToString(requestBody)
            eventType = "m.room.message"
        }

        val url = "$currentHomeserver/_matrix/client/r0/rooms/$roomId/send/$eventType/${System.currentTimeMillis()}"
        println("🌐 Sending to URL: $url")
        println("🔑 Access token present: ${currentAccessToken != null}")
        println("📝 Final content: $finalContent")
        println("📝 Content type: ${finalContent::class.simpleName}")

        val response = client.put(url) {
            bearerAuth(currentAccessToken!!)
            contentType(ContentType.Application.Json)
            setBody(finalContent)
        }

        if (response.status == HttpStatusCode.OK) {
            println("✅ Message sent successfully")
            return true
        } else {
            val errorBody = response.body<String>()
            println("❌ Failed to send message: ${response.status}")
            println("❌ Error details: $errorBody")
            return false
        }
    } catch (e: Exception) {
        println("❌ Send message failed: ${e.message}")
        return false
    }
}
