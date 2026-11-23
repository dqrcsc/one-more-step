package com.oms.client.cockpit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.json.JSONObject

enum class SyncSendOutcome {
    DELIVERED,
    RETRY_LATER,
    REJECTED_PERMANENT
}

/** Serializes reconnect/foreground triggers and acknowledges only exported revisions. */
class ChatSyncQueue private constructor(
    private val repo: ChatMemoryRepository,
    private val currentMac: () -> String,
    private val connected: () -> Boolean,
    private val sendOutcome: suspend (method: String, payload: JSONObject, macId: String) -> SyncSendOutcome,
    @Suppress("UNUSED_PARAMETER") marker: Unit
) {
    constructor(
        repo: ChatMemoryRepository,
        currentMac: () -> String,
        connected: () -> Boolean,
        send: suspend (method: String, payload: JSONObject, macId: String) -> Boolean
    ) : this(
        repo,
        currentMac,
        connected,
        { method, payload, macId -> if (send(method, payload, macId)) SyncSendOutcome.DELIVERED else SyncSendOutcome.RETRY_LATER },
        Unit
    )

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
                    val outcome = sendOutcome("chat.sync.batch", batch.payload, target)
                    if (outcome != SyncSendOutcome.DELIVERED) break
                    repo.markBatchSynced(batch)
                }
                for ((id, payload) in promotions) {
                    if (!connected() || currentMac() != target) return@withLock
                    val wirePayload = if (payload.optString("itemId").isNotBlank()) {
                        payload
                    } else {
                        JSONObject(payload.toString()).put("itemId", id)
                    }
                    when (sendOutcome("memory.promote_vault", wirePayload, target)) {
                        SyncSendOutcome.DELIVERED,
                        SyncSendOutcome.REJECTED_PERMANENT -> repo.removePendingVaultPromotion(id, payload, target)
                        SyncSendOutcome.RETRY_LATER -> return@withLock
                    }
                }
                yield()
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Leave unacknowledged data queued for the next reconnect. */ }
    }

    companion object {
        fun withOutcome(
            repo: ChatMemoryRepository,
            currentMac: () -> String,
            connected: () -> Boolean,
            sendOutcome: suspend (method: String, payload: JSONObject, macId: String) -> SyncSendOutcome
        ): ChatSyncQueue = ChatSyncQueue(repo, currentMac, connected, sendOutcome, Unit)

        fun classifyHostRpcResponse(resp: JSONObject?): SyncSendOutcome {
            if (resp == null) return SyncSendOutcome.RETRY_LATER
            if (resp.optBoolean("ok", false)) return SyncSendOutcome.DELIVERED
            val errCode = resp.optJSONObject("error")?.optInt("code", -32000)
            return if (errCode == -32602) SyncSendOutcome.REJECTED_PERMANENT else SyncSendOutcome.RETRY_LATER
        }
    }
}
