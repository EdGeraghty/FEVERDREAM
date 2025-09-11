import androidx.compose.ui.window.application
import androidx.compose.ui.window.Window
import kotlin.system.exitProcess
import ui.MatrixApp
import network.closeHttpClient
import network.olmMachine

fun main() = application {
    Window(onCloseRequest = {
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
        MatrixApp()
    }
}


