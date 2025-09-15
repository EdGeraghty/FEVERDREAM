package network

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import models.*

/**
 * Device management API functions for Matrix client
 */

/**
 * Get all devices for the current user
 */
suspend fun getDevices(): List<DeviceInfo> {
    val token = currentAccessToken ?: return emptyList()
    println("🔍 getDevices: Starting request to $currentHomeserver")
    try {
        val response = withTimeout(10000L) { // 10 second timeout
            println("🌐 getDevices: Making HTTP request...")
            client.get("$currentHomeserver/_matrix/client/v3/devices") {
                bearerAuth(token)
            }
        }
        println("📥 getDevices: Response status: ${response.status}")
        if (response.status == HttpStatusCode.OK) {
            val devicesResponse = response.body<DevicesResponse>()
            println("✅ getDevices: Successfully retrieved ${devicesResponse.devices.size} devices")
            return devicesResponse.devices
        } else {
            println("❌ getDevices: Bad response status ${response.status}")
        }
    } catch (e: TimeoutCancellationException) {
        println("❌ getDevices: Request timed out after 10 seconds")
    } catch (e: Exception) {
        println("❌ getDevices: Exception: ${e.message}")
        e.printStackTrace()
    }
    return emptyList()
}

/**
 * Delete specific devices
 */
suspend fun deleteDevices(deviceIds: List<String>): Boolean {
    val token = currentAccessToken ?: return false
    println("🔍 deleteDevices: Starting request to delete ${deviceIds.size} devices")
    try {
        val response = client.post("$currentHomeserver/_matrix/client/v3/delete_devices") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(DeleteDevicesRequest(deviceIds))
        }
        println("📥 deleteDevices: Response status: ${response.status}")
        if (response.status == HttpStatusCode.OK) {
            println("✅ deleteDevices: Successfully deleted devices")
            return true
        } else {
            println("❌ deleteDevices: Bad response status ${response.status}")
            // Log response body for debugging
            try {
                val responseBody = response.body<String>()
                println("📄 deleteDevices: Response body: $responseBody")
            } catch (e: Exception) {
                println("❌ deleteDevices: Could not read response body")
            }
        }
    } catch (e: Exception) {
        println("❌ deleteDevices: Exception: ${e.message}")
        e.printStackTrace()
    }
    return false
}

/**
 * Delete specific devices with UIA (User-Interactive Authentication) support
 */
suspend fun deleteDevices(deviceIds: List<String>, password: String? = null): Boolean {
    val token = currentAccessToken ?: return false
    val userId = currentUserId ?: return false
    println("🔍 deleteDevices: Starting request to delete ${deviceIds.size} devices")
    try {
        val response = client.post("$currentHomeserver/_matrix/client/v3/delete_devices") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(DeleteDevicesRequest(deviceIds))
        }
        println("📥 deleteDevices: Response status: ${response.status}")

        if (response.status == HttpStatusCode.OK) {
            println("✅ deleteDevices: Successfully deleted ${deviceIds.size} devices")
            return true
        } else if (response.status == HttpStatusCode.Unauthorized) {
            // Handle UIA (User-Interactive Authentication)
            println("🔐 deleteDevices: UIA required, attempting authentication...")
            try {
                val uiaResponse = response.body<UIAChallenge>()
                println("🔑 deleteDevices: Got UIA session: ${uiaResponse.session}")

                if (password == null) {
                    println("❌ deleteDevices: Password required for UIA but not provided")
                    return false
                }

                // Make authenticated request
                val authResponse = client.post("$currentHomeserver/_matrix/client/v3/delete_devices") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(DeleteDevicesRequestWithAuth(
                        devices = deviceIds,
                        auth = AuthDict(
                            type = "m.login.password",
                            session = uiaResponse.session,
                            user = userId,
                            password = password
                        )
                    ))
                }

                println("📥 deleteDevices: Auth response status: ${authResponse.status}")
                if (authResponse.status == HttpStatusCode.OK) {
                    println("✅ deleteDevices: Successfully deleted ${deviceIds.size} devices with authentication")
                    return true
                } else {
                    println("❌ deleteDevices: Authentication failed with status ${authResponse.status}")
                    try {
                        val errorBody = authResponse.body<String>()
                        println("📄 deleteDevices: Auth error response: $errorBody")
                    } catch (e: Exception) {
                        println("❌ deleteDevices: Could not read auth error response")
                    }
                }
            } catch (e: Exception) {
                println("❌ deleteDevices: Failed to parse UIA response: ${e.message}")
                // Log the raw response for debugging
                try {
                    val rawBody = response.body<String>()
                    println("📄 deleteDevices: Raw UIA response: $rawBody")
                } catch (e2: Exception) {
                    println("❌ deleteDevices: Could not read raw response")
                }
            }
        } else {
            println("❌ deleteDevices: Bad response status ${response.status}")
            // Log response body for debugging
            try {
                val responseBody = response.body<String>()
                println("📄 deleteDevices: Response body: $responseBody")
            } catch (e: Exception) {
                println("❌ deleteDevices: Could not read response body")
            }
        }
    } catch (e: Exception) {
        println("❌ deleteDevices: Exception: ${e.message}")
        e.printStackTrace()
    }
    return false
}

/**
 * Delete a single device with UIA (User-Interactive Authentication) support
 */
suspend fun deleteDevice(deviceId: String, password: String? = null): Boolean {
    val token = currentAccessToken ?: return false
    val userId = currentUserId ?: return false
    println("🔍 deleteDevice: Starting request to delete device $deviceId")
    try {
        val response = client.delete("$currentHomeserver/_matrix/client/v3/devices/$deviceId") {
            bearerAuth(token)
        }
        println("📥 deleteDevice: Response status: ${response.status}")

        if (response.status == HttpStatusCode.OK) {
            println("✅ deleteDevice: Successfully deleted device $deviceId")
            return true
        } else if (response.status == HttpStatusCode.Unauthorized) {
            // Handle UIA (User-Interactive Authentication)
            println("🔐 deleteDevice: UIA required, attempting authentication...")
            try {
                val uiaResponse = response.body<UIAChallenge>()
                println("🔑 deleteDevice: Got UIA session: ${uiaResponse.session}")

                if (password == null) {
                    println("❌ deleteDevice: Password required for UIA but not provided")
                    return false
                }

                // Make authenticated request
                val authResponse = client.delete("$currentHomeserver/_matrix/client/v3/devices/$deviceId") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(DeleteDeviceRequest(
                        auth = AuthDict(
                            type = "m.login.password",
                            session = uiaResponse.session,
                            user = userId,
                            password = password
                        )
                    ))
                }

                println("📥 deleteDevice: Auth response status: ${authResponse.status}")
                if (authResponse.status == HttpStatusCode.OK) {
                    println("✅ deleteDevice: Successfully deleted device $deviceId with authentication")
                    return true
                } else {
                    println("❌ deleteDevice: Authentication failed with status ${authResponse.status}")
                    try {
                        val errorBody = authResponse.body<String>()
                        println("📄 deleteDevice: Auth error response: $errorBody")
                    } catch (e: Exception) {
                        println("❌ deleteDevice: Could not read auth error response")
                    }
                }
            } catch (e: Exception) {
                println("❌ deleteDevice: Failed to parse UIA response: ${e.message}")
                // Log the raw response for debugging
                try {
                    val rawBody = response.body<String>()
                    println("📄 deleteDevice: Raw UIA response: $rawBody")
                } catch (e2: Exception) {
                    println("❌ deleteDevice: Could not read raw response")
                }
            }
        } else {
            println("❌ deleteDevice: Bad response status ${response.status}")
            // Log response body for debugging
            try {
                val responseBody = response.body<String>()
                println("📄 deleteDevice: Response body: $responseBody")
            } catch (e: Exception) {
                println("❌ deleteDevice: Could not read response body")
            }
        }
    } catch (e: Exception) {
        println("❌ deleteDevice: Exception: ${e.message}")
        e.printStackTrace()
    }
    return false
}

/**
 * Update device display name
 */
suspend fun updateDeviceDisplayName(deviceId: String, displayName: String): Boolean {
    val token = currentAccessToken ?: return false
    println("🔍 updateDeviceDisplayName: Starting request to update device $deviceId")
    try {
        val response = client.put("$currentHomeserver/_matrix/client/v3/devices/$deviceId") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("display_name" to kotlinx.serialization.json.JsonPrimitive(displayName))))
        }
        println("📥 updateDeviceDisplayName: Response status: ${response.status}")
        if (response.status == HttpStatusCode.OK) {
            println("✅ updateDeviceDisplayName: Successfully updated device $deviceId")
            return true
        } else {
            println("❌ updateDeviceDisplayName: Bad response status ${response.status}")
        }
    } catch (e: Exception) {
        println("❌ updateDeviceDisplayName: Exception: ${e.message}")
        e.printStackTrace()
    }
    return false
}