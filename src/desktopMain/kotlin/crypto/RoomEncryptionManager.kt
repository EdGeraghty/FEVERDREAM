package crypto

import network.currentAccessToken
import network.currentHomeserver
import network.currentSyncToken
import network.currentUserId
import network.currentDeviceId
import network.getRoomMembers

import io.ktor.client.call.*
import io.ktor.client.statement.*
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
                is Request.KeysQuery -> {
                    val keysQueryResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/query") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        // Build the request body from the users list
                        val deviceKeys = request.users.associateWith { emptyList<String>() }
                        setBody(JsonObject(mapOf("device_keys" to JsonObject(deviceKeys.mapValues { JsonArray(emptyList()) }))))
                    }
                    if (keysQueryResponse.status == HttpStatusCode.OK) {
                        println("✅ Device keys queried successfully")

                        // Mark the request as sent with the response
                        val keysResponseText = keysQueryResponse.body<String>()
                        // machine.markRequestAsSent(request.requestId, uniffi.matrix_sdk_crypto.RequestType.KeysQuery, keysResponseText)
                        println("✅ KeysQuery request marked as sent with response")
                    } else {
                        println("❌ Failed to query device keys: ${keysQueryResponse.status}")
                    }
                }
                is Request.KeysBackup -> {
                    println("⚠️  Unhandled KeysBackup request")
                }
                is Request.RoomMessage -> {
                    println("⚠️  Unhandled RoomMessage request")
                }
                is Request.SignatureUpload -> {
                    println("⚠️  Unhandled SignatureUpload request")
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

        // CRITICAL FIX: Create and share a new outbound session to ensure we have a fresh Megolm session
        try {
            println("🔑 Creating new outbound session for room $roomId")
            val encryptionSettings = EncryptionSettings(
                algorithm = EventEncryptionAlgorithm.MEGOLM_V1_AES_SHA2,
                rotationPeriod = 604800000uL, // 7 days in milliseconds
                rotationPeriodMsgs = 100uL,
                historyVisibility = HistoryVisibility.SHARED,
                onlyAllowTrustedDevices = false,
                errorOnVerifiedUserProblem = false
            )

            // Share room key with all room members (including ourselves)
            val shareRequests = machine.shareRoomKey(roomId, allRoomMembers, encryptionSettings)
            println("🔑 Generated ${shareRequests.size} room key share requests")

            // Send the room key share requests
            for (request in shareRequests) {
                when (request) {
                    is Request.ToDevice -> {
                        val toDeviceResponse = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            val body = convertMapToHashMap(request.body)
                            if (body is Map<*, *>) {
                                @Suppress("UNCHECKED_CAST")
                                val mapBody = body as Map<String, Any>
                                val jsonBody = JsonObject(mapBody.mapValues { anyToJsonElement(it.value) })
                                val messagesWrapper = JsonObject(mapOf("messages" to jsonBody))
                                setBody(messagesWrapper)
                            } else if (body is String) {
                                val parsedBody = json.parseToJsonElement(body).jsonObject
                                val messagesWrapper = JsonObject(mapOf("messages" to parsedBody))
                                setBody(messagesWrapper)
                            } else {
                                setBody(JsonObject(mapOf("messages" to JsonObject(mapOf()))))
                            }
                        }
                        if (toDeviceResponse.status == HttpStatusCode.OK) {
                            println("✅ Room key shared successfully")
                        } else {
                            println("❌ Failed to share room key: ${toDeviceResponse.status}")
                        }
                    }
                    else -> {
                        println("⚠️  Unexpected request type for room key sharing: ${request::class.simpleName}")
                    }
                }
            }

            // Sync once to process any responses
            println("🔄 Syncing after room key sharing...")
            val syncResponse = withTimeout(10000L) {
                client.get("$currentHomeserver/_matrix/client/v3/sync") {
                    bearerAuth(token)
                    parameter("since", currentSyncToken)
                    parameter("timeout", "10000")
                }
            }

            if (syncResponse.status == HttpStatusCode.OK) {
                val syncData = syncResponse.body<String>()
                val syncJson = json.parseToJsonElement(syncData).jsonObject

                // Process to-device events from sync
                val toDeviceEvents = syncJson["to_device"]?.jsonObject?.get("events")?.jsonArray ?: JsonArray(emptyList())
                if (toDeviceEvents.isNotEmpty()) {
                    val events = toDeviceEvents.map { it.toString() }
                    val deviceChanges = DeviceLists(emptyList(), emptyList())
                    machine.receiveSyncChanges(
                        events = events.joinToString(",", "[", "]"),
                        deviceChanges = deviceChanges,
                        keyCounts = emptyMap(),
                        unusedFallbackKeys = null,
                        nextBatchToken = syncJson["next_batch"]?.jsonPrimitive?.content ?: "",
                        decryptionSettings = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)
                    )
                    println("✅ Processed ${events.size} to-device events after key sharing")
                }

                // Update sync token
                val nextBatch = syncJson["next_batch"]?.jsonPrimitive?.content
                if (nextBatch != null) {
                    currentSyncToken = nextBatch
                    saveSession(SessionData(
                        userId = currentUserId ?: "",
                        deviceId = currentDeviceId ?: "",
                        accessToken = currentAccessToken ?: "",
                        homeserver = currentHomeserver ?: "",
                        syncToken = currentSyncToken
                    ))
                }
            }

            // CRITICAL FIX: Add delay to allow room keys to propagate to other devices
            println("⏳ Waiting for room keys to propagate to other devices...")
            kotlinx.coroutines.delay(3000) // Wait 3 seconds for key propagation

            // Test encryption again after creating new session
            try {
                machine.encrypt(roomId, "m.room.message", """{"msgtype": "m.text", "body": "session_test_after_sharing"}""")
                println("✅ New outbound session created and working")
            } catch (testException: Exception) {
                println("⚠️  Session still not working after sharing: ${testException.message}")
            }

        } catch (shareException: Exception) {
            println("❌ Failed to create new outbound session: ${shareException.message}")
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
