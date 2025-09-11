package network

import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import models.SessionData
import java.io.File

val sessionFile = File("session.json")

suspend fun saveSession(sessionData: SessionData) {
    try {
        sessionFile.writeText(json.encodeToString(sessionData))
        println("💾 Session saved")
    } catch (e: Exception) {
        println("❌ Failed to save session: ${e.message}")
    }
}

suspend fun loadSession(): SessionData? {
    return try {
        if (sessionFile.exists()) {
            val sessionData = json.decodeFromString<SessionData>(sessionFile.readText())
            println("📂 Session loaded for user: ${sessionData.userId}")
            sessionData
        } else {
            null
        }
    } catch (e: Exception) {
        println("❌ Failed to load session: ${e.message}")
        null
    }
}

suspend fun validateSession(sessionData: SessionData): Boolean {
    return try {
        val response = client.get("${sessionData.homeserver}/_matrix/client/v3/account/whoami") {
            bearerAuth(sessionData.accessToken)
        }
        response.status == HttpStatusCode.OK
    } catch (e: Exception) {
        println("❌ Session validation failed: ${e.message}")
        false
    }
}

suspend fun clearSession() {
    try {
        if (sessionFile.exists()) {
            sessionFile.delete()
            println("🗑️ Session cleared")
        }
    } catch (e: Exception) {
        println("❌ Failed to clear session: ${e.message}")
    }
}
