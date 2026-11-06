package com.oms.client.data

/** Crypto failure must never turn a new pairing into a plaintext or mixed-credential save. */
internal object PairingSecretPersistence {
    fun save(plain: String, encrypt: (String) -> String?, commit: (String) -> Unit) {
        check(plain.isNotBlank()) { "Pairing secret is unavailable" }
        val encrypted = encrypt(plain)?.takeIf { it.isNotBlank() }
        checkNotNull(encrypted) { "Unable to encrypt pairing secret; existing pairing was retained" }
        commit(encrypted)
    }

    fun read(
        encrypted: String?,
        legacy: String,
        decrypt: (String) -> String?,
        encrypt: (String) -> String?,
        migrate: (String) -> Unit
    ): String {
        // An encrypted entry is authoritative, including when Keystore is unavailable.
        // Falling through could resurrect a stale plaintext secret from an older save.
        if (!encrypted.isNullOrBlank()) return decrypt(encrypted).orEmpty()
        if (legacy.isNotBlank()) {
            encrypt(legacy)?.takeIf { it.isNotBlank() }?.let(migrate)
        }
        return legacy
    }
}
