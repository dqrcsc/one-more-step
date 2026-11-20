package com.oms.core.ai

import com.oms.core.proxy.ProxyNode

/**
 * Latest 2026-11 OpenAI GPT-6.2 / Realtime 2.1 & Google Gemini 4 Argon / 3.8 Live Model Baseline.
 */
object AiModelCatalog {
    const val OPENAI_SOL = "gpt-6.2-sol"           // Balanced flagship workhorse (WeChat triage & daily chat)
    const val OPENAI_ASTRA = "gpt-6.2-astra"       // Top-tier frontier reasoning (Deep thinking toggle)
    const val OPENAI_TERRA = "gpt-6.1-terra"       // Efficient visual/tool model
    const val OPENAI_LUNA = "gpt-6-luna"           // Lightweight memory consolidation
    const val OPENAI_REALTIME = "gpt-realtime-2.1" // Full-duplex speech-to-speech Walk & Talk
    const val OPENAI_LIVE_TRANSCRIBE = "gpt-live-transcribe"
    const val OPENAI_TRANSCRIBE = "gpt-transcribe"

    const val GEMINI_ARGON = "gemini-4-argon"      // Flagship multimodal & Botanical/Field Guide ID
    const val GEMINI_FLASH = "gemini-3.8-flash"    // High-speed search grounding & screen Q&A
    const val GEMINI_LIVE = "gemini-3.8-live"      // GA real-time audio+video Glass Live API

    fun isOpenAiResponsesModel(model: String): Boolean {
        val m = model.trim().lowercase()
        return m.startsWith("gpt-6") || m.startsWith("gpt-5") || m.startsWith("o3") || m.startsWith("o4")
    }
}

enum class ReasoningEffort(val wireValue: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high")
}

data class ProviderEndpointConfig(
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val fastModel: String,
    val flagshipModel: String,
    val reasoningModel: String = flagshipModel,
    val liveModel: String = "",
    val transcribeModel: String = ""
)

data class EdgeAiRuntimeConfig(
    val openai: ProviderEndpointConfig? = null,
    val gemini: ProviderEndpointConfig? = null,
    val domesticFallback: ProviderEndpointConfig? = null
) {
    fun hasAnyConfiguredProvider(): Boolean =
        listOfNotNull(openai, gemini, domesticFallback).any { it.apiKey.isNotBlank() }
}

data class LlmImageAttachment(val mimeType: String, val base64Data: String)

data class LlmTurnMessage(
    val role: String,
    val content: String,
    val images: List<LlmImageAttachment> = emptyList()
)

data class GroundingSource(val title: String, val uri: String)

sealed class LlmStreamEvent {
    data class ReasoningDelta(val text: String) : LlmStreamEvent()
    data class TextDelta(val text: String) : LlmStreamEvent()
    data class Grounding(val sources: List<GroundingSource>, val queries: List<String>) : LlmStreamEvent()
    data class Completed(
        val fullText: String,
        val fullReasoning: String,
        val modelUsed: String,
        val sources: List<GroundingSource> = emptyList()
    ) : LlmStreamEvent()
}

data class VaultCandidateNote(val title: String, val wikiLink: String, val excerpt: String)

data class ContextCapsule(
    val revision: String = "",
    val generatedAtIso: String = "",
    val nowSummary: String = "",
    val queueSummary: String = "",
    val topicsOverview: String = "",
    val analysisBackgroundFormatted: String = "",
    val analysisWarnings: List<String> = emptyList(),
    val matchedCandidates: List<VaultCandidateNote> = emptyList(),
    val recentWorkingMemory: List<String> = emptyList()
)

object ProxyNodeCodec {
    fun toJsonList(nodes: List<ProxyNode>): List<Map<String, Any?>> = nodes.map { n ->
        linkedMapOf(
            "name" to n.name,
            "protocol" to n.protocol,
            "server" to n.server,
            "port" to n.port,
            "cipher" to n.cipher,
            "password" to n.password,
            "username" to n.username,
            "plugin" to n.plugin,
            "pluginOpts" to n.pluginOpts,
            "sni" to n.sni,
            "skipCertVerify" to n.skipCertVerify,
            "udp" to n.udp,
            "source" to n.source
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun fromJsonList(list: List<*>): List<ProxyNode> = list.mapNotNull { raw ->
        val m = raw as? Map<String, Any?> ?: return@mapNotNull null
        val server = m["server"]?.toString()?.trim().orEmpty()
        val port = (m["port"] as? Number)?.toInt() ?: m["port"]?.toString()?.toIntOrNull() ?: 0
        if (server.isEmpty() || port !in 1..65535) return@mapNotNull null
        val opts = (m["pluginOpts"] as? Map<*, *>)?.entries
            ?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
        ProxyNode(
            name = m["name"]?.toString() ?: "node",
            protocol = m["protocol"]?.toString()?.lowercase() ?: "ss",
            server = server,
            port = port,
            cipher = m["cipher"]?.toString().orEmpty(),
            password = m["password"]?.toString().orEmpty(),
            username = m["username"]?.toString().orEmpty(),
            plugin = m["plugin"]?.toString()?.takeIf { it.isNotBlank() },
            pluginOpts = opts,
            sni = m["sni"]?.toString().orEmpty(),
            skipCertVerify = m["skipCertVerify"] == true,
            udp = m["udp"] == true,
            source = m["source"]?.toString().orEmpty()
        )
    }
}
