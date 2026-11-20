package com.oms.client.cockpit

import com.oms.client.data.StreamItemModel
import com.oms.core.ai.MiniJson
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ParsedChisuDocument(
    val cleanTitle: String,
    val bodyMarkdown: String,
    val previewExcerpt: String,
    val author: String = "",
    val sourceUrl: String = "",
    val publishDate: String = "",
    val capturedDate: String = "",
    val triage: String = "",
    val processLevel: String = "",
    val userNotes: String = "",
    val duration: String = ""
)

data class ChisuArticleItemUi(
    val stableKey: String,
    val articleId: Int? = null,
    val localStreamItemId: String? = null,
    val title: String,
    val author: String = "",
    val sourceUrl: String = "",
    val publishDate: String = "",
    val createdAtMs: Long,
    val status: String = "",
    val processLevel: String = "",
    val triage: String = "",
    val userNotes: String = "",
    val shortSummary: String = "",
    val previewExcerpt: String = "",
    val localNoteMarkdown: String? = null,
    val localSourceFilePath: String? = null,
    val hasNote: Boolean = false,
    val hasSource: Boolean = false,
    val isEdgeNightStandby: Boolean = false,
    val isFallbackNote: Boolean = false
)

data class ChisuArticleDetailUi(
    val stableKey: String,
    val articleId: Int? = null,
    val title: String,
    val author: String = "",
    val sourceUrl: String = "",
    val publishDate: String = "",
    val createdAtMs: Long = 0L,
    val status: String = "",
    val processLevel: String = "",
    val triage: String = "",
    val userNotes: String = "",
    val noteMarkdown: String? = null,
    val sourceMarkdown: String? = null,
    val summaryMarkdown: String? = null,
    val noteTruncated: Boolean = false,
    val sourceTruncated: Boolean = false,
    val fromCache: Boolean = false
)

/**
 * Pure-Kotlin editorial document sanitizer & library merger for 青简·尺素.
 * Mirrors Mac `reader_renderer.ts` (`stripYamlFrontmatter` + `compactVerboseTopHeader`)
 * so mobile cards, reader prose, and Commute TTS never surface raw YAML frontmatter
 * (`generated_content_sha256`, `source_path`, `candidate_vault_nodes`, etc.) or verbose metadata blocks.
 */
object ChisuDocumentParser {
    private val FRONTMATTER_KV_RE = Regex("""^([A-Za-z0-9_-]+)\s*:\s*(.*)$""")
    private val TOP_H1_RE = Regex("""^#\s+(.+)$""")
    private val META_BULLET_RE = Regex("""^-\s+\*\*([^*]+)\*\*\s*[：:]\s*(.+)$""")
    private val RULE_RE = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
    private val MD_LINK_RE = Regex("""\[([^\]]+)\]\((https?://[^)\s]+)\)""")
    private val MD_IMAGE_RE = Regex("""!\[([^\]]*)\]\(([^)\n]+)\)""")
    private val WIKILINK_RE = Regex("""\[\[([^\]|\n]+)(?:\|([^\]\n]+))?\]\]""")
    private val WX_FILE_ID_RE = Regex("""(?:^|[/_])(?:wx|article)_(\d+)_""", RegexOption.IGNORE_CASE)

    private val KNOWN_HEADER_META_KEYS = listOf(
        "原始材料",
        "原始链接",
        "公众号",
        "来源",
        "作者",
        "发布时间",
        "捕获时间",
        "归档时间",
        "视频时长",
        "处理深度",
        "建议分诊",
        "分诊",
        "关联状态",
        "转发时批注",
        "批注",
        "关联附件"
    )

    fun cleanTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .trim()
            .removePrefix("💬")
            .trim()
            .replace(Regex("""\s*[（(]初筛伴读笔记[)）]\s*$"""), "")
            .trim()
    }

    fun normalizeTitleForMatch(raw: String?): String =
        cleanTitle(raw)
            .replace(Regex("""\s+"""), " ")
            .lowercase(Locale.ROOT)

    fun extractArticleIdFromFileUri(uri: String?): Int? {
        if (uri.isNullOrBlank()) return null
        val match = WX_FILE_ID_RE.find(uri) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
    }

    fun stripYamlFrontmatter(rawMarkdown: String): Pair<String, Map<String, String>> {
        val normalized = rawMarkdown
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        val trimmedStart = normalized.trimStart('\n')
        if (!trimmedStart.startsWith("---\n")) {
            return normalized to emptyMap()
        }
        val afterOpen = trimmedStart.substring(4)
        val lines = afterOpen.split('\n')
        var closeLineIdx = -1
        for (i in lines.indices) {
            if (lines[i].trim() == "---") {
                closeLineIdx = i
                break
            }
        }
        if (closeLineIdx < 0) {
            return normalized to emptyMap()
        }

        val fm = LinkedHashMap<String, String>()
        for (i in 0 until closeLineIdx) {
            val line = lines[i]
            if (line.isBlank() || line.startsWith(" ") || line.startsWith("\t") || line.trimStart().startsWith("-")) {
                continue
            }
            val m = FRONTMATTER_KV_RE.matchEntire(line.trim()) ?: continue
            val key = m.groupValues[1].trim()
            val rawVal = m.groupValues[2].trim()
            if (rawVal.isNotEmpty()) {
                fm[key] = unquoteYamlValue(rawVal)
            }
        }
        val remaining = lines.drop(closeLineIdx + 1).joinToString("\n").trimStart('\n')
        return remaining to fm
    }

    private fun unquoteYamlValue(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .trim()
        }
        if (trimmed.length >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'")) {
            return trimmed.substring(1, trimmed.length - 1)
                .replace("''", "'")
                .trim()
        }
        return trimmed
    }

    fun parse(
        rawMarkdown: String,
        fallbackTitle: String = "",
        stripHeaderBlock: Boolean = true
    ): ParsedChisuDocument {
        val (withoutFm, fm) = stripYamlFrontmatter(rawMarkdown)

        var author = (fm["account_name"] ?: fm["channel_name"] ?: fm["author"] ?: "").trim()
        var sourceUrl = sanitizeHttpUrl(fm["source_url"] ?: fm["url"] ?: "")
        var publishDate = cleanDateValue(fm["published_date"] ?: fm["publish_date"] ?: "")
        var capturedDate = cleanDateValue(fm["captured_date"] ?: fm["created"] ?: "")
        var triage = (fm["triage"] ?: "").trim()
        var processLevel = (fm["process_level"] ?: "").trim()
        var userNotes = ""
        var duration = (fm["duration"] ?: "").trim()
        var extractedHeadingTitle = ""

        var body = withoutFm
        if (stripHeaderBlock) {
            val lines = body.split('\n')
            var idx = 0
            while (idx < lines.size && lines[idx].trim().isEmpty()) idx++

            val h1Match = if (idx < lines.size) TOP_H1_RE.matchEntire(lines[idx].trim()) else null
            var scan = if (h1Match != null) {
                extractedHeadingTitle = h1Match.groupValues[1].trim()
                idx + 1
            } else {
                idx
            }
            while (scan < lines.size && lines[scan].trim().isEmpty()) scan++

            val metaBullets = ArrayList<Pair<String, String>>()
            val bulletStart = scan
            while (scan < lines.size) {
                val t = lines[scan].trim()
                val m = META_BULLET_RE.matchEntire(t)
                if (m != null) {
                    metaBullets.add(m.groupValues[1].trim() to m.groupValues[2].trim())
                    scan++
                } else if (t.isEmpty() && metaBullets.isNotEmpty()) {
                    scan++
                    break
                } else {
                    break
                }
            }
            while (scan < lines.size && lines[scan].trim().isEmpty()) scan++

            val hasTrailingRule = scan < lines.size && RULE_RE.matches(lines[scan].trim())
            val knownKeyCount = metaBullets.count { (k, _) ->
                KNOWN_HEADER_META_KEYS.any { known -> k.contains(known) }
            }

            if (metaBullets.isNotEmpty() && (hasTrailingRule || knownKeyCount >= 2 || (h1Match != null && knownKeyCount >= 1))) {
                for ((key, value) in metaBullets) {
                    when {
                        key.contains("公众号") || key.contains("作者") || key.contains("来源") -> {
                            if (author.isBlank() && value.isNotBlank()) author = value.trim()
                        }
                        key.contains("原始链接") || key.contains("原始材料") -> {
                            val link = MD_LINK_RE.find(value)?.groupValues?.getOrNull(2) ?: value
                            val safe = sanitizeHttpUrl(link)
                            if (sourceUrl.isBlank() && safe.isNotBlank()) sourceUrl = safe
                        }
                        key.contains("发布时间") -> {
                            val clean = cleanDateValue(value)
                            if (publishDate.isBlank() && clean.isNotBlank()) publishDate = clean
                        }
                        key.contains("捕获时间") || key.contains("归档时间") -> {
                            val clean = cleanDateValue(value)
                            if (capturedDate.isBlank() && clean.isNotBlank()) capturedDate = clean
                        }
                        key.contains("建议分诊") || key.contains("分诊") -> {
                            val clean = value.replace("`", "").trim()
                            if (triage.isBlank() && clean.isNotBlank()) triage = clean
                        }
                        key.contains("处理深度") -> {
                            val clean = value.replace("`", "").substringBefore("（").substringBefore("(").trim()
                            if (processLevel.isBlank() && clean.isNotBlank()) processLevel = clean
                        }
                        key.contains("批注") -> {
                            if (userNotes.isBlank() && value.isNotBlank()) userNotes = value.trim()
                        }
                        key.contains("时长") -> {
                            if (duration.isBlank() && value.isNotBlank()) duration = value.trim()
                        }
                    }
                }
                val nextStart = if (hasTrailingRule) scan + 1 else scan
                body = lines.drop(nextStart).joinToString("\n").trimStart('\n')
            } else if (h1Match != null && bulletStart == scan) {
                // Only a leading H1 without bullets — drop it if it duplicates the resolved title
                val resolvedTitleCandidate = cleanTitle(fallbackTitle.ifBlank { fm["title"] ?: "" })
                if (resolvedTitleCandidate.isNotBlank() &&
                    normalizeTitleForMatch(extractedHeadingTitle) == normalizeTitleForMatch(resolvedTitleCandidate)
                ) {
                    body = lines.drop(idx + 1).joinToString("\n").trimStart('\n')
                }
            }
        }

        val resolvedTitle = cleanTitle(
            fallbackTitle.ifBlank {
                fm["title"].orEmpty().ifBlank { extractedHeadingTitle }
            }
        )

        // If body still begins with a redundant H1 matching our clean title, remove it
        if (stripHeaderBlock && resolvedTitle.isNotBlank()) {
            val bodyLines = body.split('\n')
            val firstNonBlank = bodyLines.indexOfFirst { it.isNotBlank() }
            if (firstNonBlank >= 0) {
                val h1 = TOP_H1_RE.matchEntire(bodyLines[firstNonBlank].trim())
                if (h1 != null && normalizeTitleForMatch(h1.groupValues[1]) == normalizeTitleForMatch(resolvedTitle)) {
                    body = bodyLines.drop(firstNonBlank + 1).joinToString("\n").trimStart('\n')
                }
            }
        }

        val cleanBody = body.trim()
        return ParsedChisuDocument(
            cleanTitle = resolvedTitle,
            bodyMarkdown = cleanBody,
            previewExcerpt = buildPlainExcerpt(cleanBody, 220),
            author = author,
            sourceUrl = sourceUrl,
            publishDate = publishDate,
            capturedDate = capturedDate,
            triage = triage,
            processLevel = processLevel,
            userNotes = userNotes,
            duration = duration
        )
    }

    fun buildPlainExcerpt(markdownBody: String, maxChars: Int = 220): String {
        if (markdownBody.isBlank()) return ""
        val plain = markdownBody
            .replace(MD_IMAGE_RE, " ")
            .replace(MD_LINK_RE, "$1")
            .replace(WIKILINK_RE) { m -> (m.groupValues[2].ifBlank { m.groupValues[1] }).trim() }
            .replace(Regex("""```[\s\S]*?```"""), " ")
            .replace(Regex("""(?m)^#{1,6}\s+"""), "")
            .replace(Regex("""(?m)^>\s?"""), "")
            .replace(Regex("""(?m)^[-*+•]\s+"""), "")
            .replace(Regex("""(?m)^(-{3,}|\*{3,}|_{3,})$"""), " ")
            .replace(Regex("""[*_`~]+"""), "")
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return if (plain.length > maxChars) plain.take(maxChars).trimEnd() + "…" else plain
    }

    fun cleanForTts(rawMarkdown: String, title: String = ""): String {
        val parsed = parse(rawMarkdown, fallbackTitle = title, stripHeaderBlock = true)
        val speakBody = buildPlainExcerpt(parsed.bodyMarkdown, maxChars = 4000)
        val cleanHead = parsed.cleanTitle.ifBlank { cleanTitle(title) }
        return when {
            cleanHead.isNotBlank() && speakBody.isNotBlank() -> "$cleanHead。$speakBody"
            speakBody.isNotBlank() -> speakBody
            else -> cleanHead
        }
    }

    fun triageDisplayLabel(triage: String, processLevel: String = ""): String {
        val pl = processLevel.trim().lowercase(Locale.ROOT)
        val tr = triage.trim().lowercase(Locale.ROOT)
        return when {
            pl == "deep" || pl == "synthesis" -> "深度精读"
            tr == "process_now" || tr == "deep" -> "建议深读"
            tr == "reference" -> "备查"
            tr == "keep_raw" -> "仅存原文"
            tr == "process_later" -> "待精读"
            else -> ""
        }
    }

    fun isEdgeStandbyItem(item: StreamItemModel): Boolean {
        if (item.intentTag.equals("wechat_edge_standby", ignoreCase = true)) return true
        if (item.itemId.startsWith("edge_wx_")) return true
        val meta = parseMetaMap(item.metadataJson)
        return metaBool(meta, "edgeAnalyzed")
    }

    fun isChisuStreamItem(item: StreamItemModel): Boolean {
        if (item.itemType == "APPROVAL") return false
        return item.intentTag.contains("wechat", ignoreCase = true) ||
            item.intentTag == "pkb_vault" ||
            item.title?.contains("《") == true ||
            item.textContent?.contains("脱水干货") == true ||
            (item.direction == "MAC_TO_PHONE" && !item.textContent.isNullOrBlank())
    }

    fun mergeChisuLibrary(
        macArticles: List<ChisuArticleItemUi>,
        streamItems: List<StreamItemModel>
    ): List<ChisuArticleItemUi> {
        val result = ArrayList<ChisuArticleItemUi>(macArticles.size + streamItems.size)
        val byArticleId = HashMap<Int, Int>()
        val byUrl = HashMap<String, Int>()
        val byNormTitle = HashMap<String, Int>()

        for (macItem in macArticles) {
            val idx = result.size
            result.add(macItem)
            macItem.articleId?.takeIf { it > 0 }?.let { byArticleId[it] = idx }
            if (macItem.sourceUrl.isNotBlank()) byUrl[macItem.sourceUrl.trim()] = idx
            val normT = normalizeTitleForMatch(macItem.title)
            if (normT.isNotBlank()) byNormTitle.putIfAbsent(normT, idx)
        }

        val localChisu = streamItems.filter(::isChisuStreamItem)
        for (item in localChisu) {
            val meta = parseMetaMap(item.metadataJson)
            val rawBody = item.textContent ?: item.voiceTranscript ?: ""
            val parsed = parse(rawBody, fallbackTitle = item.title.orEmpty())
            val resolvedArticleId = metaInt(meta, "wechatArticleId").takeIf { it > 0 }
                ?: extractArticleIdFromFileUri(item.localFileUri)
            val resolvedUrl = sanitizeHttpUrl(metaString(meta, "sourceUrl").ifBlank { parsed.sourceUrl })
            val resolvedTitle = parsed.cleanTitle.ifBlank { cleanTitle(item.title) }.ifBlank { "未命名伴读笔记" }
            val normT = normalizeTitleForMatch(resolvedTitle)
            val isEdge = isEdgeStandbyItem(item)
            val localSourcePath = metaString(meta, "sourceFilePath").takeIf { it.isNotBlank() }

            val existingIdx = resolvedArticleId?.let { byArticleId[it] }
                ?: resolvedUrl.takeIf { it.isNotBlank() }?.let { byUrl[it] }
                ?: normT.takeIf { it.isNotBlank() }?.let { byNormTitle[it] }

            if (existingIdx != null) {
                val prev = result[existingIdx]
                result[existingIdx] = prev.copy(
                    localStreamItemId = prev.localStreamItemId ?: item.itemId,
                    localNoteMarkdown = prev.localNoteMarkdown ?: rawBody.takeIf { it.isNotBlank() },
                    localSourceFilePath = prev.localSourceFilePath ?: localSourcePath,
                    hasNote = prev.hasNote || rawBody.isNotBlank(),
                    hasSource = prev.hasSource || localSourcePath != null || metaBool(meta, "hasSource"),
                    isEdgeNightStandby = prev.isEdgeNightStandby || isEdge,
                    previewExcerpt = prev.previewExcerpt.ifBlank { parsed.previewExcerpt },
                    author = prev.author.ifBlank { metaString(meta, "author").ifBlank { parsed.author } },
                    sourceUrl = prev.sourceUrl.ifBlank { resolvedUrl },
                    triage = prev.triage.ifBlank { metaString(meta, "triage").ifBlank { parsed.triage } },
                    userNotes = prev.userNotes.ifBlank { metaString(meta, "userNotes").ifBlank { parsed.userNotes } },
                    shortSummary = prev.shortSummary.ifBlank { metaString(meta, "shortSummary") }
                )
            } else {
                val idx = result.size
                val summaryStr = metaString(meta, "shortSummary").trim()
                val excerpt = parsed.previewExcerpt.ifBlank { buildPlainExcerpt(summaryStr, 220) }
                val newItem = ChisuArticleItemUi(
                    stableKey = resolvedArticleId?.let { "wx_$it" } ?: "local_${item.itemId}",
                    articleId = resolvedArticleId,
                    localStreamItemId = item.itemId,
                    title = resolvedTitle,
                    author = metaString(meta, "author").ifBlank { parsed.author },
                    sourceUrl = resolvedUrl,
                    publishDate = metaString(meta, "publishDate").ifBlank { parsed.publishDate },
                    createdAtMs = item.createdAtMs,
                    processLevel = metaString(meta, "processLevel").ifBlank { parsed.processLevel },
                    triage = metaString(meta, "triage").ifBlank { parsed.triage },
                    userNotes = metaString(meta, "userNotes").ifBlank { parsed.userNotes },
                    shortSummary = summaryStr,
                    previewExcerpt = excerpt,
                    localNoteMarkdown = rawBody.takeIf { it.isNotBlank() },
                    localSourceFilePath = localSourcePath,
                    hasNote = rawBody.isNotBlank(),
                    hasSource = localSourcePath != null || metaBool(meta, "hasSource"),
                    isEdgeNightStandby = isEdge
                )
                result.add(newItem)
                resolvedArticleId?.let { byArticleId[it] = idx }
                if (resolvedUrl.isNotBlank()) byUrl[resolvedUrl] = idx
                if (normT.isNotBlank()) byNormTitle.putIfAbsent(normT, idx)
            }
        }

        return result.sortedByDescending { it.createdAtMs }
    }

    fun parseIsoToEpochMs(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val trimmed = raw.trim()
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                if (pattern.endsWith("'Z'")) {
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(trimmed)
                if (parsed != null) return parsed.time
            } catch (_: Throwable) {
            }
        }
        return 0L
    }

    private fun parseMetaMap(raw: String?): Map<String, Any?> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { MiniJson.parseObject(raw) }.getOrDefault(emptyMap())
    }

    private fun metaString(map: Map<String, Any?>, key: String): String =
        map[key]?.toString()?.trim().orEmpty()

    private fun metaInt(map: Map<String, Any?>, key: String): Int =
        (map[key] as? Number)?.toInt() ?: map[key]?.toString()?.toIntOrNull() ?: 0

    private fun metaBool(map: Map<String, Any?>, key: String): Boolean =
        when (val v = map[key]) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            is Number -> v.toInt() != 0
            else -> false
        }

    private fun cleanDateValue(raw: String): String {
        val v = raw.trim()
        if (v.isBlank() || v.contains("未知")) return ""
        return v
    }

    private fun sanitizeHttpUrl(raw: String): String {
        val v = raw.trim().trimEnd(')', '）', '>', ' ')
        return if (v.startsWith("http://", ignoreCase = true) || v.startsWith("https://", ignoreCase = true)) {
            v
        } else {
            ""
        }
    }
}
