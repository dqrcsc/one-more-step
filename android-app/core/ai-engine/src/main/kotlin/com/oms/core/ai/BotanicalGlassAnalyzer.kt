package com.oms.core.ai

data class FieldGuideSpecimenCard(
    val specimenId: String,
    val chineseName: String,
    val commonAliases: String = "",
    val latinName: String = "",
    val familyGenus: String = "",
    val category: String = "植物",
    val confidencePercent: Int = 90,
    val oneLineSummary: String = "",
    val keyMorphology: List<String> = emptyList(),
    val lookalikeComparison: String = "",
    val bloomAndHabitat: String = "",
    val safetyOrCareNote: String = "",
    val modelUsed: String = "",
    val sources: List<GroundingSource> = emptyList(),
    val capturedAtIso: String = ""
) {
    fun toVaultMarkdown(photoFileName: String? = null): String = buildString {
        appendLine("---")
        appendLine("type: field_guide_specimen")
        appendLine("title: \"${chineseName.replace("\"", "\\\"")}\"")
        if (latinName.isNotBlank()) appendLine("latin_name: \"${latinName.replace("\"", "\\\"")}\"")
        if (familyGenus.isNotBlank()) appendLine("taxonomy: \"${familyGenus.replace("\"", "\\\"")}\"")
        appendLine("category: \"$category\"")
        appendLine("confidence: $confidencePercent")
        if (capturedAtIso.isNotBlank()) appendLine("captured_at: \"$capturedAtIso\"")
        appendLine("source: oms_botanical_glass")
        appendLine("---")
        appendLine()
        appendLine("# 🌿 $chineseName" + if (latinName.isNotBlank()) " (*$latinName*)" else "")
        appendLine()
        if (commonAliases.isNotBlank() || familyGenus.isNotBlank()) {
            appendLine("> **分类**：${familyGenus.ifBlank { category }}" +
                (if (commonAliases.isNotBlank()) " ｜ **别名**：$commonAliases" else "") +
                " ｜ **置信度**：${confidencePercent}%")
            appendLine()
        }
        if (!photoFileName.isNullOrBlank()) {
            appendLine("![[$photoFileName]]")
            appendLine()
        }
        if (oneLineSummary.isNotBlank()) {
            appendLine(oneLineSummary)
            appendLine()
        }
        if (keyMorphology.isNotEmpty()) {
            appendLine("## 🔬 画面实证形态特征")
            keyMorphology.forEach { appendLine("- $it") }
            appendLine()
        }
        if (lookalikeComparison.isNotBlank()) {
            appendLine("## ⚖️ 易混物种辨析")
            appendLine(lookalikeComparison)
            appendLine()
        }
        if (bloomAndHabitat.isNotBlank()) {
            appendLine("## 🌦️ 物候习性与博物背景")
            appendLine(bloomAndHabitat)
            appendLine()
        }
        if (safetyOrCareNote.isNotBlank()) {
            appendLine("## 💡 养护与安全提示")
            appendLine(safetyOrCareNote)
            appendLine()
        }
        if (sources.isNotEmpty()) {
            appendLine("## 🔗 联网核验参考")
            sources.forEach { s -> appendLine("- [${s.title}](${s.uri})") }
        }
    }
}

/**
 * "Google Glass" Botanical & Natural World Identifier powered by `gemini-4-argon` + Google Search Grounding
 * (with automatic `gpt-6.2-sol` multimodal fallback).
 */
class BotanicalGlassAnalyzer(private val llmEngine: DualLlmEngine) {

    @Suppress("UNCHECKED_CAST")
    suspend fun identifySpecimen(
        jpegBase64: String,
        userQuestion: String = "",
        specimenId: String = "spec_${System.currentTimeMillis()}",
        capturedAtIso: String = ""
    ): FieldGuideSpecimenCard {
        val systemPrompt = """
            你是一位兼具植物分类学严谨度与博物趣味性的随身自然导师（Google Glass 博物之眼）。
            请仔细观察用户拍摄的照片（优先结合叶序、叶缘锯齿、叶脉、花冠裂片、雄蕊形态、树皮纹理或器物细节，并联网核实学名与分类）：
            1. 给出准确的中文正式名、常见俗名、标准拉丁二名法学名（Genus species）与科属分类；
            2. 提炼 3~5 条从当前照片中直接可见的形态鉴定依据（让用户看图就能印证）；
            3. 专门指出 1~2 个日常最容易混淆的近似种，并讲清一眼区分的关键特征（如叶柄腺点、花梗长短、开花先后叶等）；
            4. 补充花果期、生态习性、原产地或人文典故，以及家庭养护要点或汁液毒性安全提醒。
            请严格只输出一个合法的 JSON 对象（不要加额外寒暄），字段如下：
            {
              "chinese_name": "中文正式名",
              "common_aliases": "常见俗名（顿号分隔）",
              "latin_name": "Genus species",
              "family_genus": "XX科 XX属",
              "category": "植物|花卉|树木|昆虫|鸟类|真菌|矿物|器物",
              "confidence_percent": 92,
              "one_line_summary": "一句话生动点明它的身份与气质（30~60字）",
              "key_morphology": ["特征1：...", "特征2：...", "特征3：..."],
              "lookalike_comparison": "与易混种的核心区分细节...",
              "bloom_and_habitat": "花果期、生境分布与博物故事...",
              "safety_or_care_note": "光照浇水养护建议或毒性安全提示..."
            }
        """.trimIndent()

        val promptText = if (userQuestion.isNotBlank()) {
            "请识别画面中的主体并重点回答我的问题：$userQuestion"
        } else {
            "请识别我镜头拍到的植物/物体，生成完整的博物图鉴卡片 JSON。"
        }

        val completed = llmEngine.completeSync(
            intent = RouteIntent.BOTANICAL_VISION,
            developerPrompt = systemPrompt,
            turns = listOf(
                LlmTurnMessage(
                    role = "user",
                    content = promptText,
                    images = listOf(LlmImageAttachment("image/jpeg", jpegBase64))
                )
            ),
            // Keep jsonMode=false when Gemini Search Grounding is active so Google Search tool is enabled!
            jsonMode = false
        )

        return parseSpecimenResponse(
            rawText = completed.fullText,
            specimenId = specimenId,
            modelUsed = completed.modelUsed,
            sources = completed.sources,
            capturedAtIso = capturedAtIso
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun parseSpecimenResponse(
        rawText: String,
        specimenId: String,
        modelUsed: String,
        sources: List<GroundingSource>,
        capturedAtIso: String
    ): FieldGuideSpecimenCard {
        val jsonCandidate = runCatching { MiniJson.extractJsonObject(rawText) }.getOrDefault("")
        val parsed = runCatching {
            if (jsonCandidate.isNotBlank()) MiniJson.parseObject(jsonCandidate) else emptyMap<String, Any?>()
        }.getOrDefault(emptyMap())

        if (parsed.isEmpty()) {
            return FieldGuideSpecimenCard(
                specimenId = specimenId,
                chineseName = "镜头博物解析",
                oneLineSummary = rawText.trim().take(600),
                modelUsed = modelUsed,
                sources = sources,
                capturedAtIso = capturedAtIso
            )
        }

        val morph = (parsed["key_morphology"] as? List<*>)?.mapNotNull {
            it?.toString()?.trim()?.takeIf(String::isNotEmpty)
        } ?: emptyList()
        val conf = ((parsed["confidence_percent"] as? Number)?.toInt()
            ?: parsed["confidence_percent"]?.toString()?.toIntOrNull()
            ?: 88).coerceIn(1, 100)

        return FieldGuideSpecimenCard(
            specimenId = specimenId,
            chineseName = parsed["chinese_name"]?.toString()?.trim().orEmpty().ifBlank { "未知物种" },
            commonAliases = parsed["common_aliases"]?.toString()?.trim().orEmpty(),
            latinName = parsed["latin_name"]?.toString()?.trim().orEmpty(),
            familyGenus = parsed["family_genus"]?.toString()?.trim().orEmpty(),
            category = parsed["category"]?.toString()?.trim().orEmpty().ifBlank { "植物" },
            confidencePercent = conf,
            oneLineSummary = parsed["one_line_summary"]?.toString()?.trim().orEmpty(),
            keyMorphology = morph,
            lookalikeComparison = parsed["lookalike_comparison"]?.toString()?.trim().orEmpty(),
            bloomAndHabitat = parsed["bloom_and_habitat"]?.toString()?.trim().orEmpty(),
            safetyOrCareNote = parsed["safety_or_care_note"]?.toString()?.trim().orEmpty(),
            modelUsed = modelUsed,
            sources = sources,
            capturedAtIso = capturedAtIso
        )
    }
}
