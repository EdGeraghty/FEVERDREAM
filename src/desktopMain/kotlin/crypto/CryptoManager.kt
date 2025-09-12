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
                println("ℹ️  OlmMachine initialized with fresh crypto store (no existing room keys)")
            }
        } catch (e: Exception) {
            println("❌ Failed to initialize Matrix SDK Crypto: ${e.message}")

            // If the error is about account mismatch, clear the crypto store and retry
            if (e.message?.contains("account in the store doesn't match") == true) {
                println("🔄 Clearing crypto store due to account mismatch...")
                try {
                    java.io.File(cryptoStorePath).deleteRecursively()
                    java.io.File(cryptoStorePath).mkdirs()

                    // Retry initialization with clean store
                    olmMachine = OlmMachine(userId, deviceId, cryptoStorePath, null)

                    val identityKeys = olmMachine!!.identityKeys()
                    println("🔑 Matrix SDK Crypto initialized after clearing store")
                    println("Curve25519 key: ${identityKeys["curve25519"]}")
                    println("Ed25519 key: ${identityKeys["ed25519"]}")
                } catch (retryException: Exception) {
                    println("❌ Failed to initialize Matrix SDK Crypto after clearing store: ${retryException.message}")
                }
            }
        }
    }
}

suspend fun syncAndProcessToDevice(timeout: ULong = 30000UL): Boolean {
    val token = currentAccessToken ?: return false
    val machine = olmMachine ?: return false

    try {
        println("🔄 Starting sync to process to-device events...")
        val response = client.get("$currentHomeserver/_matrix/client/v3/sync") {
            bearerAuth(token)
            parameter("timeout", timeout.toString()) // Use the timeout parameter
            parameter("filter", """{"room":{"timeline":{"limit":10},"state":{"limit":0},"ephemeral":{"limit":0}},"presence":{"limit":0},"account_data":{"limit":0},"receipts":{"limit":0}}""")
            // Use since token if we have one
            if (currentSyncToken.isNotBlank()) {
                parameter("since", currentSyncToken)
            }
        }

        if (response.status == HttpStatusCode.OK) {
            val syncResponse = response.body<SyncResponse>()

            // Update sync token for next sync
            if (syncResponse.nextBatch != null) {
                currentSyncToken = syncResponse.nextBatch!!
                println("🔄 Updated sync token: ${currentSyncToken.take(10)}...")
            }

            // Process room events first (for encrypted messages)
            val roomEvents = syncResponse.rooms?.join?.flatMap { (roomId, joinedRoom) ->
                joinedRoom.timeline?.events?.filter { it.type == "m.room.encrypted" } ?: emptyList()
            } ?: emptyList()

            if (roomEvents.isNotEmpty()) {
                println("📥 Received ${roomEvents.size} encrypted room events")
                // Note: Processing encrypted events here might trigger key requests
                // but we don't need to decrypt them in the sync function
            }

            // Process ALL room timeline events for real-time updates
            syncResponse.rooms?.join?.forEach { (roomId, joinedRoom) ->
                val timelineEvents = joinedRoom.timeline?.events ?: emptyList()
                if (timelineEvents.isNotEmpty()) {
                    println("📥 Received ${timelineEvents.size} timeline events for room $roomId")
                    
                    // Initialize cache for this room if not exists
                    if (!roomMessageCache.containsKey(roomId)) {
                        roomMessageCache[roomId] = mutableListOf()
                    }
                    
                    // Add new events to cache (avoiding duplicates by event ID)
                    val existingEventIds = roomMessageCache[roomId]!!.map { it.event_id }.toSet()
                    val newEvents = timelineEvents.filter { it.event_id !in existingEventIds }
                    
                    if (newEvents.isNotEmpty()) {
                        roomMessageCache[roomId]!!.addAll(newEvents)
                        println("✅ Added ${newEvents.size} new events to cache for room $roomId")
                        
                        // Keep only the most recent 100 messages per room
                        if (roomMessageCache[roomId]!!.size > 100) {
                            roomMessageCache[roomId] = roomMessageCache[roomId]!!.takeLast(100).toMutableList()
                        }
                    }
                }
            }

            // Extract to-device events
            val toDeviceEvents = syncResponse.toDevice?.events ?: emptyList()
            if (toDeviceEvents.isNotEmpty()) {
                println("📥 Received ${toDeviceEvents.size} to-device events")

                // Convert events to JSON strings - each event should be a separate string
                val toDeviceEventJsons = toDeviceEvents.map { json.encodeToString(it) }

                // Process with OlmMachine - pass as individual strings, not array
                val decryptionSettings = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)
                val syncChanges = machine.receiveSyncChanges(
                    events = toDeviceEventJsons.joinToString(","), // Individual events separated by comma, no array brackets
                    deviceChanges = DeviceLists(emptyList(), emptyList()), // Empty device lists
                    keyCounts = emptyMap<String, Int>(), // Empty key counts map
                    unusedFallbackKeys = null,
                    nextBatchToken = syncResponse.nextBatch ?: "",
                    decryptionSettings = decryptionSettings
                )

                println("🔄 Processed sync changes: ${syncChanges.roomKeyInfos.size} room keys received")
                if (syncChanges.roomKeyInfos.isNotEmpty()) {
                    println("🔑 Room keys received: ${syncChanges.roomKeyInfos.joinToString(", ") { it.roomId }}")
                }

                // Send any outgoing requests generated by processing the sync changes
                val outgoingRequests = machine.outgoingRequests()
                if (outgoingRequests.isNotEmpty()) {
                    println("📤 Sending ${outgoingRequests.size} outgoing requests from sync processing...")
                    for (request in outgoingRequests) {
                        when (request) {
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
                                    println("✅ Outgoing to-device request sent")
                                } else {
                                    println("❌ Failed to send outgoing to-device request: ${toDeviceResponse.status}")
                                }
                            }
                            is Request.KeysQuery -> {
                                val keysQueryResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/query") {
                                    bearerAuth(token)
                                    contentType(ContentType.Application.Json)
                                    val convertedUsers = convertMapToHashMap(request.users)
                                    if (convertedUsers is Map<*, *>) {
                                        @Suppress("UNCHECKED_CAST")
                                        val usersMap = convertedUsers as Map<String, Any>
                                        val deviceKeys = usersMap.mapValues { entry ->
                                            val devices = entry.value as? List<String> ?: emptyList()
                                            JsonArray(devices.map { JsonPrimitive(it) })
                                        }
                                        setBody(JsonObject(mapOf("device_keys" to JsonObject(deviceKeys))))
                                    } else {
                                        setBody(JsonObject(mapOf("device_keys" to JsonObject(emptyMap()))))
                                    }
                                }
                                if (keysQueryResponse.status == HttpStatusCode.OK) {
                                    println("✅ Outgoing keys query sent")
                                } else {
                                    println("❌ Failed to send outgoing keys query: ${keysQueryResponse.status}")
                                }
                            }
                            is Request.KeysUpload -> {
                                val keysUploadResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/upload") {
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
                                if (keysUploadResponse.status == HttpStatusCode.OK) {
                                    println("✅ Keys uploaded successfully")
                                } else {
                                    println("❌ Failed to upload keys: ${keysUploadResponse.status}")
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
                                } else {
                                    println("❌ Failed to claim one-time keys: ${keysClaimResponse.status}")
                                }
                            }
                            is Request.KeysBackup -> {
                                // Handle keys backup request
                                println("⚠️  KeysBackup request not implemented")
                            }
                            is Request.RoomMessage -> {
                                // Handle room message request
                                val roomMessageResponse = client.put("$currentHomeserver/_matrix/client/r0/rooms/${request.roomId}/send/${request.eventType}/${System.currentTimeMillis()}") {
                                    bearerAuth(token)
                                    contentType(ContentType.Application.Json)
                                    setBody(json.parseToJsonElement(request.content))
                                }
                                if (roomMessageResponse.status == HttpStatusCode.OK) {
                                    println("✅ Room message sent successfully")
                                } else {
                                    println("❌ Failed to send room message: ${roomMessageResponse.status}")
                                }
                            }
                            is Request.SignatureUpload -> {
                                val signatureUploadResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/signatures/upload") {
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
                                if (signatureUploadResponse.status == HttpStatusCode.OK) {
                                    println("✅ Signature uploaded successfully")
                                } else {
                                    println("❌ Failed to upload signature: ${signatureUploadResponse.status}")
                                }
                            }
                            else -> {
                                println("⚠️  Unhandled request type: ${request::class.simpleName}")
                            }
                        }
                    }
                }

                return true
            } else {
                println("📭 No to-device events received")
                return true
            }
        } else {
            println("❌ Sync failed: ${response.status}")
            return false
        }
    } catch (e: Exception) {
        println("❌ Sync error: ${e.message}")
        e.printStackTrace()
        return false
    }
}

suspend fun isRoomEncrypted(roomId: String): Boolean {
    val token = currentAccessToken ?: return false
    try {
        val response = client.get("$currentHomeserver/_matrix/client/v3/rooms/$roomId/state/m.room.encryption") {
            bearerAuth(token)
        }
        return response.status == HttpStatusCode.OK
    } catch (e: Exception) {
        return false
    }
}

suspend fun ensureRoomEncryption(roomId: String): Boolean {
    val token = currentAccessToken ?: return false
    val machine = olmMachine ?: return false

    var forceRoomKeyShare = false

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
                is Request.KeysQuery -> {
                    val keysQueryResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/query") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        val convertedUsers = convertMapToHashMap(request.users)
                        if (convertedUsers is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val usersMap = convertedUsers as Map<String, Any>
                            val deviceKeys = mutableMapOf<String, JsonElement>()
                            for ((user, devicesAny) in usersMap) {
                                val devices = devicesAny as? List<String> ?: emptyList()
                                val jsonDevices = devices.map { kotlinx.serialization.json.JsonPrimitive(it) }
                                deviceKeys[user] = JsonArray(jsonDevices)
                            }
                            setBody(JsonObject(mapOf("device_keys" to JsonObject(deviceKeys))))
                        } else {
                            setBody(JsonObject(mapOf("device_keys" to JsonObject(emptyMap()))))
                        }
                    }
                    if (keysQueryResponse.status == HttpStatusCode.OK) {
                        val keysResponseText = keysQueryResponse.body<String>()
                        val keysJson = json.parseToJsonElement(keysResponseText)
                        val deviceKeysJson = keysJson.jsonObject["device_keys"]?.jsonObject ?: JsonObject(emptyMap())
                        val events = mutableListOf<String>()
                        val changedUsersList = mutableListOf<String>()
                        for ((user, devices) in deviceKeysJson) {
                            changedUsersList.add(user)
                            val devicesMap = devices.jsonObject
                            for ((device, deviceInfo) in devicesMap) {
                                val event = json.encodeToString(JsonObject(mapOf(
                                    "type" to JsonPrimitive("m.device_key_update"),
                                    "sender" to JsonPrimitive(user),
                                    "content" to deviceInfo
                                )))
                                events.add(event)
                            }
                        }
                        val deviceChanges = DeviceLists(changed = changedUsersList, left = emptyList())
                        val syncChanges = machine.receiveSyncChanges(
                            events = events.joinToString(",", "[", "]"),
                            deviceChanges = deviceChanges,
                            keyCounts = emptyMap(),
                            unusedFallbackKeys = null,
                            nextBatchToken = "",
                            decryptionSettings = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)
                        )
                        println("✅ Processed initial keys query response: ${syncChanges.roomKeyInfos.size} room keys, ${changedUsersList.size} users updated")
                    } else {
                        println("❌ Failed to send initial keys query: ${keysQueryResponse.status}")
                    }
                }
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
                is Request.KeysBackup -> {
                    // Handle keys backup request
                    println("⚠️  KeysBackup request not implemented")
                }
                is Request.RoomMessage -> {
                    // Handle room message request
                    val roomMessageResponse = client.put("$currentHomeserver/_matrix/client/r0/rooms/${request.roomId}/send/${request.eventType}/${System.currentTimeMillis()}") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(json.parseToJsonElement(request.content))
                    }
                    if (roomMessageResponse.status == HttpStatusCode.OK) {
                        println("✅ Room message sent successfully")
                    } else {
                        println("❌ Failed to send room message: ${roomMessageResponse.status}")
                    }
                }
                is Request.SignatureUpload -> {
                    val signatureUploadResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/signatures/upload") {
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
                    if (signatureUploadResponse.status == HttpStatusCode.OK) {
                        println("✅ Signature uploaded successfully")
                    } else {
                        println("❌ Failed to upload signature: ${signatureUploadResponse.status}")
                    }
                }
                else -> {
                    println("⚠️  Unhandled request type: ${request::class.simpleName}")
                }
            }
        }

        // Sync to get device keys with timeout protection
        try {
            withTimeout(10000L) { // 10 second timeout for sync
                syncAndProcessToDevice(5000UL) // 5 second sync timeout
            }
        } catch (e: TimeoutCancellationException) {
            println("⚠️  Sync timed out, continuing with encryption setup...")
        } catch (e: Exception) {
            println("⚠️  Sync failed: ${e.message}, continuing with encryption setup...")
        }

        val roomMembers = allRoomMembers.filter { it != currentUserId }

        if (roomMembers.isEmpty()) {
            println("⚠️  No other room members found for $roomId")
            return false
        }

        // Always attempt to share room key with room members to ensure all have access
        val encryptionSettings = EncryptionSettings(
            algorithm = EventEncryptionAlgorithm.MEGOLM_V1_AES_SHA2,
            rotationPeriod = 604800000UL,
            rotationPeriodMsgs = 100UL,
            historyVisibility = HistoryVisibility.SHARED,
            onlyAllowTrustedDevices = false,
            errorOnVerifiedUserProblem = false
        )

        // Step 1: Establish Olm sessions with room members (required before sharing room keys)
        println("🔐 Establishing Olm sessions with room members...")
        val missingSessionsRequest = machine.getMissingSessions(roomMembers)
        if (missingSessionsRequest != null) {
            when (missingSessionsRequest) {
                is Request.KeysClaim -> {
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
                }
                else -> {
                    println("⚠️  Unexpected request type for missing sessions: ${missingSessionsRequest::class.simpleName}")
                }
            }
        } else {
            println("ℹ️  No missing sessions to establish")
        }

        // Step 2: Share room key with room members FIRST (this creates the Megolm session)
        println("🔐 Sharing room key with room members...")
        val roomKeyRequests = machine.shareRoomKey(roomId, roomMembers, encryptionSettings)
        println("🔐 Room key requests for setup: ${roomKeyRequests.size}")

        // Send room key requests if any
        for (request in roomKeyRequests) {
            when (request) {
                is Request.ToDevice -> {
                    val response = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
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
                    if (response.status == HttpStatusCode.OK) {
                        println("✅ Room key shared successfully")
                    } else {
                        println("❌ Failed to share room key: ${response.status}")
                    }
                }
                else -> {
                    println("⚠️  Unhandled room key request type: ${request::class.simpleName}")
                }
            }
        }

        // CRITICAL FIX: Also share room key with ourselves (the sender)
        // This ensures we have the room key for decryption
        if (currentUserId != null) {
            println("🔐 Sharing room key with ourselves (sender)...")
            val selfRoomKeyRequests = machine.shareRoomKey(roomId, listOf(currentUserId!!), encryptionSettings)
            println("🔐 Self room key requests: ${selfRoomKeyRequests.size}")

            // Send self room key requests
            for (request in selfRoomKeyRequests) {
                when (request) {
                    is Request.ToDevice -> {
                        val response = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
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
                        if (response.status == HttpStatusCode.OK) {
                            println("✅ Self room key shared successfully")
                        } else {
                            println("❌ Failed to share self room key: ${response.status}")
                        }
                    }
                    else -> {
                        println("⚠️  Unhandled self room key request type: ${request::class.simpleName}")
                    }
                }
            }
        } else {
            println("⚠️  Cannot share room key with self - currentUserId is null")
        }

        // If no requests were generated or we need to force sharing (e.g., session expired), try to force sharing anyway
        if (roomKeyRequests.isEmpty() || forceRoomKeyShare) {
            println("ℹ️  No room key requests generated or forcing share (session may have expired), trying to force sharing...")
            // Try to share with all room members including self to ensure everyone has the key
            try {
                val forceShareRequests = machine.shareRoomKey(roomId, allRoomMembers, encryptionSettings)
                println("🔐 Force share room key requests: ${forceShareRequests.size}")

                // Send force share requests
                for (request in forceShareRequests) {
                    when (request) {
                        is Request.ToDevice -> {
                            val response = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
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
                            if (response.status == HttpStatusCode.OK) {
                                println("✅ Force room key shared successfully")
                            } else {
                                println("❌ Failed to force share room key: ${response.status}")
                            }
                        }
                        else -> {
                            println("⚠️  Unhandled force share request type: ${request::class.simpleName}")
                        }
                    }
                }
            } catch (e: Exception) {
                println("⚠️  Force sharing failed: ${e.message}")
            }
        }

        // Step 3: Now try to encrypt a dummy message to ensure we have a valid room key
        try {
            val dummyContent = """{"body": "dummy", "msgtype": "m.text"}"""
            val encryptedDummy = machine.encrypt(roomId, "m.room.message", dummyContent)
            println("🔐 Room key validated by encrypting dummy message")
        } catch (e: Exception) {
            println("⚠️  Could not validate room key: ${e.message}")
            // This is not necessarily an error - the room key might be valid
        }

        // Debug: Check room key counts
        val keyCounts = machine.roomKeyCounts()
        println("🔑 Room key counts - Total: ${keyCounts.total}, Backed up: ${keyCounts.backedUp}")

        // Debug: Check devices for each room member
        for (member in roomMembers) {
            try {
                val userDevices = machine.getUserDevices(member, 30000U)
                println("📱 User $member has ${userDevices.size} devices")
                if (userDevices.isNotEmpty()) {
                    println("   Device IDs: ${userDevices.map { it.deviceId }}")
                    println("   Curve25519 keys: ${userDevices.map { it.keys["curve25519"] }}")
                } else {
                    println("   No devices found for user $member")
                }
            } catch (e: Exception) {
                println("⚠️  Could not get devices for user $member: ${e.message}")
                e.printStackTrace()
            }
        }

        // Debug: Check if we have any tracked users
        try {
            // Note: trackedUsers property is not directly accessible via OlmMachine interface
            // We can check individual users with isUserTracked() instead
            val trackedStatus = allRoomMembers.map { member ->
                "$member: ${machine.isUserTracked(member)}"
            }
            println("👥 Tracked status for room members: $trackedStatus")
        } catch (e: Exception) {
            println("⚠️  Could not check tracked users: ${e.message}")
        }

        // Debug: Check current room key status
        try {
            val currentKeyCounts = machine.roomKeyCounts()
            println("🔑 Current room key status - Total: ${currentKeyCounts.total}, Backed up: ${currentKeyCounts.backedUp}")

            // Check if we can encrypt for this room (indicates we have outbound group session)
            val canEncrypt = try {
                machine.encrypt(roomId, "m.room.message", """{"msgtype": "m.text", "body": "test"}""")
                true
            } catch (e: Exception) {
                println("⚠️  Encryption test failed: ${e.message}")
                false
            }

            if (canEncrypt) {
                println("✅ Can encrypt messages for room $roomId (room key available)")
            } else {
                println("⚠️  Cannot encrypt messages for room $roomId (no room key or session expired)")
                // Force room key sharing if we can't encrypt
                forceRoomKeyShare = true
            }
        } catch (e: Exception) {
            println("⚠️  Could not check room key status: ${e.message}")
        }

        // Process any remaining requests
        val remainingRequests = machine.outgoingRequests()
        for (request in remainingRequests) {
            when (request) {
                is Request.ToDevice -> {
                    val response = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
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
                    if (response.status == HttpStatusCode.OK) {
                        println("✅ Remaining request sent successfully")
                    }
                }
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
                        println("✅ Keys uploaded successfully")
                    }
                }
                is Request.KeysQuery -> {
                    val keysQueryResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/query") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        val convertedUsers = convertMapToHashMap(request.users)
                        if (convertedUsers is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val usersMap = convertedUsers as Map<String, Any>
                            val deviceKeys = usersMap.mapValues { entry ->
                                val devices = entry.value as? List<String> ?: emptyList()
                                JsonArray(devices.map { JsonPrimitive(it) })
                            }
                            setBody(JsonObject(mapOf("device_keys" to JsonObject(deviceKeys))))
                        } else {
                            setBody(JsonObject(mapOf("device_keys" to JsonObject(emptyMap()))))
                        }
                    }
                    if (keysQueryResponse.status == HttpStatusCode.OK) {
                        val keysResponseText = keysQueryResponse.body<String>()
                        val keysJson = json.parseToJsonElement(keysResponseText)
                        val deviceKeysJson = keysJson.jsonObject["device_keys"]?.jsonObject ?: JsonObject(emptyMap())
                        val events = mutableListOf<String>()
                        val changedUsersList = mutableListOf<String>()
                        for ((user, devices) in deviceKeysJson) {
                            changedUsersList.add(user)
                            val devicesMap = devices.jsonObject
                            for ((device, deviceInfo) in devicesMap) {
                                val event = json.encodeToString(JsonObject(mapOf(
                                    "type" to JsonPrimitive("m.device_key_update"),
                                    "sender" to JsonPrimitive(user),
                                    "content" to deviceInfo
                                )))
                                events.add(event)
                            }
                        }
                        val deviceChanges = DeviceLists(changed = changedUsersList, left = emptyList())
                        val syncChanges = machine.receiveSyncChanges(
                            events = events.joinToString(",", "[", "]"),
                            deviceChanges = deviceChanges,
                            keyCounts = emptyMap(),
                            unusedFallbackKeys = null,
                            nextBatchToken = "",
                            decryptionSettings = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)
                        )
                        println("✅ Processed remaining keys query response: ${syncChanges.roomKeyInfos.size} room keys, ${changedUsersList.size} users updated")
                    } else {
                        println("❌ Failed to send remaining keys query: ${keysQueryResponse.status}")
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
                        println("✅ Remaining one-time keys claimed successfully")
                    } else {
                        println("❌ Failed to claim remaining one-time keys: ${keysClaimResponse.status}")
                    }
                }
                is Request.KeysBackup -> {
                    // Handle keys backup request
                    println("⚠️  KeysBackup request not implemented")
                }
                is Request.RoomMessage -> {
                    // Handle room message request
                    val roomMessageResponse = client.put("$currentHomeserver/_matrix/client/r0/rooms/${request.roomId}/send/${request.eventType}/${System.currentTimeMillis()}") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(json.parseToJsonElement(request.content))
                    }
                    if (roomMessageResponse.status == HttpStatusCode.OK) {
                        println("✅ Room message sent successfully")
                    } else {
                        println("❌ Failed to send room message: ${roomMessageResponse.status}")
                    }
                }
                is Request.SignatureUpload -> {
                    val signatureUploadResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/signatures/upload") {
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
                    if (signatureUploadResponse.status == HttpStatusCode.OK) {
                        println("✅ Signature uploaded successfully")
                    } else {
                        println("❌ Failed to upload signature: ${signatureUploadResponse.status}")
                    }
                }
            }
        }

        println("✅ Room encryption setup completed for $roomId")
        return true
    } catch (e: Exception) {
        println("❌ Failed to ensure room encryption for $roomId: ${e.message}")
        return false
    }
}

suspend fun startPeriodicSync() {
    while (kotlin.coroutines.coroutineContext.isActive) {
        try {
            // Sync every 60 seconds to reduce load and prevent UI freezing
            kotlinx.coroutines.delay(60000)
            if (currentAccessToken != null && olmMachine != null) {
                val syncResult = syncAndProcessToDevice(30000UL)
                if (syncResult) {
                    println("🔄 Periodic sync completed successfully")
                } else {
                    println("⚠️  Periodic sync failed")
                }
            }
        } catch (e: Exception) {
            println("❌ Periodic sync error: ${e.message}")
            // Delay longer on error
            kotlinx.coroutines.delay(120000)
        }
    }
}

suspend fun hasRoomKey(roomId: String): Boolean {
    val machine = olmMachine ?: return false
    return try {
        // Try to encrypt a dummy message - if this succeeds, we have room keys
        // If it fails/panics, we don't have the necessary keys
        machine.encrypt(roomId, "m.room.message", """{"msgtype": "m.text", "body": "test"}""")
        true
    } catch (e: Exception) {
        // Handle session expiration gracefully instead of letting it panic
        if (e.message?.contains("Session expired") == true || e.message?.contains("panicked") == true) {
            println("⚠️  Session expired detected in hasRoomKey, attempting to renew...")
            // Try to force room key sharing to renew the session
            try {
                val roomMembers = getRoomMembers(roomId).filter { it != currentUserId }
                if (roomMembers.isNotEmpty()) {
                    val encryptionSettings = EncryptionSettings(
                        algorithm = EventEncryptionAlgorithm.MEGOLM_V1_AES_SHA2,
                        rotationPeriod = 604800000UL,
                        rotationPeriodMsgs = 100UL,
                        historyVisibility = HistoryVisibility.SHARED,
                        onlyAllowTrustedDevices = false,
                        errorOnVerifiedUserProblem = false
                    )
                    val forceShareRequests = machine.shareRoomKey(roomId, roomMembers, encryptionSettings)
                    println("🔄 Attempted to renew session with ${forceShareRequests.size} requests")
                }
            } catch (renewalException: Exception) {
                println("⚠️  Session renewal failed: ${renewalException.message}")
            }
        }
        false
    }
}

// Public function to check if we can encrypt messages for a room
fun canEncryptRoom(roomId: String): Boolean {
    val machine = olmMachine ?: return false
    return try {
        machine.encrypt(roomId, "m.room.message", """{"msgtype": "m.text", "body": "test"}""")
        true
    } catch (e: Exception) {
        false
    }
}
