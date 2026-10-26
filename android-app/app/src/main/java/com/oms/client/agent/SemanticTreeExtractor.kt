package com.oms.client.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

data class ExtractedSemanticNode(
    val id: String,
    val role: String,
    val label: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val bounds: Rect
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("role", role)
        put("label", label)
        put("clickable", clickable)
        put("editable", editable)
        put("scrollable", scrollable)
        if (checkable) {
            put("checkable", true)
            put("checked", checked)
        }
        if (selected) put("selected", true)
        put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
        put("center", JSONArray(listOf(bounds.centerX(), bounds.centerY())))
    }
}

/**
 * Module 05: 3-Step Semantic UI Tree Pruner & Child Text Aggregator
 * Compresses 500+ raw Android View nodes into 15~45 high-signal interactive nodes (< 6KB JSON).
 */
object SemanticTreeExtractor {
    private val nodeRefCache = ConcurrentHashMap<String, WeakReference<AccessibilityNodeInfo>>()

    fun getNodeById(shortId: String): AccessibilityNodeInfo? {
        val normalized = if (shortId.startsWith("#")) shortId else "#$shortId"
        return nodeRefCache[normalized]?.get()
    }

    fun extractCompactTree(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        maxDepth: Int = 28
    ): List<ExtractedSemanticNode> = extractCompactTreeFromRoots(
        if (root != null) listOf(root) else emptyList(),
        screenWidth,
        screenHeight,
        maxDepth
    )

    fun extractCompactTreeFromRoots(
        roots: List<AccessibilityNodeInfo>,
        screenWidth: Int,
        screenHeight: Int,
        maxDepth: Int = 32
    ): List<ExtractedSemanticNode> {
        nodeRefCache.clear()
        if (roots.isEmpty()) return emptyList()

        val screenRect = Rect(0, 0, screenWidth, screenHeight)
        val result = mutableListOf<ExtractedSemanticNode>()
        val seenBoundsLabels = HashSet<String>()
        var nextIndex = 1

        fun collectChildTexts(node: AccessibilityNodeInfo, depth: Int, acc: MutableList<String>) {
            if (depth > 6 || acc.size >= 5) return
            val ownText = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
            if (ownText.isNotEmpty()) {
                acc.add(ownText)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (!child.isClickable && !child.isEditable) {
                    collectChildTexts(child, depth + 1, acc)
                }
            }
        }

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > maxDepth || result.size >= 85) return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val inScreen = node.isVisibleToUser &&
                Rect.intersects(bounds, screenRect) &&
                bounds.width() >= 6 &&
                bounds.height() >= 6

            if (inScreen) {
                val clickable = node.isClickable || node.isLongClickable
                val editable = node.isEditable
                val scrollable = node.isScrollable
                val checkable = node.isCheckable

                var label = (node.text?.toString()
                    ?: node.contentDescription?.toString()
                    ?: node.hintText?.toString()
                    ?: "").trim()

                // Step 2: Child Text Aggregation for interactive containers without direct text
                if (label.isEmpty() && (clickable || checkable)) {
                    val childTexts = mutableListOf<String>()
                    collectChildTexts(node, 0, childTexts)
                    label = childTexts.distinct().joinToString(" · ").take(80)
                }

                val hasMeaning = clickable || editable || scrollable || checkable || label.isNotEmpty()
                val dedupKey = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}:$label"
                if (hasMeaning && seenBoundsLabels.add(dedupKey)) {
                    val shortId = "#$nextIndex"
                    nextIndex++
                    val simpleRole = (node.className?.toString() ?: "View").substringAfterLast('.')
                    nodeRefCache[shortId] = WeakReference(node)
                    result.add(
                        ExtractedSemanticNode(
                            id = shortId,
                            role = simpleRole,
                            label = label.take(100),
                            clickable = clickable,
                            editable = editable,
                            scrollable = scrollable,
                            checkable = checkable,
                            checked = node.isChecked,
                            selected = node.isSelected,
                            bounds = bounds
                        )
                    )
                }
            }

            // Always recurse into child nodes even if the parent container itself had no visible bounds
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1)
            }
        }

        for (root in roots) {
            traverse(root, 0)
        }
        return result
    }
}
