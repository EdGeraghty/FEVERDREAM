import androidx.compose.ui.window.application
import androidx.compose.ui.window.Window
import kotlin.system.exitProcess
import ui.MatrixApp

fun main() = application {
    Window(onCloseRequest = { exitProcess(0) }, title = "FEVERDREAM - Matrix Client") {
        MatrixApp()
    }
}


