package crypto

import network.currentUserId

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

        // Test if we can decrypt our own messages (basic crypto functionality test)
        // Note: This doesn't guarantee we can decrypt messages from other devices
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
        machine.decryptRoomEvent(
            roomId = roomId,
            event = testEventJson,
            decryptionSettings = decryptionSettings,
            handleVerificationEvents = false,
            strictShields = false
        )

        // If we can decrypt our own test message, basic crypto is working
        // This doesn't mean we have keys for messages from other devices
        println("✅ Basic crypto test successful - can encrypt/decrypt own messages")
        true
    } catch (e: Exception) {
        // Handle session expiration gracefully instead of letting it panic
        if (e.message?.contains("Session expired") == true || e.message?.contains("panicked") == true) {
            println("⚠️  Session expired detected in hasRoomKey, attempting comprehensive renewal...")

            // Use the comprehensive ensureRoomEncryption function
            try {
                val renewalSuccess = ensureRoomEncryption(roomId)
                if (renewalSuccess) {
                    // CRITICAL FIX: Add delay after renewal to allow session to be fully established
                    println("⏳ Waiting for session renewal to complete...")
                    kotlinx.coroutines.delay(3000) // 3 second delay

                    // Test encryption again to ensure session is truly ready
                    try {
                        machine.encrypt(roomId, "m.room.message", """{"msgtype": "m.text", "body": "final_test"}""")
                        println("✅ Session fully ready after renewal")
                    } catch (finalTestException: Exception) {
                        println("⚠️  Session still not ready after delay: ${finalTestException.message}")
                        return false
                    }

                    // Test again after comprehensive renewal and delay
                    return try {
                        machine.encrypt(roomId, "m.room.message", """{"msgtype": "m.text", "body": "test_after_renewal"}""")
                        println("✅ Session renewal successful with delay")
                        true
                    } catch (retryException: Exception) {
                        println("⚠️  Session renewal verification failed after delay: ${retryException.message}")
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
            println("⚠️  Basic crypto test failed: ${e.message}")
            false
        }
    }
}

// Public function to check if we can encrypt messages for a room
suspend fun canEncryptRoom(roomId: String): Boolean {
    val machine = OlmMachineManager.olmMachine ?: return false
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
                    // CRITICAL FIX: Add delay after renewal to allow session to be fully established
                    println("⏳ Waiting for session renewal to complete...")
                    kotlinx.coroutines.delay(5000) // 5 second delay

                    // Test encryption again to ensure session is truly ready
                    try {
                        machine.encrypt(roomId, "m.room.message", """{"msgtype": "m.text", "body": "final_test"}""")
                        println("✅ Session fully ready after renewal")
                    } catch (finalTestException: Exception) {
                        println("⚠️  Session still not ready after delay: ${finalTestException.message}")
                        return false
                    }

                    // Test again after comprehensive renewal and delay
                    return try {
                        machine.encrypt(roomId, "m.room.message", """{"msgtype": "m.text", "body": "test_after_renewal"}""")
                        println("✅ Session renewed successfully with delay")
                        true
                    } catch (retryException: Exception) {
                        println("⚠️  Session renewal verification failed after delay: ${retryException.message}")
                        false
                    }
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