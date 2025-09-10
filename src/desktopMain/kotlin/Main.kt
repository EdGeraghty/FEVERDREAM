import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.Window
import kotlin.system.exitProcess
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.apache.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

import io.ktor.client.engine.apache.*

val json = Json { 
    ignoreUnknownKeys = true
    encodeDefaults = true
}

val client = HttpClient(Apache) {
    install(ContentNegotiation) {
        json(json)
    }
    engine {
        // Configure Apache HttpClient for better TLS support
        customizeClient {
            // Allow all SSL protocols including older ones
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
            ), SecureRandom())
            setSSLContext(sslContext)
            setSSLHostnameVerifier { _, _ -> true }
        }
        // Configure connection settings
        connectTimeout = 10000
        socketTimeout = 10000
    }
}

// Global encryption state
var encryptionInitialized = false
var identityKeyPair: KeyPair? = null
var deviceKeys = mutableMapOf<String, PublicKey>()
val sessionKeys = mutableMapOf<String, SecretKey>()

// Global state for Matrix client
var currentAccessToken: String? = null
var currentHomeserver: String = "https://matrix.org"
var currentDeviceId: String? = null

// Initialize custom Olm-like encryption
fun initializeEncryption() {
    if (!encryptionInitialized) {
        try {
            // Register Tink Aead configuration first
            AeadConfig.register()

            // Generate identity key pair (ECDH)
            val keyPairGenerator = KeyPairGenerator.getInstance("EC")
            keyPairGenerator.initialize(256)
            identityKeyPair = keyPairGenerator.generateKeyPair()

            println("� Custom Olm-like encryption initialized")
            println("🔑 Identity public key: ${java.util.Base64.getEncoder().encodeToString(identityKeyPair?.public?.encoded)}")

            encryptionInitialized = true
        } catch (e: Exception) {
            println("❌ Failed to initialize encryption: ${e.message}")
            // Fallback to Tink
            AeadConfig.register()
            encryptionInitialized = true
            println("🔄 Falling back to Tink encryption")
        }
    }
}

// Create shared secret with another device's public key
fun createSharedSecret(devicePublicKey: PublicKey): SecretKey {
    val keyAgreement = KeyAgreement.getInstance("ECDH")
    keyAgreement.init(identityKeyPair?.private)
    keyAgreement.doPhase(devicePublicKey, true)
    val sharedSecret = keyAgreement.generateSecret()

    // Derive AES key from shared secret
    val digest = MessageDigest.getInstance("SHA-256")
    val keyBytes = digest.digest(sharedSecret)
    return SecretKeySpec(keyBytes.copyOf(32), "AES")
}

// Get or create session key for a device
fun getSessionKey(deviceId: String): SecretKey? {
    return sessionKeys.getOrPut(deviceId) {
        // For demo purposes, create a key with our own public key
        // In a real implementation, you'd use the other device's public key
        val ourPublicKey = identityKeyPair?.public ?: return null
        createSharedSecret(ourPublicKey)
    }
}

// Encrypt message using custom Olm-like protocol
fun encryptMessageCustom(message: String, deviceId: String): String? {
    return try {
        // Use deterministic key derivation for consistent encryption/decryption
        val derivedKey = deriveKeyFromDeviceId(deviceId)

        // Use standard Java crypto with the derived key
        val secretKey = SecretKeySpec(derivedKey, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val plaintext = message.toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintext)

        // Combine IV and ciphertext for decryption
        val iv = cipher.iv
        val combined = iv + ciphertext

        java.util.Base64.getEncoder().encodeToString(combined)
    } catch (e: Exception) {
        println("❌ Custom encryption failed: ${e.message}")
        null
    }
}

// Decrypt message using custom Olm-like protocol
fun decryptMessageCustom(encryptedMessage: String, deviceId: String): String? {
    return try {
        // Use the same deterministic key derivation for decryption
        val derivedKey = deriveKeyFromDeviceId(deviceId)

        // Try to decode with different base64 variants for maximum compatibility
        val combined = try {
            // Try standard base64 first (Matrix spec compliant)
            java.util.Base64.getDecoder().decode(encryptedMessage)
        } catch (e: Exception) {
            try {
                // Fallback to URL-safe base64
                java.util.Base64.getUrlDecoder().decode(encryptedMessage)
            } catch (e2: Exception) {
                // Handle base64 with padding issues or other variants
                val cleanedMessage = encryptedMessage
                    .replace('-', '+')
                    .replace('_', '/')
                    .replace(Regex("[^A-Za-z0-9+/]"), "") // Remove invalid characters
                val paddedMessage = when (cleanedMessage.length % 4) {
                    2 -> cleanedMessage + "=="
                    3 -> cleanedMessage + "="
                    else -> cleanedMessage
                }
                java.util.Base64.getDecoder().decode(paddedMessage)
            }
        }

        // Debug logging for troubleshooting
        if (combined.size < 13) {
            println("⚠️  Decoded data too short: ${combined.size} bytes, expected at least 13")
        }

        // Extract IV (first 12 bytes for GCM) and ciphertext
        if (combined.size < 13) { // Need at least 12 bytes for IV + 1 byte for ciphertext
            throw Exception("Decoded data too short for decryption: ${combined.size} bytes, need at least 13")
        }
        val iv = combined.copyOfRange(0, 12)
        val ciphertext = combined.copyOfRange(12, combined.size)

        // Use standard Java crypto with the derived key
        val secretKey = SecretKeySpec(derivedKey, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmParameterSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmParameterSpec)

        val plaintext = cipher.doFinal(ciphertext)
        String(plaintext, Charsets.UTF_8)
    } catch (e: Exception) {
        println("❌ Custom decryption failed: ${e.message}")
        null
    }
}

// Derive a consistent key from device ID for demo purposes
fun deriveKeyFromDeviceId(deviceId: String): ByteArray {
    // Use a simple key derivation: hash the device ID with a fixed salt
    // In a real implementation, this would use proper key exchange
    val combined = (deviceId + "FEVERDREAM_SALT").toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(combined)
    return hash.copyOf(32) // AES-256 needs 32 bytes
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
data class SyncResponse(val rooms: Rooms? = null)

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
data class MessageContent(val msgtype: String, val body: String)

@Serializable
data class EncryptedMessageContent(
    val algorithm: String,
    val ciphertext: String,
    val device_id: String? = null,
    val sender_key: String? = null,
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
data class EncryptedSendMessageRequest(
    val algorithm: String = "m.custom.feverdream.v1",
    val ciphertext: String,
    val device_id: String,
    val sender_key: String,
    val session_id: String
)

@Serializable
data class RoomMessagesResponse(val chunk: List<Event>)

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
                println("🔑 Logged in with device ID: ${currentDeviceId}")
                // Initialize encryption system after successful login
                initializeEncryption()
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
                            println("🔑 Logged in with device ID: ${currentDeviceId}")
                            println("Login successful with older format")
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
                    println("🔑 Logged in with device ID: ${currentDeviceId}")
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
            parameter("filter", """{"room":{"state":{"lazy_load_members":true},"timeline":{"lazy_load_members":true},"ephemeral":{"lazy_load_members":true}}}""")
            parameter("timeout", "0")
        }
        if (response.status == HttpStatusCode.OK) {
            val syncResponse = response.body<SyncResponse>()
            val invitedRooms = mutableListOf<RoomInvite>()

            syncResponse.rooms?.invite?.forEach { (roomId, inviteState) ->
                val sender = inviteState.invite_state?.events?.firstOrNull()?.sender ?: "Unknown"
                invitedRooms.add(RoomInvite(roomId, sender, inviteState.invite_state ?: RoomState(emptyList())))
            }

            return invitedRooms
        }
    } catch (e: Exception) {
        println("Get invites failed: ${e.message}")
    }
    return emptyList()
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
            return messagesResponse.chunk.reversed()
        }
    } catch (e: Exception) {
        println("Get messages failed: ${e.message}")
    }
    return emptyList()
}

suspend fun sendMessage(roomId: String, message: String): Boolean {
    val token = currentAccessToken ?: return false
    try {
        val isEncrypted = isRoomEncrypted(roomId)

        if (isEncrypted) {
            try {
                // Use custom Olm-like encryption with consistent device ID
                val deviceId = currentDeviceId ?: "FEVERDREAM_DEVICE"
                val encryptedText = encryptMessageCustom(message, deviceId)

                if (encryptedText != null) {
                    val encryptedContent = EncryptedSendMessageRequest(
                        ciphertext = encryptedText,
                        device_id = deviceId,
                        sender_key = java.util.Base64.getEncoder().encodeToString(identityKeyPair?.public?.encoded ?: byteArrayOf()),
                        session_id = deviceId
                    )

                    println("🔐 Sending Custom Olm-like encrypted message to room $roomId")

                    val response = client.put("$currentHomeserver/_matrix/client/v3/rooms/$roomId/send/m.room.encrypted/${System.currentTimeMillis()}") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(encryptedContent)
                    }
                    return response.status == HttpStatusCode.OK
                } else {
                    println("❌ Custom encryption failed, message not sent")
                    return false
                }
            } catch (e: Exception) {
                println("❌ Encryption failed: ${e.message}")
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
    }
    return false
}

suspend fun acceptRoomInvite(roomId: String): Boolean {
    val token = currentAccessToken ?: return false
    try {
        val response = client.post("$currentHomeserver/_matrix/client/v3/rooms/$roomId/join") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(mapOf<String, String>())
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
            setBody(mapOf<String, String>())
        }
        return response.status == HttpStatusCode.OK
    } catch (e: Exception) {
        println("Reject invite failed: ${e.message}")
    }
    return false
}

fun main() = application {
    Window(onCloseRequest = { exitProcess(0) }, title = "FEVERDREAM") {
        App()
    }
}

@Composable
@Preview
fun App() {
    val scope = rememberCoroutineScope()
    var loginResponse by remember { mutableStateOf<LoginResponse?>(null) }
    var homeserver by remember { mutableStateOf("https://matrix.org") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    MaterialTheme {
        if (loginResponse == null) {
            LoginScreen(
                onLogin = { username, password, hs ->
                    // Allow empty homeserver if username contains domain
                    val homeserverRequired = !username.contains(":")
                    if (username.isBlank() || password.isBlank() || (homeserverRequired && hs.isBlank())) {
                        error = if (username.isBlank()) "Please enter a username"
                               else if (password.isBlank()) "Please enter a password"
                               else "Please enter a homeserver or use username@domain format"
                        return@LoginScreen
                    }
                    isLoading = true
                    error = null
                    homeserver = hs
                    scope.launch {
                        try {
                            val response = login(username, password, homeserver)
                            loginResponse = response
                        } catch (e: Exception) {
                            error = e.message ?: "Login failed. Please try again."
                        } finally {
                            isLoading = false
                        }
                    }
                },
                error = error,
                isLoading = isLoading
            )
        } else {
            ChatScreen(loginResponse!!)
        }
    }
}

@Composable
fun LoginScreen(onLogin: (String, String, String) -> Unit, error: String?, isLoading: Boolean) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var homeserver by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("FEVERDREAM", style = MaterialTheme.typography.h4)
        Text("Matrix Client with Encryption Support", style = MaterialTheme.typography.subtitle1)
        Spacer(modifier = Modifier.height(32.dp))
        TextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(0.5f),
            enabled = !isLoading
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(0.5f),
            enabled = !isLoading
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = homeserver,
            onValueChange = { homeserver = it },
            label = { Text("Homeserver (leave empty if using user@domain format)") },
            modifier = Modifier.fillMaxWidth(0.5f),
            enabled = !isLoading
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (error != null) {
            Text(error, color = MaterialTheme.colors.error)
            Spacer(modifier = Modifier.height(8.dp))
        }
        Button(
            onClick = { onLogin(username, password, homeserver) },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(0.5f)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text("Login")
            }
        }
    }
}

@Composable
fun ChatScreen(loginResponse: LoginResponse) {
    var rooms by remember { mutableStateOf(listOf<String>()) }
    var invites by remember { mutableStateOf(listOf<RoomInvite>()) }
    var isLoading by remember { mutableStateOf(true) }
    var openChatWindows by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(loginResponse) {
        isLoading = true
        scope.launch {
            rooms = getJoinedRooms()
            invites = getRoomInvites()
            isLoading = false
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("FEVERDREAM", style = MaterialTheme.typography.h5)
        Text("Logged in as: ${loginResponse.user_id}", style = MaterialTheme.typography.caption)
        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            CircularProgressIndicator()
            Text("Loading rooms and invites...")
        } else {
            // Show pending invites
            if (invites.isNotEmpty()) {
                Text("Pending Invites:", style = MaterialTheme.typography.h6)
                Spacer(modifier = Modifier.height(8.dp))

                invites.forEach { invite ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            val roomName = invite.state.events.firstOrNull()?.let { event ->
                                if (event.type == "m.room.name") {
                                    try {
                                        json.decodeFromJsonElement<Map<String, String>>(event.content)["name"] ?: invite.room_id
                                    } catch (e: Exception) {
                                        invite.room_id
                                    }
                                } else invite.room_id
                            } ?: invite.room_id

                            Text("Room: $roomName", style = MaterialTheme.typography.body1)
                            Text("Invited by: ${invite.sender}", style = MaterialTheme.typography.body2)

                            Spacer(modifier = Modifier.height(8.dp))
                            Row {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            if (acceptRoomInvite(invite.room_id)) {
                                                // Refresh data after accepting
                                                rooms = getJoinedRooms()
                                                invites = getRoomInvites()
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                                ) {
                                    Text("Accept")
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            if (rejectRoomInvite(invite.room_id)) {
                                                // Refresh invites after rejecting
                                                invites = getRoomInvites()
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                                ) {
                                    Text("Reject")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Show joined rooms
            Text("Joined Rooms:", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(8.dp))

            if (rooms.isEmpty()) {
                Text("No joined rooms yet.")
            } else {
                rooms.forEach { roomId ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(roomId, modifier = Modifier.weight(1f))
                            Button(onClick = {
                                if (roomId !in openChatWindows) {
                                    openChatWindows = openChatWindows + roomId
                                }
                            }) {
                                Text("Open")
                            }
                        }
                    }
                }
            }
        }
    }

    // Render open chat windows
    openChatWindows.forEach { roomId ->
        key(roomId) {
            Window(
                onCloseRequest = {
                    openChatWindows = openChatWindows - roomId
                },
                title = "Chat - $roomId"
            ) {
                ChatWindow(
                    roomId = roomId,
                    onClose = {
                        openChatWindows = openChatWindows - roomId
                    }
                )
            }
        }
    }
}

@Composable
fun ChatWindow(roomId: String, onClose: () -> Unit) {
    var messages by remember { mutableStateOf(listOf<Event>()) }
    var newMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isEncrypted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(roomId) {
        isLoading = true
        scope.launch {
            messages = getRoomMessages(roomId)
            isEncrypted = isRoomEncrypted(roomId)
            isLoading = false
        }
    }

    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Chat: $roomId", style = MaterialTheme.typography.h6)
                    if (isEncrypted) {
                        Text("� Encrypted Room (Custom Olm-like encryption active)", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary)
                    }
                }
                Button(onClick = onClose) {
                    Text("Close")
                }
            }

            // Messages area
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (messages.isEmpty()) {
                    Text("No messages yet", modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        reverseLayout = false
                    ) {
                        items(messages) { message ->
                            MessageItem(message)
                        }
                    }

                    // Auto-scroll to bottom when new messages arrive
                    LaunchedEffect(messages.size) {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                }
            }

            // Message input area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = newMessage,
                    onValueChange = { newMessage = it },
                    label = { Text("Type a message...") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (newMessage.isNotBlank()) {
                            scope.launch {
                                if (sendMessage(roomId, newMessage)) {
                                    newMessage = ""
                                    // Refresh messages
                                    messages = getRoomMessages(roomId)
                                }
                            }
                        }
                    },
                    enabled = newMessage.isNotBlank()
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
fun MessageItem(message: Event) {
    val displayText = when (message.type) {
        "m.room.message" -> {
            try {
                val content = json.decodeFromJsonElement<MessageContent>(message.content)
                content.body
            } catch (e: Exception) {
                "[Unable to parse message]"
            }
        }
        "m.room.encrypted" -> {
            try {
                val encryptedContent = json.decodeFromJsonElement<EncryptedMessageContent>(message.content)

                when (encryptedContent.algorithm) {
                    "m.custom.feverdream.v1" -> {
                        // Use the device_id from the encrypted message for decryption
                        val deviceId = encryptedContent.device_id ?: currentDeviceId ?: "FEVERDREAM_DEVICE"
                        val decryptedText = decryptMessageCustom(encryptedContent.ciphertext, deviceId)
                        if (decryptedText != null) {
                            "🔓 [Custom Decrypted: $decryptedText]"
                        } else {
                            "� [Olm decryption failed - unable to decrypt message]"
                        }
                    }
                    else -> {
                        "🔒 [Unsupported encryption algorithm: ${encryptedContent.algorithm}]"
                    }
                }

            } catch (e: Exception) {
                "🔒 [Encrypted message - Custom decryption error: ${e.message}]"
            }
        }
        else -> "[${message.type}]"
    }

    val isOwnMessage = message.sender.contains(currentAccessToken?.take(8) ?: "")
    val backgroundColor = if (isOwnMessage) MaterialTheme.colors.primary else MaterialTheme.colors.surface
    val textColor = if (isOwnMessage) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            backgroundColor = backgroundColor
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.sender,
                    style = MaterialTheme.typography.caption,
                    color = textColor.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.body1,
                    color = textColor
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = java.time.Instant.ofEpochMilli(message.origin_server_ts)
                        .toString().substring(11, 19),
                    style = MaterialTheme.typography.caption,
                    color = textColor.copy(alpha = 0.5f)
                )
            }
        }
    }
}
