package models

import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class LoginRequest(val type: String = "m.login.password", val user: String, val password: String)

@Serializable
data class LoginRequestV2(val type: String = "m.login.password", val identifier: Identifier, val password: String)

@Serializable
data class Identifier(val type: String = "m.id.user", val user: String)

@Serializable
data class LoginResponse(val user_id: String, val access_token: String, val device_id: String)

@Serializable
data class JoinedRoomsResponse(val joined_rooms: List<String>)

@Serializable
data class RoomInvite(val room_id: String, val sender: String, val state: RoomState)

@Serializable
data class RoomState(val events: List<StateEvent>)

@Serializable
data class StateEvent(val type: String, val state_key: String, val sender: String, val content: JsonElement)

@Serializable
data class SyncResponse(
    val rooms: Rooms? = null,
    val toDevice: ToDevice? = null,
    val next_batch: String? = null
)

@Serializable
data class ToDevice(val events: List<Event> = emptyList())

@Serializable
data class Rooms(val invite: Map<String, InvitedRoom>? = null, val join: Map<String, JoinedRoom>? = null)

@Serializable
data class InvitedRoom(val invite_state: RoomState? = null)

@Serializable
data class JoinedRoom(val state: RoomState? = null, val timeline: Timeline? = null)

@Serializable
data class Timeline(val events: List<Event> = emptyList())

@Serializable
data class Event(val type: String, val event_id: String, val sender: String, val origin_server_ts: Long, val content: JsonElement)

@Serializable
data class MessageContent(val msgtype: String = "m.text", val body: String? = null)

@Serializable
data class EncryptedMessageContent(
    val algorithm: String,
    val ciphertext: JsonElement, // For Olm it's Map<String, CiphertextInfo>, for Megolm it's String
    val sender_key: String,
    val device_id: String? = null,
    val session_id: String? = null
)

@Serializable
data class ServerDelegationResponse(
    @SerialName("m.server")
    val mServer: String? = null
)

@Serializable
data class ClientWellKnownResponse(
    @SerialName("m.homeserver")
    val homeserver: HomeserverInfo? = null,
    @SerialName("org.matrix.msc3575.proxy")
    val proxy: JsonElement? = null
)

@Serializable
data class HomeserverInfo(
    val base_url: String
)

@Serializable
data class SendMessageRequest(val msgtype: String = "m.text", val body: String)

@Serializable
data class RoomMessagesResponse(val chunk: List<Event>)

@Serializable
data class SessionData(
    val userId: String,
    val deviceId: String,
    val accessToken: String,
    val homeserver: String,
    val syncToken: String = ""
)

@Serializable
data class DeviceInfo(
    val device_id: String,
    val display_name: String? = null,
    val last_seen_ip: String? = null,
    val last_seen_ts: Long? = null,
    val user_id: String
)

@Serializable
data class DevicesResponse(val devices: List<DeviceInfo>)

@Serializable
data class DeleteDevicesRequest(val devices: List<String>)

@Serializable
data class DeleteDeviceRequest(val auth: AuthDict? = null)

@Serializable
data class AuthDict(
    val type: String,
    val session: String? = null,
    val user: String? = null,
    val password: String? = null
)

@Serializable
data class UIAFlows(
    val stages: List<String>
)

@Serializable
data class UIAChallenge(
    val session: String,
    val flows: List<UIAFlows>,
    val params: JsonObject? = null
)

@Serializable
data class RoomMembersResponse(val chunk: List<MemberEvent>)

@Serializable
data class MemberEvent(
    val type: String,
    val state_key: String,
    val sender: String,
    val content: MemberContent,
    val origin_server_ts: Long
)

@Serializable
data class MemberContent(
    val membership: String,
    val displayname: String? = null,
    val avatar_url: String? = null
)

@Serializable
data class DeleteDevicesRequestWithAuth(
    val devices: List<String>,
    val auth: AuthDict
)
