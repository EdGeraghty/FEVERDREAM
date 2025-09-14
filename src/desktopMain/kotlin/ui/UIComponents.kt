package ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.Icons.Default
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import models.*
import network.*
import crypto.*

// Screen navigation enum
sealed class Screen {
    object Login : Screen()
    object Rooms : Screen()
    object Settings : Screen()
}

@Composable
fun SettingsWindow(windowManager: WindowManager) {
    MaterialTheme {
        SettingsScreen(
            onBack = { windowManager.closeSettingsWindow() },
            windowManager = windowManager
        )
    }
}

@Composable
fun LoginWindow(onLoginSuccess: () -> Unit) {
    // Load existing session on startup to reuse device ID
    LaunchedEffect(Unit) {
        val session = loadSession()
        if (session != null && validateSession(session)) {
            currentUserId = session.userId
            currentDeviceId = session.deviceId
            currentAccessToken = session.accessToken
            currentHomeserver = session.homeserver
            currentSyncToken = session.syncToken
            println("📂 Existing session loaded, will reuse device ID: ${currentDeviceId}")
            // Initialize encryption and automatically log in
            initializeEncryption(session.userId, session.deviceId)
            onLoginSuccess()
        }
    }

    // Use rememberCoroutineScope for composition-aware coroutines
    val scope = rememberCoroutineScope()
    var loginError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    MaterialTheme {
        LoginScreen(
            onLogin = { username, password, homeserver ->
                scope.launch {
                    isLoading = true
                    loginError = null
                    try {
                        val result = login(username, password, homeserver)
                        if (result != null) {
                            onLoginSuccess()
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
    }
}

@Composable
fun MatrixApp(windowManager: WindowManager, onLogout: () -> Unit = {}) {
    // Use rememberCoroutineScope for composition-aware coroutines
    val appScope = rememberCoroutineScope()
    var isLoggedIn by remember { mutableStateOf(false) }
    var isPeriodicSyncRunning by remember { mutableStateOf(false) }

    println("🎨 MatrixApp composable called, isLoggedIn: $isLoggedIn")

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
            isLoggedIn = true
            // Start periodic sync only if not already running
            if (!isPeriodicSyncRunning) {
                isPeriodicSyncRunning = true
                // Temporarily disable periodic sync to debug the continuous syncing issue
                // appScope.launch { crypto.startPeriodicSync() }
                println("🔄 Periodic sync disabled for debugging")
            }
        } else {
            // If no valid session, ensure we're logged out
            isLoggedIn = false
        }
    }

    if (isLoggedIn) {
        MaterialTheme {
            RoomsScreen(
                onRoomSelected = { roomId ->
                    windowManager.openChatWindow(roomId)
                },
                onSettings = {
                    windowManager.openSettingsWindow()
                },
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
                        isLoggedIn = false
                        // Close all windows when logging out
                        windowManager.closeAllChatWindows()
                        windowManager.closeSettingsWindow()
                        // Show login window again
                        onLogout()
                    }
                },
                windowManager = windowManager
            )
        }
    } else {
        // Show nothing in main window when not logged in - login window will handle this
        Box(modifier = Modifier.fillMaxSize()) {
            Text("Please use the login window to sign in.", modifier = Modifier.align(Alignment.Center))
        }
    }
}
