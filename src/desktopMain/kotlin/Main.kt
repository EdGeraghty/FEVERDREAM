import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.Window
import kotlin.system.exitProcess
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.builtins.*
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.io.File
import org.matrix.rustcomponents.sdk.crypto.*
import uniffi.matrix_sdk_crypto.CollectStrategy
import uniffi.matrix_sdk_crypto.DecryptionSettings
import uniffi.matrix_sdk_crypto.TrustRequirement

import kotlinx.serialization.modules.*

val json = Json { 
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    serializersModule = SerializersModule {
        // Register contextual serializer for Map to handle internal implementations like SingletonMap
        contextual(Map::class) { args ->
            if (args.size >= 2) {
                MapSerializer(args[0], args[1])
            } else {
                // Fallback for unknown type arguments
                MapSerializer(String.serializer(), JsonElement.serializer())
            }
        }
    }
}

// Utility function to convert Maps to HashMap recursively to avoid serialization issues with SingletonMap
@Suppress("UNCHECKED_CAST")
fun convertMapToHashMap(map: Any?): Any? {
    return when (map) {
        is Map<*, *> -> {
            if (map.isEmpty()) {
                mutableMapOf<String, Any>()
            } else {
                map.mapValues { convertMapToHashMap(it.value) }.toMutableMap()
            }
        }
        is List<*> -> map.map { convertMapToHashMap(it) }
        else -> map
    }
}

// Utility function to convert Any to JsonElement for serialization
fun anyToJsonElement(value: Any?): JsonElement {
    return when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.map { it.key.toString() to anyToJsonElement(it.value) }.toMap())
        is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
        else -> JsonPrimitive(value.toString()) // fallback for other types
    }
}

val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(json)
    }
}

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

// Initialize Olm encryption
fun initializeEncryption(userId: String, deviceId: String) {
    if (olmMachine == null) {
        // Create crypto store directory
        val cryptoStorePath = "crypto_store"
        File(cryptoStorePath).mkdirs()

        try {
            // Create OlmMachine with persistent storage
            olmMachine = OlmMachine(userId, deviceId, cryptoStorePath, null)

            val identityKeys = olmMachine!!.identityKeys()
            println("🔑 Matrix SDK Crypto initialized")
            println("Curve25519 key: ${identityKeys["curve25519"]}")
            println("Ed25519 key: ${identityKeys["ed25519"]}")
        } catch (e: Exception) {
            println("❌ Failed to initialize Matrix SDK Crypto: ${e.message}")

            // If the error is about account mismatch, clear the crypto store and retry
            if (e.message?.contains("account in the store doesn't match") == true) {
                println("🔄 Clearing crypto store due to account mismatch...")
                try {
                    File(cryptoStorePath).deleteRecursively()
                    File(cryptoStorePath).mkdirs()

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



@Serializable
data class SessionData(
    val userId: String,
    val deviceId: String,
    val accessToken: String,
    val homeserver: String,
    val syncToken: String = ""
)

val sessionFile = File("session.json")

suspend fun saveSession(sessionData: SessionData) {
    try {
        sessionFile.writeText(json.encodeToString(sessionData))
        println("💾 Session saved")
    } catch (e: Exception) {
        println("❌ Failed to save session: ${e.message}")
    }
}

suspend fun loadSession(): SessionData? {
    return try {
        if (sessionFile.exists()) {
            val sessionData = json.decodeFromString<SessionData>(sessionFile.readText())
            println("📂 Session loaded for user: ${sessionData.userId}")
            sessionData
        } else {
            null
        }
    } catch (e: Exception) {
        println("❌ Failed to load session: ${e.message}")
        null
    }
}

suspend fun validateSession(sessionData: SessionData): Boolean {
    return try {
        val response = client.get("${sessionData.homeserver}/_matrix/client/v3/account/whoami") {
            bearerAuth(sessionData.accessToken)
        }
        response.status == HttpStatusCode.OK
    } catch (e: Exception) {
        println("❌ Session validation failed: ${e.message}")
        false
    }
}

suspend fun clearSession() {
    try {
        if (sessionFile.exists()) {
            sessionFile.delete()
            println("🗑️ Session cleared")
        }
    } catch (e: Exception) {
        println("❌ Failed to clear session: ${e.message}")
    }
}

@Serializable
data class LoginRequest(val type: String = "m.login.password", val user: String, val password: String)

@Serializable
data class LoginRequestV2(val type: String = "m.login.password", val identifier: Identifier, val password: String)

@Serializable
data class Identifier(val type: String = "m.id.user", val user: String)

@Serializable
data class LoginResponse(val user_id: String, val access_token: String, val device_id: String)

@Serializable
data class JoinedRoomsResponse(val joined_rooms: List<String>)

@Serializable
data class RoomInvite(val room_id: String, val sender: String, val state: RoomState)

@Serializable
data class RoomState(val events: List<StateEvent>)

@Serializable
data class StateEvent(val type: String, val state_key: String, val sender: String, val content: JsonElement)

@Serializable
data class SyncResponse(
    val rooms: Rooms? = null,
    val toDevice: ToDevice? = null,
    val nextBatch: String? = null
)

@Serializable
data class ToDevice(val events: List<Event> = emptyList())

@Serializable
data class Rooms(val invite: Map<String, InvitedRoom>? = null, val join: Map<String, JoinedRoom>? = null)

@Serializable
data class InvitedRoom(val invite_state: RoomState? = null)

@Serializable
data class JoinedRoom(val state: RoomState? = null, val timeline: Timeline? = null)

@Serializable
data class Timeline(val events: List<Event> = emptyList())

@Serializable
data class Event(val type: String, val event_id: String, val sender: String, val origin_server_ts: Long, val content: JsonElement)

@Serializable
data class MessageContent(val msgtype: String = "m.text", val body: String? = null)

@Serializable
data class EncryptedMessageContent(
    val algorithm: String,
    val ciphertext: JsonElement, // For Olm it's Map<String, CiphertextInfo>, for Megolm it's String
    val sender_key: String,
    val device_id: String? = null,
    val session_id: String? = null
)

@Serializable
data class ServerDelegationResponse(
    @SerialName("m.server")
    val mServer: String? = null
)

@Serializable
data class ClientWellKnownResponse(
    @SerialName("m.homeserver")
    val homeserver: HomeserverInfo? = null,
    @SerialName("org.matrix.msc3575.proxy")
    val proxy: JsonElement? = null
)

@Serializable
data class HomeserverInfo(
    val base_url: String
)

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

@Serializable
data class SendMessageRequest(val msgtype: String = "m.text", val body: String)

@Serializable
data class RoomMessagesResponse(val chunk: List<Event>)

@Serializable
data class RoomMembersResponse(val chunk: List<MemberEvent>)

@Serializable
data class MemberEvent(
    val type: String,
    val state_key: String,
    val sender: String,
    val content: MemberContent,
    val origin_server_ts: Long
)

@Serializable
data class MemberContent(
    val membership: String,
    val displayname: String? = null,
    val avatar_url: String? = null
)

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

suspend fun login(username: String, password: String, homeserver: String): LoginResponse? {
    try {
        val cleanHomeserver = if (homeserver.startsWith("http://")) {
            homeserver.replace("http://", "https://")
        } else if (!homeserver.startsWith("https://")) {
            "https://$homeserver"
        } else {
            homeserver
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

        println("Attempting login with user: $userId on homeserver: $finalHomeserver")

        // Try login with the determined homeserver
        try {
            val loginRequest = LoginRequestV2(
                identifier = Identifier(user = userId),
                password = password
            )

            println("Sending login request: ${json.encodeToString(loginRequest)}")

            val response = client.post("$finalHomeserver/_matrix/client/v3/login") {
                contentType(ContentType.Application.Json)
                setBody(loginRequest)
            }

            println("Login response status: ${response.status}")

            if (response.status == HttpStatusCode.OK) {
                val loginResponse = response.body<LoginResponse>()
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
                            setBody(oldLoginRequest)
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
            val originalDomainHomeserver = "https://${userId.split(":").getOrNull(1) ?: ""}"
            if (actualHomeserver != originalDomainHomeserver && cleanHomeserver.isBlank()) {
                println("Not attempting fallback since homeserver was auto-discovered and user didn't specify one")
                throw e
            }

            // If the extracted homeserver fails and it's different from the provided one, try the provided homeserver
            if (actualHomeserver != cleanHomeserver && cleanHomeserver.isNotBlank() && cleanHomeserver != "https://") {
                println("Login failed on discovered homeserver $actualHomeserver, trying provided homeserver: $cleanHomeserver")
                currentHomeserver = cleanHomeserver

                val fallbackRequest = LoginRequestV2(
                    identifier = Identifier(user = userId),
                    password = password
                )

                val fallbackResponse = client.post("$cleanHomeserver/_matrix/client/v3/login") {
                    contentType(ContentType.Application.Json)
                    setBody(fallbackRequest)
                }

                if (fallbackResponse.status == HttpStatusCode.OK) {
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

            // Extract to-device events
            val toDeviceEvents = syncResponse.toDevice?.events ?: emptyList()
            if (toDeviceEvents.isNotEmpty()) {
                println("📥 Received ${toDeviceEvents.size} to-device events")

                // Convert events to JSON strings - each event should be a separate string
                val toDeviceEventJsons = toDeviceEvents.map { json.encodeToString(it) }

                // Process with OlmMachine - pass as array of strings
                val decryptionSettings = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)
                val syncChanges = machine.receiveSyncChanges(
                    events = toDeviceEventJsons.joinToString(",", "[", "]"), // Array format
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
                                    val body = convertMapToHashMap(request.body)
                                    if (body is Map<*, *>) {
                                        @Suppress("UNCHECKED_CAST")
                                        val mapBody = body as Map<String, Any>
                                        setBody(JsonObject(mapBody.mapValues { anyToJsonElement(it.value) }))
                                    } else if (body is String) {
                                        setBody(json.parseToJsonElement(body))
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
                                        val deviceKeys = usersMap.mapValues { JsonArray(emptyList<JsonElement>()) }
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
                            else -> {
                                println("⚠️  Unhandled outgoing request type: ${request::class.simpleName}")
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

suspend fun getRoomMessages(roomId: String): List<Event> {
    val token = currentAccessToken ?: return emptyList()
    try {
        val response = client.get("$currentHomeserver/_matrix/client/v3/rooms/$roomId/messages") {
            bearerAuth(token)
            parameter("limit", "50")
            parameter("dir", "b")
        }
        if (response.status == HttpStatusCode.OK) {
            val messagesResponse = response.body<RoomMessagesResponse>()
            val messages = messagesResponse.chunk.reversed()

            // Decrypt encrypted messages
            val machine = olmMachine
            if (machine != null) {
                val decryptedMessages = messages.map { event ->
                    if (event.type == "m.room.encrypted") {
                        try {
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
                            val decryptedContent = json.parseToJsonElement(decrypted.clearEvent)

                            // Try to decode as MessageContent, but handle cases where it might not match
                            val messageContent = try {
                                json.decodeFromJsonElement<MessageContent>(decryptedContent)
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
                                    val keyRequests = machine.outgoingRequests()
                                    for (request in keyRequests) {
                                        when (request) {
                                            is Request.KeysQuery -> {
                                                println("📤 Sending keys query to request missing keys")
                                                val keysQueryResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/query") {
                                                    bearerAuth(token)
                                                    contentType(ContentType.Application.Json)
                                                    val convertedUsers = convertMapToHashMap(request.users)
                                                    if (convertedUsers is Map<*, *>) {
                                                        @Suppress("UNCHECKED_CAST")
                                                        val usersMap = convertedUsers as Map<String, Any>
                                                        val deviceKeys = usersMap.mapValues { JsonArray(emptyList<JsonElement>()) }
                                                        setBody(JsonObject(mapOf("device_keys" to JsonObject(deviceKeys))))
                                                    } else {
                                                        setBody(JsonObject(mapOf("device_keys" to JsonObject(emptyMap()))))
                                                    }
                                                }
                                                if (keysQueryResponse.status == HttpStatusCode.OK) {
                                                    println("✅ Keys query sent for missing keys")
                                                }
                                            }
                                            else -> {
                                                // Handle other requests if needed
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
                                        val eventJson = json.encodeToString(event)
                                        val decryptionSettings = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)
                                        val decrypted = machine.decryptRoomEvent(
                                            roomId = roomId,
                                            event = eventJson,
                                            decryptionSettings = decryptionSettings,
                                            handleVerificationEvents = false,
                                            strictShields = false
                                        )

                                        val decryptedContent = json.parseToJsonElement(decrypted.clearEvent)
                                        val messageContent = try {
                                            json.decodeFromJsonElement<MessageContent>(decryptedContent)
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
                return messages
            }
        }
    } catch (e: Exception) {
        println("Get messages failed: ${e.message}")
    }
    return emptyList()
}

suspend fun sendMessage(roomId: String, message: String): Boolean {
    val token = currentAccessToken ?: return false
    try {
        // Sync to process any incoming to-device events (room keys) before sending
        val syncSuccess = syncAndProcessToDevice(30000UL)
        if (!syncSuccess) {
            println("⚠️  Sync failed, but continuing with message send...")
        }

        val isEncrypted = isRoomEncrypted(roomId)

        if (isEncrypted) {
            try {
                val machine = olmMachine ?: throw Exception("OlmMachine not initialized")

                // Ensure room encryption is properly set up
                val encryptionSetup = ensureRoomEncryption(roomId)
                if (!encryptionSetup) {
                    println("⚠️  Failed to set up room encryption, but continuing...")
                }

                // Step 1: Get room members
                val allRoomMembers = getRoomMembers(roomId)
                val roomMembers = allRoomMembers.filter { it != currentUserId }
                println("🔍 Found ${roomMembers.size} room members to share key with: ${roomMembers.joinToString(", ")}")
                println("🔍 Current user ID: $currentUserId")
                println("🔍 All room members: ${allRoomMembers.joinToString(", ")}")

                // Validate user ID formats
                val validUserIds = roomMembers.filter { it.startsWith("@") && it.contains(":") }
                if (validUserIds.size != roomMembers.size) {
                    println("⚠️  Some user IDs may be malformed. Valid: ${validUserIds.size}/${roomMembers.size}")
                }

                if (roomMembers.isEmpty()) {
                    println("⚠️  No other room members found, sending unencrypted message")
                    val response = client.put("$currentHomeserver/_matrix/client/v3/rooms/$roomId/send/m.room.message/${System.currentTimeMillis()}") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(SendMessageRequest(body = message))
                    }
                    return response.status == HttpStatusCode.OK
                }

                // Step 1: Mark room members for tracking to ensure we have their keys
                println("🔑 Marking room members for tracking...")
                machine.updateTrackedUsers(roomMembers)
                println("✅ Marked ${roomMembers.size} users for tracking")

                // Step 1.5: Force a keys query to get device keys for tracked users
                println("🔑 Querying device keys for tracked users...")
                val keysQueryRequest = machine.outgoingRequests()
                for (request in keysQueryRequest) {
                    when (request) {
                        is Request.KeysQuery -> {
                            println("📤 Sending keys query for tracked users")
                            val keysQueryResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/query") {
                                bearerAuth(token)
                                contentType(ContentType.Application.Json)
                                val convertedUsers = convertMapToHashMap(request.users)
                                if (convertedUsers is Map<*, *>) {
                                    @Suppress("UNCHECKED_CAST")
                                    val usersMap = convertedUsers as Map<String, Any>
                                    val deviceKeys = usersMap.mapValues { JsonArray(emptyList<JsonElement>()) }
                                    setBody(JsonObject(mapOf("device_keys" to JsonObject(deviceKeys))))
                                } else {
                                    setBody(JsonObject(mapOf("device_keys" to JsonObject(emptyMap()))))
                                }
                            }
                            if (keysQueryResponse.status == HttpStatusCode.OK) {
                                println("✅ Keys query sent for tracked users")
                            } else {
                                println("❌ Failed to query keys for tracked users: ${keysQueryResponse.status}")
                            }
                        }
                        else -> {
                            // Handle other requests if needed
                        }
                    }
                }

                // Step 2: Sync multiple times to get the latest keys for tracked users
                println("🔄 Syncing multiple times to get device keys...")
                repeat(5) { syncAttempt -> // Increased from 3 to 5
                    println("🔄 Device key sync attempt ${syncAttempt + 1}/5...")
                    val keySyncResult = syncAndProcessToDevice(30000UL)
                    if (!keySyncResult) {
                        println("⚠️  Device key sync ${syncAttempt + 1} failed, but continuing...")
                    }
                    // Longer delay between syncs to allow key sharing
                    kotlinx.coroutines.delay(2000) // Increased from 1000 to 2000
                }

                // Step 3: Check for missing sessions and establish them
                println("🔑 Checking for missing sessions with room members...")
                val missingSessions = machine.getMissingSessions(roomMembers)
                val missingSessionsCount = (missingSessions as? Collection<*>)?.size ?: 0
                println("🔑 Missing sessions for $missingSessionsCount users")

                // Debug: Check what devices we have for each user
                for (userId in roomMembers) {
                    try {
                        val userDevices = machine.getUserDevices(userId, 30000U)
                        println("🔍 User $userId has ${userDevices.size} devices")
                        if (userDevices.isNotEmpty()) {
                            println("🔍 Device IDs: ${userDevices.joinToString(", ")}")
                        } else {
                            println("⚠️  User $userId has no devices available!")
                        }
                    } catch (e: Exception) {
                        println("🔍 Could not get devices for $userId: ${e.message}")
                    }
                }

                if (missingSessionsCount > 0) {
                    println("🔑 Establishing Olm sessions with room members...")
                    println("🔑 Missing sessions count: $missingSessionsCount")
                    val sessionRequests = machine.outgoingRequests()
                    for (request in sessionRequests) {
                        when (request) {
                            is Request.ToDevice -> {
                                println("📤 Sending to-device request: ${request.eventType}")
                                val toDeviceResponse = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
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
                                if (toDeviceResponse.status != HttpStatusCode.OK) {
                                    println("❌ Failed to send to-device request: ${toDeviceResponse.status}")
                                }
                            }
                            is Request.KeysUpload -> {
                                println("📤 Sending keys upload request")
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
                            is Request.KeysQuery -> {
                                println("📤 Sending keys query request")
                                val keysQueryResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/query") {
                                    bearerAuth(token)
                                    contentType(ContentType.Application.Json)
                                    val convertedUsers = convertMapToHashMap(request.users)
                                    if (convertedUsers is Map<*, *>) {
                                        @Suppress("UNCHECKED_CAST")
                                        val usersMap = convertedUsers as Map<String, Any>
                                        val deviceKeys = usersMap.mapValues { JsonArray(emptyList<JsonElement>()) }
                                        setBody(JsonObject(mapOf("device_keys" to JsonObject(deviceKeys))))
                                    } else {
                                        setBody(JsonObject(mapOf("device_keys" to JsonObject(emptyMap()))))
                                    }
                                }
                                if (keysQueryResponse.status != HttpStatusCode.OK) {
                                    println("❌ Failed to send keys query: ${keysQueryResponse.status}")
                                }
                            }
                            else -> {
                                println("⚠️  Unhandled request type: ${request::class.simpleName}")
                            }
                        }
                    }
                }

                // Step 4: Share room key with all room members
                val encryptionSettings = EncryptionSettings(
                    algorithm = EventEncryptionAlgorithm.MEGOLM_V1_AES_SHA2,
                    rotationPeriod = 604800000UL, // 7 days in milliseconds
                    rotationPeriodMsgs = 100UL, // 100 messages
                    historyVisibility = HistoryVisibility.SHARED,
                    onlyAllowTrustedDevices = false,
                    errorOnVerifiedUserProblem = false
                )

                // Check if we have devices for all room members before sharing keys
                val usersWithDevices = roomMembers.filter { userId ->
                    try {
                        val userDevices = machine.getUserDevices(userId, 30000U)
                        userDevices.isNotEmpty()
                    } catch (e: Exception) {
                        false
                    }
                }

                println("🔐 Users with available devices: ${usersWithDevices.size}/${roomMembers.size}")
                if (usersWithDevices.size < roomMembers.size) {
                    println("⚠️  Not all room members have devices available, but proceeding with available devices...")
                }

                println("🔐 Sharing room key with ${usersWithDevices.size} members...")
                val roomKeyRequests = machine.shareRoomKey(roomId, usersWithDevices, encryptionSettings)
                println("🔐 Room key requests generated: ${roomKeyRequests.size}")

                // If no room key requests were generated, try with all members anyway
                if (roomKeyRequests.isEmpty() && usersWithDevices.size < roomMembers.size) {
                    println("🔐 Retrying room key sharing with all members...")
                    val retryRoomKeyRequests = machine.shareRoomKey(roomId, roomMembers, encryptionSettings)
                    println("🔐 Retry room key requests generated: ${retryRoomKeyRequests.size}")
                    // Use retry results if they exist
                    if (retryRoomKeyRequests.isNotEmpty()) {
                        // Process retry requests
                        for (request in retryRoomKeyRequests) {
                            when (request) {
                                is Request.ToDevice -> {
                                    println("📤 Sending retry room key to-device request: ${request.eventType}")
                                    val roomKeyResponse = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
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
                                    if (roomKeyResponse.status != HttpStatusCode.OK) {
                                        println("❌ Failed to send retry room key: ${roomKeyResponse.status}")
                                    }
                                }
                                else -> {
                                    println("⚠️  Unhandled retry room key request type: ${request::class.simpleName}")
                                }
                            }
                        }
                    }
                }

                // Send room key sharing requests
                for (request in roomKeyRequests) {
                    when (request) {
                        is Request.ToDevice -> {
                            println("📤 Sending room key to-device request: ${request.eventType}")
                            val roomKeyResponse = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
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
                            if (roomKeyResponse.status != HttpStatusCode.OK) {
                                println("❌ Failed to send room key: ${roomKeyResponse.status}")
                            }
                        }
                        else -> {
                            println("⚠️  Unhandled room key request type: ${request::class.simpleName}")
                        }
                    }
                }

                // Step 5: Process any remaining requests
                val remainingRequests = machine.outgoingRequests()
                if (remainingRequests.isNotEmpty()) {
                    println("📤 Processing ${remainingRequests.size} remaining requests...")
                    for (request in remainingRequests) {
                        when (request) {
                            is Request.ToDevice -> {
                                val remainingToDeviceResponse = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
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
                                if (remainingToDeviceResponse.status != HttpStatusCode.OK) {
                                    println("❌ Failed to send remaining request: ${remainingToDeviceResponse.status}")
                                }
                            }
                            is Request.KeysUpload -> {
                                val remainingKeysUploadResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/upload") {
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
                                if (remainingKeysUploadResponse.status == HttpStatusCode.OK) {
                                    println("✅ Keys uploaded successfully")
                                } else {
                                    println("❌ Failed to upload keys: ${remainingKeysUploadResponse.status}")
                                }
                            }
                            is Request.KeysQuery -> {
                                val remainingKeysQueryResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/query") {
                                    bearerAuth(token)
                                    contentType(ContentType.Application.Json)
                                    val convertedUsers = convertMapToHashMap(request.users)
                                    if (convertedUsers is Map<*, *>) {
                                        @Suppress("UNCHECKED_CAST")
                                        val usersMap = convertedUsers as Map<String, Any>
                                        val deviceKeys = usersMap.mapValues { JsonArray(emptyList<JsonElement>()) }
                                        setBody(JsonObject(mapOf("device_keys" to JsonObject(deviceKeys))))
                                    } else {
                                        setBody(JsonObject(mapOf("device_keys" to JsonObject(emptyMap()))))
                                    }
                                }
                                if (remainingKeysQueryResponse.status != HttpStatusCode.OK) {
                                    println("❌ Failed to send keys query: ${remainingKeysQueryResponse.status}")
                                }
                            }
                            else -> {
                                println("⚠️  Unhandled remaining request type: ${request::class.simpleName}")
                            }
                        }
                    }
                }

                // Step 6: Final sync to ensure all keys are processed
                println("🔄 Final sync before encryption...")
                val syncResult = syncAndProcessToDevice(30000UL)
                if (!syncResult) {
                    println("⚠️  Final sync failed, but continuing...")
                }

                // Step 7: Encrypt the message
                println("� Encrypting message...")
                val messageContent = json.encodeToString(MessageContent("m.text", message))
                val encryptedContent = machine.encrypt(roomId, "m.room.message", messageContent)

                println("🔐 Sending encrypted message...")
                val encryptedMessageResponse = client.put("$currentHomeserver/_matrix/client/v3/rooms/$roomId/send/m.room.encrypted/${System.currentTimeMillis()}") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(json.parseToJsonElement(encryptedContent))
                }

                if (encryptedMessageResponse.status == HttpStatusCode.OK) {
                    println("✅ Encrypted message sent successfully")

                    // Process any final requests from the encrypt operation
                    val encryptRequests = machine.outgoingRequests()
                    if (encryptRequests.isNotEmpty()) {
                        println("📤 Processing ${encryptRequests.size} encrypt-generated requests...")
                        for (request in encryptRequests) {
                            when (request) {
                                is Request.ToDevice -> {
                                    val encryptToDeviceResponse = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
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
                                    if (encryptToDeviceResponse.status != HttpStatusCode.OK) {
                                        println("❌ Failed to send encrypt request: ${encryptToDeviceResponse.status}")
                                    }
                                }
                                is Request.KeysUpload -> {
                                    val encryptKeysUploadResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/upload") {
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
                                    if (encryptKeysUploadResponse.status == HttpStatusCode.OK) {
                                        println("✅ Keys uploaded successfully")
                                    } else {
                                        println("❌ Failed to upload keys: ${encryptKeysUploadResponse.status}")
                                    }
                                }
                                is Request.KeysQuery -> {
                                    val encryptKeysQueryResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/query") {
                                        bearerAuth(token)
                                        contentType(ContentType.Application.Json)
                                        val convertedUsers = convertMapToHashMap(request.users)
                                        if (convertedUsers is Map<*, *>) {
                                            @Suppress("UNCHECKED_CAST")
                                            val usersMap = convertedUsers as Map<String, Any>
                                            val deviceKeys = usersMap.mapValues { JsonArray(emptyList<JsonElement>()) }
                                            setBody(JsonObject(mapOf("device_keys" to JsonObject(deviceKeys))))
                                        } else {
                                            setBody(JsonObject(mapOf("device_keys" to JsonObject(emptyMap()))))
                                        }
                                    }
                                    if (encryptKeysQueryResponse.status != HttpStatusCode.OK) {
                                        println("❌ Failed to send keys query: ${encryptKeysQueryResponse.status}")
                                    }
                                }
                                else -> {
                                    println("⚠️  Unhandled encrypt request type: ${request::class.simpleName}")
                                }
                            }
                        }
                    }

                    return true
                } else {
                    println("❌ Failed to send encrypted message: ${encryptedMessageResponse.status}")
                    return false
                }
            } catch (e: Exception) {
                println("❌ Matrix SDK Crypto encryption failed: ${e.message}")
                e.printStackTrace()
                return false
            }
        } else {
            val response = client.put("$currentHomeserver/_matrix/client/v3/rooms/$roomId/send/m.room.message/${System.currentTimeMillis()}") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(SendMessageRequest(body = message))
            }
            return response.status == HttpStatusCode.OK
        }
    } catch (e: Exception) {
        println("Send message failed: ${e.message}")
        e.printStackTrace()
    }
    return false
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

        val roomKeyRequests = machine.shareRoomKey(roomId, roomMembers, encryptionSettings)
        println("🔐 Room key requests for setup: ${roomKeyRequests.size}")

        // Send room key requests
        for (request in roomKeyRequests) {
            when (request) {
                is Request.ToDevice -> {
                    val response = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
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
                        println("✅ Room key shared successfully")
                    }
                }
                else -> {
                    println("⚠️  Unhandled room key request type: ${request::class.simpleName}")
                }
            }
        }

        // Process any remaining requests
        val remainingRequests = machine.outgoingRequests()
        for (request in remainingRequests) {
            when (request) {
                is Request.ToDevice -> {
                    val response = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
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
                    val response = client.post("$currentHomeserver/_matrix/client/v3/keys/query") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        val convertedUsers = convertMapToHashMap(request.users)
                        if (convertedUsers is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val usersMap = convertedUsers as Map<String, Any>
                            val deviceKeys = usersMap.mapValues { JsonArray(emptyList<JsonElement>()) }
                            setBody(JsonObject(mapOf("device_keys" to JsonObject(deviceKeys))))
                        } else {
                            setBody(JsonObject(mapOf("device_keys" to JsonObject(emptyMap()))))
                        }
                    }
                    if (response.status == HttpStatusCode.OK) {
                        println("✅ Keys query sent successfully")
                    }
                }
                else -> {
                    println("⚠️  Unhandled remaining request type: ${request::class.simpleName}")
                }
            }
        }

        println("✅ Room encryption setup completed for $roomId")
        return true
    } catch (e: Exception) {
        println("❌ Failed to ensure room encryption for $roomId: ${e.message}")
        return false
    }

    try {
        // Request missing keys for the room
        println("🔑 Requesting missing keys for room: $roomId")

        // Get room members to query their keys
        val roomMembers = getRoomMembers(roomId).filter { it != currentUserId }

        if (roomMembers.isEmpty()) {
            println("⚠️  No other room members found")
            return false
        }

        // Mark users for tracking to ensure we get their device keys
        machine.updateTrackedUsers(roomMembers)
        println("✅ Marked ${roomMembers.size} users for tracking")

        // Debug: Check devices for each user
        for (userId in roomMembers) {
            try {
                val userDevices = machine.getUserDevices(userId, 30000U)
                println("🔍 User $userId has ${userDevices.size} devices: ${userDevices.joinToString(", ")}")
            } catch (e: Exception) {
                println("🔍 Could not get devices for $userId: ${e.message}")
            }
        }

        // Send keys query to get device keys for all room members
        val keysQueryRequest = machine.outgoingRequests()
        for (request in keysQueryRequest) {
            when (request) {
                is Request.KeysQuery -> {
                    println("📤 Sending keys query for room members")
                    val keysQueryResponse = client.post("$currentHomeserver/_matrix/client/v3/keys/query") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        val convertedUsers = convertMapToHashMap(request.users)
                        if (convertedUsers is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val usersMap = convertedUsers as Map<String, Any>
                            val deviceKeys = usersMap.mapValues { JsonArray(emptyList<JsonElement>()) }
                            setBody(JsonObject(mapOf("device_keys" to JsonObject(deviceKeys))))
                        } else {
                            setBody(JsonObject(mapOf("device_keys" to JsonObject(emptyMap()))))
                        }
                    }
                    if (keysQueryResponse.status == HttpStatusCode.OK) {
                        println("✅ Keys query sent successfully")
                    } else {
                        println("❌ Failed to send keys query: ${keysQueryResponse.status}")
                    }
                }
                else -> {
                    // Handle other requests
                }
            }
        }

        // Sync multiple times to get the device keys and any room keys
        println("🔄 Syncing multiple times to get keys...")
        repeat(5) { syncAttempt -> // Increased from 3 to 5
            println("🔄 Key sync attempt ${syncAttempt + 1}/5...")
            val syncResult = syncAndProcessToDevice(30000UL)
            if (!syncResult) {
                println("⚠️  Key sync ${syncAttempt + 1} failed")
            }
            kotlinx.coroutines.delay(2000) // Increased delay
        }

        // Try to share room key again to ensure all members have it
        val encryptionSettings = EncryptionSettings(
            algorithm = EventEncryptionAlgorithm.MEGOLM_V1_AES_SHA2,
            rotationPeriod = 604800000UL,
            rotationPeriodMsgs = 100UL,
            historyVisibility = HistoryVisibility.SHARED,
            onlyAllowTrustedDevices = false,
            errorOnVerifiedUserProblem = false
        )

        val roomKeyRequests = machine.shareRoomKey(roomId, roomMembers, encryptionSettings)
        println("🔐 Additional room key requests: ${roomKeyRequests.size}")

        // Send any new room key requests
        for (request in roomKeyRequests) {
            when (request) {
                is Request.ToDevice -> {
                    val response = client.put("$currentHomeserver/_matrix/client/v3/sendToDevice/${request.eventType}/${System.currentTimeMillis()}") {
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
                        println("✅ Additional room key shared")
                    }
                }
                else -> {
                    println("⚠️  Unhandled room key request type: ${request::class.simpleName}")
                }
            }
        }

        // Final sync to process any responses
        val finalSyncResult = syncAndProcessToDevice(30000UL)
        if (finalSyncResult) {
            println("✅ Key request process completed successfully")
            return true
        } else {
            println("⚠️  Final sync after key request failed")
            return false
        }

    } catch (e: Exception) {
        println("❌ Failed to request missing keys: ${e.message}")
        return false
    }
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

suspend fun startPeriodicSync() {
    while (true) {
        try {
            // Sync every 5 seconds for better responsiveness to key sharing
            kotlinx.coroutines.delay(5000)
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
        }
    }
}

fun main() = application {
    Window(onCloseRequest = { exitProcess(0) }, title = "FEVERDREAM - Matrix Client") {
        MatrixApp()
    }
}

@Composable
@Preview
fun MatrixApp() {
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Login) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // Load session on startup
    LaunchedEffect(Unit) {
        val session = loadSession()
        if (session != null && validateSession(session)) {
            currentUserId = session.userId
            currentDeviceId = session.deviceId
            currentAccessToken = session.accessToken
            currentHomeserver = session.homeserver
            currentSyncToken = session.syncToken
            initializeEncryption(session.userId, session.deviceId)
            currentScreen = Screen.Rooms
            // Start periodic sync
            scope.launch { startPeriodicSync() }
        }
    }

    MaterialTheme {
        when (currentScreen) {
            is Screen.Login -> LoginScreen(
                onLogin = { username, password, homeserver ->
                    scope.launch {
                        isLoading = true
                        loginError = null
                        try {
                            val result = login(username, password, homeserver)
                            if (result != null) {
                                currentScreen = Screen.Rooms
                                // Start periodic sync
                                scope.launch { startPeriodicSync() }
                            } else {
                                loginError = "Login failed"
                            }
                        } catch (e: Exception) {
                            loginError = e.message ?: "Login failed"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                error = loginError,
                isLoading = isLoading
            )
            is Screen.Rooms -> RoomsScreen(
                onRoomSelected = { roomId -> currentScreen = Screen.Chat(roomId) },
                onLogout = {
                    scope.launch {
                        clearSession()
                        currentAccessToken = null
                        currentUserId = null
                        currentDeviceId = null
                        olmMachine = null
                        currentScreen = Screen.Login
                    }
                }
            )
            is Screen.Chat -> {
                val roomId = (currentScreen as Screen.Chat).roomId
                ChatScreen(
                    roomId = roomId,
                    onBack = { currentScreen = Screen.Rooms }
                )
            }
        }
    }
}

@Composable
fun LoginScreen(
    onLogin: (String, String, String) -> Unit,
    error: String? = null,
    isLoading: Boolean = false
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var homeserver by remember { mutableStateOf("matrix.org") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("FEVERDREAM", style = MaterialTheme.typography.h4)
        Text("Matrix Client", style = MaterialTheme.typography.subtitle1)
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = homeserver,
            onValueChange = { homeserver = it },
            label = { Text("Homeserver") },
            modifier = Modifier.fillMaxWidth(0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = { onLogin(username, password, homeserver) },
                modifier = Modifier.fillMaxWidth(0.3f)
            ) {
                Text("Login")
            }
        }

        error?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colors.error)
        }
    }
}

@Composable
fun RoomsScreen(
    onRoomSelected: (String) -> Unit,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var rooms by remember { mutableStateOf<List<String>>(emptyList()) }
    var invites by remember { mutableStateOf<List<RoomInvite>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        scope.launch {
            rooms = getJoinedRooms()
            invites = getRoomInvites()
            isLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Rooms") },
            actions = {
                Button(onClick = onLogout) {
                    Text("Logout")
                }
            }
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (invites.isNotEmpty()) {
                    item {
                        Text("Room Invites", style = MaterialTheme.typography.h6, modifier = Modifier.padding(16.dp))
                    }
                    items(invites) { invite ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            elevation = 4.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Invite from ${invite.sender}")
                                    Text(invite.room_id, style = MaterialTheme.typography.caption)
                                }
                                Row {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                if (acceptRoomInvite(invite.room_id)) {
                                                    rooms = getJoinedRooms()
                                                    invites = getRoomInvites()
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Accept")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                rejectRoomInvite(invite.room_id)
                                                invites = getRoomInvites()
                                            }
                                        }
                                    ) {
                                        Text("Reject")
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Text("Joined Rooms", style = MaterialTheme.typography.h6, modifier = Modifier.padding(16.dp))
                }
                items(rooms) { roomId ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { onRoomSelected(roomId) },
                        elevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(roomId)
                                val isEncrypted = runBlocking { isRoomEncrypted(roomId) }
                                if (isEncrypted) {
                                    Text("🔒 Encrypted", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary)
                                }
                            }
                            Icon(Icons.Default.ArrowForward, contentDescription = "Enter room")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(
    roomId: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<Event>>(emptyList()) }
    var newMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(roomId) {
        scope.launch {
            messages = getRoomMessages(roomId)
            isLoading = false
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(roomId) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            }
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState
            ) {
                items(messages) { event ->
                    val isOwnMessage = event.sender == currentUserId
                    val messageContent = event.content as? JsonObject
                    val body = messageContent?.get("body")?.jsonPrimitive?.content ?: ""
                    val msgtype = messageContent?.get("msgtype")?.jsonPrimitive?.content ?: "m.text"

                    if (msgtype == "m.text" || msgtype == "m.bad.encrypted") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            elevation = 2.dp,
                            backgroundColor = if (isOwnMessage) MaterialTheme.colors.primary else MaterialTheme.colors.surface
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    event.sender,
                                    style = MaterialTheme.typography.caption,
                                    color = if (isOwnMessage) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface
                                )
                                Text(
                                    body,
                                    color = if (isOwnMessage) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newMessage,
                    onValueChange = { newMessage = it },
                    label = { Text("Message") },
                    modifier = Modifier.weight(1f),
                    enabled = !isSending
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Button(
                        onClick = {
                            if (newMessage.isNotBlank()) {
                                scope.launch {
                                    isSending = true
                                    if (sendMessage(roomId, newMessage)) {
                                        newMessage = ""
                                        // Refresh messages
                                        messages = getRoomMessages(roomId)
                                    }
                                    isSending = false
                                }
                            }
                        }
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

// Screen navigation enum
sealed class Screen {
    object Login : Screen()
    object Rooms : Screen()
    data class Chat(val roomId: String) : Screen()
}


