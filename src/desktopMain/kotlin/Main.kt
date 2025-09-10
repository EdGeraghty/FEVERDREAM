import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import javax.net.ssl.X509TrustManager

val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    engine {
        https {
            // Allow older TLS versions for compatibility
            trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
        }
    }
}

@Serializable
data class LoginRequest(val type: String = "m.login.password", val user: String, val password: String)

@Serializable
data class LoginResponse(val access_token: String)

@Serializable
data class JoinedRoomsResponse(val joined_rooms: List<String>)

suspend fun login(username: String, password: String, homeserver: String): String? {
    try {
        // Ensure homeserver has https
        val cleanHomeserver = if (homeserver.startsWith("http://")) {
            homeserver.replace("http://", "https://")
        } else if (!homeserver.startsWith("https://")) {
            "https://$homeserver"
        } else {
            homeserver
        }

        // Clean username - remove @ if present
        val cleanUsername = username.removePrefix("@")

        // Try different user ID formats
        val userId = if (cleanUsername.contains(":")) {
            cleanUsername
        } else {
            // For matrix.org and similar, try both formats
            cleanUsername // Let the server handle the homeserver part
        }

        println("Attempting login with user: $userId, homeserver: $cleanHomeserver")

        val response = client.post("$cleanHomeserver/_matrix/client/v3/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(user = userId, password = password))
        }

        println("Login response status: ${response.status}")

        return when (response.status) {
            HttpStatusCode.OK -> {
                val loginResponse = response.body<LoginResponse>()
                loginResponse.access_token
            }
            HttpStatusCode.Unauthorized -> {
                throw Exception("Invalid username or password")
            }
            HttpStatusCode.Forbidden -> {
                throw Exception("Login forbidden - check your account status")
            }
            HttpStatusCode.BadRequest -> {
                throw Exception("Bad request - check username format (try without @ or :server)")
            }
            else -> {
                throw Exception("Server error: ${response.status}")
            }
        }
    } catch (e: Exception) {
        println("Login failed: ${e.message}")
        throw e // Re-throw to preserve the error message
    }
}

suspend fun getJoinedRooms(homeserver: String, accessToken: String): List<String> {
    try {
        val response = client.get("$homeserver/_matrix/client/v3/joined_rooms") {
            bearerAuth(accessToken)
        }
        if (response.status == HttpStatusCode.OK) {
            val roomsResponse = response.body<JoinedRoomsResponse>()
            return roomsResponse.joined_rooms
        }
    } catch (e: Exception) {
        println("Get rooms failed: ${e.message}")
    }
    return emptyList()
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "FEVERDREAM") {
        App()
    }
}

@Composable
@Preview
fun App() {
    val scope = rememberCoroutineScope()
    var accessToken by remember { mutableStateOf<String?>(null) }
    var homeserver by remember { mutableStateOf("https://matrix.org") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    MaterialTheme {
        if (accessToken == null) {
            LoginScreen(
                onLogin = { username, password, hs ->
                    if (username.isBlank() || password.isBlank() || hs.isBlank()) {
                        error = "Please fill in all fields"
                        return@LoginScreen
                    }
                    isLoading = true
                    error = null
                    homeserver = hs
                    scope.launch {
                        try {
                            val token = login(username, password, homeserver)
                            accessToken = token
                        } catch (e: Exception) {
                            error = e.message ?: "Login failed. Please try again."
                        } finally {
                            isLoading = false
                        }
                    }
                },
                error = error,
                isLoading = isLoading
            )
        } else {
            ChatScreen(accessToken!!, homeserver)
        }
    }
}

@Composable
fun LoginScreen(onLogin: (String, String, String) -> Unit, error: String?, isLoading: Boolean) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var homeserver by remember { mutableStateOf("https://matrix.org") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("FEVERDREAM", style = MaterialTheme.typography.h4)
        Spacer(modifier = Modifier.height(32.dp))
        TextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(0.5f),
            enabled = !isLoading
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(0.5f),
            enabled = !isLoading
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = homeserver,
            onValueChange = { homeserver = it },
            label = { Text("Homeserver") },
            modifier = Modifier.fillMaxWidth(0.5f),
            enabled = !isLoading
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (error != null) {
            Text(error, color = MaterialTheme.colors.error)
            Spacer(modifier = Modifier.height(8.dp))
        }
        Button(
            onClick = { onLogin(username, password, homeserver) },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(0.5f)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text("Login")
            }
        }
    }
}

@Composable
fun ChatScreen(accessToken: String, homeserver: String) {
    var rooms by remember { mutableStateOf(listOf<String>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(accessToken) {
        scope.launch {
            rooms = getJoinedRooms(homeserver, accessToken)
        }
    }

    Column {
        Text("Joined Rooms:")
        rooms.forEach { roomId ->
            Button(onClick = { /* TODO: Open chat window for room */ }) {
                Text(roomId)
            }
        }
    }
}
