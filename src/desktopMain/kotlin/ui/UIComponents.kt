package ui

import androidx.compose.material.*
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import models.*
import network.*
import crypto.*

// Screen navigation enum
sealed class Screen {
    object Login : Screen()
    object Rooms : Screen()
    data class Chat(val roomId: String) : Screen()
}

@Composable
fun MatrixApp() {
    // Use GlobalScope for long-running operations to avoid composition cancellation
    val appScope = remember { kotlinx.coroutines.GlobalScope }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Login) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isPeriodicSyncRunning by remember { mutableStateOf(false) }

    println("🎨 MatrixApp composable called, currentScreen: $currentScreen")

    // Load session on startup
    LaunchedEffect(Unit) {
        println("🔄 Loading session on startup...")
        val session = loadSession()
        if (session != null && validateSession(session)) {
            currentUserId = session.userId
            currentDeviceId = session.deviceId
            currentAccessToken = session.accessToken
            currentHomeserver = session.homeserver
            currentSyncToken = session.syncToken
            crypto.initializeEncryption(session.userId, session.deviceId)
            currentScreen = Screen.Rooms
            // Start periodic sync only if not already running
            if (!isPeriodicSyncRunning) {
                isPeriodicSyncRunning = true
                // Temporarily disable periodic sync to debug the continuous syncing issue
                // appScope.launch { crypto.startPeriodicSync() }
                println("🔄 Periodic sync disabled for debugging")
            }
        }
    }

    MaterialTheme {
        when (currentScreen) {
            is Screen.Login -> LoginScreen(
                onLogin = { username, password, homeserver ->
                    appScope.launch {
                        isLoading = true
                        loginError = null
                        try {
                            val result = login(username, password, homeserver)
                            if (result != null) {
                                currentScreen = Screen.Rooms
                                // Start periodic sync only if not already running
                                if (!isPeriodicSyncRunning) {
                                    isPeriodicSyncRunning = true
                                    appScope.launch { crypto.startPeriodicSync() }
                                }
                            } else {
                                loginError = "Login failed"
                            }
                        } catch (e: Exception) {
                            loginError = e.message ?: "Login failed"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                error = loginError,
                isLoading = isLoading
            )
            is Screen.Rooms -> RoomsScreen(
                onRoomSelected = { roomId -> currentScreen = Screen.Chat(roomId) },
                onLogout = {
                    appScope.launch {
                        clearSession()
                        currentAccessToken = null
                        currentUserId = null
                        currentDeviceId = null
                        // Properly dispose of OlmMachine resources
                        olmMachine?.let { machine ->
                            try {
                                // Close the OlmMachine to free native resources
                                (machine as? AutoCloseable)?.close()
                                println("✅ OlmMachine resources cleaned up")
                            } catch (e: Exception) {
                                println("⚠️  Error cleaning up OlmMachine: ${e.message}")
                            }
                        }
                        olmMachine = null
                        isPeriodicSyncRunning = false  // Reset periodic sync flag
                        currentScreen = Screen.Login
                    }
                }
            )
            is Screen.Chat -> {
                val roomId = (currentScreen as Screen.Chat).roomId
                ChatScreen(
                    roomId = roomId,
                    onBack = { currentScreen = Screen.Rooms }
                )
            }
        }
    }
}
