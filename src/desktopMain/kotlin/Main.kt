import androidx.compose.ui.window.application
import androidx.compose.ui.window.Window
import kotlin.system.exitProcess
import ui.*
import network.closeHttpClient
import crypto.olmMachine
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.io.PrintStream
import java.io.OutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream

fun main() = application {
    // More aggressive stderr suppression - redirect to /dev/null equivalent
    val originalStderr = System.err
    try {
        // Try to redirect to null device
        val nullStream = if (System.getProperty("os.name").lowercase().contains("windows")) {
            FileOutputStream("NUL")
        } else {
            FileOutputStream("/dev/null")
        }
        System.setErr(PrintStream(nullStream))
    } catch (e: Exception) {
        // Fallback: redirect to a temporary file that gets deleted
        try {
            val tempFile = kotlin.io.path.createTempFile("stderr", ".log").toFile()
            tempFile.deleteOnExit()
            System.setErr(PrintStream(tempFile))
        } catch (fallbackException: Exception) {
            // Last resort: suppress stderr completely
            System.setErr(PrintStream(object : OutputStream() {
                override fun write(b: Int) {}
                override fun write(b: ByteArray, off: Int, len: Int) {}
            }))
        }
    }

    // Global scope for background tasks like periodic sync
    val backgroundScope = rememberCoroutineScope { Dispatchers.IO + SupervisorJob() }

    // Shared state for managing multiple windows
    val windowManager = remember { WindowManager() }
    var showLoginWindow by remember { mutableStateOf(true) }

    // Login window - shown initially and when logged out
    if (showLoginWindow) {
        Window(
            onCloseRequest = {
                // Restore stderr before exit
                System.setErr(originalStderr)
                // Close all windows
                windowManager.closeAllChatWindows()
                windowManager.closeSettingsWindow()
                closeHttpClient()
                println("ℹ️  App closing - OlmMachine will be cleaned up by garbage collector")
                exitProcess(0)
            },
            title = "Login - FEVERDREAM"
        ) {
            LoginWindow(onLoginSuccess = {
                showLoginWindow = false
            })
        }
    }

    // Main rooms window - shown after login
    if (!showLoginWindow) {
        Window(onCloseRequest = {
            // Instead of exiting, treat this as a logout to keep background tasks running
            showLoginWindow = true
            // Close all chat windows
            windowManager.closeAllChatWindows()
            // Close settings window
            windowManager.closeSettingsWindow()
        }, title = "FEVERDREAM - Matrix Client") {
            MatrixApp(windowManager, backgroundScope, onLogout = { showLoginWindow = true })
        }
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

    // Create settings window
    windowManager.settingsWindows.forEach { settingsWindow ->
        key(settingsWindow.id) {
            Window(
                onCloseRequest = {
                    windowManager.closeSettingsWindow()
                },
                title = "Settings - FEVERDREAM"
            ) {
                SettingsWindow(windowManager)
            }
        }
    }
}


