package com.oms.client.cockpit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.json.JSONObject

/** Serializes reconnect/foreground triggers and acknowledges only exported revisions. */
class ChatSyncQueue(
    private val repo: ChatMemoryRepository,
    private val currentMac: () -> String,
    private val connected: () -> Boolean,
    private val send: suspend (method: String, payload: JSONObject, macId: String) -> Boolean
) {
    private val mutex = Mutex()
    suspend fun flush() = mutex.withLock {
        val target = currentMac()
        if (target.isBlank()) return@withLock
        repo.bindLegacyPromotions(target)
        try {
            while (connected() && currentMac() == target) {
                val batch = repo.exportUnsyncedBatch(target, 40)
                val promotions = repo.listPendingVaultPromotions(target)
                if (batch == null && promotions.isEmpty()) break
                if (batch != null) {
                    if (!send("chat.sync.batch", batch.payload, target)) break
                    repo.markBatchSynced(batch)
                }
                for ((id, payload) in promotions) {
                    if (!connected() || currentMac() != target || !send("memory.promote_vault", payload, target)) return@withLock
                    repo.removePendingVaultPromotion(id, payload, target)
                }
                yield()
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Leave unacknowledged data queued for the next reconnect. */ }
    }
}
