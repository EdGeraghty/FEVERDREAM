package network

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import models.*

/**
 * Room management API functions for Matrix client
 */
suspend fun getJoinedRooms(): List<String> {
    val token = currentAccessToken ?: return emptyList()
    println("🔍 getJoinedRooms: Starting request to $currentHomeserver")
    try {
        val response = withTimeout(10000L) { // 10 second timeout
            println("🌐 getJoinedRooms: Making HTTP request...")
            client.get("$currentHomeserver/_matrix/client/v3/joined_rooms") {
                bearerAuth(token)
            }
        }
        println("📥 getJoinedRooms: Response status: ${response.status}")
        if (response.status == HttpStatusCode.OK) {
            val roomsResponse = response.body<JoinedRoomsResponse>()
            println("✅ getJoinedRooms: Successfully retrieved ${roomsResponse.joined_rooms.size} rooms")
            return roomsResponse.joined_rooms
        } else {
            println("❌ getJoinedRooms: Bad response status ${response.status}")
        }
    } catch (e: TimeoutCancellationException) {
        println("❌ getJoinedRooms: Request timed out after 10 seconds")
    } catch (e: Exception) {
        println("❌ getJoinedRooms: Exception: ${e.message}")
        e.printStackTrace()
    }
    return emptyList()
}

suspend fun getRoomInvites(): List<RoomInvite> {
    val token = currentAccessToken ?: return emptyList()
    println("🔍 getRoomInvites: Starting request to $currentHomeserver")
    try {
        val response = withTimeout(10000L) { // 10 second timeout
            println("🌐 getRoomInvites: Making HTTP request...")
            client.get("$currentHomeserver/_matrix/client/v3/sync") {
                bearerAuth(token)
                parameter("filter", """{"room":{"invite":{"state":{"limit":0},"timeline":{"limit":0}},"leave":{"state":{"limit":0},"timeline":{"limit":0}},"join":{"state":{"limit":0},"timeline":{"limit":0}},"knock":{"state":{"limit":0},"timeline":{"limit":0}},"ban":{"state":{"limit":0},"timeline":{"limit":0}}},"presence":{"limit":0},"account_data":{"limit":0},"receipts":{"limit":0}}""")
            }
        }
        println("📥 getRoomInvites: Response status: ${response.status}")
        if (response.status == HttpStatusCode.OK) {
            val syncResponse = response.body<SyncResponse>()
            val invites = mutableListOf<RoomInvite>()

            syncResponse.rooms?.invite?.forEach { (roomId, invitedRoom) ->
                val inviteState = invitedRoom.invite_state
                if (inviteState != null) {
                    val sender = inviteState.events.firstOrNull { it.type == "m.room.member" && it.state_key == currentUserId }?.sender ?: ""
                    invites.add(RoomInvite(roomId, sender, inviteState))
                }
            }

            println("✅ getRoomInvites: Successfully retrieved ${invites.size} invites")
            return invites
        } else {
            println("❌ getRoomInvites: Bad response status ${response.status}")
        }
    } catch (e: TimeoutCancellationException) {
        println("❌ getRoomInvites: Request timed out after 10 seconds")
    } catch (e: Exception) {
        println("❌ getRoomInvites: Exception: ${e.message}")
        e.printStackTrace()
    }
    return emptyList()
}

suspend fun acceptRoomInvite(roomId: String): Boolean {
    val token = currentAccessToken ?: return false
    try {
        val response = client.post("$currentHomeserver/_matrix/client/v3/rooms/$roomId/join") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(JsonObject(emptyMap()))
        }
        return response.status == HttpStatusCode.OK
    } catch (e: Exception) {
        println("Accept invite failed: ${e.message}")
    }
    return false
}

suspend fun rejectRoomInvite(roomId: String): Boolean {
    val token = currentAccessToken ?: return false
    try {
        val response = client.post("$currentHomeserver/_matrix/client/v3/rooms/$roomId/leave") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(JsonObject(emptyMap()))
        }
        return response.status == HttpStatusCode.OK
    } catch (e: Exception) {
        println("Reject invite failed: ${e.message}")
    }
    return false
}

suspend fun getRoomMembers(roomId: String): List<String> {
    val token = currentAccessToken ?: return emptyList()
    try {
        val response = client.get("$currentHomeserver/_matrix/client/v3/rooms/$roomId/members") {
            bearerAuth(token)
        }
        if (response.status == HttpStatusCode.OK) {
            val membersResponse = response.body<RoomMembersResponse>()
            return membersResponse.chunk
                .filter { it.content.membership == "join" }
                .map { it.state_key }
        }
    } catch (e: Exception) {
        println("Get room members failed: ${e.message}")
    }
    return emptyList()
}
