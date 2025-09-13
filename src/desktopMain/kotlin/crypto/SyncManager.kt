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
                currentSyncToken = syncResponse.nextBatch
                println("🔄 Updated sync token: ${currentSyncToken.take(10)}...")
            }

            // Process room events first (for encrypted messages)
            val roomEvents = syncResponse.rooms?.join?.flatMap { (_, joinedRoom) ->
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
                                            val devices = (entry.value as? List<*>)?.filterIsInstance<String>() ?: emptyList()
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