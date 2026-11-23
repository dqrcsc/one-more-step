package com.oms.client.cockpit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = android.app.Application::class)
class ChatSyncRegressionTest {
    private fun repository(): ChatMemoryRepository {
        val context = RuntimeEnvironment.getApplication() as Context
        context.deleteDatabase("oms_cockpit_v2.db")
        return ChatMemoryRepository(context)
    }

    @Test fun backlogDrainsInBatchesAndEveryMessageHasItsThread() = runBlocking {
        val repo = repository()
        val thread = repo.createNewThread(initialTitle = "Backlog")
        for (i in 1..125) repo.upsertMessage(ChatMessageModel("m$i", thread.threadId, "user", "content $i", createdAtMs = i.toLong()))
        for (i in 1..45) repo.enqueuePendingVaultPromotion("p$i", JSONObject().put("itemId", "p$i"), "macA")
        val ids = mutableSetOf<String>()
        var promoted = 0
        var inFlight = 0
        val queue = ChatSyncQueue(repo, { "macA" }, { true }) { method, body, target ->
            assertEquals("macA", target)
            assertEquals(1, ++inFlight)
            delay(1)
            if (method == "chat.sync.batch") {
                val messages = body.getJSONArray("messages")
                assertTrue(messages.length() <= 40)
                assertTrue(body.getJSONArray("threads").length() > 0)
                for (i in 0 until messages.length()) assertTrue(ids.add(messages.getJSONObject(i).getString("messageId")))
            } else promoted++
            inFlight--
            true
        }
        val a = launch { queue.flush() }; val b = launch { queue.flush() }
        a.join(); b.join()
        assertEquals(125, ids.size)
        assertEquals(45, promoted)
        assertNull(repo.exportUnsyncedBatch("macA"))
        assertNotNull(repo.exportUnsyncedBatch("macB"))
        repo.close()
    }

    @Test fun failedFlushStopsAndReconnectResumesWithoutLosingData() = runBlocking {
        val repo = repository()
        val thread = repo.createNewThread(initialTitle = "Retry")
        repo.upsertMessage(ChatMessageModel("m", thread.threadId, "user", "pending", createdAtMs = 1))
        var succeeds = false
        var calls = 0
        val queue = ChatSyncQueue(repo, { "macA" }, { true }) { _, _, _ -> calls++; succeeds }
        queue.flush()
        assertEquals(1, calls); assertNotNull(repo.exportUnsyncedBatch("macA"))
        succeeds = true
        queue.flush()
        assertNull(repo.exportUnsyncedBatch("macA"))
        repo.close()
    }

    @Test fun lateAckCannotSwallowRenamePinOrMessageUpdate() {
        val repo = repository()
        val t = repo.createNewThread(initialTitle = "A")
        val message = ChatMessageModel("m", t.threadId, "user", "old", createdAtMs = 1)
        repo.upsertMessage(message, false)
        val old = repo.exportUnsyncedBatch("macA")!!
        repo.renameThread(t.threadId, "B")
        repo.togglePinThread(t.threadId)
        repo.upsertMessage(message.copy(content = "new"), false)
        repo.markBatchSynced(old)
        val next = repo.exportUnsyncedBatch("macA")!!
        assertEquals("new", next.payload.getJSONArray("messages").getJSONObject(0).getString("content"))
        val threads = next.payload.getJSONArray("threads")
        val edited = (0 until threads.length()).map { threads.getJSONObject(it) }.first { it.getString("threadId") == t.threadId }
        assertEquals("B", edited.getString("title")); assertTrue(edited.getBoolean("pinned"))
        repo.markBatchSynced(next)
        repo.markBatchSynced(old)
        assertNull(repo.exportUnsyncedBatch("macA"))
        assertNotNull(repo.exportUnsyncedBatch("macB"))
        repo.close()
    }

    @Test fun incognitoAndOtherMacPromotionsAreNotExportedAndOldReceiptDoesNotDeleteReplacement() {
        val repo = repository()
        val secret = repo.createNewThread(incognito = true)
        repo.upsertMessage(ChatMessageModel("secret", secret.threadId, "user", "private", createdAtMs = 1))
        val batch = repo.exportUnsyncedBatch("macA")!!
        assertFalse(batch.messages.containsKey("secret")); assertFalse(batch.threads.containsKey(secret.threadId))
        val old = JSONObject().put("body", "old")
        val newer = JSONObject().put("body", "new")
        repo.enqueuePendingVaultPromotion("p", old, "macA")
        assertTrue(repo.listPendingVaultPromotions("macB").isEmpty())
        repo.enqueuePendingVaultPromotion("p", newer, "macA")
        repo.removePendingVaultPromotion("p", old, "macA")
        assertEquals("new", repo.listPendingVaultPromotions("macA").single().second.getString("body"))
        repo.close()
    }

    @Test fun v1MigrationPreservesExistingChatsAndPendingPromotion() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.deleteDatabase("oms_cockpit_v2.db")
        val db = context.openOrCreateDatabase("oms_cockpit_v2.db", Context.MODE_PRIVATE, null)
        db.execSQL("CREATE TABLE chat_threads(thread_id TEXT PRIMARY KEY,title TEXT,pinned INTEGER,incognito INTEGER,synced_to_mac INTEGER,created_at_ms INTEGER,updated_at_ms INTEGER)")
        db.execSQL("CREATE TABLE chat_messages(message_id TEXT PRIMARY KEY,thread_id TEXT,role TEXT,content TEXT,reasoning TEXT,model_used TEXT,sources_json TEXT,actions_json TEXT,specimen_json TEXT,image_local_path TEXT,status TEXT,synced_to_mac INTEGER,created_at_ms INTEGER)")
        db.execSQL("CREATE TABLE pending_vault_promotions(item_id TEXT PRIMARY KEY,payload_json TEXT,created_at_ms INTEGER)")
        db.execSQL("INSERT INTO chat_threads VALUES('legacy','Legacy',0,0,1,1,1)")
        db.execSQL("INSERT INTO pending_vault_promotions VALUES('legacy-p','{}',1)")
        db.version = 1; db.close()
        val repo = ChatMemoryRepository(context)
        assertTrue(repo.exportUnsyncedBatch("macA")!!.threads.containsKey("legacy"))
        repo.bindLegacyPromotions("macA"); repo.bindLegacyPromotions("macB")
        assertEquals(1, repo.listPendingVaultPromotions("macA").size)
        assertTrue(repo.listPendingVaultPromotions("macB").isEmpty())
        repo.close()
    }

    @Test fun permanentRejectionEvictsPoisonPillWithoutBlockingSubsequentOrLegacyPromotions() = runBlocking {
        val repo = repository()
        // Legacy row without itemId inside payload_json, followed by a poison pill (-32602), followed by a valid promotion
        repo.enqueuePendingVaultPromotion("legacy-no-id", JSONObject().put("title", "Legacy"), "macA")
        repo.enqueuePendingVaultPromotion("poison-pill", JSONObject().put("itemId", "poison-pill").put("title", "Conflict"), "macA")
        repo.enqueuePendingVaultPromotion("healthy-next", JSONObject().put("itemId", "healthy-next").put("title", "Healthy"), "macA")

        val deliveredIds = mutableListOf<String>()
        val queue = ChatSyncQueue.withOutcome(repo, { "macA" }, { true }) { method, payload, _ ->
            if (method == "chat.sync.batch") {
                return@withOutcome SyncSendOutcome.DELIVERED
            }
            assertEquals("memory.promote_vault", method)
            val id = payload.optString("itemId")
            assertTrue("wire payload must carry itemId", id.isNotBlank())
            if (id == "poison-pill") {
                ChatSyncQueue.classifyHostRpcResponse(
                    JSONObject().put("error", JSONObject().put("code", -32602).put("message", "Promotion itemId already used with different content"))
                )
            } else {
                deliveredIds.add(id)
                ChatSyncQueue.classifyHostRpcResponse(JSONObject().put("ok", true))
            }
        }
        queue.flush()
        assertEquals(listOf("legacy-no-id", "healthy-next"), deliveredIds)
        assertTrue(repo.listPendingVaultPromotions("macA").isEmpty())
        repo.close()
    }
}
