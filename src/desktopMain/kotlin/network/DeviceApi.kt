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
 * Delete a single device
 */
suspend fun deleteDevice(deviceId: String): Boolean {
    val token = currentAccessToken ?: return false
    println("🔍 deleteDevice: Starting request to delete device $deviceId")
    try {
        val response = client.delete("$currentHomeserver/_matrix/client/v3/devices/$deviceId") {
            bearerAuth(token)
        }
        println("📥 deleteDevice: Response status: ${response.status}")
        if (response.status == HttpStatusCode.OK) {
            println("✅ deleteDevice: Successfully deleted device $deviceId")
            return true
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