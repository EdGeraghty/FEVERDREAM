package ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

// Data class to represent a chat window
data class ChatWindowData(val roomId: String)

// Window manager to handle multiple chat windows
class WindowManager {
    val chatWindows: SnapshotStateList<ChatWindowData> = mutableStateListOf()

    fun openChatWindow(roomId: String) {
        // Check if window is already open
        if (chatWindows.none { it.roomId == roomId }) {
            chatWindows.add(ChatWindowData(roomId))
        }
    }

    fun closeChatWindow(roomId: String) {
        chatWindows.removeIf { it.roomId == roomId }
    }

    fun closeAllChatWindows() {
        chatWindows.clear()
    }

    fun isChatWindowOpen(roomId: String): Boolean {
        return chatWindows.any { it.roomId == roomId }
    }
}
