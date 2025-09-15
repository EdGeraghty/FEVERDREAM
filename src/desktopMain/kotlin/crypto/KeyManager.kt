package crypto

import network.currentUserId
import network.json
import crypto.OlmMachineManager

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import network.*
import network.client
import models.*
import org.matrix.rustcomponents.sdk.crypto.*
import uniffi.matrix_sdk_crypto.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.modules.*
import kotlinx.serialization.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

suspend fun hasRoomKey(roomId: String): Boolean {
    val machine = OlmMachineManager.olmMachine ?: return false

    // Get room members to check if multi-device
    val roomMembers = withTimeout(10000L) {
        network.getRoomMembers(roomId)
    }
    val isMultiDevice = crypto.isMultiDeviceRoom(roomMembers)

    if (!isMultiDevice) {
        return false // No room keys in single-device rooms
    }

    return try {
        // First check if we can encrypt (outbound capability)
        val messageContent = """{"msgtype":"m.text","body":${JsonPrimitive("test")}}"""
        machine.encrypt(roomId, "m.room.message", messageContent)
        true
    } catch (e: Exception) {
        println("⚠️  Cannot encrypt for room $roomId: ${e.message}")
        false
    }
}

// Public function to check if we can encrypt messages for a room
suspend fun canEncryptRoom(roomId: String): Boolean {
    val machine = OlmMachineManager.olmMachine ?: return false

    // Get room members to check if multi-device
    val roomMembers = withTimeout(10000L) {
        network.getRoomMembers(roomId)
    }
    val isMultiDevice = crypto.isMultiDeviceRoom(roomMembers)

    if (!isMultiDevice) {
        return false // Cannot encrypt in single-device rooms
    }

    // For multi-device rooms, check if we can actually encrypt
    return try {
        val messageContent = """{"msgtype":"m.text","body":${JsonPrimitive("test")}}"""
        machine.encrypt(roomId, "m.room.message", messageContent)
        true
    } catch (e: Exception) {
        println("⚠️  Encryption test failed: ${e.message}")
        false
    }
}