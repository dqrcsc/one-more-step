package com.oms.core.ai

data class EdgeArticleInput(
    val url: String,
    val title: String,
    val author: String = "",
    val publishDate: String = "",
    val digest: String = "",
    val markdownBody: String,
    val extractionQualityStatus: String = "complete"
)

data class EdgeArticleTriageResult(
    val englishSlug: String,
    val triage: String, // "keep_raw" | "reference" | "process_later" | "process_now"
    val wechatReply: String,
    val companionNoteMarkdown: String,
    val modelUsed: String,
    val isFallbackNote: Boolean = false
)

/**
 * Mobile Edge implementation of 青简·尺素 article initial screening,
 * preserving 100% parity with Mac `wechat-context` validation_contract & anti-hallucination rules.
 */
object WeChatArticleAnalyzer {
    const val VALIDATION_SLOT = "{{VALIDATION_CONTRACT}}"
    const val INITIAL_REPLY_LIMIT = 650
    const val LONG_REPLY_NOTICE = "⚠️ 本次初筛未压缩到手机短版，保留完整内容以免丢失证据或验收条件。"
    const val FALLBACK_MARKER = "未完成模型分析，以下仅为本地原文摘录，不代表已完成观点或业务判断。"

    private val VALID_TRIAGES = setOf("keep_raw", "reference", "process_later", "process_now")

    fun hasSubstantiveAnnotation(notes: String, title: String): Boolean {
        fun clean(v: String) = v.replace(Regex("""^\s*\[链接\]\s*"""), "")
            .replace(Regex("""[\s“”"'「」『』，。！？：、]"""), "")
        val value = clean(notes)
        val heading = clean(title)
        if (value.isEmpty() || value == "（无额外批注）") return false
        if (value == heading) return false
        if (notes.trimStart().startsWith("[链接]") && value.length >= 4 && heading.startsWith(value)) return false
        return true
    }

    fun omitEmptyAnnotation(text: String, keep: Boolean): String {
        if (keep) return text
        val result = ArrayList<String>()
        var skipping = false
        val startRe = Regex("""^\s*(?:#{1,6}\s*)?(?:🎯\s*)?(?:\*\*)?批注回应(?:\*\*)?\s*[:：]?""")
        val nextSecRe = Regex("""^\s*(?:#{1,6}\s+|[💧💡🏢🔍🚦]|(?:脱水干货|观点碰撞|业务与规划启发|边界与待验证|分诊与下一步))""")
        for (line in text.split('\n')) {
            if (startRe.containsMatchIn(line)) {
                skipping = true
                continue
            }
            if (skipping && nextSecRe.containsMatchIn(line)) {
                skipping = false
            }
            if (!skipping) result.add(line)
        }
        return result.joinToString("\n").trim()
    }

    @Suppress("UNCHECKED_CAST")
    fun applyValidationContract(
        validationContract: Any?,
        wechatReply: String,
        companionMarkdown: String
    ): Pair<String, String> {
        val bodies = listOf(wechatReply, companionMarkdown)
        if (validationContract == null) {
            if (bodies.any { it.contains(VALIDATION_SLOT) }) {
                throw IllegalArgumentException("Unexpected validation slot when validation_contract is null")
            }
            return wechatReply to companionMarkdown
        }
        val contractMap = validationContract as? Map<String, Any?>
            ?: throw IllegalArgumentException("Invalid validation_contract object")
        val metricsRaw = contractMap["metrics"] as? List<*>
            ?: throw IllegalArgumentException("Missing metrics array")
        if (metricsRaw.isEmpty() || metricsRaw.size > 3) {
            throw IllegalArgumentException("metrics count must be 1..3")
        }
        if (bodies.any { it.split(VALIDATION_SLOT).size != 2 }) {
            throw IllegalArgumentException("Each output requires exactly one {{VALIDATION_CONTRACT}} slot")
        }

        val bizHeaderRe = Regex("""(?:^|\n)(?:#{1,3}\s*)?(?:\d+[.、]\s*)?(?:🏢\s*)?业务(?:与规划启发|验证|启发)(?:（[^）\n]*）|\([^)\n]*\))?(?:[：:][ \t]*\n?|[ \t]*\n)""")
        val nextMajorRe = Regex("""\n(?:##\s|(?:#{1,3}\s*)?(?:\d+[.、]\s*)?(?:🔍|🚦))""")
        val forbiddenStatsRe = Regex("""显著|波动范围|置信区间|百分点|相对提升|相对下降""")

        for (body in bodies) {
            val m = bizHeaderRe.find(body) ?: throw IllegalArgumentException("Missing business validation section")
            val rest = body.substring(m.range.last + 1)
            val endMatch = nextMajorRe.find(rest)
            val bizBody = if (endMatch == null) rest else rest.substring(0, endMatch.range.first)
            if (!bizBody.contains(VALIDATION_SLOT)) {
                throw IllegalArgumentException("Validation slot must be inside business section")
            }
            if (forbiddenStatsRe.containsMatchIn(bizBody.replace(VALIDATION_SLOT, ""))) {
                throw IllegalArgumentException("Statistics terms repeated outside validation_contract")
            }
        }

        val seenNames = HashSet<String>()
        val formattedMetrics = metricsRaw.map { item ->
            val mm = item as? Map<String, Any?> ?: throw IllegalArgumentException("Invalid metric item")
            val name = mm["name"]?.toString()?.trim().orEmpty()
            val num = mm["numerator"]?.toString()?.trim().orEmpty()
            val den = mm["denominator"]?.toString()?.trim().orEmpty()
            if (listOf(name, num, den).any { it.isEmpty() || it.length > 100 || it.contains('\n') || it.contains('\r') }) {
                throw IllegalArgumentException("Invalid metric name/numerator/denominator")
            }
            if (!seenNames.add(name)) throw IllegalArgumentException("Duplicate metric name")
            val dir = mm["direction"]?.toString().orEmpty()
            if (dir != "increase" && dir != "decrease") throw IllegalArgumentException("Invalid direction")
            val gain = mm["minimum_gain"] as? Map<String, Any?> ?: throw IllegalArgumentException("Missing minimum_gain")
            val unit = gain["unit"]?.toString().orEmpty()
            if (unit != "percentage_points" && unit != "relative_percent") throw IllegalArgumentException("Invalid gain unit")
            val rawVal = gain["value"]
            val numVal: Double? = when (rawVal) {
                null -> null
                is Number -> rawVal.toDouble()
                else -> throw IllegalArgumentException("Invalid gain value")
            }
            if (numVal != null && (!numVal.isFinite() || numVal < 0.0 || (unit == "percentage_points" && numVal > 100.0))) {
                throw IllegalArgumentException("Out-of-range gain value")
            }
            val diff = if (dir == "increase") "B - A" else "A - B"
            val relative = unit == "relative_percent"
            val label = if (relative) {
                if (dir == "increase") "相对提升" else "相对下降"
            } else {
                if (dir == "increase") "提升" else "下降"
            }
            val formula = if (relative) "($diff) / A × 100%" else "($diff) × 100（百分点）"
            val cleanValStr = if (numVal != null && numVal == numVal.toLong().toDouble()) {
                numVal.toLong().toString()
            } else {
                numVal?.toString()
            }
            val threshold = if (cleanValStr == null) "门槛待定" else "$label ≥ $cleanValStr${if (relative) "%" else " 个百分点"}"
            "$name = $num / $den；$formula；$threshold。"
        }

        val block = buildList {
            add("指标（建议阈值、待确认）：A基线/B试验，比率0–1。")
            addAll(formattedMetrics)
            add("分母为 0 不判定；A = 0 时相对变化不可计算。")
            add("有效试验须全达已确认门槛才通过；未定/未确认不能判定通过。未达仅限本次样本、模型、规则与配置。")
            add("门槛不是波动范围；波动尚未估计，不作统计显著性判断；证据不足需复核。")
        }.joinToString("\n")

        return wechatReply.replace(VALIDATION_SLOT, block) to companionMarkdown.replace(VALIDATION_SLOT, block)
    }

    private fun buildDeveloperPrompt(): String = """
你是用户的文章精读搭档。初筛的目标是帮助判断“哪些观点值得更新，对正在做的事有什么用”，不是给文章寻找归档位置。
当前正文是作者立场的唯一直接依据；知识库胶囊与近期思考仅作对比背景，不得把原文未提及的概念归给作者，必要类比标明“分析类比，原文未提及”。保留原文“往往/可能/在某范围”等限定，不把必要条件改成充分条件。

请严格输出合法 JSON 对象，不加任何外层说明：
{
  "english_slug": "3到6个英文单词的小写下划线短名",
  "triage": "keep_raw / reference / process_later / process_now 四选一",
  "validation_contract": "无业务试验则为 null；否则按规范输出对象，两份正文共用",
  "wechat_reply": "手机微信紧凑初筛纯文本，含统计段尽量控制在 450–650 字左右",
  "companion_note_markdown": "完整 Markdown 伴读正文，使用二级标题展开论证与实验设计，不含 frontmatter 和一级标题"
}
validation_contract 结构：{"metrics":[{"name":"指标短名","numerator":"分子短名","denominator":"分母短名","direction":"increase 或 decrease","minimum_gain":{"unit":"percentage_points 或 relative_percent","value":5}}]}。
有试验时，wechat_reply 与 companion_note_markdown 各在“🏢 业务与规划启发”章节放且仅放一次 {{VALIDATION_CONTRACT}}，且该章节除占位符外严禁出现“显著、波动范围、置信区间、百分点、相对提升、相对下降”等词；无试验则 validation_contract 为 null 且两份正文均不放占位符。
固定模块顺序：1. 🎯 批注回应（无实质批注省略） 2. 💧 脱水干货 3. 💡 观点碰撞 4. 🏢 业务与规划启发 5. 🔍 边界与待验证 6. 🚦 分诊与下一步（含逐段精读价值、场景验证价值、分诊与下一步）。初筛正文不得输出具体笔记文件名或 [[WikiLink]]。
""".trimIndent()

    suspend fun analyzeArticle(
        engine: DualLlmEngine,
        article: EdgeArticleInput,
        userNotes: String,
        capsule: ContextCapsule
    ): EdgeArticleTriageResult {
        val keepAnnotation = hasSubstantiveAnnotation(userNotes, article.title)
        if (!engine.runtimeConfig.hasAnyConfiguredProvider()) {
            return buildFallbackDigest(article, userNotes, keepAnnotation)
        }

        val excerpt = if (article.markdownBody.length > 14000) {
            article.markdownBody.take(14000) + "\n\n...(正文过长已截断，不代表已阅读全文)"
        } else {
            article.markdownBody
        }

        val userPayload = MiniJson.stringify(
            linkedMapOf(
                "user_annotation" to if (keepAnnotation) userNotes.trim() else "（无额外批注）",
                "analysis_background" to capsule.analysisBackgroundFormatted.ifBlank { "（未提供）" },
                "focus" to capsule.nowSummary.ifBlank { "（未提供）" },
                "queue" to capsule.queueSummary.ifBlank { "（未提供）" },
                "topics" to capsule.topicsOverview.ifBlank { "（未提供）" },
                "recent_mobile_thinking" to capsule.recentWorkingMemory,
                "candidate_excerpts" to capsule.matchedCandidates.mapIndexed { idx, c ->
                    linkedMapOf(
                        "candidate" to (idx + 1),
                        "evidence_scope" to "background_only_not_article_evidence",
                        "excerpt" to c.excerpt
                    )
                },
                "article" to linkedMapOf(
                    "evidence_scope" to "current_article_author_claims_not_verified_facts",
                    "title" to article.title,
                    "author" to article.author,
                    "publishDate" to article.publishDate,
                    "url" to article.url,
                    "extraction_quality" to article.extractionQualityStatus,
                    "body" to excerpt
                )
            )
        )

        val devPrompt = buildDeveloperPrompt()
        val baseTurns = listOf(LlmTurnMessage("user", userPayload))

        return try {
            var completed = engine.completeSync(RouteIntent.WECHAT_ARTICLE_TRIAGE, devPrompt, baseTurns, jsonMode = true)
            var parsed = MiniJson.parseObject(completed.fullText)
            val rendered = try {
                validateAndApply(parsed)
            } catch (contractErr: Throwable) {
                val retryTurns = baseTurns + listOf(
                    LlmTurnMessage("assistant", completed.fullText),
                    LlmTurnMessage(
                        "user",
                        "上次 JSON 未通过 validation_contract 校验（${contractErr.message}）。请修正后重新输出完整合法 JSON：" +
                            "若保留试验，确保两份正文的“🏢 业务与规划启发”内各含且仅含一次 {{VALIDATION_CONTRACT}} 且不含禁用统计词；否则设 validation_contract 为 null 并移除占位符。"
                    )
                )
                completed = engine.completeSync(RouteIntent.WECHAT_ARTICLE_TRIAGE, devPrompt, retryTurns, jsonMode = true)
                parsed = MiniJson.parseObject(completed.fullText)
                validateAndApply(parsed)
            }

            var wxReply = omitEmptyAnnotation(rendered.first, keepAnnotation)
            var compMd = omitEmptyAnnotation(rendered.second, keepAnnotation)
            if (capsule.analysisWarnings.isNotEmpty()) {
                val warn = "⚠️ 背景材料未完整采用：${capsule.analysisWarnings.joinToString("；")}"
                wxReply += "\n\n$warn"
                compMd += "\n\n$warn"
            }
            if (wxReply.codePointCount(0, wxReply.length) > INITIAL_REPLY_LIMIT) {
                wxReply += "\n\n$LONG_REPLY_NOTICE"
            }

            val rawTriage = parsed["triage"]?.toString()?.trim().orEmpty()
            val triage = if (rawTriage in VALID_TRIAGES) rawTriage else "process_later"
            val slug = parsed["english_slug"]?.toString()?.trim()
                ?.replace(Regex("[^a-z0-9_]+"), "_")
                ?.trim('_')
                ?.takeIf { it.isNotEmpty() } ?: "wechat_article"

            EdgeArticleTriageResult(
                englishSlug = slug,
                triage = triage,
                wechatReply = wxReply.trim(),
                companionNoteMarkdown = compMd.trim(),
                modelUsed = completed.modelUsed,
                isFallbackNote = false
            )
        } catch (_: Throwable) {
            buildFallbackDigest(article, userNotes, keepAnnotation)
        }
    }

    private fun validateAndApply(parsed: Map<String, Any?>): Pair<String, String> {
        val wx = parsed["wechat_reply"]?.toString()?.trim().orEmpty()
        val md = parsed["companion_note_markdown"]?.toString()?.trim().orEmpty()
        require(wx.isNotEmpty() && md.isNotEmpty()) { "Incomplete analysis fields" }
        return applyValidationContract(parsed["validation_contract"], wx, md)
    }

    private fun buildFallbackDigest(
        article: EdgeArticleInput,
        userNotes: String,
        keepAnnotation: Boolean
    ): EdgeArticleTriageResult {
        val snippet = article.markdownBody.replace(Regex("\\s+"), " ").trim().take(360)
        val noteLine = if (keepAnnotation) "\n🎯 批注记录：${userNotes.trim()}\n" else ""
        val reply = buildString {
            append("⚠️ ").append(FALLBACK_MARKER).append('\n')
            append("📄 《").append(article.title.ifBlank { "未命名文章" }).append("》")
            if (article.author.isNotBlank()) append(" · ").append(article.author)
            append('\n').append(noteLine)
            append("💧 原文摘录：").append(snippet)
            append("\n🚦 分诊：process_later（Mac 唤醒后可执行 oms wx reanalyze 补跑完整初筛）")
        }
        return EdgeArticleTriageResult(
            englishSlug = "wechat_article",
            triage = "process_later",
            wechatReply = reply,
            companionNoteMarkdown = "## 本地摘录（待补跑初筛）\n\n$FALLBACK_MARKER\n\n$snippet",
            modelUsed = "local_fallback",
            isFallbackNote = true
        )
    }
}
