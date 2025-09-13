import androidx.compose.ui.window.application
import androidx.compose.ui.window.Window
import kotlin.system.exitProcess
import ui.*
import network.closeHttpClient
import crypto.olmMachine
import androidx.compose.runtime.*
import kotlinx.coroutines.*

fun main() = application {
    // Shared state for managing multiple windows
    val windowManager = remember { WindowManager() }

    // Main rooms window
    Window(onCloseRequest = {
        // Close all chat windows first
        windowManager.closeAllChatWindows()
        // Clean up HTTP client
        closeHttpClient()
        // Clean up OlmMachine resources
        olmMachine?.let { machine ->
            try {
                (machine as? AutoCloseable)?.close()
                println("✅ OlmMachine resources cleaned up on app exit")
            } catch (e: Exception) {
                println("⚠️  Error cleaning up OlmMachine on app exit: ${e.message}")
            }
        }
        exitProcess(0)
    }, title = "FEVERDREAM - Matrix Client") {
        MatrixApp(windowManager)
    }

    // Create chat windows dynamically
    windowManager.chatWindows.forEach { chatWindow ->
        key(chatWindow.roomId) {
            Window(
                onCloseRequest = {
                    windowManager.closeChatWindow(chatWindow.roomId)
                },
                title = "Chat - ${chatWindow.roomId}"
            ) {
                ChatWindow(
                    roomId = chatWindow.roomId,
                    onClose = { windowManager.closeChatWindow(chatWindow.roomId) }
                )
            }
        }
    }
}


