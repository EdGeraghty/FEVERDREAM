package network

import crypto.initializeEncryption
import crypto.syncAndProcessToDevice
import crypto.roomMessageCache
import crypto.isRoomEncrypted
import crypto.hasRoomKey
import crypto.ensureRoomEncryption
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
                
                // Sync once at the beginning to get latest keys for all messages
                println("🔄 Syncing once before decryption...")
                val syncResult = syncAndProcessToDevice(10000UL) // Reduced timeout to 10 seconds
                if (!syncResult) {
                    println("⚠️  Sync failed before decryption")
                }

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

                            // Debug: Check if we have the room key
                            val hasKey = hasRoomKey(roomId)
                            println("🔑 Room $roomId has key: $hasKey")

                            val eventJson = json.encodeToString(event)
                            val decryptionSettings = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)
                            
                            // Wrap decryption in try-catch to handle session expiration panics
                            val decrypted = try {
                                machine.decryptRoomEvent(
                                    roomId = roomId,
                                    event = eventJson,
                                    decryptionSettings = decryptionSettings,
                                    handleVerificationEvents = false,
                                    strictShields = false
                                )
                            } catch (e: Exception) {
                                // Handle session expiration panics gracefully
                                if (e.message?.contains("Session expired") == true || e.message?.contains("panicked") == true) {
                                    println("⚠️  Session expired during decryption, attempting to renew...")
                                    throw Exception("Session expired - decryption failed")
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
                                        
                                        // Send the key request
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
                                                        println("✅ Room key request sent")
                                                    } else {
                                                        println("❌ Failed to send room key request: ${keysQueryResponse.status}")
                                                        // Log response body for debugging
                                                        try {
                                                            val responseBody = keysQueryResponse.body<String>()
                                                            println("❌ Response body: $responseBody")
                                                        } catch (e: Exception) {
                                                            println("❌ Could not read response body: ${e.message}")
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    println("❌ Exception sending key request: ${e.message}")
                                                    e.printStackTrace()
                                                }
                                            }
                                            else -> {
                                                println("⚠️  Unexpected key request type: ${keyRequest::class.simpleName}")
                                            }
                                        }
                                        
                                        // Send a cancellation request as well (Matrix protocol requirement)
                                        val cancellationRequest = keyRequestPair.cancellation
                                        if (cancellationRequest != null) {
                                            when (cancellationRequest) {
                                                is Request.ToDevice -> {
                                                    println("📤 Sending key request cancellation")
                                                    val cancelResponse = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${cancellationRequest.eventType}/${System.currentTimeMillis()}") {
                                                        bearerAuth(token)
                                                        contentType(ContentType.Application.Json)
                                                        // Parse the string body as JSON
                                                        val body = convertMapToHashMap(cancellationRequest.body)
                                                        if (body is Map<*, *>) {
                                                            @Suppress("UNCHECKED_CAST")
                                                            val mapBody = body as Map<String, Any>
                                                            setBody(JsonObject(mapBody.mapValues { anyToJsonElement(it.value) }))
                                                        } else if (body is String) {
                                                            val parsedElement = json.parseToJsonElement(body)
                                                            if (parsedElement is JsonObject) {
                                                                setBody(parsedElement)
                                                            } else {
                                                                setBody(JsonObject(mapOf()))
                                                            }
                                                        }
                                                    }
                                                    if (cancelResponse.status == HttpStatusCode.OK) {
                                                        println("✅ Key request cancellation sent")
                                                    } else {
                                                        println("❌ Failed to send key request cancellation: ${cancelResponse.status}")
                                                    }
                                                }
                                                else -> {
                                                    println("⚠️  Unexpected cancellation request type: ${cancellationRequest::class.simpleName}")
                                                }
                                            }
                                        }
                                        
                                        // Sync again to process any incoming key responses
                                        println("🔄 Syncing again after key request...")
                                        syncAndProcessToDevice(10000UL) // Reduced timeout
                                        
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
                                            // Fall through to return undecryptable event
                                        }
                                    } catch (keyRequestException: Exception) {
                                        println("❌ Key request failed: ${keyRequestException.message}")
                                        // Fall through to return undecryptable event
                                    }
                                }
                                else -> {
                                    println("❌ Other decryption error: ${e.message}")
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
                    println("⚠️  Failed to set up encryption, sending as plain text")
                    val requestBody = SendMessageRequest(body = message)
                    finalContent = json.encodeToString(requestBody)
                    eventType = "m.room.message"
                } else {
                    // Encrypt the message
                    println("🔐 Encrypting message for room $roomId")
                    try {
                        val encryptedContent = machine.encrypt(roomId, "m.room.message", """{"body": "$message", "msgtype": "m.text"}""")
                        finalContent = encryptedContent // Don't double-encode - OlmMachine already returns JSON string
                        eventType = "m.room.encrypted"
                        println("✅ Message encrypted successfully")
                        println("🔐 Encrypted content: $finalContent")
                    } catch (encryptError: Exception) {
                        println("❌ Message encryption failed: ${encryptError.message}")
                        
                        // Handle session expiration specifically
                        if (encryptError.message?.contains("Session expired") == true || encryptError.message?.contains("panicked") == true) {
                            println("⚠️  Session expired during encryption, attempting to renew...")
                            try {
                                // Try to renew the session by ensuring room encryption again
                                val renewed = ensureRoomEncryption(roomId)
                                if (renewed) {
                                    // Retry encryption with renewed session
                                    val retryEncryptedContent = machine.encrypt(roomId, "m.room.message", """{"body": "$message", "msgtype": "m.text"}""")
                                    finalContent = retryEncryptedContent // Don't double-encode
                                    eventType = "m.room.encrypted"
                                    println("✅ Message encrypted successfully after session renewal")
                                } else {
                                    throw Exception("Failed to renew session")
                                }
                            } catch (renewalException: Exception) {
                                println("❌ Session renewal failed: ${renewalException.message}")
                                // Fallback to plain text
                                val requestBody = SendMessageRequest(body = message)
                                finalContent = json.encodeToString(requestBody)
                                eventType = "m.room.message"
                            }
                        } else {
                            // Fallback to plain text for other encryption errors
                            val requestBody = SendMessageRequest(body = message)
                            finalContent = json.encodeToString(requestBody)
                            eventType = "m.room.message"
                        }
                    }
                }
            } else {
                // Skip encryption setup - encrypt directly
                println("🔐 Encrypting message for room $roomId (skipping setup check)")
                try {
                    val encryptedContent = machine.encrypt(roomId, "m.room.message", """{"body": "$message", "msgtype": "m.text"}""")
                    finalContent = encryptedContent // Don't double-encode
                    eventType = "m.room.encrypted"
                    println("✅ Message encrypted successfully (setup skipped)")
                    println("🔐 Encrypted content: $finalContent")
                } catch (encryptError: Exception) {
                    println("❌ Message encryption failed: ${encryptError.message}")
                    // Fallback to plain text
                    val requestBody = SendMessageRequest(body = message)
                    finalContent = json.encodeToString(requestBody)
                    eventType = "m.room.message"
                }
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
            header("Authorization", "Bearer $currentAccessToken")
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
