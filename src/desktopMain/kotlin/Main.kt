import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.key
import androidx.compose.ui.window.Window
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import javax.net.ssl.X509TrustManager

val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    engine {
        https {
            // Allow older TLS versions for compatibility
            trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
        }
    }
}

@Serializable
data class LoginRequest(val type: String = "m.login.password", val user: String, val password: String)

@Serializable
data class LoginRequestV2(val type: String = "m.login.password", val identifier: Identifier, val password: String)

@Serializable
data class Identifier(val type: String = "m.id.user", val user: String)

@Serializable
data class LoginResponse(val access_token: String)

@Serializable
data class LoginFlowsResponse(val flows: List<LoginFlow>)

@Serializable
data class LoginFlow(val type: String)

@Serializable
data class JoinedRoomsResponse(val joined_rooms: List<String>)

@Serializable
data class RoomInvite(val room_id: String, val sender: String, val state: RoomState)

@Serializable
data class RoomState(val events: List<StateEvent>)

@Serializable
data class StateEvent(val type: String, val state_key: String, val sender: String, val content: RoomNameContent)

@Serializable
data class RoomNameContent(val name: String? = null)

@Serializable
data class SyncResponse(val rooms: Rooms? = null)

@Serializable
data class Rooms(val invite: Map<String, InvitedRoom>? = null)

@Serializable
data class InvitedRoom(val invite_state: RoomState? = null)

@Serializable
data class MessageEvent(val type: String, val event_id: String, val sender: String, val origin_server_ts: Long, val content: MessageContent)

@Serializable
data class MessageContent(val msgtype: String, val body: String)

@Serializable
data class SendMessageRequest(val msgtype: String = "m.text", val body: String)

@Serializable
data class RoomMessagesResponse(val chunk: List<MessageEvent>)

suspend fun getRoomMessages(homeserver: String, accessToken: String, roomId: String): List<MessageEvent> {
    try {
        val response = client.get("$homeserver/_matrix/client/v3/rooms/$roomId/messages") {
            bearerAuth(accessToken)
            parameter("limit", "50")
            parameter("dir", "b")
        }
        if (response.status == HttpStatusCode.OK) {
            val messagesResponse = response.body<RoomMessagesResponse>()
            return messagesResponse.chunk.reversed() // Reverse to show oldest first
        }
    } catch (e: Exception) {
        println("Get messages failed: ${e.message}")
    }
    return emptyList()
}

suspend fun sendMessage(homeserver: String, accessToken: String, roomId: String, message: String): Boolean {
    try {
        val response = client.put("$homeserver/_matrix/client/v3/rooms/$roomId/send/m.room.message/${System.currentTimeMillis()}") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(body = message))
        }
        return response.status == HttpStatusCode.OK
    } catch (e: Exception) {
        println("Send message failed: ${e.message}")
    }
    return false
}

suspend fun login(username: String, password: String, homeserver: String): String? {
    try {
        // Ensure homeserver has https
        val cleanHomeserver = if (homeserver.startsWith("http://")) {
            homeserver.replace("http://", "https://")
        } else if (!homeserver.startsWith("https://")) {
            "https://$homeserver"
        } else {
            homeserver
        }

        // Clean username - remove @ if present
        val cleanUsername = username.removePrefix("@")

        // Parse username to separate user and server parts
        val (userPart, userServerPart) = if (cleanUsername.contains(":")) {
            val parts = cleanUsername.split(":", limit = 2)
            parts[0] to parts[1]
        } else {
            cleanUsername to null
        }

        val serverDomain = cleanHomeserver.removePrefix("https://").removePrefix("http://")
        println("Parsed username: user='$userPart', userServer='${userServerPart ?: "none"}', homeserverDomain='$serverDomain'")

        // Try different login formats
        val loginAttempts = mutableListOf<Any>()

        // Format 1: Simple user field (just the localpart)
        loginAttempts.add(LoginRequest(user = userPart, password = password))

        // Format 2: With identifier object (just the localpart)
        loginAttempts.add(LoginRequestV2(identifier = Identifier(user = userPart), password = password))

        // Only add server-specific formats if the username didn't already include a server
        if (userServerPart == null) {
            // Format 3: Full user ID with server
            loginAttempts.add(LoginRequest(user = "$userPart:$serverDomain", password = password))

            // Format 4: Full user ID with identifier
            loginAttempts.add(LoginRequestV2(identifier = Identifier(user = "$userPart:$serverDomain"), password = password))
        } else {
            // If username already has server, use it as-is
            loginAttempts.add(LoginRequest(user = cleanUsername, password = password))
            loginAttempts.add(LoginRequestV2(identifier = Identifier(user = cleanUsername), password = password))
        }

        // Format 5: Very basic map format
        loginAttempts.add(mapOf(
            "type" to "m.login.password",
            "user" to userPart,
            "password" to password
        ))

        for ((index, loginRequest) in loginAttempts.withIndex()) {
            println("Attempting login format ${index + 1}: $loginRequest")

            try {
                val response = client.post("$cleanHomeserver/_matrix/client/v3/login") {
                    contentType(ContentType.Application.Json)
                    setBody(loginRequest)
                }

                println("Login format ${index + 1} response status: ${response.status}")

                if (response.status == HttpStatusCode.OK) {
                    val loginResponse = response.body<LoginResponse>()
                    return loginResponse.access_token
                } else if (response.status == HttpStatusCode.Unauthorized) {
                    // Wrong credentials, don't try other formats
                    throw Exception("Invalid username or password")
                } else if (response.status == HttpStatusCode.Forbidden) {
                    // Account might be deactivated or other auth issue
                    throw Exception("Login forbidden - check your account status")
                }
                // Continue to next format for other errors

            } catch (e: Exception) {
                println("Login format ${index + 1} failed: ${e.message}")
                if (e.message?.contains("Invalid username or password") == true ||
                    e.message?.contains("Login forbidden") == true) {
                    throw e // Don't continue if credentials are wrong
                }
                // Continue to next format
            }
        }

        throw Exception("All login formats failed - server may have custom requirements")

    } catch (e: Exception) {
        println("Login failed: ${e.message}")
        throw e
    }
}

suspend fun getJoinedRooms(homeserver: String, accessToken: String): List<String> {
    try {
        val response = client.get("$homeserver/_matrix/client/v3/joined_rooms") {
            bearerAuth(accessToken)
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

suspend fun getRoomInvites(homeserver: String, accessToken: String): List<RoomInvite> {
    try {
        // Use sync endpoint to get invited rooms
        val response = client.get("$homeserver/_matrix/client/v3/sync") {
            bearerAuth(accessToken)
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

suspend fun acceptRoomInvite(homeserver: String, accessToken: String, roomId: String): Boolean {
    try {
        val response = client.post("$homeserver/_matrix/client/v3/rooms/$roomId/join") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(mapOf<String, String>())
        }
        return response.status == HttpStatusCode.OK
    } catch (e: Exception) {
        println("Accept invite failed: ${e.message}")
    }
    return false
}

suspend fun rejectRoomInvite(homeserver: String, accessToken: String, roomId: String): Boolean {
    try {
        val response = client.post("$homeserver/_matrix/client/v3/rooms/$roomId/leave") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(mapOf<String, String>())
        }
        return response.status == HttpStatusCode.OK
    } catch (e: Exception) {
        println("Reject invite failed: ${e.message}")
    }
    return false
}

suspend fun getLoginFlows(homeserver: String): List<String> {
    try {
        val cleanHomeserver = if (homeserver.startsWith("http://")) {
            homeserver.replace("http://", "https://")
        } else if (!homeserver.startsWith("https://")) {
            "https://$homeserver"
        } else {
            homeserver
        }

        val response = client.get("$cleanHomeserver/_matrix/client/v3/login")
        if (response.status == HttpStatusCode.OK) {
            val flowsResponse = response.body<LoginFlowsResponse>()
            return flowsResponse.flows.map { it.type }
        }
    } catch (e: Exception) {
        println("Failed to get login flows: ${e.message}")
    }
    return emptyList()
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "FEVERDREAM") {
        App()
    }
}

@Composable
@Preview
fun App() {
    val scope = rememberCoroutineScope()
    var accessToken by remember { mutableStateOf<String?>(null) }
    var homeserver by remember { mutableStateOf("https://matrix.org") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    MaterialTheme {
        if (accessToken == null) {
            LoginScreen(
                onLogin = { username, password, hs ->
                    if (username.isBlank() || password.isBlank() || hs.isBlank()) {
                        error = "Please fill in all fields"
                        return@LoginScreen
                    }
                    isLoading = true
                    error = null
                    homeserver = hs
                    scope.launch {
                        try {
                            val token = login(username, password, homeserver)
                            accessToken = token
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
            ChatScreen(accessToken!!, homeserver)
        }
    }
}

@Composable
fun LoginScreen(onLogin: (String, String, String) -> Unit, error: String?, isLoading: Boolean) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var homeserver by remember { mutableStateOf("https://matrix.org") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("FEVERDREAM", style = MaterialTheme.typography.h4)
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
            label = { Text("Homeserver") },
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
fun ChatScreen(accessToken: String, homeserver: String) {
    var rooms by remember { mutableStateOf(listOf<String>()) }
    var invites by remember { mutableStateOf(listOf<RoomInvite>()) }
    var isLoading by remember { mutableStateOf(true) }
    var openChatWindows by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(accessToken) {
        isLoading = true
        scope.launch {
            rooms = getJoinedRooms(homeserver, accessToken)
            invites = getRoomInvites(homeserver, accessToken)
            isLoading = false
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("FEVERDREAM", style = MaterialTheme.typography.h5)
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
                            val roomName = invite.state.events.firstOrNull()?.content?.name ?: invite.room_id
                            Text("Room: $roomName", style = MaterialTheme.typography.body1)
                            Text("Invited by: ${invite.sender}", style = MaterialTheme.typography.body2)

                            Spacer(modifier = Modifier.height(8.dp))
                            Row {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            if (acceptRoomInvite(homeserver, accessToken, invite.room_id)) {
                                                // Refresh data after accepting
                                                rooms = getJoinedRooms(homeserver, accessToken)
                                                invites = getRoomInvites(homeserver, accessToken)
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
                                            if (rejectRoomInvite(homeserver, accessToken, invite.room_id)) {
                                                // Refresh invites after rejecting
                                                invites = getRoomInvites(homeserver, accessToken)
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
                    homeserver = homeserver,
                    accessToken = accessToken,
                    onClose = {
                        openChatWindows = openChatWindows - roomId
                    }
                )
            }
        }
    }
}

@Composable
fun ChatWindow(roomId: String, homeserver: String, accessToken: String, onClose: () -> Unit) {
    var messages by remember { mutableStateOf(listOf<MessageEvent>()) }
    var newMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(roomId) {
        isLoading = true
        scope.launch {
            messages = getRoomMessages(homeserver, accessToken, roomId)
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
                Text("Chat: $roomId", style = MaterialTheme.typography.h6, modifier = Modifier.weight(1f))
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
                                if (sendMessage(homeserver, accessToken, roomId, newMessage)) {
                                    newMessage = ""
                                    // Refresh messages
                                    messages = getRoomMessages(homeserver, accessToken, roomId)
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
fun MessageItem(message: MessageEvent) {
    val isOwnMessage = message.sender == "You" // This would need to be updated with actual user ID
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
                    text = message.content.body,
                    style = MaterialTheme.typography.body1,
                    color = textColor
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = java.time.Instant.ofEpochMilli(message.origin_server_ts)
                        .toString().substring(11, 19), // Show time only
                    style = MaterialTheme.typography.caption,
                    color = textColor.copy(alpha = 0.5f)
                )
            }
        }
    }
}
