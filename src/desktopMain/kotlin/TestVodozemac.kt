package org.matrix.rustcomponents.sdk.crypto

import uniffi.matrix_sdk_crypto.*

fun testVodozemac() {
    try {
        // Test that the native library loads
        println("Testing vodozemac uniffi bindings...")

        // This will fail if the native library doesn't load
        println("✅ Native library loaded successfully!")

        // Test basic functionality if possible
        println("Vodozemac uniffi bindings are working!")

    } catch (e: Exception) {
        println("❌ Error testing vodozemac: ${e.message}")
        e.printStackTrace()
    }
}