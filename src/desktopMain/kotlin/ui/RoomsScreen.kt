package ui

import network.getJoinedRooms
import network.getRoomInvites
import network.acceptRoomInvite
import network.rejectRoomInvite

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import models.RoomInvite
import network.*
import crypto.*

/**
 * Custom hook for managing rooms data, invites, and encryption status
 */
@Composable
fun useRoomsData(): RoomsDataState {
    val scope = rememberCoroutineScope()
    var rooms by remember { mutableStateOf<List<String>>(emptyList()) }
    var roomEncryptionStatus by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var invites by remember { mutableStateOf<List<RoomInvite>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    fun loadRoomsData() {
        scope.launch {
            try {
                rooms = getJoinedRooms()
                invites = getRoomInvites()
                isLoading = false

                // Load encryption status asynchronously in the background
                scope.launch {
                    try {
                        val encryptionMap = mutableMapOf<String, Boolean>()
                        rooms.forEach { roomId ->
                            try {
                                val isEncrypted = withTimeout(3000L) { // 3 second timeout per room
                                    crypto.isRoomEncrypted(roomId)
                                }
                                encryptionMap[roomId] = isEncrypted
                            } catch (e: Exception) {
                                println("⚠️  Failed to check encryption for room $roomId: ${e.message}")
                                encryptionMap[roomId] = false // Default to not encrypted
                            }
                        }
                        roomEncryptionStatus = encryptionMap
                    } catch (e: Exception) {
                        println("⚠️  Failed to load encryption status: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                println("❌ Failed to load rooms: ${e.message}")
                isLoading = false
            }
        }
    }

    fun acceptInvite(roomId: String) {
        scope.launch {
            if (acceptRoomInvite(roomId)) {
                loadRoomsData()
            }
        }
    }

    fun rejectInvite(roomId: String) {
        scope.launch {
            rejectRoomInvite(roomId)
            invites = getRoomInvites()
        }
    }

    // Load data on first composition
    LaunchedEffect(Unit) {
        loadRoomsData()
    }

    return RoomsDataState(
        rooms = rooms,
        roomEncryptionStatus = roomEncryptionStatus,
        invites = invites,
        isLoading = isLoading,
        acceptInvite = ::acceptInvite,
        rejectInvite = ::rejectInvite
    )
}

data class RoomsDataState(
    val rooms: List<String>,
    val roomEncryptionStatus: Map<String, Boolean>,
    val invites: List<RoomInvite>,
    val isLoading: Boolean,
    val acceptInvite: (String) -> Unit,
    val rejectInvite: (String) -> Unit
)

/**
 * Top app bar component for the rooms screen
 */
@Composable
fun RoomsTopBar(
    onSettings: () -> Unit,
    onLogout: () -> Unit
) {
    TopAppBar(
        title = { Text("Rooms") },
        actions = {
            Button(onClick = onSettings) {
                Text("Settings")
            }
            Button(onClick = onLogout) {
                Text("Logout")
            }
        }
    )
}

/**
 * Component for displaying room invites
 */
@Composable
fun RoomInvitesSection(
    invites: List<RoomInvite>,
    onAcceptInvite: (String) -> Unit,
    onRejectInvite: (String) -> Unit
) {
    if (invites.isNotEmpty()) {
        Text("Room Invites", style = MaterialTheme.typography.h6, modifier = Modifier.padding(16.dp))
        invites.forEach { invite ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                elevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Invite from ${invite.sender}")
                        Text(invite.room_id, style = MaterialTheme.typography.caption)
                    }
                    Row {
                        Button(onClick = { onAcceptInvite(invite.room_id) }) {
                            Text("Accept")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { onRejectInvite(invite.room_id) }) {
                            Text("Reject")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Component for displaying a single joined room
 */
@Composable
fun JoinedRoomItem(
    roomId: String,
    isEncrypted: Boolean?,
    isChatWindowOpen: Boolean,
    onRoomSelected: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onRoomSelected(roomId) },
        elevation = 4.dp,
        backgroundColor = if (isChatWindowOpen) MaterialTheme.colors.primary.copy(alpha = 0.1f) else MaterialTheme.colors.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(roomId)
                // Show encryption status
                when (isEncrypted) {
                    true -> Text("🔒 Encrypted", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary)
                    false -> Text("Not encrypted", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.secondary)
                    null -> Text("Checking encryption...", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f))
                }
                // Show chat window status
                if (isChatWindowOpen) {
                    Text("💬 Chat open", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.secondary)
                }
            }
            Icon(Icons.Default.ArrowForward, contentDescription = "Enter room")
        }
    }
}

/**
 * Component for displaying joined rooms
 */
@Composable
fun JoinedRoomsSection(
    rooms: List<String>,
    roomEncryptionStatus: Map<String, Boolean>,
    windowManager: WindowManager?,
    onRoomSelected: (String) -> Unit
) {
    Text("Joined Rooms", style = MaterialTheme.typography.h6, modifier = Modifier.padding(16.dp))
    rooms.forEach { roomId ->
        val isChatWindowOpen = windowManager?.isChatWindowOpen(roomId) ?: false
        JoinedRoomItem(
            roomId = roomId,
            isEncrypted = roomEncryptionStatus[roomId],
            isChatWindowOpen = isChatWindowOpen,
            onRoomSelected = onRoomSelected
        )
    }
}

@Composable
fun RoomsScreen(
    onRoomSelected: (String) -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    windowManager: WindowManager? = null
) {
    val roomsData = useRoomsData()

    Column(modifier = Modifier.fillMaxSize()) {
        RoomsTopBar(
            onSettings = onSettings,
            onLogout = onLogout
        )

        if (roomsData.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (roomsData.invites.isNotEmpty()) {
                    item {
                        RoomInvitesSection(
                            invites = roomsData.invites,
                            onAcceptInvite = roomsData.acceptInvite,
                            onRejectInvite = roomsData.rejectInvite
                        )
                    }
                }

                item {
                    JoinedRoomsSection(
                        rooms = roomsData.rooms,
                        roomEncryptionStatus = roomsData.roomEncryptionStatus,
                        windowManager = windowManager,
                        onRoomSelected = onRoomSelected
                    )
                }
            }
        }
    }
}
