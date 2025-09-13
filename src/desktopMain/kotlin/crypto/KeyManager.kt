package crypto

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
    val machine = olmMachine ?: return false
    return try {
        // First check if we can encrypt (outbound capability)
        val canEncrypt = try {
            machine.encrypt(roomId, "m.room.message", """{"msgtype": "m.text", "body": "test"}""")
            true
        } catch (e: Exception) {
            println("⚠️  Cannot encrypt for room $roomId: ${e.message}")
            false
        }

        if (!canEncrypt) {
            return false
        }

        // Also test if we can decrypt by creating and attempting to decrypt a test message
        // This ensures we have both outbound and inbound capability
        val eventId = "\$test:${System.currentTimeMillis()}"
        val testEventJson = """{
            "type": "m.room.encrypted",
            "event_id": "$eventId",
            "sender": "${currentUserId ?: "@test:example.com"}",
            "origin_server_ts": ${System.currentTimeMillis()},
            "room_id": "$roomId",
            "content": ${machine.encrypt(roomId, "m.room.message", """{"msgtype": "m.text", "body": "test"}""")}
        }"""

        val decryptionSettings = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)
        val decrypted = machine.decryptRoomEvent(
            roomId = roomId,
            event = testEventJson,
            decryptionSettings = decryptionSettings,
            handleVerificationEvents = false,
            strictShields = false
        )

        // If we can decrypt our own test message, we have full room key capability
        println("✅ Room key test successful - can encrypt and decrypt")
        true
    } catch (e: Exception) {
        // Handle session expiration gracefully instead of letting it panic
        if (e.message?.contains("Session expired") == true || e.message?.contains("panicked") == true) {
            println("⚠️  Session expired detected in hasRoomKey, attempting comprehensive renewal...")

            // Use the comprehensive ensureRoomEncryption function
            try {
                val renewalSuccess = ensureRoomEncryption(roomId)
                if (renewalSuccess) {
                    // Test again after comprehensive renewal
                    return try {
                        machine.encrypt(roomId, "m.room.message", """{"msgtype": "m.text", "body": "test_after_renewal"}""")
                        println("✅ Session renewal successful")
                        true
                    } catch (retryException: Exception) {
                        println("⚠️  Session renewal verification failed: ${retryException.message}")
                        false
                    }
                } else {
                    println("❌ Comprehensive session renewal failed")
                    return false
                }
            } catch (renewalException: Exception) {
                println("⚠️  Session renewal failed: ${renewalException.message}")
                return false
            }
        } else {
            println("⚠️  Room key test failed: ${e.message}")
        }
        false
    }
}

// Public function to check if we can encrypt messages for a room
suspend fun canEncryptRoom(roomId: String): Boolean {
    val machine = olmMachine ?: return false
    return try {
        machine.encrypt(roomId, "m.room.message", """{"msgtype": "m.text", "body": "test"}""")
        true
    } catch (e: Exception) {
        println("⚠️  Encryption test failed: ${e.message}")
        // Try to renew session if it failed
        if (e.message?.contains("Session expired") == true || e.message?.contains("panicked") == true) {
            println("🔄 Attempting comprehensive session renewal in canEncryptRoom...")
            try {
                // Use the comprehensive ensureRoomEncryption function
                val renewalSuccess = ensureRoomEncryption(roomId)
                if (renewalSuccess) {
                    // Test again after comprehensive renewal
                    machine.encrypt(roomId, "m.room.message", """{"msgtype": "m.text", "body": "test"}""")
                    println("✅ Session renewed successfully via ensureRoomEncryption")
                    return true
                } else {
                    println("❌ Comprehensive session renewal failed")
                    return false
                }
            } catch (renewalException: Exception) {
                println("❌ Session renewal failed: ${renewalException.message}")
                return false
            }
        } else {
            return false
        }
    }
}