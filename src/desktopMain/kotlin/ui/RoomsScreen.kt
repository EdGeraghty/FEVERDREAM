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

@Composable
fun RoomsScreen(
    onRoomSelected: (String) -> Unit,
    onLogout: () -> Unit,
    windowManager: WindowManager? = null
) {
    // Use GlobalScope for long-running operations
    val scope = remember { kotlinx.coroutines.GlobalScope }
    var rooms by remember { mutableStateOf<List<String>>(emptyList()) }
    var roomEncryptionStatus by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var invites by remember { mutableStateOf<List<RoomInvite>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                rooms = getJoinedRooms()
                invites = getRoomInvites()
                isLoading = false

                // Load encryption status asynchronously in the background
                // This prevents blocking the UI if the encryption check is slow
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

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Rooms") },
            actions = {
                Button(onClick = onLogout) {
                    Text("Logout")
                }
            }
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (invites.isNotEmpty()) {
                    item {
                        Text("Room Invites", style = MaterialTheme.typography.h6, modifier = Modifier.padding(16.dp))
                    }
                    items(invites) { invite ->
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
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                if (acceptRoomInvite(invite.room_id)) {
                                                    rooms = getJoinedRooms()
                                                    invites = getRoomInvites()
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Accept")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                rejectRoomInvite(invite.room_id)
                                                invites = getRoomInvites()
                                            }
                                        }
                                    ) {
                                        Text("Reject")
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Text("Joined Rooms", style = MaterialTheme.typography.h6, modifier = Modifier.padding(16.dp))
                }
                items(rooms) { roomId ->
                    val isChatWindowOpen = windowManager?.isChatWindowOpen(roomId) ?: false
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
                                val isEncrypted = roomEncryptionStatus[roomId] ?: false
                                if (isEncrypted) {
                                    Text("🔒 Encrypted", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary)
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
            }
        }
    }
}
