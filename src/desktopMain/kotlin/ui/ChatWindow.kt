package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import models.Event
import network.*
import crypto.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun ChatWindow(
    roomId: String,
    onClose: () -> Unit
) {
    println("💬 ChatWindow opened for room: $roomId")
    // Use GlobalScope for long-running operations to avoid composition cancellation
    val scope = remember { kotlinx.coroutines.GlobalScope }
    var messages by remember { mutableStateOf<List<Event>>(emptyList()) }
    var newMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    println("📝 ChatWindow state initialized: messages=${messages.size}, newMessage='$newMessage', isLoading=$isLoading, isSending=$isSending")

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
            println("🔄 ChatWindow: Loading messages for room $roomId")
            // ensureRoomEncryption call - too slow for UI initialization
            // Only set up encryption when actually sending a message
            messages = getRoomMessages(roomId)
            println("✅ ChatWindow: Loaded ${messages.size} messages for room $roomId")
            isLoading = false
            println("✅ ChatWindow: Loading complete, isLoading = false")

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
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(roomId) },
                actions = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
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
                                "Some messages cannot be decrypted because their encryption sessions have expired. This is normal in single-device setups. Send a new message to establish a fresh encryption session.",
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
                                                "🔒 This message was sent before you joined the room or from another device. Room keys are not available."
                                            }
                                            body.contains("Session expired") -> {
                                                "🔄 This message's encryption session has expired. Send a new message to create a fresh session."
                                            }
                                            body.contains("Can't find the room key") -> {
                                                "🔑 Room key not found. This is normal in single-device setups - send a message to establish encryption."
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
}
