package ui

import network.getRoomMessages
import network.currentUserId
import network.sendMessage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.TimeoutCancellationException
import models.Event
import network.*
import crypto.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun ChatScreen(
    roomId: String,
    onBack: () -> Unit
) {
    println("💬 ChatScreen called for room: $roomId")
    // Use rememberCoroutineScope for coroutine operations in composables
    val scope = rememberCoroutineScope()
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
                if (cachedMessages.size != messages.size && !isSending && !isLoading) {
                    // Only refresh if we're not currently sending a message or loading
                    println("🔄 Refreshing messages from cache: ${cachedMessages.size} vs ${messages.size}")
                    messages = cachedMessages.toList() // Use cached messages directly to avoid API calls
                }
            } catch (e: Exception) {
                println("⚠️  Error during periodic refresh: ${e.message}")
            }
            kotlinx.coroutines.delay(10000) // Check every 10 seconds instead of 5
        }
    }

    LaunchedEffect(roomId) {
        scope.launch {
            try {
                println("🔄 ChatScreen: Loading messages for room $roomId")
                isLoading = true // Ensure loading state is set
                println("🔄 ChatScreen: Set isLoading = true")

                // Check cache first
                val cachedMessages = crypto.roomMessageCache[roomId]
                if (cachedMessages != null && cachedMessages.isNotEmpty()) {
                    println("📋 ChatScreen: Using cached messages: ${cachedMessages.size}")
                    messages = cachedMessages.toList()
                    isLoading = false
                    println("✅ ChatScreen: Loading complete from cache, isLoading = false")
                } else {
                    println("🌐 ChatScreen: No cached messages, fetching from API...")
                    // Add timeout to prevent hanging on network issues
                    val loadedMessages = withTimeout(20000L) { // 20 second timeout
                        println("⏱️ ChatScreen: Starting getRoomMessages with 20s timeout")
                        getRoomMessages(roomId)
                    }
                    messages = loadedMessages
                    println("✅ ChatScreen: Loaded ${messages.size} messages for room $roomId")
                    isLoading = false
                    println("✅ ChatScreen: Loading complete, isLoading = false")
                }

                // Proactively ensure encryption is set up for this room
                // This creates a fresh outbound session so future messages can be encrypted/decrypted
                scope.launch {
                    try {
                        println("🔐 Proactively setting up encryption for room $roomId")
                        val encryptionResult = withTimeout(15000L) { // 15 second timeout
                            crypto.ensureRoomEncryption(roomId)
                        }
                        if (encryptionResult) {
                            println("✅ Proactive encryption setup successful for room $roomId")
                        } else {
                            println("⚠️  Proactive encryption setup failed for room $roomId")
                        }
                    } catch (e: TimeoutCancellationException) {
                        println("❌ Proactive encryption setup timed out for room $roomId")
                    } catch (e: Exception) {
                        println("⚠️  Proactive encryption setup failed: ${e.message}")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                println("❌ ChatScreen: Loading messages timed out for room $roomId")
                isLoading = false
                println("🔄 ChatScreen: Set isLoading = false due to timeout")
                // Show empty messages or cached if available
                val cachedMessages = crypto.roomMessageCache[roomId]
                if (cachedMessages != null) {
                    messages = cachedMessages.toList()
                    println("📋 ChatScreen: Fallback to cached messages after timeout: ${cachedMessages.size}")
                }
            } catch (e: Exception) {
                println("❌ Error loading messages: ${e.message}")
                isLoading = false
                println("🔄 ChatScreen: Set isLoading = false due to error")
            }
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Loading messages...",
                        style = MaterialTheme.typography.body1,
                        color = MaterialTheme.colors.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "If this takes too long, check your network connection.",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = {
                            println("🛑 User cancelled loading")
                            isLoading = false
                            // Try to use cached messages if available
                            val cachedMessages = crypto.roomMessageCache[roomId]
                            if (cachedMessages != null) {
                                messages = cachedMessages.toList()
                                println("📋 Used cached messages after cancel: ${cachedMessages.size}")
                            }
                        }
                    ) {
                        Text("Cancel Loading")
                    }
                }
            }
        } else {
            // Show info message about encryption state
            val hasUndecryptableMessages = messages.any { event ->
                val messageContent = event.content as? JsonObject
                val msgtype = (messageContent?.get("msgtype") as? JsonPrimitive)?.content
                msgtype == "m.bad.encrypted"
            }

            if (hasUndecryptableMessages) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    backgroundColor = MaterialTheme.colors.surface,
                    elevation = 2.dp
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "🔐 End-to-End Encryption Status",
                            style = MaterialTheme.typography.subtitle2,
                            color = MaterialTheme.colors.primary
                        )
                        Text(
                            "Some messages cannot be decrypted because their encryption keys are not available. This is normal when:\n" +
                            "• Messages were sent before you joined the room\n" +
                            "• Messages were sent from other devices\n" +
                            "• You're using a single-device setup\n\n" +
                            "The app automatically requests missing keys from other devices. Send a new message to establish a fresh encryption session.",
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onSurface
                        )
                    }
                }
            }

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
                                if (msgtype == "m.bad.encrypted") {
                                    // Display different messages based on the error content
                                    val displayText = when {
                                        body.contains("Room key not available") -> {
                                            "� This message cannot be decrypted because the encryption key is not available. " +
                                            "This happens when messages were sent before you joined or from other devices. " +
                                            "The app has requested the key automatically."
                                        }
                                        body.contains("Session expired") -> {
                                            "🔄 This message's encryption session has expired. Send a new message to create a fresh session."
                                        }
                                        body.contains("Can't find the room key") -> {
                                            "� Room key not found. This is normal in single-device setups - send a message to establish encryption."
                                        }
                                        body.contains("OlmMachine not available") -> {
                                            "⚠️ Encryption system not ready. Please restart the app."
                                        }
                                        else -> {
                                            "🔐 Unable to decrypt message: ${body.replace("**", "").trim()}"
                                        }
                                    }
                                    Text(
                                        displayText,
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
                IconButton(
                    onClick = {
                        println("🔄 Refresh button clicked")
                        scope.launch {
                            try {
                                val refreshedMessages = getRoomMessages(roomId)
                                messages = refreshedMessages
                                println("✅ Messages refreshed: ${messages.size} messages")
                            } catch (e: Exception) {
                                println("❌ Error refreshing messages: ${e.message}")
                            }
                        }
                    },
                    enabled = !isSending
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh messages")
                }
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
                                                // Add delay to allow room keys to propagate
                                                println("⏳ Waiting for room keys to propagate before sending...")
                                                kotlinx.coroutines.delay(2000)

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
