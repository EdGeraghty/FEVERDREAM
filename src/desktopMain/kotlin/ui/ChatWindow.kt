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
            LoadingIndicator(onCancel = { /* TODO: implement cancel loading */ })
        } else {
            // Show encryption status if there are undecryptable messages
            EncryptionStatusCard(messages = chatMessagesState.messages)

            MessageList(
                messages = chatMessagesState.messages,
                listState = chatMessagesState.listState,
                modifier = Modifier.weight(1f) // Added weight modifier
            )

            MessageInput(
                messageSendingState = messageSendingState,
                onRefresh = { /* TODO: implement refresh */ }
            )
        }
    }
}
