package ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import models.Event
import network.currentUserId

/**
 * Composable for displaying the list of messages
 */
@Composable
fun MessageList(
    messages: List<Event>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
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
                                    "🔐 This message cannot be decrypted because the encryption key is not available. " +
                                    "This happens when messages were sent before you joined or from other devices. " +
                                    "The app has requested the key automatically."
                                }
                                body.contains("Session expired") -> {
                                    "🔄 This message's encryption session has expired. Send a new message to create a fresh session."
                                }
                                body.contains("Can't find the room key") -> {
                                    "🔐 Room key not found. This is normal in single-device setups - send a message to establish encryption."
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
}

/**
 * Composable for message input with send button and refresh
 */
@Composable
fun MessageInput(
    messageSendingState: MessageSendingState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = messageSendingState.newMessage,
            onValueChange = messageSendingState.onMessageChange,
            label = { Text("Message") },
            modifier = Modifier.weight(1f),
            enabled = !messageSendingState.isSending
        )
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(
            onClick = onRefresh,
            enabled = !messageSendingState.isSending
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh messages")
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (messageSendingState.isSending) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            Button(
                onClick = messageSendingState.sendMessage
            ) {
                Text("Send")
            }
        }
    }
}

/**
 * Composable for displaying encryption status and warnings
 */
@Composable
fun EncryptionStatusCard(
    messages: List<Event>,
    modifier: Modifier = Modifier
) {
    val hasUndecryptableMessages = messages.any { event ->
        val messageContent = event.content as? JsonObject
        val msgtype = (messageContent?.get("msgtype") as? JsonPrimitive)?.content
        msgtype == "m.bad.encrypted"
    }

    if (hasUndecryptableMessages) {
        Card(
            modifier = modifier
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
}

/**
 * Composable for displaying loading state with cancel option
 */
@Composable
fun LoadingIndicator(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
            OutlinedButton(onClick = onCancel) {
                Text("Cancel Loading")
            }
        }
    }
}
