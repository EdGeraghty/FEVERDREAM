package crypto

import org.matrix.rustcomponents.sdk.crypto.*
import java.io.File

/**
 * Manages the OlmMachine lifecycle, initialization, and global state.
 * Handles crypto store management and device ID mismatch recovery.
 */
class OlmMachineManager {
    companion object {
        // Global OlmMachine instance for encryption operations
        var olmMachine: OlmMachine? = null
            private set

        private const val CRYPTO_STORE_PATH = "crypto_store"

        /**
         * Initialize Olm encryption for the given user and device.
         * Handles crypto store creation and device ID mismatch recovery.
         */
        fun initializeEncryption(userId: String, deviceId: String) {
            if (olmMachine != null) {
                println("ℹ️  OlmMachine already initialized")
                return
            }

            // Create crypto store directory
            val cryptoStoreDir = File(CRYPTO_STORE_PATH)
            cryptoStoreDir.mkdirs()

            try {
                // Create OlmMachine with persistent storage
                olmMachine = OlmMachine(userId, deviceId, CRYPTO_STORE_PATH, null)

                val identityKeys = olmMachine!!.identityKeys()
                println("🔑 Matrix SDK Crypto initialized")
                println("Curve25519 key: ${identityKeys["curve25519"]}")
                println("Ed25519 key: ${identityKeys["ed25519"]}")

                // Debug: Check if we're loading from existing store
                val existingKeyCounts = olmMachine!!.roomKeyCounts()
                println("🔑 Existing room key counts on initialization - Total: ${existingKeyCounts.total}, Backed up: ${existingKeyCounts.backedUp}")

                if (existingKeyCounts.total > 0) {
                    println("✅ OlmMachine loaded from existing crypto store with ${existingKeyCounts.total} room keys")
                } else {
                    println("🆕 OlmMachine created with new crypto store")
                }
            } catch (e: Exception) {
                handleInitializationError(e, userId, deviceId)
            }
        }

        /**
         * Handle OlmMachine initialization errors, including device ID mismatch recovery.
         */
        private fun handleInitializationError(error: Exception, userId: String, deviceId: String) {
            // Check if the error is due to account mismatch (device ID change)
            if (error.message?.contains("the account in the store doesn't match the account in the constructor") == true) {
                println("⚠️  Crypto store mismatch detected (device ID changed). Clearing old crypto store and reinitializing...")
                try {
                    resetCryptoStore()
                    reinitializeOlmMachine(userId, deviceId)
                } catch (retryException: Exception) {
                    println("❌ Failed to reinitialize OlmMachine after store reset: ${retryException.message}")
                    retryException.printStackTrace()
                }
            } else {
                println("❌ Failed to initialize OlmMachine: ${error.message}")
                error.printStackTrace()
            }
        }

        /**
         * Reset the crypto store by deleting and recreating the directory.
         */
        private fun resetCryptoStore() {
            val cryptoStoreDir = File(CRYPTO_STORE_PATH)
            if (cryptoStoreDir.exists()) {
                cryptoStoreDir.deleteRecursively()
                println("🗑️  Old crypto store cleared")
            }
            cryptoStoreDir.mkdirs()
        }

        /**
         * Reinitialize OlmMachine after store reset.
         */
        private fun reinitializeOlmMachine(userId: String, deviceId: String) {
            olmMachine = OlmMachine(userId, deviceId, CRYPTO_STORE_PATH, null)

            val identityKeys = olmMachine!!.identityKeys()
            println("🔑 Matrix SDK Crypto reinitialized after store reset")
            println("Curve25519 key: ${identityKeys["curve25519"]}")
            println("Ed25519 key: ${identityKeys["ed25519"]}")

            val existingKeyCounts = olmMachine!!.roomKeyCounts()
            println("🔑 Room key counts after reset - Total: ${existingKeyCounts.total}, Backed up: ${existingKeyCounts.backedUp}")
            println("🆕 New crypto store created")
        }

        /**
         * Clear the OlmMachine instance (used during logout).
         */
        fun clearOlmMachine() {
            olmMachine = null
        }

        /**
         * Check if OlmMachine is initialized.
         */
        fun isInitialized(): Boolean = olmMachine != null
    }
}
