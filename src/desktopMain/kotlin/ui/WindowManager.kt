package ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

// Data class to represent a chat window
data class ChatWindowData(val roomId: String)

// Data class to represent a settings window
data class SettingsWindowData(val id: String = "settings")

// Window manager to handle multiple chat windows and settings windows
class WindowManager {
    val chatWindows: SnapshotStateList<ChatWindowData> = mutableStateListOf()
    val settingsWindows: SnapshotStateList<SettingsWindowData> = mutableStateListOf()

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

    fun openSettingsWindow() {
        // Only allow one settings window
        if (settingsWindows.isEmpty()) {
            settingsWindows.add(SettingsWindowData())
        }
    }

    fun closeSettingsWindow() {
        settingsWindows.clear()
    }

    fun isSettingsWindowOpen(): Boolean {
        return settingsWindows.isNotEmpty()
    }
}
