package com.oms.client.cockpit

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.oms.client.data.StreamItemModel
import com.oms.client.transport.OmsFrameProtocol
import com.oms.core.ai.ContextCapsule
import com.oms.core.ai.FieldGuideSpecimenCard
import com.oms.core.ai.GroundingSource
import com.oms.core.ai.ProposedAlarmAction
import com.oms.core.ai.ProposedCalendarAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ChatThreadModel(
    val threadId: String,
    val title: String,
    val pinned: Boolean,
    val incognito: Boolean,
    val syncedToMac: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

data class ChatMessageModel(
    val messageId: String,
    val threadId: String,
    val role: String, // "user" | "assistant" | "system"
    val content: String,
    val reasoning: String = "",
    val modelUsed: String = "",
    val sources: List<GroundingSource> = emptyList(),
    val calendarActions: List<ProposedCalendarAction> = emptyList(),
    val alarmActions: List<ProposedAlarmAction> = emptyList(),
    val specimenCard: FieldGuideSpecimenCard? = null,
    val imageLocalPath: String? = null,
    val status: String = "DONE", // "STREAMING" | "DONE" | "ERROR"
    val syncedToMac: Boolean = false,
    val createdAtMs: Long
)

data class ChatFtsHit(
    val messageId: String,
    val threadId: String,
    val threadTitle: String,
    val snippet: String,
    val createdAtMs: Long
)

/**
 * Milestone 2 (v2.0): SQLite + FTS4 Full-Text Store for Silky Mobile Chat & 3-Tier Memory Funnel.
 */
class ChatMemoryRepository(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "oms_cockpit_v2.db", null, DB_VERSION) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("oms_cockpit_prefs", Context.MODE_PRIVATE)

    private val _threadsFlow = MutableStateFlow<List<ChatThreadModel>>(emptyList())
    val threadsFlow: StateFlow<List<ChatThreadModel>> = _threadsFlow

    private val _activeThreadIdFlow = MutableStateFlow<String>("")
    val activeThreadIdFlow: StateFlow<String> = _activeThreadIdFlow

    private val _activeMessagesFlow = MutableStateFlow<List<ChatMessageModel>>(emptyList())
    val activeMessagesFlow: StateFlow<List<ChatMessageModel>> = _activeMessagesFlow

    init {
        refreshThreads()
        val existingFirst = _threadsFlow.value.firstOrNull { !it.incognito }?.threadId
        if (existingFirst != null) {
            selectThread(existingFirst)
        } else {
            createNewThread(incognito = false)
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_threads (
                thread_id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                pinned INTEGER NOT NULL DEFAULT 0,
                incognito INTEGER NOT NULL DEFAULT 0,
                synced_to_mac INTEGER NOT NULL DEFAULT 0,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_messages (
                message_id TEXT PRIMARY KEY,
                thread_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                reasoning TEXT NOT NULL DEFAULT '',
                model_used TEXT NOT NULL DEFAULT '',
                sources_json TEXT NOT NULL DEFAULT '[]',
                actions_json TEXT NOT NULL DEFAULT '{}',
                specimen_json TEXT NOT NULL DEFAULT '',
                image_local_path TEXT,
                status TEXT NOT NULL DEFAULT 'DONE',
                synced_to_mac INTEGER NOT NULL DEFAULT 0,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_threads_upd ON chat_threads(pinned DESC, updated_at_ms DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_messages_tid ON chat_messages(thread_id, created_at_ms ASC)")
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS chat_messages_fts USING fts4(
                message_id,
                thread_id,
                content,
                tokenize=unicode61
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_vault_promotions (
                item_id TEXT PRIMARY KEY,
                payload_json TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    @Synchronized
    fun createNewThread(incognito: Boolean = false, initialTitle: String = "新对话"): ChatThreadModel {
        val now = System.currentTimeMillis()
        val id = "thr_${OmsFrameProtocol.generateUuidV7().takeLast(12)}"
        val model = ChatThreadModel(
            threadId = id,
            title = if (incognito && initialTitle == "新对话") "🕵️ 无痕对话" else initialTitle,
            pinned = false,
            incognito = incognito,
            syncedToMac = incognito,
            createdAtMs = now,
            updatedAtMs = now
        )
        val cv = ContentValues().apply {
            put("thread_id", model.threadId)
            put("title", model.title)
            put("pinned", 0)
            put("incognito", if (incognito) 1 else 0)
            put("synced_to_mac", if (incognito) 1 else 0)
            put("created_at_ms", now)
            put("updated_at_ms", now)
        }
        writableDatabase.insertWithOnConflict("chat_threads", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        refreshThreads()
        selectThread(id)
        return model
    }

    fun selectThread(threadId: String) {
        _activeThreadIdFlow.value = threadId
        refreshActiveMessages()
    }

    @Synchronized
    fun togglePinThread(threadId: String) {
        val cur = _threadsFlow.value.firstOrNull { it.threadId == threadId } ?: return
        val cv = ContentValues().apply {
            put("pinned", if (cur.pinned) 0 else 1)
            put("synced_to_mac", if (cur.incognito) 1 else 0)
        }
        writableDatabase.update("chat_threads", cv, "thread_id = ?", arrayOf(threadId))
        refreshThreads()
    }

    @Synchronized
    fun deleteThread(threadId: String) {
        val db = writableDatabase
        db.delete("chat_messages_fts", "thread_id = ?", arrayOf(threadId))
        db.delete("chat_messages", "thread_id = ?", arrayOf(threadId))
        db.delete("chat_threads", "thread_id = ?", arrayOf(threadId))
        refreshThreads()
        if (_activeThreadIdFlow.value == threadId) {
            val next = _threadsFlow.value.firstOrNull()?.threadId
            if (next != null) selectThread(next) else createNewThread(incognito = false)
        }
    }

    @Synchronized
    fun upsertMessage(msg: ChatMessageModel, autoTitleFromFirstUserTurn: Boolean = true) {
        val db = writableDatabase
        val thread = getThreadById(msg.threadId)
        val isIncognito = thread?.incognito == true

        val cv = ContentValues().apply {
            put("message_id", msg.messageId)
            put("thread_id", msg.threadId)
            put("role", msg.role)
            put("content", msg.content)
            put("reasoning", msg.reasoning)
            put("model_used", msg.modelUsed)
            put("sources_json", encodeSources(msg.sources))
            put("actions_json", encodeActions(msg.calendarActions, msg.alarmActions))
            put("specimen_json", msg.specimenCard?.let(::encodeSpecimen).orEmpty())
            put("image_local_path", msg.imageLocalPath)
            put("status", msg.status)
            put("synced_to_mac", if (isIncognito || msg.syncedToMac) 1 else 0)
            put("created_at_ms", msg.createdAtMs)
        }
        db.insertWithOnConflict("chat_messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)

        if (!isIncognito && msg.status == "DONE" && msg.content.isNotBlank()) {
            db.delete("chat_messages_fts", "message_id = ?", arrayOf(msg.messageId))
            val ftsCv = ContentValues().apply {
                put("message_id", msg.messageId)
                put("thread_id", msg.threadId)
                put("content", msg.content)
            }
            runCatching { db.insert("chat_messages_fts", null, ftsCv) }
        }

        val threadCv = ContentValues().apply {
            put("updated_at_ms", maxOf(System.currentTimeMillis(), msg.createdAtMs))
            if (!isIncognito) put("synced_to_mac", 0)
            if (autoTitleFromFirstUserTurn && msg.role == "user" && msg.content.isNotBlank() &&
                (thread?.title == "新对话" || thread?.title == "🕵️ 无痕对话")
            ) {
                val cleanTitle = msg.content.replace(Regex("\\s+"), " ").trim().take(26)
                put("title", if (isIncognito) "🕵️ $cleanTitle" else cleanTitle)
            }
        }
        db.update("chat_threads", threadCv, "thread_id = ?", arrayOf(msg.threadId))

        refreshThreads()
        if (_activeThreadIdFlow.value == msg.threadId) {
            refreshActiveMessages()
        }
    }

    fun getThreadById(threadId: String): ChatThreadModel? =
        _threadsFlow.value.firstOrNull { it.threadId == threadId }

    fun loadMessagesForThread(threadId: String): List<ChatMessageModel> {
        val list = ArrayList<ChatMessageModel>()
        readableDatabase.rawQuery(
            """
            SELECT message_id, thread_id, role, content, reasoning, model_used,
                   sources_json, actions_json, specimen_json, image_local_path, status, synced_to_mac, created_at_ms
            FROM chat_messages
            WHERE thread_id = ?
            ORDER BY created_at_ms ASC
            """.trimIndent(),
            arrayOf(threadId)
        ).use { c ->
            while (c.moveToNext()) {
                val (cals, alarms) = decodeActions(c.getString(7).orEmpty())
                list.add(
                    ChatMessageModel(
                        messageId = c.getString(0),
                        threadId = c.getString(1),
                        role = c.getString(2),
                        content = c.getString(3).orEmpty(),
                        reasoning = c.getString(4).orEmpty(),
                        modelUsed = c.getString(5).orEmpty(),
                        sources = decodeSources(c.getString(6).orEmpty()),
                        calendarActions = cals,
                        alarmActions = alarms,
                        specimenCard = decodeSpecimen(c.getString(8).orEmpty()),
                        imageLocalPath = c.getString(9),
                        status = c.getString(10).orEmpty().ifBlank { "DONE" },
                        syncedToMac = c.getInt(11) == 1,
                        createdAtMs = c.getLong(12)
                    )
                )
            }
        }
        return list
    }

    fun searchGlobalMessages(query: String, limit: Int = 20): List<ChatFtsHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<ChatFtsHit>()
        val seenIds = HashSet<String>()

        // 1. Try SQLite FTS4 fast token match
        val tokens = q.split(Regex("\\s+")).filter { it.isNotBlank() }.take(4)
        if (tokens.isNotEmpty()) {
            val ftsQuery = tokens.joinToString(" ") { "${it.replace("\"", "")}*" }
            runCatching {
                readableDatabase.rawQuery(
                    """
                    SELECT m.message_id, m.thread_id, COALESCE(t.title, '对话'), m.content, m.created_at_ms
                    FROM chat_messages_fts f
                    JOIN chat_messages m ON m.message_id = f.message_id
                    LEFT JOIN chat_threads t ON t.thread_id = m.thread_id
                    WHERE chat_messages_fts MATCH ?
                    ORDER BY m.created_at_ms DESC
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(ftsQuery, limit.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        val mid = c.getString(0)
                        if (seenIds.add(mid)) {
                            hits.add(
                                ChatFtsHit(
                                    messageId = mid,
                                    threadId = c.getString(1),
                                    threadTitle = c.getString(2),
                                    snippet = buildSnippet(c.getString(3).orEmpty(), q),
                                    createdAtMs = c.getLong(4)
                                )
                            )
                        }
                    }
                }
            }
        }

        // 2. CJK substring LIKE supplement so Chinese 2-char phrases always hit accurately
        if (hits.size < limit) {
            readableDatabase.rawQuery(
                """
                SELECT m.message_id, m.thread_id, COALESCE(t.title, '对话'), m.content, m.created_at_ms
                FROM chat_messages m
                LEFT JOIN chat_threads t ON t.thread_id = m.thread_id
                WHERE COALESCE(t.incognito, 0) = 0 AND (m.content LIKE ? OR t.title LIKE ?)
                ORDER BY m.created_at_ms DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf("%$q%", "%$q%", limit.toString())
            ).use { c ->
                while (c.moveToNext() && hits.size < limit) {
                    val mid = c.getString(0)
                    if (seenIds.add(mid)) {
                        hits.add(
                            ChatFtsHit(
                                messageId = mid,
                                threadId = c.getString(1),
                                threadTitle = c.getString(2),
                                snippet = buildSnippet(c.getString(3).orEmpty(), q),
                                createdAtMs = c.getLong(4)
                            )
                        )
                    }
                }
            }
        }
        return hits
    }

    /**
     * Local millisecond recall for the `search_my_context` tool:
     * searches Mac-synced `ContextCapsule` + recent WeChat `尺素` articles + mobile FTS history!
     */
    fun buildPersonalContextRecall(
        query: String,
        capsule: ContextCapsule,
        streamItems: List<StreamItemModel>
    ): String {
        val terms = query.lowercase().split(Regex("[\\s,，、]+")).filter { it.isNotBlank() }
        return buildString {
            appendLine("【个人上下文按需检索结果 (query=\"$query\")】")
            if (capsule.nowSummary.isNotBlank()) {
                appendLine("- 当前近期主线 (now.md)：${capsule.nowSummary.take(400)}")
            }
            if (capsule.recentWorkingMemory.isNotEmpty()) {
                appendLine("- 近期工作记忆焦点：${capsule.recentWorkingMemory.joinToString("；")}")
            }
            val matchedVault = capsule.matchedCandidates.filter { cand ->
                terms.isEmpty() || terms.any {
                    cand.title.lowercase().contains(it) || cand.excerpt.lowercase().contains(it)
                }
            }.ifEmpty { capsule.matchedCandidates.take(3) }
            if (matchedVault.isNotEmpty()) {
                appendLine("- 知识库关联笔记：")
                matchedVault.take(4).forEach { n ->
                    appendLine("  * ${n.wikiLink} ${n.title}：${n.excerpt.take(180)}")
                }
            }

            val matchedChisu = streamItems.filter { item ->
                val hay = "${item.title.orEmpty()} ${item.textContent.orEmpty()}".lowercase()
                terms.any { hay.contains(it) }
            }.take(3)
            if (matchedChisu.isNotEmpty()) {
                appendLine("- 尺素近期文章与记录：")
                matchedChisu.forEach { it ->
                    appendLine("  * 《${it.title ?: "尺素条目"}》：${it.textContent.orEmpty().replace(Regex("\\s+"), " ").take(200)}")
                }
            }

            val chatHits = searchGlobalMessages(query, limit = 4)
            if (chatHits.isNotEmpty()) {
                appendLine("- 手机历史对话片段：")
                chatHits.forEach { h ->
                    appendLine("  * [${h.threadTitle}] ${h.snippet}")
                }
            }
        }
    }

    fun getLocalWorkingMemory(): List<String> {
        val raw = prefs.getString("local_working_memory_json", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it)?.trim()?.takeIf(String::isNotEmpty) }
        }.getOrDefault(emptyList())
    }

    fun saveLocalWorkingMemory(items: List<String>) {
        val arr = JSONArray(items.distinct().take(10))
        prefs.edit().putString("local_working_memory_json", arr.toString()).apply()
    }

    fun exportUnsyncedBatch(maxMessages: Int = 40): Pair<JSONObject, Pair<List<String>, List<String>>>? {
        val threadIds = ArrayList<String>()
        val threadsJson = JSONArray()
        readableDatabase.rawQuery(
            """
            SELECT thread_id, title, pinned, incognito, created_at_ms, updated_at_ms
            FROM chat_threads
            WHERE incognito = 0 AND synced_to_mac = 0
            LIMIT 25
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                val tid = c.getString(0)
                threadIds.add(tid)
                threadsJson.put(
                    JSONObject().apply {
                        put("threadId", tid)
                        put("title", c.getString(1))
                        put("pinned", c.getInt(2) == 1)
                        put("incognito", false)
                        put("createdAtMs", c.getLong(4))
                        put("updatedAtMs", c.getLong(5))
                    }
                )
            }
        }

        val msgIds = ArrayList<String>()
        val msgsJson = JSONArray()
        readableDatabase.rawQuery(
            """
            SELECT m.message_id, m.thread_id, m.role, m.content, m.reasoning, m.model_used, m.sources_json, m.created_at_ms
            FROM chat_messages m
            JOIN chat_threads t ON t.thread_id = m.thread_id
            WHERE t.incognito = 0 AND m.status = 'DONE' AND m.synced_to_mac = 0
            ORDER BY m.created_at_ms ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(maxMessages.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val mid = c.getString(0)
                msgIds.add(mid)
                msgsJson.put(
                    JSONObject().apply {
                        put("messageId", mid)
                        put("threadId", c.getString(1))
                        put("role", c.getString(2))
                        put("content", c.getString(3))
                        put("reasoning", c.getString(4))
                        put("modelUsed", c.getString(5))
                        put("sourcesJson", c.getString(6))
                        put("createdAtMs", c.getLong(7))
                    }
                )
            }
        }

        if (threadIds.isEmpty() && msgIds.isEmpty()) return null
        val payload = JSONObject().apply {
            put("threads", threadsJson)
            put("messages", msgsJson)
            val wm = getLocalWorkingMemory()
            if (wm.isNotEmpty()) put("workingMemoryItems", JSONArray(wm))
            put("syncedAtMs", System.currentTimeMillis())
        }
        return payload to (threadIds to msgIds)
    }

    @Synchronized
    fun markBatchSynced(threadIds: List<String>, messageIds: List<String>) {
        val db = writableDatabase
        val cv = ContentValues().apply { put("synced_to_mac", 1) }
        for (tid in threadIds) {
            db.update("chat_threads", cv, "thread_id = ?", arrayOf(tid))
        }
        for (mid in messageIds) {
            db.update("chat_messages", cv, "message_id = ?", arrayOf(mid))
        }
    }

    @Synchronized
    fun enqueuePendingVaultPromotion(itemId: String, payload: JSONObject) {
        val cv = ContentValues().apply {
            put("item_id", itemId)
            put("payload_json", payload.toString())
            put("created_at_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("pending_vault_promotions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun listPendingVaultPromotions(): List<Pair<String, JSONObject>> {
        val out = ArrayList<Pair<String, JSONObject>>()
        readableDatabase.rawQuery(
            "SELECT item_id, payload_json FROM pending_vault_promotions ORDER BY created_at_ms ASC LIMIT 20",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val obj = runCatching { JSONObject(c.getString(1)) }.getOrNull() ?: continue
                out.add(id to obj)
            }
        }
        return out
    }

    @Synchronized
    fun removePendingVaultPromotion(itemId: String) {
        writableDatabase.delete("pending_vault_promotions", "item_id = ?", arrayOf(itemId))
    }

    private fun refreshThreads() {
        val list = ArrayList<ChatThreadModel>()
        readableDatabase.rawQuery(
            """
            SELECT thread_id, title, pinned, incognito, synced_to_mac, created_at_ms, updated_at_ms
            FROM chat_threads
            ORDER BY pinned DESC, updated_at_ms DESC
            LIMIT 120
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    ChatThreadModel(
                        threadId = c.getString(0),
                        title = c.getString(1),
                        pinned = c.getInt(2) == 1,
                        incognito = c.getInt(3) == 1,
                        syncedToMac = c.getInt(4) == 1,
                        createdAtMs = c.getLong(5),
                        updatedAtMs = c.getLong(6)
                    )
                )
            }
        }
        _threadsFlow.value = list
    }

    private fun refreshActiveMessages() {
        val tid = _activeThreadIdFlow.value
        if (tid.isBlank()) {
            _activeMessagesFlow.value = emptyList()
            return
        }
        _activeMessagesFlow.value = loadMessagesForThread(tid)
    }

    private fun buildSnippet(content: String, query: String): String {
        val clean = content.replace(Regex("\\s+"), " ").trim()
        val idx = clean.indexOf(query, ignoreCase = true)
        if (idx < 0) return clean.take(90)
        val start = (idx - 24).coerceAtLeast(0)
        val end = (idx + query.length + 52).coerceAtMost(clean.length)
        return (if (start > 0) "…" else "") + clean.substring(start, end) + (if (end < clean.length) "…" else "")
    }

    private fun encodeSources(sources: List<GroundingSource>): String {
        val arr = JSONArray()
        for (s in sources) {
            arr.put(JSONObject().put("title", s.title).put("uri", s.uri))
        }
        return arr.toString()
    }

    private fun decodeSources(raw: String): List<GroundingSource> = runCatching {
        if (raw.isBlank()) return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { idx ->
            val o = arr.optJSONObject(idx) ?: return@mapNotNull null
            val uri = o.optString("uri").trim()
            if (uri.isEmpty()) null else GroundingSource(o.optString("title", uri), uri)
        }
    }.getOrDefault(emptyList())

    private fun encodeActions(
        cals: List<ProposedCalendarAction>,
        alarms: List<ProposedAlarmAction>
    ): String {
        val calArr = JSONArray()
        for (c in cals) {
            calArr.put(
                JSONObject()
                    .put("title", c.title)
                    .put("startEpochMs", c.startEpochMs)
                    .put("durationMinutes", c.durationMinutes)
                    .put("location", c.location)
                    .put("description", c.description)
            )
        }
        val alArr = JSONArray()
        for (a in alarms) {
            alArr.put(
                JSONObject()
                    .put("hour", a.hour)
                    .put("minute", a.minute)
                    .put("label", a.label)
                    .put("days", JSONArray(a.daysOfWeek))
            )
        }
        return JSONObject().put("calendars", calArr).put("alarms", alArr).toString()
    }

    private fun decodeActions(raw: String): Pair<List<ProposedCalendarAction>, List<ProposedAlarmAction>> = runCatching {
        if (raw.isBlank()) return emptyList<ProposedCalendarAction>() to emptyList()
        val root = JSONObject(raw)
        val cals = ArrayList<ProposedCalendarAction>()
        root.optJSONArray("calendars")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                cals.add(
                    ProposedCalendarAction(
                        title = o.optString("title", "AI 日程"),
                        startEpochMs = o.optLong("startEpochMs"),
                        durationMinutes = o.optInt("durationMinutes", 60),
                        location = o.optString("location", ""),
                        description = o.optString("description", "")
                    )
                )
            }
        }
        val alarms = ArrayList<ProposedAlarmAction>()
        root.optJSONArray("alarms")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val daysArr = o.optJSONArray("days")
                val days = if (daysArr != null) {
                    (0 until daysArr.length()).map { daysArr.optInt(it) }.filter { it in 1..7 }
                } else emptyList()
                alarms.add(
                    ProposedAlarmAction(
                        hour = o.optInt("hour", 8),
                        minute = o.optInt("minute", 0),
                        label = o.optString("label", "咫尺提醒"),
                        daysOfWeek = days
                    )
                )
            }
        }
        cals to alarms
    }.getOrDefault(emptyList<ProposedCalendarAction>() to emptyList())

    private fun encodeSpecimen(card: FieldGuideSpecimenCard): String = JSONObject().apply {
        put("specimenId", card.specimenId)
        put("chineseName", card.chineseName)
        put("commonAliases", card.commonAliases)
        put("latinName", card.latinName)
        put("familyGenus", card.familyGenus)
        put("category", card.category)
        put("confidencePercent", card.confidencePercent)
        put("oneLineSummary", card.oneLineSummary)
        put("keyMorphology", JSONArray(card.keyMorphology))
        put("lookalikeComparison", card.lookalikeComparison)
        put("bloomAndHabitat", card.bloomAndHabitat)
        put("safetyOrCareNote", card.safetyOrCareNote)
        put("modelUsed", card.modelUsed)
        put("capturedAtIso", card.capturedAtIso)
    }.toString()

    private fun decodeSpecimen(raw: String): FieldGuideSpecimenCard? = runCatching {
        if (raw.isBlank()) return null
        val o = JSONObject(raw)
        val morphArr = o.optJSONArray("keyMorphology")
        val morph = if (morphArr != null) {
            (0 until morphArr.length()).mapNotNull { morphArr.optString(it)?.takeIf(String::isNotBlank) }
        } else emptyList()
        FieldGuideSpecimenCard(
            specimenId = o.optString("specimenId"),
            chineseName = o.optString("chineseName"),
            commonAliases = o.optString("commonAliases"),
            latinName = o.optString("latinName"),
            familyGenus = o.optString("familyGenus"),
            category = o.optString("category", "植物"),
            confidencePercent = o.optInt("confidencePercent", 90),
            oneLineSummary = o.optString("oneLineSummary"),
            keyMorphology = morph,
            lookalikeComparison = o.optString("lookalikeComparison"),
            bloomAndHabitat = o.optString("bloomAndHabitat"),
            safetyOrCareNote = o.optString("safetyOrCareNote"),
            modelUsed = o.optString("modelUsed"),
            capturedAtIso = o.optString("capturedAtIso")
        )
    }.getOrNull()

    private companion object {
        private const val DB_VERSION = 1
    }
}
