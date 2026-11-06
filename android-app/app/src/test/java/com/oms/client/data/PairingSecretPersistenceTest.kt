package com.oms.client.data

import org.junit.Assert.*
import org.junit.Test

class PairingSecretPersistenceTest {
    @Test
    fun encryptionFailureRetainsTheEntireOldPairing() {
        val stored = mutableMapOf("mac_id" to "old-mac", "pair_secret_enc" to "old-ciphertext")
        val before = stored.toMap()
        try {
            PairingSecretPersistence.save("new-secret", { null }) { encrypted ->
                stored["mac_id"] = "new-mac"
                stored["pair_secret_enc"] = encrypted
            }
            fail("Encryption failure must prevent the commit")
        } catch (_: IllegalStateException) {}
        assertEquals(before, stored)
    }

    @Test
    fun emptyCredentialCannotEraseAnExistingPairing() {
        var committed = false
        try {
            PairingSecretPersistence.save("", { "" }) { committed = true }
            fail("Empty credential must fail")
        } catch (_: IllegalStateException) {}
        assertFalse(committed)
    }

    @Test
    fun successfulSavePassesOnlyEncryptedSecretToAtomicCommit() {
        val stored = mutableMapOf("pair_secret_enc" to "old", "pair_secret_hex" to "stale")
        PairingSecretPersistence.save("new-secret", { "encrypted:$it" }) {
            stored["pair_secret_enc"] = it
            stored.remove("pair_secret_hex")
        }
        assertEquals(mapOf("pair_secret_enc" to "encrypted:new-secret"), stored)
        assertEquals("new-secret", PairingSecretPersistence.read(stored["pair_secret_enc"], "", {
            it.removePrefix("encrypted:")
        }, { error("Must not re-encrypt an existing ciphertext") }, { error("Must not migrate") }))
    }

    @Test
    fun unreadableCiphertextNeverResurrectsStalePlaintext() {
        assertEquals("", PairingSecretPersistence.read("old-ciphertext", "stale-plain", { null },
            { error("Must not migrate stale plaintext") }, { error("Must not commit") }))
    }

    @Test
    fun legacyMigrationFailureLeavesLegacyUsableAndUntouched() {
        var migrated = false
        assertEquals("legacy", PairingSecretPersistence.read(null, "legacy", { null }, { null }, { migrated = true }))
        assertFalse(migrated)
    }

    @Test
    fun legacyMigrationReplacesPlaintextWithCiphertext() {
        val stored = mutableMapOf("pair_secret_hex" to "legacy")
        assertEquals("legacy", PairingSecretPersistence.read(null, "legacy", { null }, { "wrapped:$it" }) {
            stored["pair_secret_enc"] = it
            stored.remove("pair_secret_hex")
        })
        assertEquals(mapOf("pair_secret_enc" to "wrapped:legacy"), stored)
    }
}
