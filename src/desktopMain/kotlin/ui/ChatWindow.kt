package ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp

@Composable
fun ChatWindow(
    roomId: String,
    onClose: () -> Unit
) {
    println("💬 ChatWindow opened for room: $roomId")

    // Use the refactored hooks
    val chatMessagesState = useChatMessages(roomId)
    val messageSendingState = useMessageSending(roomId)

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

            if (chatMessagesState.isLoading) {
                LoadingIndicator()
            } else {
                // Show encryption status if there are undecryptable messages
                val hasUndecryptableMessages = chatMessagesState.messages.any { event ->
                    val messageContent = event.content as? kotlinx.serialization.json.JsonObject
                    val msgtype = (messageContent?.get("msgtype") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    msgtype == "m.bad.encrypted"
                }

                if (hasUndecryptableMessages) {
                    EncryptionStatusCard()
                }

                MessageList(
                    messages = chatMessagesState.messages,
                    listState = chatMessagesState.listState
                )

                MessageInput(
                    newMessage = messageSendingState.newMessage,
                    isSending = messageSendingState.isSending,
                    onMessageChange = messageSendingState.onMessageChange,
                    onSendMessage = messageSendingState.sendMessage
                )
            }
        }
    }
}
