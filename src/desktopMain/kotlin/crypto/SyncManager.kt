package crypto

import network.currentAccessToken
import network.currentHomeserver
import network.currentSyncToken
import network.currentUserId
import network.currentDeviceId

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
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
 * Handles the sync API call and response parsing
 */
class SyncResponseHandler {
    suspend fun performSync(): SyncResult {
        val token = currentAccessToken ?: return SyncResult.Failure("No access token")

        return try {
            val response = client.get("$currentHomeserver/_matrix/client/v3/sync") {
                bearerAuth(token)
                if (currentSyncToken.isNotBlank()) {
                    parameter("since", currentSyncToken)
                }
            }

            if (response.status == HttpStatusCode.OK) {
                val responseText = response.body<String>()
                val syncResponse = json.decodeFromString<SyncResponse>(responseText)

                // Extract next_batch manually for reliability
                val nextBatch = extractNextBatchToken(responseText)

                SyncResult.Success(syncResponse, nextBatch)
            } else {
                SyncResult.Failure("Sync failed: ${response.status}")
            }
        } catch (e: Exception) {
            SyncResult.Failure("Sync error: ${e.message}")
        }
    }

    private fun extractNextBatchToken(responseText: String): String? {
        return try {
            val jsonElement = json.parseToJsonElement(responseText)
            val nextBatchElement = jsonElement.jsonObject["next_batch"]
            if (nextBatchElement is JsonPrimitive && nextBatchElement.isString) {
                nextBatchElement.content
            } else {
                null
            }
        } catch (e: Exception) {
            println("❌ Error extracting next_batch: ${e.message}")
            null
        }
    }
}

/**
 * Processes room timeline events and updates message cache
 */
class RoomEventProcessor {
    fun processRoomEvents(syncResponse: SyncResponse) {
        syncResponse.rooms?.join?.forEach { (roomId, joinedRoom) ->
            val timelineEvents = joinedRoom.timeline?.events ?: emptyList()
            if (timelineEvents.isNotEmpty()) {
                println("📥 Received ${timelineEvents.size} timeline events for room $roomId")

                // Initialize cache for this room if not exists
                if (!MessageCacheManager.hasMessages(roomId)) {
                    MessageCacheManager.setRoomMessages(roomId, emptyList())
                }

                // Add new events to cache (avoiding duplicates by event ID)
                val existingEvents = MessageCacheManager.getRoomMessages(roomId)
                val existingEventIds = existingEvents.map { it.event_id }.toSet()
                val newEvents = timelineEvents.filter { it.event_id !in existingEventIds }

                if (newEvents.isNotEmpty()) {
                    val updatedEvents = existingEvents + newEvents
                    MessageCacheManager.setRoomMessages(roomId, updatedEvents)
                    println("✅ Added ${newEvents.size} new events to cache for room $roomId")

                    // Keep only the most recent 100 messages per room
                    if (updatedEvents.size > 100) {
                        MessageCacheManager.setRoomMessages(roomId, updatedEvents.takeLast(100))
                    }
                }
            }
        }
    }
}

/**
 * Processes to-device events with OlmMachine
 */
class ToDeviceEventProcessor {
    suspend fun processToDeviceEvents(syncResponse: SyncResponse, nextBatchToken: String?): Boolean {
        val token = currentAccessToken ?: return false
        val machine = OlmMachineManager.olmMachine ?: return false

        val toDeviceEvents = syncResponse.toDevice?.events ?: emptyList()
        if (toDeviceEvents.isEmpty()) {
            println("📭 No to-device events received")
            return true
        }

        println("📥 Received ${toDeviceEvents.size} to-device events")

        return try {
            // Convert events to JSON strings
            val toDeviceEventJsons = toDeviceEvents.map { json.encodeToString(it) }

            // Process with OlmMachine
            val decryptionSettings = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)
            val syncChanges = machine.receiveSyncChanges(
                events = toDeviceEventJsons.joinToString(","),
                deviceChanges = DeviceLists(emptyList(), emptyList()),
                keyCounts = emptyMap<String, Int>(),
                unusedFallbackKeys = null,
                nextBatchToken = nextBatchToken ?: "",
                decryptionSettings = decryptionSettings
            )

            println("🔄 Processed sync changes: ${syncChanges.roomKeyInfos.size} room keys received")
            if (syncChanges.roomKeyInfos.isNotEmpty()) {
                println("🔑 Room keys received: ${syncChanges.roomKeyInfos.joinToString(", ") { it.roomId }}")
            }

            // Process any outgoing requests generated
            val requestProcessor = SyncOutgoingRequestProcessor()
            requestProcessor.processOutgoingRequests(machine, token)

            true
        } catch (e: Exception) {
            println("❌ Error processing to-device events: ${e.message}")
            false
        }
    }
}

/**
 * Processes outgoing requests from OlmMachine
 */
class SyncOutgoingRequestProcessor {
    suspend fun processOutgoingRequests(machine: OlmMachine, token: String) {
        println("🔄 Calling machine.outgoingRequests() in SyncManager...")
        val outgoingRequests = machine.outgoingRequests()
        println("📋 Got ${outgoingRequests.size} outgoing requests from OlmMachine")
        if (outgoingRequests.isEmpty()) return

        println("📤 Sending ${outgoingRequests.size} outgoing requests from sync processing...")

        for (request in outgoingRequests) {
            println("🔍 Processing request type: ${request::class.simpleName}")
            when (request) {
                is Request.ToDevice -> processToDeviceRequest(request, token)
                is Request.KeysQuery -> processKeysQueryRequest(request, token)
                is Request.KeysUpload -> processKeysUploadRequest(request, token)
                is Request.KeysClaim -> processKeysClaimRequest(request, token)
                is Request.KeysBackup -> println("⚠️  KeysBackup request not implemented")
                is Request.RoomMessage -> processRoomMessageRequest(request, token)
                is Request.SignatureUpload -> processSignatureUploadRequest(request, token)
            }
        }
    }

    private suspend fun processToDeviceRequest(request: Request.ToDevice, token: String) {
        val response = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            val body = convertMapToHashMap(request.body)
            val jsonBody = createRequestBody(body)
            val messagesWrapper = JsonObject(mapOf("messages" to jsonBody))
            setBody(messagesWrapper)
        }
        logRequestResult(response, "to-device request")
    }

    private suspend fun processKeysQueryRequest(request: Request.KeysQuery, token: String) {
        println("🔍 Processing keys query for users: ${request.users}")
        val response = client.post("$currentHomeserver/_matrix/client/v3/keys/query") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            // Create proper device_keys format: each user should map to an empty array to request all devices
            val deviceKeys = request.users.associateWith { JsonArray(emptyList()) }
            val body = JsonObject(mapOf("device_keys" to JsonObject(deviceKeys)))
            setBody(body)
        }
        if (response.status != HttpStatusCode.OK) {
            // Log the request body for debugging
            val deviceKeys = request.users.associateWith { JsonArray(emptyList()) }
            val body = JsonObject(mapOf("device_keys" to JsonObject(deviceKeys)))
            println("📤 Keys query request body: ${body}")
            println("📤 Users being queried: ${request.users}")
            println("📤 Response body: ${response.body<String>()}")
        }
        logRequestResult(response, "keys query")
    }

    private suspend fun processKeysUploadRequest(request: Request.KeysUpload, token: String) {
        val response = client.post("$currentHomeserver/_matrix/client/v3/keys/upload") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            val body = convertMapToHashMap(request.body)
            setBody(createRequestBody(body))
        }
        logRequestResult(response, "keys upload")
    }

    private suspend fun processKeysClaimRequest(request: Request.KeysClaim, token: String) {
        val response = client.post("$currentHomeserver/_matrix/client/v3/keys/claim") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            val body = convertMapToHashMap(request.oneTimeKeys)
            setBody(createRequestBody(body))
        }
        logRequestResult(response, "keys claim")
    }

    private suspend fun processRoomMessageRequest(request: Request.RoomMessage, token: String) {
        val response = client.put("$currentHomeserver/_matrix/client/v3/rooms/${request.roomId}/send/${request.eventType}/${System.currentTimeMillis()}") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(json.parseToJsonElement(request.content))
        }
        logRequestResult(response, "room message")
    }

    private suspend fun processSignatureUploadRequest(request: Request.SignatureUpload, token: String) {
        val response = client.post("$currentHomeserver/_matrix/client/v3/keys/signatures/upload") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            val body = convertMapToHashMap(request.body)
            setBody(createRequestBody(body))
        }
        logRequestResult(response, "signature upload")
    }

    private fun createRequestBody(body: Any?): JsonElement {
        return when (body) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val mapBody = body as Map<String, Any>
                JsonObject(mapBody.mapValues { anyToJsonElement(it.value) })
            }
            is String -> json.parseToJsonElement(body)
            else -> JsonObject(emptyMap())
        }
    }

    private fun createKeysQueryBody(users: Any?): JsonElement {
        return if (users is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val usersMap = users as Map<String, Any>
            val deviceKeys = usersMap.mapValues { entry ->
                val devices = (entry.value as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                JsonArray(devices.map { JsonPrimitive(it) })
            }
            JsonObject(mapOf("device_keys" to JsonObject(deviceKeys)))
        } else {
            JsonObject(mapOf("device_keys" to JsonObject(emptyMap())))
        }
    }

    private fun logRequestResult(response: HttpResponse, requestType: String) {
        if (response.status == HttpStatusCode.OK) {
            println("✅ $requestType sent successfully")
        } else {
            println("❌ Failed to send $requestType: ${response.status}")
        }
    }
}

/**
 * Manages the periodic sync loop
 */
class PeriodicSyncManager {
    suspend fun startPeriodicSync() {
        println("🔄 Starting periodic sync loop")
        try {
            while (true) {
                try {
                    println("🔄 Periodic sync: waiting 60 seconds...")
                    kotlinx.coroutines.delay(60000)

                    if (currentAccessToken != null && OlmMachineManager.olmMachine != null) {
                        val syncResult = syncAndProcessToDevice()
                        if (syncResult) {
                            println("🔄 Periodic sync completed successfully")
                        } else {
                            println("⚠️  Periodic sync failed")
                        }
                    } else {
                        println("⚠️  Periodic sync: conditions not met, skipping")
                    }
                } catch (e: Exception) {
                    println("❌ Periodic sync error: ${e.message}")
                    e.printStackTrace()
                    kotlinx.coroutines.delay(120000) // Delay longer on error
                }
            }
        } catch (e: Exception) {
            println("❌ Periodic sync loop crashed: ${e.message}")
            e.printStackTrace()
        }
    }
}

/**
 * Result of a sync operation
 */
sealed class SyncResult {
    data class Success(val syncResponse: SyncResponse, val nextBatch: String?) : SyncResult()
    data class Failure(val error: String) : SyncResult()
}

/**
 * Main sync function that orchestrates the sync process using modular components
 */
suspend fun syncAndProcessToDevice(): Boolean {
    println("🔄 SYNC FUNCTION CALLED: syncAndProcessToDevice starting")

    val syncHandler = SyncResponseHandler()
    val roomProcessor = RoomEventProcessor()
    val toDeviceProcessor = ToDeviceEventProcessor()

    return try {
        // Perform the sync
        val syncResult = syncHandler.performSync()

        when (syncResult) {
            is SyncResult.Success -> {
                val (syncResponse, nextBatch) = syncResult

                // Update sync token
                if (nextBatch != null) {
                    currentSyncToken = nextBatch
                    println("🔄 Updated sync token: ${currentSyncToken.take(10)}...")

                    // Persist the updated sync token to session.json
                    saveSession(SessionData(
                        userId = currentUserId ?: "",
                        deviceId = currentDeviceId ?: "",
                        accessToken = currentAccessToken ?: "",
                        homeserver = currentHomeserver ?: "",
                        syncToken = currentSyncToken
                    ))
                } else {
                    println("⚠️  No next_batch token in sync response")
                }

                // Process room events for message cache updates
                roomProcessor.processRoomEvents(syncResponse)

                // Process to-device events for encryption
                toDeviceProcessor.processToDeviceEvents(syncResponse, nextBatch)

                true
            }
            is SyncResult.Failure -> {
                println("❌ Sync failed: ${syncResult.error}")
                false
            }
        }
    } catch (e: Exception) {
        println("❌ Sync error: ${e.message}")
        e.printStackTrace()
        false
    }
}

/**
 * Starts the periodic sync loop using the PeriodicSyncManager
 */
suspend fun startPeriodicSync() {
    val periodicSyncManager = PeriodicSyncManager()
    periodicSyncManager.startPeriodicSync()
}