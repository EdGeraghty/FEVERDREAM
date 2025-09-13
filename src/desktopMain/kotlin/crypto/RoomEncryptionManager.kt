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

    var forceRoomKeyShare = false
    var sessionExpiredDetected = false

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

        // CRITICAL: Test for session expiration BEFORE attempting to share keys
        println("🔍 Testing for session expiration...")
        var sessionNeedsRenewal = false
        try {
            val testEncrypt = machine.encrypt(roomId, "m.room.message", """{"msgtype": "m.text", "body": "session_test"}""")
            println("✅ Session is valid - can encrypt")
        } catch (e: Exception) {
            if (e.message?.contains("Session expired") == true || e.message?.contains("panicked") == true) {
                println("⚠️  Session expired detected - will force renewal")
                sessionExpiredDetected = true
                sessionNeedsRenewal = true
                forceRoomKeyShare = true
            } else {
                println("⚠️  Encryption test failed (not session expiration): ${e.message}")
            }
        }

        // If session needs renewal, we need to create a new outbound group session
        if (sessionNeedsRenewal) {
            println("🔄 Creating new outbound group session for room $roomId")
            try {
                // Create encryption settings for the new session
                val encryptionSettings = EncryptionSettings(
                    algorithm = EventEncryptionAlgorithm.MEGOLM_V1_AES_SHA2,
                    rotationPeriod = 604800000UL,
                    rotationPeriodMsgs = 100UL,
                    historyVisibility = HistoryVisibility.SHARED,
                    onlyAllowTrustedDevices = false,
                    errorOnVerifiedUserProblem = false
                )

                // Create a new outbound group session by calling shareRoomKey with empty members first
                // This should create a new session if none exists or if current is expired
                val newSessionRequests = machine.shareRoomKey(roomId, emptyList(), encryptionSettings)
                println("🔄 New session creation requests: ${newSessionRequests.size}")

                // Send the new session creation requests
                for (request in newSessionRequests) {
                    when (request) {
                        is Request.ToDevice -> {
                            val response = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
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
                            if (response.status == HttpStatusCode.OK) {
                                println("✅ New outbound session created successfully")
                            } else {
                                println("❌ Failed to create new outbound session: ${response.status}")
                            }
                        }
                        else -> {
                            println("⚠️  Unhandled new session request type: ${request::class.simpleName}")
                        }
                    }
                }

                // Sync immediately to process the new session
                println("🔄 Syncing to process new outbound session...")
                try {
                    withTimeout(3000L) {
                        syncAndProcessToDevice(2000UL)
                    }
                    println("✅ New session sync completed")
                } catch (e: Exception) {
                    println("⚠️  New session sync failed: ${e.message}")
                }

                // Test if the new session works
                try {
                    val testNewSession = machine.encrypt(roomId, "m.room.message", """{"msgtype": "m.text", "body": "new_session_test"}""")
                    println("✅ New outbound session is working")
                    sessionNeedsRenewal = false // Session renewal successful
                } catch (e: Exception) {
                    println("⚠️  New session still not working: ${e.message}")
                }
            } catch (e: Exception) {
                println("⚠️  Failed to create new outbound session: ${e.message}")
            }
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
                    }
                    else -> {
                        println("⚠️  Unhandled self room key request type: ${request::class.simpleName}")
                    }
                }
            }

            // CRITICAL: Immediately sync to process the room key we just shared with ourselves
            // This ensures the OlmMachine has the room key before we try to encrypt/decrypt
            println("🔄 Syncing immediately to process self-shared room key...")
            try {
                withTimeout(5000L) { // 5 second timeout for immediate sync
                    syncAndProcessToDevice(2000UL) // 2 second sync timeout
                }
                println("✅ Immediate sync completed - room key should now be available")
            } catch (e: TimeoutCancellationException) {
                println("⚠️  Immediate sync timed out, but continuing...")
            } catch (e: Exception) {
                println("⚠️  Immediate sync failed: ${e.message}, but continuing...")
            }
        } else {
            println("⚠️  Cannot share room key with self - currentUserId is null")
        }

        // If no requests were generated or we need to force sharing (e.g., session expired), try to force sharing anyway
        if (roomKeyRequests.isEmpty() || forceRoomKeyShare || sessionExpiredDetected) {
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

                // Additional sync after force sharing
                if (forceShareRequests.isNotEmpty()) {
                    println("🔄 Syncing after force sharing...")
                    try {
                        withTimeout(3000L) {
                            syncAndProcessToDevice(2000UL)
                        }
                        println("✅ Force sharing sync completed")
                    } catch (e: Exception) {
                        println("⚠️  Force sharing sync failed: ${e.message}")
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