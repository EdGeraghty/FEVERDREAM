package ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.TimeoutCancellationException
import models.Event
import network.getRoomMessages
import crypto.*
import network.sendMessage

/**
 * Custom hook for managing periodic cache checking for new messages
 */
@Composable
fun useMessageCache(roomId: String, currentMessages: List<Event>, isLoading: Boolean): List<Event> {
    var messages by remember { mutableStateOf(currentMessages) }

    // Periodic refresh to check for new messages from cache - less aggressive
    LaunchedEffect(roomId) {
        while (true) {
            try {
                val cachedMessages = crypto.MessageCacheManager.getRoomMessages(roomId)
                if (cachedMessages.size != messages.size && !isLoading) {
                    // Only refresh if we're not currently loading
                    println("🔄 Refreshing messages from cache: ${cachedMessages.size} vs ${messages.size}")
                    messages = cachedMessages.toList()
                }
            } catch (e: Exception) {
                println("⚠️  Error during periodic refresh: ${e.message}")
            }
            kotlinx.coroutines.delay(10000) // Check every 10 seconds instead of 5
        }
    }

    return messages
}

/**
 * Custom hook for loading messages with timeout and fallback logic
 */
@Composable
fun useMessageLoading(roomId: String): MessageLoadingState {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<Event>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Load messages on room change
    LaunchedEffect(roomId) {
        scope.launch {
            try {
                println("🔄 ChatScreen: Loading messages for room $roomId")
                isLoading = true // Ensure loading state is set
                println("🔄 ChatScreen: Set isLoading = true")

                // Check cache first
                val cachedMessages = crypto.MessageCacheManager.getRoomMessages(roomId)
                if (cachedMessages.isNotEmpty()) {
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
            } catch (e: TimeoutCancellationException) {
                println("❌ ChatScreen: Loading messages timed out for room $roomId")
                isLoading = false
                println("🔄 ChatScreen: Set isLoading = false due to timeout")
                // Show empty messages or cached if available
                val cachedMessages = crypto.MessageCacheManager.getRoomMessages(roomId)
                if (cachedMessages.isNotEmpty()) {
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

    return MessageLoadingState(
        messages = messages,
        isLoading = isLoading,
        refreshMessages = {
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
        cancelLoading = {
            println("🛑 User cancelled loading")
            isLoading = false
            // Try to use cached messages if available
            val cachedMessages = crypto.MessageCacheManager.getRoomMessages(roomId)
            if (cachedMessages.isNotEmpty()) {
                messages = cachedMessages.toList()
                println("📋 Used cached messages after cancel: ${cachedMessages.size}")
            }
        }
    )
}

data class MessageLoadingState(
    val messages: List<Event>,
    val isLoading: Boolean,
    val refreshMessages: () -> Unit,
    val cancelLoading: () -> Unit
)

/**
 * Custom hook for proactive encryption setup for a room
 */
@Composable
fun useEncryptionSetup(roomId: String) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(roomId) {
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

/**
 * Custom hook for auto-scrolling to bottom when new messages arrive
 */
@Composable
fun useAutoScroll(messages: List<Event>, listState: LazyListState) {
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
}

/**
 * Custom hook for managing message input state
 */
@Composable
fun useMessageInput(): MessageInputState {
    var newMessage by remember { mutableStateOf("") }

    return MessageInputState(
        newMessage = newMessage,
        onMessageChange = { text: String -> newMessage = text },
        clearMessage = { newMessage = "" }
    )
}

data class MessageInputState(
    val newMessage: String,
    val onMessageChange: (String) -> Unit,
    val clearMessage: () -> Unit
)

/**
 * Custom hook for managing message sending logic with encryption
 */
@Composable
fun useMessageSendLogic(roomId: String, onMessageSent: () -> Unit): MessageSendLogicState {
    val scope = rememberCoroutineScope()
    var isSending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val sendMessageAction: (String) -> Unit = { messageText ->
        println("🔘 Send button clicked, message: '$messageText'")
        if (messageText.isNotBlank()) {
            println("📤 Starting send message process...")
            scope.launch {
                isSending = true
                errorMessage = null // Clear any previous error
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
                        val sendResult = sendMessage(roomId, messageText, skipEncryptionSetup = true)
                        println("📤 sendMessage returned: $sendResult")

                        if (sendResult) {
                            println("✅ Message sent successfully")
                            onMessageSent()
                        } else {
                            println("❌ Message sending failed")
                            errorMessage = "Failed to send encrypted message. Please try again."
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
                            val sendResult = sendMessage(roomId, messageText)
                            println("📤 sendMessage returned: $sendResult")

                            if (sendResult) {
                                println("✅ Message sent successfully")
                                onMessageSent()
                            } else {
                                println("❌ Message sending failed")
                                errorMessage = "Failed to send encrypted message. Please try again."
                            }
                        } else {
                            println("❌ ensureRoomEncryption failed, not sending message")
                            errorMessage = "Encryption setup failed. Message not sent."
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    println("❌ ensureRoomEncryption timed out after 15 seconds")
                    errorMessage = "Encryption setup timed out. Message not sent."
                } catch (e: Exception) {
                    println("❌ Error during message sending: ${e.message}")
                    e.printStackTrace()
                    errorMessage = "Error during message sending: ${e.message}"
                } finally {
                    println("🔄 Resetting isSending to false")
                    isSending = false
                }
            }
        } else {
            println("⚠️  Message is blank, not sending")
        }
    }

    return MessageSendLogicState(
        isSending = isSending,
        sendMessage = sendMessageAction,
        errorMessage = errorMessage
    )
}

data class MessageSendLogicState(
    val isSending: Boolean,
    val sendMessage: (String) -> Unit,
    val errorMessage: String?
)
/**
 * Custom hook for managing chat messages state, loading, and periodic refresh
 */
@Composable
fun useChatMessages(roomId: String): ChatMessagesState {
    val loadingState = useMessageLoading(roomId)
    val cachedMessages = useMessageCache(roomId, loadingState.messages, loadingState.isLoading)
    val listState = rememberLazyListState()

    // Use cached messages if available, otherwise use loading state messages
    val currentMessages = if (cachedMessages.isNotEmpty()) cachedMessages else loadingState.messages

    // Set up encryption proactively
    useEncryptionSetup(roomId)

    // Auto-scroll to bottom when new messages arrive
    useAutoScroll(currentMessages, listState)

    return ChatMessagesState(
        messages = currentMessages,
        isLoading = loadingState.isLoading,
        listState = listState,
        refreshMessages = loadingState.refreshMessages,
        cancelLoading = loadingState.cancelLoading
    )
}

/**
 * State holder for chat messages
 */
data class ChatMessagesState(
    val messages: List<Event>,
    val isLoading: Boolean,
    val listState: LazyListState,
    val refreshMessages: () -> Unit,
    val cancelLoading: () -> Unit
)

/**
 * Custom hook for managing message sending state and logic
 */
@Composable
fun useMessageSending(roomId: String): MessageSendingState {
    val inputState = useMessageInput()
    val sendLogicState = useMessageSendLogic(roomId, inputState.clearMessage)

    return MessageSendingState(
        newMessage = inputState.newMessage,
        isSending = sendLogicState.isSending,
        onMessageChange = inputState.onMessageChange,
        sendMessage = { sendLogicState.sendMessage(inputState.newMessage) },
        errorMessage = sendLogicState.errorMessage
    )
}

/**
 * State holder for message sending
 */
data class MessageSendingState(
    val newMessage: String,
    val isSending: Boolean,
    val onMessageChange: (String) -> Unit,
    val sendMessage: () -> Unit,
    val errorMessage: String?
)
