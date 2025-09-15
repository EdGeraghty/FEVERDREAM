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
        println("🪟 WindowManager: Attempting to open chat window for $roomId")
        // Check if window is already open
        if (chatWindows.none { it.roomId == roomId }) {
            println("🪟 WindowManager: Creating new chat window for $roomId")
            chatWindows.add(ChatWindowData(roomId))
            println("🪟 WindowManager: Chat window added. Total windows: ${chatWindows.size}")
        } else {
            println("🪟 WindowManager: Chat window for $roomId already exists")
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
