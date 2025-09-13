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

suspend fun isRoomEncrypted(roomId: String): Boolean {
    val token = currentAccessToken ?: return false
    println("🔍 isRoomEncrypted: Checking encryption for room $roomId")
    try {
        val response = withTimeout(5000L) { // 5 second timeout
            println("🌐 isRoomEncrypted: Making HTTP request...")
            client.get("$currentHomeserver/_matrix/client/v3/rooms/$roomId/state/m.room.encryption") {
                bearerAuth(token)
            }
        }
        val result = response.status == HttpStatusCode.OK
        println("✅ isRoomEncrypted: Room $roomId is ${if (result) "encrypted" else "not encrypted"}")
        return result
    } catch (e: TimeoutCancellationException) {
        println("❌ isRoomEncrypted: Request timed out for room $roomId")
        return false
    } catch (e: Exception) {
        println("⚠️ isRoomEncrypted: Exception for room $roomId: ${e.message}")
        return false
    }
}

suspend fun ensureRoomEncryption(roomId: String): Boolean {
    val token = currentAccessToken ?: return false
    val machine = olmMachine ?: return false

    try {
        // Check if room is encrypted
        if (!isRoomEncrypted(roomId)) {
            println("⚠️  Room $roomId is not encrypted")
            return false
        }

        // Get room members
        val allRoomMembers = getRoomMembers(roomId)
        println("🔍 Room members: $allRoomMembers")

        // Update tracked users to ensure OlmMachine knows about them
        machine.updateTrackedUsers(allRoomMembers)

        // Send any outgoing requests (like keys query) before sharing room key
        val initialRequests = machine.outgoingRequests()
        for (request in initialRequests) {
            when (request) {
                is Request.KeysUpload -> {
                    val response = client.post("$currentHomeserver/_matrix/client/v3/keys/upload") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        val body = convertMapToHashMap(request.body)
                        if (body is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val mapBody = body as Map<String, Any>
                            setBody(JsonObject(mapBody.mapValues { anyToJsonElement(it.value) }))
                        } else if (body is String) {
                            setBody(json.parseToJsonElement(body))
                        }
                    }
                    if (response.status == HttpStatusCode.OK) {
                        println("✅ Initial keys uploaded")
                    }
                }
                is Request.KeysClaim -> {
                    val keysClaimResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/claim") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        val body = convertMapToHashMap(request.oneTimeKeys)
                        if (body is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val mapBody = body as Map<String, Any>
                            setBody(JsonObject(mapBody.mapValues { anyToJsonElement(it.value) }))
                        } else if (body is String) {
                            setBody(json.parseToJsonElement(body))
                        }
                    }
                    if (keysClaimResponse.status == HttpStatusCode.OK) {
                        println("✅ One-time keys claimed successfully")

                        // Process the claimed keys to establish Olm sessions
                        val keysResponseText = keysClaimResponse.body<String>()
                        val keysJson = json.parseToJsonElement(keysResponseText)

                        // Create events for the claimed keys
                        val events = mutableListOf<String>()
                        val oneTimeKeysJson = keysJson.jsonObject["one_time_keys"]?.jsonObject ?: JsonObject(emptyMap())

                        for ((userDeviceKey, keyData) in oneTimeKeysJson) {
                            run {
                                // Create a dummy device key update event for each claimed key
                                val event = json.encodeToString(JsonObject(mapOf(
                                    "type" to JsonPrimitive("m.dummy"),
                                    "sender" to JsonPrimitive("dummy"),
                                    "content" to JsonObject(mapOf(
                                        "one_time_key" to keyData,
                                        "user_device_key" to JsonPrimitive(userDeviceKey)
                                    ))
                                )))
                                events.add(event)
                            }
                        }

                        if (events.isNotEmpty()) {
                            // Process the claimed keys through OlmMachine
                            val deviceChanges = DeviceLists(emptyList(), emptyList())
                            val syncChanges = machine.receiveSyncChanges(
                                events = events.joinToString(",", "[", "]"),
                                deviceChanges = deviceChanges,
                                keyCounts = emptyMap(),
                                unusedFallbackKeys = null,
                                nextBatchToken = "",
                                decryptionSettings = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)
                            )
                            println("✅ Processed claimed keys: established sessions with ${syncChanges.roomKeyInfos.size} devices")

                            // Add a small delay to allow session establishment to complete
                            kotlinx.coroutines.delay(1000)
                        }
                    } else {
                        println("❌ Failed to claim one-time keys: ${keysClaimResponse.status}")
                    }
                }
                is Request.ToDevice -> {
                    val toDeviceResponse = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        // Use the request body directly - OlmMachine already formats it correctly
                        val body = convertMapToHashMap(request.body)
                        if (body is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val mapBody = body as Map<String, Any>
                            val jsonBody = JsonObject(mapBody.mapValues { anyToJsonElement(it.value) })
                            // Matrix API requires messages wrapper for to-device requests
                            val messagesWrapper = JsonObject(mapOf("messages" to jsonBody))
                            setBody(messagesWrapper)
                        } else if (body is String) {
                            val parsedBody = json.parseToJsonElement(body).jsonObject
                            // Matrix API requires messages wrapper for to-device requests
                            val messagesWrapper = JsonObject(mapOf("messages" to parsedBody))
                            setBody(messagesWrapper)
                        } else {
                            setBody(JsonObject(mapOf("messages" to JsonObject(mapOf()))))
                        }
                    }
                    if (toDeviceResponse.status == HttpStatusCode.OK) {
                        println("✅ Initial to-device request sent")
                    } else {
                        println("❌ Failed to send initial to-device request: ${toDeviceResponse.status}")
                    }
                }
                is Request.KeysBackup -> {
                    // Handle keys backup request
                    println("⚠️  KeysBackup request not implemented")
                }
                else -> {
                    println("⚠️  Unhandled remaining request type: ${request::class.simpleName}")
                }
            }
        }

        // Test if the new session works
        try {
            machine.encrypt(roomId, "m.room.message", """{"msgtype": "m.text", "body": "new_session_test"}""")
            println("✅ New outbound session is working")
        } catch (e: Exception) {
            println("⚠️  New session still not working: ${e.message}")
        }

        println("✅ Room encryption setup completed for $roomId")
        return true
    } catch (e: Exception) {
        println("❌ Failed to ensure room encryption for $roomId: ${e.message}")
        return false
    }
}

suspend fun handleMissingSessions(missingSessionsRequest: Request.KeysClaim): Boolean {
    val token = currentAccessToken ?: return false
    val machine = olmMachine ?: return false

    try {
        val keysClaimResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/claim") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            val body = convertMapToHashMap(missingSessionsRequest.oneTimeKeys)
            if (body is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val mapBody = body as Map<String, Any>
                setBody(JsonObject(mapBody.mapValues { anyToJsonElement(it.value) }))
            } else if (body is String) {
                setBody(json.parseToJsonElement(body))
            }
        }
        if (keysClaimResponse.status == HttpStatusCode.OK) {
            println("✅ One-time keys claimed for session establishment")

            // Process the claimed keys to establish Olm sessions
            val keysResponseText = keysClaimResponse.body<String>()
            val keysJson = json.parseToJsonElement(keysResponseText)

            // Create events for the claimed keys
            val events = mutableListOf<String>()
            val oneTimeKeysJson = keysJson.jsonObject["one_time_keys"]?.jsonObject ?: JsonObject(emptyMap())

            for ((userDeviceKey, keyData) in oneTimeKeysJson) {
                run {
                    // Create a dummy device key update event for each claimed key
                    val event = json.encodeToString(JsonObject(mapOf(
                        "type" to JsonPrimitive("m.dummy"),
                        "sender" to JsonPrimitive("dummy"),
                        "content" to JsonObject(mapOf(
                            "one_time_key" to keyData,
                            "user_device_key" to JsonPrimitive(userDeviceKey)
                        ))
                    )))
                    events.add(event)
                }
            }

            if (events.isNotEmpty()) {
                // Process the claimed keys through OlmMachine
                val deviceChanges = DeviceLists(emptyList(), emptyList())
                val syncChanges = machine.receiveSyncChanges(
                    events = events.joinToString(",", "[", "]"),
                    deviceChanges = deviceChanges,
                    keyCounts = emptyMap(),
                    unusedFallbackKeys = null,
                    nextBatchToken = "",
                    decryptionSettings = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)
                )
                println("✅ Processed claimed keys: established sessions with ${syncChanges.roomKeyInfos.size} devices")

                // Add a small delay to allow session establishment to complete
                kotlinx.coroutines.delay(1000)
            }
        } else {
            println("❌ Failed to claim one-time keys: ${keysClaimResponse.status}")
        }
    } catch (e: Exception) {
        println("❌ Error handling missing sessions: ${e.message}")
        return false
    }

    return true
}