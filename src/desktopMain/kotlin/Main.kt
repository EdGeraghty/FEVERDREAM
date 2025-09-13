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

    // Shared state for managing multiple windows
    val windowManager = remember { WindowManager() }

    // Main rooms window
    Window(onCloseRequest = {
        // Restore stderr before exit
        System.setErr(originalStderr)
        // Close all chat windows first
        windowManager.closeAllChatWindows()
        // Clean up HTTP client
        closeHttpClient()
        // Note: Don't clean up OlmMachine here as it may still be needed
        // OlmMachine will be cleaned up by the JVM garbage collector
        println("ℹ️  App closing - OlmMachine will be cleaned up by garbage collector")
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


