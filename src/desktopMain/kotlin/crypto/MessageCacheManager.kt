package crypto

import models.Event

/**
 * Manages the global message cache for real-time updates.
 * Provides thread-safe operations for storing and retrieving room messages.
 */
class MessageCacheManager {

    companion object {
        // Message cache for real-time updates
        private val roomMessageCache = mutableMapOf<String, MutableList<Event>>()

        /**
         * Get messages for a specific room.
         */
        fun getRoomMessages(roomId: String): List<Event> {
            return roomMessageCache[roomId] ?: emptyList()
        }

        /**
         * Add a message to the cache for a specific room.
         */
        fun addMessage(roomId: String, event: Event) {
            roomMessageCache.getOrPut(roomId) { mutableListOf() }.add(event)
        }

        /**
         * Replace all messages for a specific room.
         */
        fun setRoomMessages(roomId: String, messages: List<Event>) {
            roomMessageCache[roomId] = messages.toMutableList()
        }

        /**
         * Clear messages for a specific room.
         */
        fun clearRoomMessages(roomId: String) {
            roomMessageCache.remove(roomId)
        }

        /**
         * Clear all cached messages.
         */
        fun clearAllMessages() {
            roomMessageCache.clear()
        }

        /**
         * Get the number of cached messages for a room.
         */
        fun getMessageCount(roomId: String): Int {
            return roomMessageCache[roomId]?.size ?: 0
        }

        /**
         * Check if a room has cached messages.
         */
        fun hasMessages(roomId: String): Boolean {
            return roomMessageCache.containsKey(roomId) && roomMessageCache[roomId]?.isNotEmpty() == true
        }
    }
}
