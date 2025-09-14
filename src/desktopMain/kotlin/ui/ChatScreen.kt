package ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatScreen(
    roomId: String,
    onBack: () -> Unit
) {
    println("💬 ChatScreen called for room: $roomId")

    // Use custom hooks for state management
    val chatMessagesState = useChatMessages(roomId)
    val messageSendingState = useMessageSending(roomId)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(roomId) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            }
        )

        if (chatMessagesState.isLoading) {
            LoadingIndicator(onCancel = chatMessagesState.cancelLoading)
        } else {
            // Show encryption status card if needed
            EncryptionStatusCard(messages = chatMessagesState.messages)

            // Show message list
            MessageList(
                messages = chatMessagesState.messages,
                listState = chatMessagesState.listState,
                modifier = Modifier.weight(1f)
            )

            // Show message input
            MessageInput(
                messageSendingState = messageSendingState,
                onRefresh = chatMessagesState.refreshMessages
            )
        }
    }
}
