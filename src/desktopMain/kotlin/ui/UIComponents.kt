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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
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
    // Use GlobalScope for long-running operations to avoid composition cancellation
    val appScope = remember { kotlinx.coroutines.GlobalScope }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Login) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isPeriodicSyncRunning by remember { mutableStateOf(false) }

    println("🎨 MatrixApp composable called, currentScreen: $currentScreen")

    // Load session on startup
    LaunchedEffect(Unit) {
        println("🔄 Loading session on startup...")
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
                appScope.launch { crypto.startPeriodicSync() }
            }
        }
    }

    MaterialTheme {
        when (currentScreen) {
            is Screen.Login -> LoginScreen(
                onLogin = { username, password, homeserver ->
                    appScope.launch {
                        isLoading = true
                        loginError = null
                        try {
                            val result = login(username, password, homeserver)
                            if (result != null) {
                                currentScreen = Screen.Rooms
                                // Start periodic sync only if not already running
                                if (!isPeriodicSyncRunning) {
                                    isPeriodicSyncRunning = true
                                    appScope.launch { crypto.startPeriodicSync() }
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
                    appScope.launch {
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

    // Focus requesters for tab navigation
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val homeserverFocus = remember { FocusRequester() }
    val loginButtonFocus = remember { FocusRequester() }

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
        else -> ""  // Don't auto-fill from detected server, let login() handle discovery
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
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .focusRequester(usernameFocus)
                .onKeyEvent { event ->
                    if (event.key == Key.Tab) {
                        passwordFocus.requestFocus()
                        true
                    } else {
                        false
                    }
                }
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .focusRequester(passwordFocus)
                .onKeyEvent { event ->
                    if (event.key == Key.Tab) {
                        if (showServerField || detectedServer == null) {
                            homeserverFocus.requestFocus()
                        } else {
                            loginButtonFocus.requestFocus()
                        }
                        true
                    } else {
                        false
                    }
                }
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
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .focusRequester(homeserverFocus)
                    .onKeyEvent { event ->
                        if (event.key == Key.Tab) {
                            loginButtonFocus.requestFocus()
                            true
                        } else {
                            false
                        }
                    }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = { onLogin(username, password, effectiveHomeserver) },
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .focusRequester(loginButtonFocus),
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
    // Use GlobalScope for long-running operations
    val scope = remember { kotlinx.coroutines.GlobalScope }
    var rooms by remember { mutableStateOf<List<String>>(emptyList()) }
    var roomEncryptionStatus by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var invites by remember { mutableStateOf<List<RoomInvite>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        scope.launch {
            rooms = getJoinedRooms()
            // Load encryption status for each room
            val encryptionMap = mutableMapOf<String, Boolean>()
            rooms.forEach { roomId ->
                val isEncrypted = crypto.isRoomEncrypted(roomId)
                encryptionMap[roomId] = isEncrypted
            }
            roomEncryptionStatus = encryptionMap
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
                                // Show encryption status
                                val isEncrypted = roomEncryptionStatus[roomId] ?: false
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
    println("💬 ChatScreen called for room: $roomId")
    // Use GlobalScope for long-running operations to avoid composition cancellation
    val scope = remember { kotlinx.coroutines.GlobalScope }
    var messages by remember { mutableStateOf<List<Event>>(emptyList()) }
    var newMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    println("📝 ChatScreen state initialized: messages=${messages.size}, newMessage='$newMessage', isLoading=$isLoading, isSending=$isSending")

    // Periodic refresh to check for new messages from cache - less aggressive
    LaunchedEffect(roomId) {
        while (true) {
            try {
                val cachedMessages = crypto.roomMessageCache[roomId] ?: emptyList()
                if (cachedMessages.size != messages.size && !isSending) {
                    // Only refresh if we're not currently sending a message
                    println("🔄 Refreshing messages from cache: ${cachedMessages.size} vs ${messages.size}")
                    messages = cachedMessages.toList() // Use cached messages directly to avoid API calls
                }
            } catch (e: Exception) {
                println("⚠️  Error during periodic refresh: ${e.message}")
            }
            kotlinx.coroutines.delay(5000) // Check every 5 seconds instead of 2
        }
    }

    LaunchedEffect(roomId) {
        scope.launch {
            println("🔄 ChatScreen: Loading messages for room $roomId")
            // ensureRoomEncryption call - too slow for UI initialization
            // Only set up encryption when actually sending a message
            messages = getRoomMessages(roomId)
            println("✅ ChatScreen: Loaded ${messages.size} messages for room $roomId")
            isLoading = false
            println("✅ ChatScreen: Loading complete, isLoading = false")
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
                                if (msgtype == "m.bad.encrypted" || body.contains("Can't find the room key")) {
                                    Text(
                                        "🔒 Encrypted message (waiting for room keys from other devices)",
                                        color = MaterialTheme.colors.secondary,
                                        style = MaterialTheme.typography.body2
                                    )
                                } else {
                                    Text(
                                        body,
                                        color = if (isOwnMessage) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface
                                    )
                                }
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
                            println("🔘 Send button clicked, message: '$newMessage'")
                            if (newMessage.isNotBlank()) {
                                println("📤 Starting send message process...")
                                scope.launch {
                                    isSending = true
                                    println("🔄 Set isSending = true")
                                    try {
                                        // Quick check: see if we can encrypt without full setup
                                        val canEncrypt = crypto.canEncryptRoom(roomId)
                                        if (canEncrypt) {
                                            println("✅ Can encrypt - room key available")
                                        } else {
                                            println("⚠️  Cannot encrypt - need room key")
                                        }

                                        if (canEncrypt) {
                                            // Skip encryption setup - we've already verified it's available
                                            println("📤 Calling sendMessage (skipping encryption setup)...")
                                            val sendResult = sendMessage(roomId, newMessage, skipEncryptionSetup = true)
                                            println("📤 sendMessage returned: $sendResult")
                                            
                                            if (sendResult) {
                                                println("✅ Message sent successfully, clearing newMessage")
                                                newMessage = ""
                                                // Refresh messages
                                                messages = getRoomMessages(roomId)
                                            } else {
                                                println("❌ Message sending failed")
                                            }
                                        } else {
                                            // Only do full encryption setup if needed
                                            println("🔐 Room key not available, doing full encryption setup...")
                                            val encryptionResult = withTimeout(15000L) { // 15 second timeout
                                                println("🔐 Calling ensureRoomEncryption...")
                                                crypto.ensureRoomEncryption(roomId)
                                            }
                                            println("🔐 ensureRoomEncryption returned: $encryptionResult")
                                            
                                            if (encryptionResult) {
                                                println("📤 Calling sendMessage...")
                                                val sendResult = sendMessage(roomId, newMessage)
                                                println("📤 sendMessage returned: $sendResult")
                                                
                                                if (sendResult) {
                                                    println("✅ Message sent successfully, clearing newMessage")
                                                    newMessage = ""
                                                    // Refresh messages
                                                    messages = getRoomMessages(roomId)
                                                } else {
                                                    println("❌ Message sending failed")
                                                }
                                            } else {
                                                println("❌ ensureRoomEncryption failed, not sending message")
                                            }
                                        }
                                    } catch (e: TimeoutCancellationException) {
                                        println("❌ ensureRoomEncryption timed out after 15 seconds")
                                    } catch (e: Exception) {
                                        println("❌ Error during message sending: ${e.message}")
                                        e.printStackTrace()
                                    } finally {
                                        println("🔄 Resetting isSending to false")
                                        isSending = false
                                    }
                                }
                            } else {
                                println("⚠️  Message is blank, not sending")
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
