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
 * Custom hook for managing chat messages state, loading, and periodic refresh
 */
@Composable
fun useChatMessages(roomId: String): ChatMessagesState {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<Event>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    // Periodic refresh to check for new messages from cache - less aggressive
    LaunchedEffect(roomId) {
        while (true) {
            try {
                val cachedMessages = crypto.roomMessageCache[roomId] ?: emptyList()
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

    // Load messages on room change
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

    return ChatMessagesState(
        messages = messages,
        isLoading = isLoading,
        listState = listState,
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
            val cachedMessages = crypto.roomMessageCache[roomId]
            if (cachedMessages != null) {
                messages = cachedMessages.toList()
                println("📋 Used cached messages after cancel: ${cachedMessages.size}")
            }
        }
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
    val scope = rememberCoroutineScope()
    var newMessage by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    val sendMessageAction = {
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

    return MessageSendingState(
        newMessage = newMessage,
        isSending = isSending,
        onMessageChange = fun(text: String) { newMessage = text },
        sendMessage = sendMessageAction
    )
}

/**
 * State holder for message sending
 */
data class MessageSendingState(
    val newMessage: String,
    val isSending: Boolean,
    val onMessageChange: (String) -> Unit,
    val sendMessage: () -> Unit
)
