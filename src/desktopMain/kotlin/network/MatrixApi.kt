package network

/**
 * Main Matrix API entry point - centralized interface for all Matrix client operations
 *
 * This module provides a clean, organized interface to Matrix functionality
 * by organizing functions into specialized modules:
 *
 * - AuthApi.kt: Authentication and login functions (discoverHomeserver, login)
 * - RoomApi.kt: Room management and membership functions (getJoinedRooms, getRoomInvites, etc.)
 * - MessageApi.kt: Message sending and retrieval functions (getRoomMessages, sendMessage, etc.)
 * - GlobalState.kt: Shared state management (currentAccessToken, currentHomeserver, etc.)
 * - NetworkClient.kt: HTTP client and JSON utilities (client, json, etc.)
 *
 * All functions are available through package-level access within the network package.
 * External code can import this package to access all Matrix API functions.
 */
