package network

// Global state for Matrix client - accessible from all packages
var currentAccessToken: String? = null
var currentHomeserver: String = "https://matrix.org"
var currentDeviceId: String? = null
var currentUserId: String? = null
var currentSyncToken: String = ""
