package ui

import network.login

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import network.login

@Composable
fun LoginScreen(
    onLogin: (String, String, String) -> Unit,
    error: String? = null,
    isLoading: Boolean = false
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var homeserver by remember { mutableStateOf("") }
    var detectedServer by remember { mutableStateOf<String?>(null) }
    var showServerField by remember { mutableStateOf(false) }

    // Focus requesters for tab navigation
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val homeserverFocus = remember { FocusRequester() }
    val loginButtonFocus = remember { FocusRequester() }

    // Auto-detect server from username
    LaunchedEffect(username) {
        if (username.isNotEmpty()) {
            val cleanUsername = username.removePrefix("@")
            if (cleanUsername.contains(":")) {
                val domain = cleanUsername.split(":").last()
                detectedServer = "https://$domain"
            } else if (detectedServer == null) {
                detectedServer = "https://matrix.org"
            }
        }
    }

    val effectiveHomeserver = when {
        homeserver.isNotBlank() -> homeserver
        else -> ""  // Don't auto-fill from detected server, let login() handle discovery
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("FEVERDREAM", style = MaterialTheme.typography.h4)
        Text("Matrix Client", style = MaterialTheme.typography.subtitle1)
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            placeholder = { Text("user or user:server.com") },
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .focusRequester(usernameFocus)
                .onKeyEvent { event ->
                    if (event.key == Key.Tab) {
                        passwordFocus.requestFocus()
                        true
                    } else {
                        false
                    }
                }
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .focusRequester(passwordFocus)
                .onKeyEvent { event ->
                    if (event.key == Key.Tab) {
                        if (showServerField || detectedServer == null) {
                            homeserverFocus.requestFocus()
                        } else {
                            loginButtonFocus.requestFocus()
                        }
                        true
                    } else {
                        false
                    }
                }
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Server detection info
        if (detectedServer != null && !showServerField) {
            Row(
                modifier = Modifier.fillMaxWidth(0.5f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Server: ${detectedServer?.removePrefix("https://")}",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.primary
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { showServerField = true }) {
                    Text("Change", style = MaterialTheme.typography.caption)
                }
            }
        }

        // Server field (shown when user wants to change or if no detection)
        if (showServerField || detectedServer == null) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = homeserver,
                onValueChange = { homeserver = it },
                label = { Text("Homeserver (optional)") },
                placeholder = { Text("matrix.org or https://your-server.com") },
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .focusRequester(homeserverFocus)
                    .onKeyEvent { event ->
                        if (event.key == Key.Tab) {
                            loginButtonFocus.requestFocus()
                            true
                        } else {
                            false
                        }
                    }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = { onLogin(username, password, effectiveHomeserver) },
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .focusRequester(loginButtonFocus),
                enabled = username.isNotBlank() && password.isNotBlank()
            ) {
                Text("Login")
            }
        }

        error?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colors.error)
        }
    }
}
