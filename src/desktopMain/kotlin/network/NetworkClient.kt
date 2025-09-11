package network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.*
import models.*

val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    serializersModule = SerializersModule {
        // Simplified serializers module
    }
}

val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(json)
    }
}

// Function to properly close the HTTP client
fun closeHttpClient() {
    client.close()
}

// Utility function to convert Maps to HashMap recursively to avoid serialization issues with SingletonMap
@Suppress("UNCHECKED_CAST")
fun convertMapToHashMap(map: Any?): Any? {
    return when (map) {
        is Map<*, *> -> {
            if (map.isEmpty()) {
                mutableMapOf<String, Any>()
            } else {
                map.mapValues { convertMapToHashMap(it.value) }.toMutableMap()
            }
        }
        is List<*> -> map.map { convertMapToHashMap(it) }
        else -> map
    }
}

// Utility function to convert Any to JsonElement for serialization
fun anyToJsonElement(value: Any?): JsonElement {
    return when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.map { it.key.toString() to anyToJsonElement(it.value) }.toMap())
        is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
        else -> JsonPrimitive(value.toString()) // fallback for other types
    }
}
