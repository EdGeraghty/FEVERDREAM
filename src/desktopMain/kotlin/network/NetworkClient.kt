package network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.apache.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.*
import models.*
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.ssl.SSLContextBuilder
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    serializersModule = SerializersModule {
        // Simplified serializers module
    }
}

val client = HttpClient(Apache) {
    install(ContentNegotiation) {
        json(json)
    }
    engine {
        // Configure Apache HttpClient for better TLS support
        customizeClient {
            // Allow all SSL protocols including older ones
            setSSLContext(SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(
                    object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                    }
                ), java.security.SecureRandom())
            })
            setSSLHostnameVerifier { _, _ -> true }
        }
        // Configure connection settings
        connectTimeout = 10000
        socketTimeout = 10000
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
