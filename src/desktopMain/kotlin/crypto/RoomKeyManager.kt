package crypto

import org.matrix.rustcomponents.sdk.crypto.RoomKeyCounts

/**
 * Manages room key operations including key counts and identity keys.
 */
class RoomKeyManager {

    /**
     * Get the current room key counts (total and backed up).
     */
    fun getRoomKeyCount(): RoomKeyCounts {
        val machine = OlmMachineManager.olmMachine ?: return RoomKeyCounts(0L, 0L)
        return try {
            machine.roomKeyCounts()
        } catch (e: Exception) {
            println("❌ Failed to get room key counts: ${e.message}")
            RoomKeyCounts(0L, 0L)
        }
    }

    /**
     * Get the identity keys (Curve25519 and Ed25519) for the current device.
     */
    fun getIdentityKeys(): Map<String, String>? {
        val machine = OlmMachineManager.olmMachine ?: return null
        return try {
            machine.identityKeys()
        } catch (e: Exception) {
            println("❌ Failed to get identity keys: ${e.message}")
            null
        }
    }
}
