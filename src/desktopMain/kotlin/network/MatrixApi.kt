package network

import crypto.initializeEncryption
import crypto.syncAndProcessToDevice
import crypto.roomMessageCache
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import models.*
import models.RoomMembersResponse
import models.MemberEvent
import models.MemberContent
import java.io.File
import org.matrix.rustcomponents.sdk.crypto.*
import uniffi.matrix_sdk_crypto.CollectStrategy
import uniffi.matrix_sdk_crypto.DecryptionSettings
import uniffi.matrix_sdk_crypto.TrustRequirement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.modules.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json


// Global encryption state
// NOTE: Now using matrix-sdk-crypto (modern, vodozemac-based encryption)
// Successfully migrated from deprecated jOlm library to current Matrix encryption standard
var olmMachine: OlmMachine? = null

// Global state for Matrix client
var currentAccessToken: String? = null
var currentHomeserver: String = "https://matrix.org"
var currentDeviceId: String? = null
var currentUserId: String? = null
var currentSyncToken: String = ""

suspend fun discoverHomeserver(domain: String): String {
    try {
        // Try to discover homeserver via .well-known/matrix/client first
        val clientWellKnownUrl = "https://$domain/.well-known/matrix/client"
        println("Checking for homeserver discovery at: $clientWellKnownUrl")

        val clientResponse = client.get(clientWellKnownUrl)

        println("Client well-known response status: ${clientResponse.status}")

        if (clientResponse.status == HttpStatusCode.OK) {
            val clientResponseText = clientResponse.body<String>()
            println("Client well-known response body: $clientResponseText")

            try {
                val clientWellKnown = json.decodeFromString<ClientWellKnownResponse>(clientResponseText)
                val homeserverInfo = clientWellKnown.homeserver

                if (homeserverInfo != null) {
                    val baseUrl = homeserverInfo.base_url
                    println("Found homeserver via client discovery: $domain -> $baseUrl")
                    return baseUrl
                }
            } catch (e: Exception) {
                println("Failed to parse client well-known response: ${e.message}")
                // Try to parse as plain text fallback
                if (clientResponseText.contains("m.homeserver")) {
                    val baseUrlMatch = Regex("\"base_url\"\\s*:\\s*\"([^\"]+)\"").find(clientResponseText)
                    if (baseUrlMatch != null) {
                        val baseUrl = baseUrlMatch.groupValues[1]
                        println("Parsed homeserver from regex: $domain -> $baseUrl")
                        return baseUrl
                    }
                }
            }
        } else {
            println("Client well-known not found (status: ${clientResponse.status}), trying server delegation...")
        }

        // Fallback: Try server delegation
        try {
            val serverWellKnownUrl = "https://$domain/.well-known/matrix/server"
            println("Checking for server delegation at: $serverWellKnownUrl")

            val serverResponse = client.get(serverWellKnownUrl)

            println("Server delegation response status: ${serverResponse.status}")

            if (serverResponse.status == HttpStatusCode.OK) {
                val serverResponseText = serverResponse.body<String>()
                println("Server delegation response body: $serverResponseText")

                try {
                    val delegation = json.decodeFromString<ServerDelegationResponse>(serverResponseText)
                    val serverValue = delegation.mServer

                    if (serverValue != null) {
                        // Handle server value that might include port
                        val actualServer = if (serverValue.contains(":")) {
                            val parts = serverValue.split(":", limit = 2)
                            val serverHost = parts[0]
                            val serverPort = parts[1]
                            if (serverPort == "443") {
                                "https://$serverHost"
                            } else {
                                "https://$serverHost:$serverPort"
                            }
                        } else {
                            "https://$serverValue"
                        }

                        println("Found server delegation: $domain -> $actualServer")
                        return actualServer
                    }
                } catch (e: Exception) {
                    println("Failed to parse server delegation response: ${e.message}")
                }

                // Try to parse as plain text fallback
                if (serverResponseText.contains("m.server")) {
                    val serverMatch = Regex("\"m\\.server\"\\s*:\\s*\"([^\"]+)\"").find(serverResponseText)
                    if (serverMatch != null) {
                        val serverValue = serverMatch.groupValues[1]
                        val actualServer = if (serverValue.contains(":")) {
                            val parts = serverValue.split(":", limit = 2)
                            val serverHost = parts[0]
                            val serverPort = parts[1]
                            if (serverPort == "443") {
                                "https://$serverHost"
                            } else {
                                "https://$serverHost:$serverPort"
                            }
                        } else {
                            "https://$serverValue"
                        }
                        println("Parsed server delegation from regex: $domain -> $actualServer")
                        return actualServer
                    }
                }
            } else {
                println("Server delegation not found (status: ${serverResponse.status})")
            }
        } catch (e: Exception) {
            println("Error checking server delegation for $domain: ${e.message}")
        }

    } catch (e: Exception) {
        println("Error checking homeserver discovery for $domain: ${e.message}")
    }

    // Final fallback to the domain itself
    println("Using fallback homeserver: https://$domain")
    return "https://$domain"
}

suspend fun login(username: String, password: String, homeserver: String): LoginResponse? {
    try {
        // Handle empty homeserver with smart defaults
        val cleanHomeserver = when {
            homeserver.isBlank() -> {
                // Try to extract from username first
                val cleanUsername = username.removePrefix("@")
                if (cleanUsername.contains(":")) {
                    val domain = cleanUsername.split(":").last()
                    "https://$domain"
                } else {
                    "https://matrix.org" // Default fallback
                }
            }
            homeserver.startsWith("http://") -> {
                homeserver.replace("http://", "https://")
            }
            !homeserver.startsWith("https://") -> {
                "https://$homeserver"
            }
            else -> homeserver
        }

        val cleanUsername = username.removePrefix("@")

        // Extract homeserver from user ID if it contains a domain
        val (userId, actualHomeserver) = if (cleanUsername.contains(":")) {
            val parts = cleanUsername.split(":", limit = 2)
            val userPart = parts[0]
            val domainPart = parts[1]

            // Discover the actual homeserver (check for delegation)
            val discoveredHomeserver = discoverHomeserver(domainPart)
            Pair(userPart, discoveredHomeserver)
        } else {
            val userId = cleanUsername
            Pair(userId, cleanHomeserver)
        }

        // Use the extracted homeserver if available, otherwise use the provided one
        val finalHomeserver = actualHomeserver
        currentHomeserver = finalHomeserver

        println("🔍 Login attempt:")
        println("   User: $userId")
        println("   Server: $finalHomeserver")
        println("   Auto-detected: ${cleanUsername.contains(":")}")

        // Try login with the determined homeserver
        try {
            val loginRequest = LoginRequestV2(
                identifier = Identifier(user = userId),
                password = password
            )

            println("📤 Sending login request...")

            val response = client.post("$finalHomeserver/_matrix/client/v3/login") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(loginRequest))
            }

            println("📥 Login response: ${response.status}")

            if (response.status == HttpStatusCode.OK) {
                val loginResponse = response.body<LoginResponse>()
                currentAccessToken = loginResponse.access_token
                currentDeviceId = loginResponse.device_id
                currentUserId = loginResponse.user_id
                println("✅ Logged in successfully!")
                println("   Device ID: ${currentDeviceId}")
                println("   User ID: ${currentUserId}")
                // Initialize encryption system after successful login
                initializeEncryption(loginResponse.user_id, loginResponse.device_id)
                // Save session data
                val sessionData = SessionData(
                    userId = loginResponse.user_id,
                    deviceId = loginResponse.device_id,
                    accessToken = loginResponse.access_token,
                    homeserver = finalHomeserver,
                    syncToken = currentSyncToken
                )
                saveSession(sessionData)
                return loginResponse
            } else {
                // Try to get error details from response
                val errorText = response.body<String>()
                println("Login error response: $errorText")

                // If it's a 400 error, try the older login format as fallback
                if (response.status == HttpStatusCode.BadRequest) {
                    println("Trying older login format...")
                    try {
                        val oldLoginRequest = LoginRequest(
                            user = userId,
                            password = password
                        )

                        val oldResponse = client.post("$finalHomeserver/_matrix/client/v3/login") {
                            contentType(ContentType.Application.Json)
                            setBody(json.encodeToString(oldLoginRequest))
                        }

                        if (oldResponse.status == HttpStatusCode.OK) {
                            val loginResponse = oldResponse.body<LoginResponse>()
                            currentAccessToken = loginResponse.access_token
                            currentDeviceId = loginResponse.device_id
                            currentUserId = loginResponse.user_id
                            println("🔑 Logged in with device ID: ${currentDeviceId}")
                            println("Login successful with older format")
                            // Initialize encryption system after successful login
                            initializeEncryption(loginResponse.user_id, loginResponse.device_id)
                            // Save session data
                            val sessionData = SessionData(
                                userId = loginResponse.user_id,
                                deviceId = loginResponse.device_id,
                                accessToken = loginResponse.access_token,
                                homeserver = finalHomeserver,
                                syncToken = currentSyncToken
                            )
                            saveSession(sessionData)
                            return loginResponse
                        } else {
                            println("Older login format also failed: ${oldResponse.status}")
                        }
                    } catch (oldException: Exception) {
                        println("Older login format failed: ${oldException.message}")
                    }
                }

                throw Exception("Login failed: ${response.status} - ${response.status.description}")
            }
        } catch (e: Exception) {
            println("Login failed on homeserver $finalHomeserver: ${e.message}")

            // If the login failed and we discovered a homeserver (not using the original domain),
            // and the user didn't provide a specific homeserver, don't try fallback
            val originalDomainHomeserver = if (cleanUsername.contains(":")) {
                "https://${cleanUsername.split(":").last()}"
            } else {
                ""
            }
            if (homeserver.isBlank() && actualHomeserver != originalDomainHomeserver) {
                println("Not attempting fallback since homeserver was auto-discovered and user didn't specify one")
                throw e
            }

            // Only try fallback if user explicitly provided a different homeserver
            if (homeserver.isNotBlank() && actualHomeserver != cleanHomeserver) {
                println("Login failed on discovered homeserver $actualHomeserver, trying provided homeserver: $cleanHomeserver")
                currentHomeserver = cleanHomeserver

                val fallbackRequest = LoginRequestV2(
                    identifier = Identifier(user = userId),
                    password = password
                )

                val fallbackResponse = client.post("$cleanHomeserver/_matrix/client/v3/login") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(fallbackRequest))
                }

                println("📥 Fallback login response: ${fallbackResponse.status}")

                if (fallbackResponse.status == HttpStatusCode.OK) {
                    try {
                        val loginResponse = fallbackResponse.body<LoginResponse>()
                        currentAccessToken = loginResponse.access_token
                        currentDeviceId = loginResponse.device_id
                        currentUserId = loginResponse.user_id
                        println("🔑 Logged in with device ID: ${currentDeviceId}")
                        // Initialize encryption system after successful login
                        initializeEncryption(loginResponse.user_id, loginResponse.device_id)
                        // Save session data
                        val sessionData = SessionData(
                            userId = loginResponse.user_id,
                            deviceId = loginResponse.device_id,
                            accessToken = loginResponse.access_token,
                            homeserver = cleanHomeserver,
                            syncToken = currentSyncToken
                        )
                        saveSession(sessionData)
                        return loginResponse
                    } catch (parseException: Exception) {
                        println("Failed to parse fallback login response as JSON: ${parseException.message}")
                        // Try to get the raw response for debugging
                        try {
                            val rawResponse = fallbackResponse.body<String>()
                            println("Raw fallback response: $rawResponse")
                        } catch (rawException: Exception) {
                            println("Could not read raw fallback response: ${rawException.message}")
                        }
                        throw Exception("Fallback homeserver returned invalid response format")
                    }
                } else {
                    // Try to get error details from fallback response
                    try {
                        val errorText = fallbackResponse.body<String>()
                        println("Fallback login error response: $errorText")
                    } catch (errorException: Exception) {
                        println("Could not read fallback error response: ${errorException.message}")
                    }
                }
            }
            throw e
        }

    } catch (e: Exception) {
        println("Login failed: ${e.message}")
        throw e
    }
}

suspend fun getJoinedRooms(): List<String> {
    val token = currentAccessToken ?: return emptyList()
    try {
        val response = client.get("$currentHomeserver/_matrix/client/v3/joined_rooms") {
            bearerAuth(token)
        }
        if (response.status == HttpStatusCode.OK) {
            val roomsResponse = response.body<JoinedRoomsResponse>()
            return roomsResponse.joined_rooms
        }
    } catch (e: Exception) {
        println("Get rooms failed: ${e.message}")
    }
    return emptyList()
}

suspend fun getRoomInvites(): List<RoomInvite> {
    val token = currentAccessToken ?: return emptyList()
    try {
        val response = client.get("$currentHomeserver/_matrix/client/v3/sync") {
            bearerAuth(token)
            parameter("filter", """{"room":{"invite":{"state":{"limit":0},"timeline":{"limit":0}},"leave":{"state":{"limit":0},"timeline":{"limit":0}},"join":{"state":{"limit":0},"timeline":{"limit":0}},"knock":{"state":{"limit":0},"timeline":{"limit":0}},"ban":{"state":{"limit":0},"timeline":{"limit":0}}},"presence":{"limit":0},"account_data":{"limit":0},"receipts":{"limit":0}}""")
        }
        if (response.status == HttpStatusCode.OK) {
            val syncResponse = response.body<SyncResponse>()
            val invites = mutableListOf<RoomInvite>()

            syncResponse.rooms?.invite?.forEach { (roomId, invitedRoom) ->
                val inviteState = invitedRoom.invite_state
                if (inviteState != null) {
                    val sender = inviteState.events.firstOrNull { it.type == "m.room.member" && it.state_key == currentUserId }?.sender ?: ""
                    invites.add(RoomInvite(roomId, sender, inviteState))
                }
            }

            return invites
        }
    } catch (e: Exception) {
        println("Get room invites failed: ${e.message}")
    }
    return emptyList()
}

suspend fun acceptRoomInvite(roomId: String): Boolean {
    val token = currentAccessToken ?: return false
    try {
        val response = client.post("$currentHomeserver/_matrix/client/v3/rooms/$roomId/join") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(JsonObject(emptyMap()))
        }
        return response.status == HttpStatusCode.OK
    } catch (e: Exception) {
        println("Accept invite failed: ${e.message}")
    }
    return false
}

suspend fun rejectRoomInvite(roomId: String): Boolean {
    val token = currentAccessToken ?: return false
    try {
        val response = client.post("$currentHomeserver/_matrix/client/v3/rooms/$roomId/leave") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(JsonObject(emptyMap()))
        }
        return response.status == HttpStatusCode.OK
    } catch (e: Exception) {
        println("Reject invite failed: ${e.message}")
    }
    return false
}

suspend fun getRoomMembers(roomId: String): List<String> {
    val token = currentAccessToken ?: return emptyList()
    try {
        val response = client.get("$currentHomeserver/_matrix/client/v3/rooms/$roomId/members") {
            bearerAuth(token)
        }
        if (response.status == HttpStatusCode.OK) {
            val membersResponse = response.body<RoomMembersResponse>()
            return membersResponse.chunk
                .filter { it.content.membership == "join" }
                .map { it.state_key }
        }
    } catch (e: Exception) {
        println("Get room members failed: ${e.message}")
    }
    return emptyList()
}

suspend fun getRoomMessages(roomId: String): List<Event> {
    val token = currentAccessToken ?: return emptyList()
    
    // Get cached messages first
    val cachedMessages = roomMessageCache[roomId] ?: emptyList()
    
    try {
        val response = client.get("$currentHomeserver/_matrix/client/v3/rooms/$roomId/messages") {
            bearerAuth(token)
            parameter("limit", "50")
            parameter("dir", "b")
        }
        if (response.status == HttpStatusCode.OK) {
            val messagesResponse = response.body<RoomMessagesResponse>()
            val fetchedMessages = messagesResponse.chunk.reversed()

            // Merge cached and fetched messages, avoiding duplicates
            val allMessages = (cachedMessages + fetchedMessages).distinctBy { it.event_id }
            
            // Update cache with merged messages
            roomMessageCache[roomId] = allMessages.takeLast(100).toMutableList()

            // Decrypt encrypted messages from the merged list
            val machine = olmMachine
            if (machine != null) {
                val decryptedMessages = allMessages.map { event ->
                    if (event.type == "m.room.encrypted") {
                        try {
                            // Check if the event content has the required fields for decryption
                            val contentObj = event.content as? JsonObject ?: JsonObject(emptyMap())
                            val hasAlgorithm = contentObj.containsKey("algorithm")
                            val hasCiphertext = contentObj.containsKey("ciphertext")
                            
                            if (!hasAlgorithm || !hasCiphertext) {
                                println("⚠️  Encrypted event missing required fields (algorithm or ciphertext), skipping decryption")
                                // Return the event as-is with a bad encrypted marker
                                return@map event.copy(
                                    type = "m.room.message",
                                    content = json.parseToJsonElement("""{"msgtype": "m.bad.encrypted", "body": "** Unable to decrypt: Malformed encrypted event (missing algorithm or ciphertext) **"}""")
                                )
                            }

                            // Always sync before attempting decryption to get latest keys
                            println("🔄 Syncing before decryption...")
                            val syncResult = syncAndProcessToDevice(30000UL)
                            if (!syncResult) {
                                println("⚠️  Sync failed before decryption")
                            }

                            // Add a small delay to allow key processing
                            kotlinx.coroutines.delay(1000)

                            val eventJson = json.encodeToString(event)
                            val decryptionSettings = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)
                            val decrypted = machine.decryptRoomEvent(
                                roomId = roomId,
                                event = eventJson,
                                decryptionSettings = decryptionSettings,
                                handleVerificationEvents = false,
                                strictShields = false
                            )

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

                            // If decryption fails due to missing keys, try to request them
                            if (e.message?.contains("Can't find the room key") == true) {
                                println("🔑 Room key missing, attempting to request keys...")
                                try {
                                    // Request missing keys from other devices
                                    val eventJsonForKeyRequest = json.encodeToString(event)
                                    val keyRequestPair = machine.requestRoomKey(eventJsonForKeyRequest, roomId)
                                    
                                    // Send the key request
                                    val keyRequest = keyRequestPair.keyRequest
                                    when (keyRequest) {
                                        is Request.ToDevice -> {
                                            println("📤 Sending room key request")
                                            val keysQueryResponse = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${keyRequest.eventType}/${System.currentTimeMillis()}") {
                                                bearerAuth(token)
                                                contentType(ContentType.Application.Json)
                                                val body = convertMapToHashMap(keyRequest.body)
                                                if (body is Map<*, *>) {
                                                    @Suppress("UNCHECKED_CAST")
                                                    val mapBody = body as Map<String, Any>
                                                    // Wrap the body in the "messages" structure as required by Matrix API
                                                    val messagesBody = mapOf("messages" to JsonObject(mapBody.mapValues { anyToJsonElement(it.value) }))
                                                    setBody(JsonObject(messagesBody))
                                                } else if (body is String) {
                                                    // Parse the string and wrap it in messages structure
                                                    val parsedBody = json.parseToJsonElement(body)
                                                    if (parsedBody is JsonObject) {
                                                        val messagesBody = mapOf("messages" to parsedBody)
                                                        setBody(JsonObject(messagesBody))
                                                    } else {
                                                        setBody(json.parseToJsonElement(body))
                                                    }
                                                }
                                            }
                                            if (keysQueryResponse.status == HttpStatusCode.OK) {
                                                println("✅ Room key request sent")
                                            }
                                        }
                                        else -> {
                                            println("⚠️  Unexpected request type for key request: ${keyRequest::class.simpleName}")
                                        }
                                    }
                                    
                                    // If there's a cancellation request, send it too
                                    keyRequestPair.cancellation?.let { cancellation ->
                                        when (cancellation) {
                                            is Request.ToDevice -> {
                                                println("📤 Sending key request cancellation")
                                                val cancelResponse = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${cancellation.eventType}/${System.currentTimeMillis()}") {
                                                    bearerAuth(token)
                                                    contentType(ContentType.Application.Json)
                                                    val body = convertMapToHashMap(cancellation.body)
                                                    if (body is Map<*, *>) {
                                                        @Suppress("UNCHECKED_CAST")
                                                        val mapBody = body as Map<String, Any>
                                                        // Wrap the body in the "messages" structure as required by Matrix API
                                                        val messagesBody = mapOf("messages" to JsonObject(mapBody.mapValues { anyToJsonElement(it.value) }))
                                                        setBody(JsonObject(messagesBody))
                                                    } else if (body is String) {
                                                        // Parse the string and wrap it in messages structure
                                                        val parsedBody = json.parseToJsonElement(body)
                                                        if (parsedBody is JsonObject) {
                                                            val messagesBody = mapOf("messages" to parsedBody)
                                                            setBody(JsonObject(messagesBody))
                                                        } else {
                                                            setBody(json.parseToJsonElement(body))
                                                        }
                                                    }
                                                }
                                                if (cancelResponse.status == HttpStatusCode.OK) {
                                                    println("✅ Key request cancellation sent")
                                                }
                                            }
                                            else -> {
                                                println("⚠️  Unexpected request type for cancellation: ${cancellation::class.simpleName}")
                                            }
                                        }
                                    }

                                    // Try one more sync after requesting keys
                                    println("🔄 Syncing again after key request...")
                                    syncAndProcessToDevice(30000UL)

                                    // Add delay before retrying decryption
                                    kotlinx.coroutines.delay(2000)

                                    // Try decryption again
                                    try {
                                        val eventJsonForRetry = json.encodeToString(event)
                                        val decryptionSettings = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)
                                        val decrypted = machine.decryptRoomEvent(
                                            roomId = roomId,
                                            event = eventJsonForRetry,
                                            decryptionSettings = decryptionSettings,
                                            handleVerificationEvents = false,
                                            strictShields = false
                                        )

                                        val decryptedContent = try {
                                            json.parseToJsonElement(decrypted.clearEvent)
                                        } catch (e: Exception) {
                                            // If decrypted content is not valid JSON, treat it as plain text
                                            println("⚠️  Retry decrypted content is not valid JSON: ${decrypted.clearEvent}")
                                            JsonPrimitive(decrypted.clearEvent)
                                        }
                                        val messageContent = try {
                                            json.decodeFromString<MessageContent>(json.encodeToString(decryptedContent))
                                        } catch (e: Exception) {
                                            MessageContent("m.text", decrypted.clearEvent.trim('"'))
                                        }

                                        val finalContent = if (messageContent.body.isNullOrBlank()) {
                                            messageContent.copy(body = decrypted.clearEvent.trim('"'))
                                        } else {
                                            messageContent
                                        }

                                        event.copy(
                                            type = "m.room.message",
                                            content = json.parseToJsonElement(json.encodeToString(finalContent))
                                        )
                                    } catch (retryException: Exception) {
                                        println("❌ Retry decryption also failed: ${retryException.message}")
                                        // Create error message with more details
                                        event.copy(
                                            type = "m.room.message",
                                            content = json.parseToJsonElement("""{"msgtype": "m.bad.encrypted", "body": "** Unable to decrypt: Key not available. Please wait for key sharing or try again later. **"}""")
                                        )
                                    }
                                } catch (keyRequestException: Exception) {
                                    println("❌ Key request failed: ${keyRequestException.message}")
                                    event.copy(
                                        type = "m.room.message",
                                        content = json.parseToJsonElement("""{"msgtype": "m.bad.encrypted", "body": "** Unable to decrypt: ${e.message} **"}""")
                                    )
                                }
                            } else {
                                // Other decryption error
                                event.copy(
                                    type = "m.room.message",
                                    content = json.parseToJsonElement("""{"msgtype": "m.bad.encrypted", "body": "** Unable to decrypt: ${e.message} **"}""")
                                )
                            }
                        }
                    } else {
                        event
                    }
                }
                return decryptedMessages
            } else {
                return allMessages
            }
        }
    } catch (e: Exception) {
        println("Get messages failed: ${e.message}")
    }
    return emptyList()
}

suspend fun sendMessage(roomId: String, message: String): Boolean {
    try {
        val url = "$currentHomeserver/_matrix/client/r0/rooms/$roomId/send/m.room.message/${System.currentTimeMillis()}"
        val requestBody = SendMessageRequest(body = message)
        val response = client.post(url) {
            header("Authorization", "Bearer $currentAccessToken")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(requestBody))
        }
        return response.status == HttpStatusCode.OK
    } catch (e: Exception) {
        println("Send message failed: ${e.message}")
        return false
    }
}
