import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
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

val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
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
        val response = client.post("$homeserver/_matrix/client/v3/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(user = username, password = password))
        }
        if (response.status == HttpStatusCode.OK) {
            val loginResponse = response.body<LoginResponse>()
            return loginResponse.access_token
        }
    } catch (e: Exception) {
        println("Login failed: ${e.message}")
    }
    return null
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

    MaterialTheme {
        if (accessToken == null) {
            LoginScreen { username, password, hs ->
                homeserver = hs
                scope.launch {
                    accessToken = login(username, password, homeserver)
                }
            }
        } else {
            ChatScreen(accessToken!!, homeserver)
        }
    }
}

@Composable
fun LoginScreen(onLogin: (String, String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var homeserver by remember { mutableStateOf("https://matrix.org") }

    Column(modifier = Modifier.padding(16.dp)) {
        TextField(value = username, onValueChange = { username = it }, label = { Text("Username") })
        TextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
        TextField(value = homeserver, onValueChange = { homeserver = it }, label = { Text("Homeserver") })
        Button(onClick = { onLogin(username, password, homeserver) }) {
            Text("Login")
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
