use std::collections::HashMap;
use std::sync::Mutex;
use vodozemac::{olm::Account, megolm::GroupSession, Curve25519PublicKey, Ed25519PublicKey};
use serde::{Deserialize, Serialize};
use uniffi::export;

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct DeviceKeys {
    pub user_id: String,
    pub device_id: String,
    pub ed25519_key: String,
    pub curve25519_key: String,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct EncryptedMessage {
    pub algorithm: String,
    pub ciphertext: String,
    pub sender_key: String,
    pub session_id: String,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct DecryptionResult {
    pub plaintext: String,
    pub sender_key: String,
}

static ENCRYPTION_STATE: Mutex<Option<EncryptionManager>> = Mutex::new(None);

pub struct EncryptionManager {
    olm_account: Account,
    outbound_sessions: HashMap<String, GroupSession>,
    device_keys: DeviceKeys,
}

impl EncryptionManager {
    pub fn new(user_id: String, device_id: String) -> Self {
        let olm_account = Account::new();
        let identity_keys = olm_account.identity_keys();

        let device_keys = DeviceKeys {
            user_id,
            device_id,
            ed25519_key: hex::encode(identity_keys.ed25519.to_bytes()),
            curve25519_key: hex::encode(identity_keys.curve25519.to_bytes()),
        };

        Self {
            olm_account,
            outbound_sessions: HashMap::new(),
            device_keys,
        }
    }

    pub fn get_device_keys(&self) -> DeviceKeys {
        self.device_keys.clone()
    }

    pub fn create_outbound_session(&mut self, room_id: &str) -> Result<(), String> {
        let group_session = GroupSession::new();
        self.outbound_sessions.insert(room_id.to_string(), group_session);
        Ok(())
    }

    pub fn encrypt_message(&mut self, content: &str, room_id: &str) -> Result<EncryptedMessage, String> {
        let session = self.outbound_sessions.get_mut(room_id)
            .ok_or_else(|| format!("No outbound session for room {}", room_id))?;

        let message = session.encrypt(content);
        let identity_keys = self.olm_account.identity_keys();

        Ok(EncryptedMessage {
            algorithm: "m.megolm.v1.aes-sha2".to_string(),
            ciphertext: hex::encode(message.ciphertext()),
            sender_key: hex::encode(identity_keys.curve25519.to_bytes()),
            session_id: hex::encode(session.session_id()),
        })
    }

    pub fn decrypt_message(&mut self, encrypted: &EncryptedMessage, room_id: &str) -> Result<DecryptionResult, String> {
        // For now, we'll implement basic decryption
        // In a full implementation, you'd need to manage inbound sessions
        // and handle key sharing properly
        Err("Decryption not yet implemented - requires inbound session management".to_string())
    }
}

#[uniffi::export]
pub fn init_encryption(user_id: String, device_id: String) -> Result<(), String> {
    let manager = EncryptionManager::new(user_id, device_id);
    *ENCRYPTION_STATE.lock().unwrap() = Some(manager);
    Ok(())
}

#[uniffi::export]
pub fn get_device_keys() -> Result<DeviceKeys, String> {
    let state = ENCRYPTION_STATE.lock().unwrap();
    match &*state {
        Some(manager) => Ok(manager.get_device_keys()),
        None => Err("Encryption not initialized".to_string()),
    }
}

#[uniffi::export]
pub fn create_outbound_session(room_id: String) -> Result<(), String> {
    let mut state = ENCRYPTION_STATE.lock().unwrap();
    match &mut *state {
        Some(manager) => manager.create_outbound_session(&room_id),
        None => Err("Encryption not initialized".to_string()),
    }
}

#[uniffi::export]
pub fn encrypt_message(content: String, room_id: String) -> Result<EncryptedMessage, String> {
    let mut state = ENCRYPTION_STATE.lock().unwrap();
    match &mut *state {
        Some(manager) => manager.encrypt_message(&content, &room_id),
        None => Err("Encryption not initialized".to_string()),
    }
}

#[uniffi::export]
pub fn decrypt_message(encrypted: String, room_id: String) -> Result<DecryptionResult, String> {
    let encrypted_msg: EncryptedMessage = serde_json::from_str(&encrypted)
        .map_err(|e| format!("Invalid encrypted message format: {}", e))?;

    let mut state = ENCRYPTION_STATE.lock().unwrap();
    match &mut *state {
        Some(manager) => manager.decrypt_message(&encrypted_msg, &room_id),
        None => Err("Encryption not initialized".to_string()),
    }
}

uniffi::setup_scaffolding!("feverdream_crypto");
