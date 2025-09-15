package crypto

import network.currentAccessToken
import network.currentHomeserver
import network.currentSyncToken
import network.currentUserId
import network.currentDeviceId
import network.getRoomMembers
import network.saveSession
import models.SessionData
import network.anyToJsonElement
import network.convertMapToHashMap
import network.json
import crypto.OlmMachineManager

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

/**
 * Handles checking if rooms are encrypted
 */
class RoomEncryptionChecker {
    suspend fun isRoomEncrypted(roomId: String): Boolean {
        val token = currentAccessToken ?: return false
        println("🔍 isRoomEncrypted: Checking encryption for room $roomId")
        return try {
            val response = withTimeout(5000L) { // 5 second timeout
                println("🌐 isRoomEncrypted: Making HTTP request...")
                client.get("$currentHomeserver/_matrix/client/v3/rooms/$roomId/state/m.room.encryption") {
                    bearerAuth(token)
                }
            }
            val result = response.status == HttpStatusCode.OK
            println("✅ isRoomEncrypted: Room $roomId is ${if (result) "encrypted" else "not encrypted"}")
            result
        } catch (e: TimeoutCancellationException) {
            println("❌ isRoomEncrypted: Request timed out for room $roomId")
            false
        } catch (e: Exception) {
            println("⚠️ isRoomEncrypted: Exception for room $roomId: ${e.message}")
            false
        }
    }
}

/**
 * Handles processing outgoing requests from OlmMachine
 */
class OutgoingRequestProcessor(private val machine: OlmMachine) {
    suspend fun processOutgoingRequests(requests: List<Request>): Boolean {
        val token = currentAccessToken ?: return false

        println("📤 Processing ${requests.size} outgoing requests from RoomEncryptionManager...")

        for (request in requests) {
            println("🔍 Processing request type: ${request::class.simpleName}")
            when (request) {
                is Request.KeysUpload -> processKeysUpload(request, token)
                is Request.KeysClaim -> processKeysClaim(request, token)
                is Request.ToDevice -> processToDevice(request, token)
                is Request.KeysQuery -> processKeysQuery(request, token)
                is Request.KeysBackup -> println("⚠️  Unhandled KeysBackup request")
                is Request.RoomMessage -> println("⚠️  Unhandled RoomMessage request")
                is Request.SignatureUpload -> println("⚠️  Unhandled SignatureUpload request")
            }
        }
        return true
    }

    private suspend fun processKeysUpload(request: Request.KeysUpload, token: String) {
        val response = client.post("$currentHomeserver/_matrix/client/v3/keys/upload") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            val body = convertMapToHashMap(request.body)
            val jsonBody = when (body) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val mapBody = body as Map<String, Any?>
                    JsonObject(mapBody.mapValues { anyToJsonElement(it.value) })
                }
                is String -> json.parseToJsonElement(body)
                else -> JsonObject(emptyMap())
            }
            setBody(jsonBody)
        }
        if (response.status == HttpStatusCode.OK) {
            println("✅ Keys uploaded successfully")
        } else {
            println("❌ Failed to upload keys: ${response.status}")
        }
    }

    private suspend fun processKeysClaim(request: Request.KeysClaim, token: String) {
        val response = client.post("$currentHomeserver/_matrix/client/v3/keys/claim") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            val body = convertMapToHashMap(request.oneTimeKeys)
            val jsonBody = when (body) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val mapBody = body as Map<String, Any?>
                    JsonObject(mapBody.mapValues { anyToJsonElement(it.value) })
                }
                is String -> json.parseToJsonElement(body)
                else -> JsonObject(emptyMap())
            }
            setBody(jsonBody)
        }
        if (response.status == HttpStatusCode.OK) {
            println("✅ Keys claimed successfully")
        } else {
            println("❌ Failed to claim keys: ${response.status}")
        }
    }

    private suspend fun processToDevice(request: Request.ToDevice, token: String) {
        val response = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            val body = convertMapToHashMap(request.body)
            val jsonBody = when (body) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val mapBody = body as Map<String, Any?>
                    val jsonBody = JsonObject(mapBody.mapValues { anyToJsonElement(it.value) })
                    JsonObject(mapOf("messages" to jsonBody))
                }
                is String -> {
                    val parsedBody = json.parseToJsonElement(body).jsonObject
                    JsonObject(mapOf("messages" to parsedBody))
                }
                else -> JsonObject(mapOf("messages" to JsonObject(mapOf())))
            }
            setBody(jsonBody)
        }
        if (response.status == HttpStatusCode.OK) {
            println("✅ To-device message sent successfully")
        } else {
            println("❌ Failed to send to-device message: ${response.status}")
        }
    }

    private suspend fun processKeysQuery(request: Request.KeysQuery, token: String) {
        println("🔍 Processing keys query for users: ${request.users}")
        val response = client.post("$currentHomeserver/_matrix/client/v3/keys/query") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            // Create proper device_keys format: each user should map to an empty array to request all devices
            val deviceKeys = request.users.associateWith { JsonArray(emptyList()) }
            val body = JsonObject(mapOf("device_keys" to JsonObject(deviceKeys)))
            setBody(body)
        }
        if (response.status == HttpStatusCode.OK) {
            println("✅ Keys queried successfully")
        } else {
            println("❌ Failed to query keys: ${response.status}")
            // Log the request body for debugging
            val deviceKeys = request.users.associateWith { JsonArray(emptyList()) }
            val body = JsonObject(mapOf("device_keys" to JsonObject(deviceKeys)))
            println("📤 Request body: ${body}")
            println("📤 Users being queried: ${request.users}")
            println("📤 Response body: ${response.body<String>()}")
        }
    }
}

/**
 * Handles room key creation and sharing
 */
class RoomKeySharingManager(private val machine: OlmMachine) {
    suspend fun createAndShareRoomKey(roomId: String, roomMembers: List<String>): Boolean {
        val token = currentAccessToken ?: return false

        return try {
            println("🔑 Creating new outbound session for room $roomId")
            println("🔑 Room members count: ${roomMembers.size}")
            println("🔑 Room members: $roomMembers")

            val encryptionSettings = EncryptionSettings(
                algorithm = EventEncryptionAlgorithm.MEGOLM_V1_AES_SHA2,
                rotationPeriod = 604800uL, // 7 days in seconds (was 604800000uL milliseconds)
                rotationPeriodMsgs = 100uL,
                historyVisibility = HistoryVisibility.SHARED,
                onlyAllowTrustedDevices = false,
                errorOnVerifiedUserProblem = false
            )

            println("🔑 Encryption settings created successfully")
            println("🔑 Calling machine.shareRoomKey...")

            val shareRequests = machine.shareRoomKey(roomId, roomMembers, encryptionSettings)
            println("🔑 Generated ${shareRequests.size} room key share requests")

            sendRoomKeyShareRequests(shareRequests, token)
            syncAfterKeySharing(token)
            delayForKeyPropagation()

            testEncryptionAfterSharing(roomId)
            true
        } catch (e: Exception) {
            println("❌ Failed to create new outbound session: ${e.message}")
            println("❌ Exception type: ${e::class.simpleName}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun sendRoomKeyShareRequests(requests: List<Request>, token: String) {
        for (request in requests) {
            when (request) {
                is Request.ToDevice -> sendToDeviceRequest(request, token)
                else -> println("⚠️  Unexpected request type for room key sharing: ${request::class.simpleName}")
            }
        }
    }

    private suspend fun sendToDeviceRequest(request: Request.ToDevice, token: String) {
        val toDeviceResponse = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            val body = convertMapToHashMap(request.body)
            val jsonBody = when (body) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val mapBody = body as Map<String, Any?>
                    val jsonBody = JsonObject(mapBody.mapValues { anyToJsonElement(it.value) })
                    JsonObject(mapOf("messages" to jsonBody))
                }
                is String -> {
                    val parsedBody = json.parseToJsonElement(body).jsonObject
                    JsonObject(mapOf("messages" to parsedBody))
                }
                else -> JsonObject(mapOf("messages" to JsonObject(mapOf())))
            }
            setBody(jsonBody)
        }
        if (toDeviceResponse.status == HttpStatusCode.OK) {
            println("✅ Room key shared successfully")
        } else {
            println("❌ Failed to share room key: ${toDeviceResponse.status}")
        }
    }

    private suspend fun syncAfterKeySharing(token: String) {
        println("🔄 Syncing after room key sharing...")
        val syncResponse = withTimeout(10000L) {
            client.get("$currentHomeserver/_matrix/client/v3/sync") {
                bearerAuth(token)
                parameter("since", currentSyncToken)
                parameter("timeout", "10000")
            }
        }

        if (syncResponse.status == HttpStatusCode.OK) {
            processSyncResponse(syncResponse.body<String>())
        }
    }

    private suspend fun processSyncResponse(syncData: String) {
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

    private suspend fun delayForKeyPropagation() {
        println("⏳ Waiting for room keys to propagate to other devices...")
        kotlinx.coroutines.delay(3000) // Wait 3 seconds for key propagation
    }

    private suspend fun testEncryptionAfterSharing(roomId: String) {
        try {
            val messageContent = """{"msgtype":"m.text","body":${JsonPrimitive("session_test_after_sharing")}}"""
            machine.encrypt(roomId, "m.room.message", messageContent)
            println("✅ New outbound session created and working")
        } catch (testException: Exception) {
            println("⚠️  Session still not working after sharing: ${testException.message}")
        }
    }
}

/**
 * Handles session establishment and missing session recovery
 */
class SessionManager(private val machine: OlmMachine) {
    suspend fun handleMissingSessions(missingSessionsRequest: Request.KeysClaim): Boolean {
        val token = currentAccessToken ?: return false

        return try {
            val keysClaimResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/claim") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                val body = convertMapToHashMap(missingSessionsRequest.oneTimeKeys)
                val jsonBody = when (body) {
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val mapBody = body as Map<String, Any?>
                        JsonObject(mapBody.mapValues { anyToJsonElement(it.value) })
                    }
                    is String -> json.parseToJsonElement(body)
                    else -> JsonObject(emptyMap())
                }
                setBody(jsonBody)
            }

            if (keysClaimResponse.status == HttpStatusCode.OK) {
                println("✅ One-time keys claimed for session establishment")
                processClaimedKeysForSessions(keysClaimResponse.body<String>())
                true
            } else {
                println("❌ Failed to claim one-time keys: ${keysClaimResponse.status}")
                false
            }
        } catch (e: Exception) {
            println("❌ Error handling missing sessions: ${e.message}")
            false
        }
    }

    private suspend fun processClaimedKeysForSessions(keysResponseText: String) {
        val keysJson = json.parseToJsonElement(keysResponseText)
        val events = createEventsFromClaimedKeys(keysJson)

        if (events.isNotEmpty()) {
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
            kotlinx.coroutines.delay(1000) // Allow session establishment to complete
        }
    }

    private fun createEventsFromClaimedKeys(keysJson: JsonElement): MutableList<String> {
        val events = mutableListOf<String>()
        val oneTimeKeysJson = keysJson.jsonObject["one_time_keys"]?.jsonObject ?: JsonObject(emptyMap())

        for ((userDeviceKey, keyData) in oneTimeKeysJson) {
            val event = json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
                "type" to JsonPrimitive("m.dummy"),
                "sender" to JsonPrimitive("dummy"),
                "content" to JsonObject(mapOf(
                    "one_time_key" to keyData,
                    "user_device_key" to JsonPrimitive(userDeviceKey)
                ))
            )))
            events.add(event)
        }
        return events
    }
}

suspend fun isRoomEncrypted(roomId: String): Boolean {
    val checker = RoomEncryptionChecker()
    return checker.isRoomEncrypted(roomId)
}

suspend fun ensureRoomEncryption(roomId: String): Boolean {
    val token = currentAccessToken ?: return false
    val machine = OlmMachineManager.olmMachine ?: return false

    val encryptionChecker = RoomEncryptionChecker()
    val requestProcessor = OutgoingRequestProcessor(machine)

    return try {
        // Check if room is encrypted
        if (!encryptionChecker.isRoomEncrypted(roomId)) {
            println("⚠️  Room $roomId is not encrypted")
            return false
        }

        // Get room members
        val allRoomMembers = getRoomMembers(roomId)
        println("🔍 Room members: $allRoomMembers")

        // Update tracked users to ensure OlmMachine knows about them
        machine.updateTrackedUsers(allRoomMembers)

        // Send any outgoing requests (like keys query) before sharing room key
        println("🔄 Calling machine.outgoingRequests() in RoomEncryptionManager...")
        val initialRequests = machine.outgoingRequests()
        println("📋 Got ${initialRequests.size} initial requests from OlmMachine")
        requestProcessor.processOutgoingRequests(initialRequests)

        // Test if the new session works
        try {
            val messageContent = """{"msgtype":"m.text","body":${JsonPrimitive("new_session_test")}}"""
            machine.encrypt(roomId, "m.room.message", messageContent)
            println("✅ New outbound session is working")
        } catch (e: Exception) {
            println("⚠️  New session still not working: ${e.message}")
            // If encryption fails, try to create a new outbound session
            if (e.message?.contains("session") == true || e.message?.contains("expired") == true) {
                println("🔄 Creating new outbound session...")
                val roomKeySharingManager = RoomKeySharingManager(machine)
                val sessionRenewalSuccess = roomKeySharingManager.createAndShareRoomKey(roomId, allRoomMembers)
                if (sessionRenewalSuccess) {
                    println("✅ Session renewal successful")
                    // Test encryption again after session renewal
                    try {
                        val messageContent = """{"msgtype":"m.text","body":${JsonPrimitive("session_test_after_renewal")}}"""
                        machine.encrypt(roomId, "m.room.message", messageContent)
                        println("✅ Session working after renewal")
                    } catch (renewalTestException: Exception) {
                        println("⚠️  Session still not working after renewal: ${renewalTestException.message}")
                    }
                } else {
                    println("❌ Session renewal failed")
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

suspend fun handleMissingSessions(missingSessionsRequest: Request.KeysClaim): Boolean {
    val machine = OlmMachineManager.olmMachine ?: return false
    val sessionManager = SessionManager(machine)
    return sessionManager.handleMissingSessions(missingSessionsRequest)
}
