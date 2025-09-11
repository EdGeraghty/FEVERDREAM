package ui

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
import kotlinx.coroutines.*
import models.*
import network.*
import crypto.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Screen navigation enum
sealed class Screen {
    object Login : Screen()
    object Rooms : Screen()
    data class Chat(val roomId: String) : Screen()
}

@Composable
fun MatrixApp() {
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Login) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isPeriodicSyncRunning by remember { mutableStateOf(false) }

    // Load session on startup
    LaunchedEffect(Unit) {
        val session = loadSession()
        if (session != null && validateSession(session)) {
            currentUserId = session.userId
            currentDeviceId = session.deviceId
            currentAccessToken = session.accessToken
            currentHomeserver = session.homeserver
            currentSyncToken = session.syncToken
            crypto.initializeEncryption(session.userId, session.deviceId)
            currentScreen = Screen.Rooms
            // Start periodic sync only if not already running
            if (!isPeriodicSyncRunning) {
                isPeriodicSyncRunning = true
                scope.launch { crypto.startPeriodicSync() }
            }
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
                                // Start periodic sync only if not already running
                                if (!isPeriodicSyncRunning) {
                                    isPeriodicSyncRunning = true
                                    scope.launch { crypto.startPeriodicSync() }
                                }
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
                        // Properly dispose of OlmMachine resources
                        olmMachine?.let { machine ->
                            try {
                                // Close the OlmMachine to free native resources
                                (machine as? AutoCloseable)?.close()
                                println("✅ OlmMachine resources cleaned up")
                            } catch (e: Exception) {
                                println("⚠️  Error cleaning up OlmMachine: ${e.message}")
                            }
                        }
                        olmMachine = null
                        isPeriodicSyncRunning = false  // Reset periodic sync flag
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
    var homeserver by remember { mutableStateOf("") }
    var detectedServer by remember { mutableStateOf<String?>(null) }
    var showServerField by remember { mutableStateOf(false) }

    // Auto-detect server from username
    LaunchedEffect(username) {
        if (username.isNotEmpty()) {
            val cleanUsername = username.removePrefix("@")
            if (cleanUsername.contains(":")) {
                val domain = cleanUsername.split(":").last()
                detectedServer = "https://$domain"
            } else if (detectedServer == null) {
                detectedServer = "https://matrix.org"
            }
        }
    }

    val effectiveHomeserver = when {
        homeserver.isNotBlank() -> homeserver
        detectedServer != null -> detectedServer!!
        else -> "matrix.org"
    }

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
            placeholder = { Text("user or user:server.com") },
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

        // Server detection info
        if (detectedServer != null && !showServerField) {
            Row(
                modifier = Modifier.fillMaxWidth(0.5f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Server: ${detectedServer?.removePrefix("https://")}",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.primary
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { showServerField = true }) {
                    Text("Change", style = MaterialTheme.typography.caption)
                }
            }
        }

        // Server field (shown when user wants to change or if no detection)
        if (showServerField || detectedServer == null) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = homeserver,
                onValueChange = { homeserver = it },
                label = { Text("Homeserver (optional)") },
                placeholder = { Text("matrix.org or https://your-server.com") },
                modifier = Modifier.fillMaxWidth(0.5f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = { onLogin(username, password, effectiveHomeserver) },
                modifier = Modifier.fillMaxWidth(0.3f),
                enabled = username.isNotBlank() && password.isNotBlank()
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
            
            // Removed: Encryption setup for all rooms on load - too slow and blocks UI
            // Only set up encryption when actually needed (entering room or sending message)
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
                                // TODO: Show encryption status asynchronously to avoid UI freezing
                                // val isEncrypted = runBlocking { crypto.isRoomEncrypted(roomId) }
                                // if (isEncrypted) {
                                //     Text("🔒 Encrypted", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary)
                                // }
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
            // Removed: ensureRoomEncryption call - too slow for UI initialization
            // Only set up encryption when actually sending a message
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
                    val body = (messageContent?.get("body") as? JsonPrimitive)?.content ?: ""
                    val msgtype = (messageContent?.get("msgtype") as? JsonPrimitive)?.content ?: "m.text"

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
                                    // Ensure encryption is set up before sending message
                                    crypto.ensureRoomEncryption(roomId)
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
