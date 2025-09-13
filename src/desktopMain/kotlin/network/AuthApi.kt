package network

import crypto.initializeEncryption
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonObject
import models.*
import kotlinx.serialization.json.Json

/**
 * Authentication API functions for Matrix client
 */
suspend fun discoverHomeserver(domain: String): String {
    try {
        // Try to discover homeserver via .well-known/matrix/client first
        val clientWellKnownUrl = "https://$domain/.well-known/matrix/client"
        println("Checking for homeserver discovery at: $clientWellKnownUrl")

        val clientResponse = client.get(clientWellKnownUrl)

        println("Client well-known response status: ${clientResponse.status}")

        if (clientResponse.status == HttpStatusCode.OK) {
            val clientResponseText = clientResponse.body<String>()
            println("Client well-known response body: $clientResponseText")

            try {
                val clientWellKnown = json.decodeFromString<ClientWellKnownResponse>(clientResponseText)
                val homeserverInfo = clientWellKnown.homeserver

                if (homeserverInfo != null) {
                    val baseUrl = homeserverInfo.base_url
                    println("Found homeserver via client discovery: $domain -> $baseUrl")
                    return baseUrl
                }
            } catch (e: Exception) {
                println("Failed to parse client well-known response: ${e.message}")
                // Try to parse as plain text fallback
                if (clientResponseText.contains("m.homeserver")) {
                    val baseUrlMatch = Regex("\"base_url\"\\s*:\\s*\"([^\"]+)\"").find(clientResponseText)
                    if (baseUrlMatch != null) {
                        val baseUrl = baseUrlMatch.groupValues[1]
                        println("Parsed homeserver from regex: $domain -> $baseUrl")
                        return baseUrl
                    }
                }
            }
        } else {
            println("Client well-known not found (status: ${clientResponse.status}), trying server delegation...")
        }

        // Fallback: Try server delegation
        try {
            val serverWellKnownUrl = "https://$domain/.well-known/matrix/server"
            println("Checking for server delegation at: $serverWellKnownUrl")

            val serverResponse = client.get(serverWellKnownUrl)

            println("Server delegation response status: ${serverResponse.status}")

            if (serverResponse.status == HttpStatusCode.OK) {
                val serverResponseText = serverResponse.body<String>()
                println("Server delegation response body: $serverResponseText")

                try {
                    val delegation = json.decodeFromString<ServerDelegationResponse>(serverResponseText)
                    val serverValue = delegation.mServer

                    if (serverValue != null) {
                        // Handle server value that might include port
                        val actualServer = if (serverValue.contains(":")) {
                            val parts = serverValue.split(":", limit = 2)
                            val serverHost = parts[0]
                            val serverPort = parts[1]
                            if (serverPort == "443") {
                                "https://$serverHost"
                            } else {
                                "https://$serverHost:$serverPort"
                            }
                        } else {
                            "https://$serverValue"
                        }

                        println("Found server delegation: $domain -> $actualServer")
                        return actualServer
                    }
                } catch (e: Exception) {
                    println("Failed to parse server delegation response: ${e.message}")
                }

                // Try to parse as plain text fallback
                if (serverResponseText.contains("m.server")) {
                    val serverMatch = Regex("\"m\\.server\"\\s*:\\s*\"([^\"]+)\"").find(serverResponseText)
                    if (serverMatch != null) {
                        val serverValue = serverMatch.groupValues[1]
                        val actualServer = if (serverValue.contains(":")) {
                            val parts = serverValue.split(":", limit = 2)
                            val serverHost = parts[0]
                            val serverPort = parts[1]
                            if (serverPort == "443") {
                                "https://$serverHost"
                            } else {
                                "https://$serverHost:$serverPort"
                            }
                        } else {
                            "https://$serverValue"
                        }
                        println("Parsed server delegation from regex: $domain -> $actualServer")
                        return actualServer
                    }
                }
            } else {
                println("Server delegation not found (status: ${serverResponse.status})")
            }
        } catch (e: Exception) {
            println("Error checking server delegation for $domain: ${e.message}")
        }

    } catch (e: Exception) {
        println("Error checking homeserver discovery for $domain: ${e.message}")
    }

    // Final fallback to the domain itself
    println("Using fallback homeserver: https://$domain")
    return "https://$domain"
}

suspend fun login(username: String, password: String, homeserver: String): LoginResponse? {
    try {
        // Handle empty homeserver with smart defaults
        val cleanHomeserver = when {
            homeserver.isBlank() -> {
                // Try to extract from username first
                val cleanUsername = username.removePrefix("@")
                if (cleanUsername.contains(":")) {
                    val domain = cleanUsername.split(":").last()
                    "https://$domain"
                } else {
                    "https://matrix.org" // Default fallback
                }
            }
            homeserver.startsWith("http://") -> {
                homeserver.replace("http://", "https://")
            }
            !homeserver.startsWith("https://") -> {
                "https://$homeserver"
            }
            else -> homeserver
        }

        val cleanUsername = username.removePrefix("@")

        // Extract homeserver from user ID if it contains a domain
        val (userId, actualHomeserver) = if (cleanUsername.contains(":")) {
            val parts = cleanUsername.split(":", limit = 2)
            val userPart = parts[0]
            val domainPart = parts[1]

            // Discover the actual homeserver (check for delegation)
            val discoveredHomeserver = discoverHomeserver(domainPart)
            Pair(userPart, discoveredHomeserver)
        } else {
            val userId = cleanUsername
            Pair(userId, cleanHomeserver)
        }

        // Use the extracted homeserver if available, otherwise use the provided one
        val finalHomeserver = actualHomeserver
        currentHomeserver = finalHomeserver

        println("🔍 Login attempt:")
        println("   User: $userId")
        println("   Server: $finalHomeserver")
        println("   Auto-detected: ${cleanUsername.contains(":")}")

        // Try login with the determined homeserver
        try {
            val loginRequest = LoginRequestV2(
                identifier = Identifier(user = userId),
                password = password
            )

            println("📤 Sending login request...")

            val response = client.post("$finalHomeserver/_matrix/client/v3/login") {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(LoginRequestV2.serializer(), loginRequest))
            }

            println("📥 Login response: ${response.status}")

            if (response.status == HttpStatusCode.OK) {
                val loginResponse = response.body<LoginResponse>()
                currentAccessToken = loginResponse.access_token
                currentDeviceId = loginResponse.device_id
                currentUserId = loginResponse.user_id
                println("✅ Logged in successfully!")
                println("   Device ID: ${currentDeviceId}")
                println("   User ID: ${currentUserId}")
                // Initialize encryption system after successful login
                initializeEncryption(loginResponse.user_id, loginResponse.device_id)
                // Save session data
                val sessionData = SessionData(
                    userId = loginResponse.user_id,
                    deviceId = loginResponse.device_id,
                    accessToken = loginResponse.access_token,
                    homeserver = finalHomeserver,
                    syncToken = currentSyncToken
                )
                saveSession(sessionData)
                return loginResponse
            } else {
                // Try to get error details from response
                val errorText = response.body<String>()
                println("Login error response: $errorText")

                // If it's a 400 error, try the older login format as fallback
                if (response.status == HttpStatusCode.BadRequest) {
                    println("Trying older login format...")
                    try {
                        val oldLoginRequest = LoginRequest(
                            user = userId,
                            password = password
                        )

                        val oldResponse = client.post("$finalHomeserver/_matrix/client/v3/login") {
                            contentType(ContentType.Application.Json)
                            setBody(Json.encodeToString(LoginRequest.serializer(), oldLoginRequest))
                        }

                        if (oldResponse.status == HttpStatusCode.OK) {
                            val loginResponse = oldResponse.body<LoginResponse>()
                            currentAccessToken = loginResponse.access_token
                            currentDeviceId = loginResponse.device_id
                            currentUserId = loginResponse.user_id
                            println("🔑 Logged in with device ID: ${currentDeviceId}")
                            println("Login successful with older format")
                            // Initialize encryption system after successful login
                            initializeEncryption(loginResponse.user_id, loginResponse.device_id)
                            // Save session data
                            val sessionData = SessionData(
                                userId = loginResponse.user_id,
                                deviceId = loginResponse.device_id,
                                accessToken = loginResponse.access_token,
                                homeserver = finalHomeserver,
                                syncToken = currentSyncToken
                            )
                            saveSession(sessionData)
                            return loginResponse
                        }
                    } catch (oldException: Exception) {
                        println("Older login format failed: ${oldException.message}")
                    }
                }

                throw Exception("Login failed: ${response.status} - ${response.status.description}")
            }
        } catch (e: Exception) {
            println("Login failed on homeserver $finalHomeserver: ${e.message}")

            // If the login failed and we discovered a homeserver (not using the original domain),
            // and the user didn't provide a specific homeserver, don't try fallback
            val originalDomainHomeserver = if (cleanUsername.contains(":")) {
                "https://${cleanUsername.split(":").last()}"
            } else {
                ""
            }
            if (homeserver.isBlank() && actualHomeserver != originalDomainHomeserver) {
                println("Not attempting fallback since homeserver was auto-discovered and user didn't specify one")
                throw e
            }

            // Only try fallback if user explicitly provided a different homeserver
            if (homeserver.isNotBlank() && actualHomeserver != cleanHomeserver) {
                println("Login failed on discovered homeserver $actualHomeserver, trying provided homeserver: $cleanHomeserver")
                currentHomeserver = cleanHomeserver

                val fallbackRequest = LoginRequestV2(
                    identifier = Identifier(user = userId),
                    password = password
                )

                val fallbackResponse = client.post("$cleanHomeserver/_matrix/client/v3/login") {
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(LoginRequestV2.serializer(), fallbackRequest))
                }

                println("📥 Fallback login response: ${fallbackResponse.status}")

                if (fallbackResponse.status == HttpStatusCode.OK) {
                    try {
                        val loginResponse = fallbackResponse.body<LoginResponse>()
                        currentAccessToken = loginResponse.access_token
                        currentDeviceId = loginResponse.device_id
                        currentUserId = loginResponse.user_id
                        println("🔑 Logged in with device ID: ${currentDeviceId}")
                        // Initialize encryption system after successful login
                        initializeEncryption(loginResponse.user_id, loginResponse.device_id)
                        // Save session data
                        val sessionData = SessionData(
                            userId = loginResponse.user_id,
                            deviceId = loginResponse.device_id,
                            accessToken = loginResponse.access_token,
                            homeserver = cleanHomeserver,
                            syncToken = currentSyncToken
                        )
                        saveSession(sessionData)
                        return loginResponse
                    } catch (parseException: Exception) {
                        println("Failed to parse fallback login response as JSON: ${parseException.message}")
                        // Try to get the raw response for debugging
                        try {
                            val rawResponse = fallbackResponse.body<String>()
                            println("Raw fallback response: $rawResponse")
                        } catch (rawException: Exception) {
                            println("Could not read raw fallback response: ${rawException.message}")
                        }
                        throw Exception("Fallback homeserver returned invalid response format")
                    }
                } else {
                    // Try to get error details from fallback response
                    try {
                        val errorText = fallbackResponse.body<String>()
                        println("Fallback login error response: $errorText")
                    } catch (errorException: Exception) {
                        println("Could not read fallback error response: ${errorException.message}")
                    }
                }
            }
            throw e
        }

    } catch (e: Exception) {
        println("Login failed: ${e.message}")
        throw e
    }
}
