package com.oms.client.ui.cockpit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oms.client.cockpit.CockpitRuntimeCoordinator
import com.oms.client.cockpit.LivingMemoryEntryUi
import com.oms.client.ui.theme.OmsCircleIconButton
import com.oms.client.ui.theme.OmsFeedbackController
import com.oms.client.ui.theme.OmsIcons
import com.oms.client.ui.theme.OmsPrimaryButton
import com.oms.client.ui.theme.OmsSecondaryButton
import com.oms.client.ui.theme.OmsSurfaceCard
import com.oms.client.ui.theme.OmsTheme
import com.oms.client.ui.theme.OmsToastKind
import com.oms.client.ui.theme.OmsTogglePill
import java.util.Locale

@Composable
fun MemoryCenterModal(
    coordinator: CockpitRuntimeCoordinator,
    macConnected: Boolean,
    onOpenVaultDoc: (relPath: String) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = OmsTheme.colors
    val snapshot by coordinator.memoryCenterFlow.collectAsState()
    val isRefreshing by coordinator.isRefreshingMemoryCenterFlow.collectAsState()

    var filterTab by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    var editingEntry by remember { mutableStateOf<LivingMemoryEntryUi?>(null) }
    var showAddOrEditDialog by remember { mutableStateOf(false) }
    var editorText by remember { mutableStateOf("") }

    LaunchedEffect(Unit, macConnected) {
        coordinator.refreshMemoryCenter()
    }

    val allCombined = remember(snapshot) {
        snapshot.profileEntries + snapshot.workingEntries + snapshot.vaultAnchors
    }
    val manualCount = remember(snapshot) {
        snapshot.profileEntries.count { it.source == "manual" }
    }
    val autoDialogueCount = remember(snapshot) {
        snapshot.profileEntries.count { it.source == "auto" } + snapshot.workingEntries.size
    }
    val vaultCount = snapshot.vaultAnchors.size

    val filteredList = remember(allCombined, filterTab, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.ROOT)
        allCombined.filter { item ->
            val matchFilter = when (filterTab) {
                "MANUAL" -> item.source == "manual"
                "AUTO" -> item.source == "auto" && item.category != "vault"
                "WORKING" -> item.category == "working"
                "VAULT" -> item.category == "vault"
                else -> true
            }
            if (!matchFilter) return@filter false
            if (q.isEmpty()) return@filter true
            item.text.lowercase(Locale.ROOT).contains(q) ||
                item.originDetail.lowercase(Locale.ROOT).contains(q)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.canvas)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.topBar)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OmsCircleIconButton(
                        icon = OmsIcons.ArrowLeft,
                        contentDescription = "返回",
                        onClick = onDismiss
                    )
                    Column {
                        Text(
                            text = "随身助理 · 记忆中心",
                            color = palette.textPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (macConnected) "已与 Mac 记忆中枢及个人知识库实时同步" else "离线缓存视图 · 连上 Mac 自动同步",
                            color = palette.textTertiary,
                            fontSize = 11.5.sp
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OmsCircleIconButton(
                        icon = OmsIcons.Refresh,
                        contentDescription = "刷新记忆",
                        size = 34.dp,
                        iconSize = 16.dp,
                        contentColor = if (isRefreshing) palette.accent else palette.textSecondary,
                        onClick = { coordinator.refreshMemoryCenter() }
                    )
                    OmsPrimaryButton(
                        label = "手动添加",
                        icon = OmsIcons.Plus,
                        height = 34.dp,
                        onClick = {
                            editingEntry = null
                            editorText = ""
                            showAddOrEditDialog = true
                        }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))

            // Overview Stats Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MemoryStatChip(
                    label = "手动添加",
                    value = "$manualCount",
                    tint = palette.accent,
                    modifier = Modifier.weight(1f)
                )
                MemoryStatChip(
                    label = "对话自动提取",
                    value = "$autoDialogueCount",
                    tint = palette.success,
                    modifier = Modifier.weight(1f)
                )
                MemoryStatChip(
                    label = "知识库脉络",
                    value = "$vaultCount",
                    tint = palette.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                MemoryStatChip(
                    label = "已遗忘屏蔽",
                    value = "${snapshot.forgottenCount}",
                    tint = palette.textTertiary,
                    modifier = Modifier.weight(1f)
                )
            }

            // Search Box
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(palette.subtleSurface)
                    .border(0.6.dp, palette.hairline, RoundedCornerShape(19.dp))
                    .padding(horizontal = 12.dp)
            ) {
                Icon(OmsIcons.Search, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(15.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text("搜索已保存的偏好、近期焦点或知识库记忆…", color = palette.textTertiary, fontSize = 12.8.sp)
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(color = palette.textPrimary, fontSize = 13.2.sp),
                        cursorBrush = SolidColor(palette.accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    OmsCircleIconButton(
                        icon = OmsIcons.Close,
                        contentDescription = "清空",
                        size = 22.dp,
                        iconSize = 12.dp,
                        onClick = { searchQuery = "" }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Filter Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                OmsTogglePill("全部 (${allCombined.size})", filterTab == "ALL", onClick = { filterTab = "ALL" })
                OmsTogglePill("手动添加 ($manualCount)", filterTab == "MANUAL", onClick = { filterTab = "MANUAL" })
                OmsTogglePill("对话自动提取 ($autoDialogueCount)", filterTab == "AUTO", onClick = { filterTab = "AUTO" })
                OmsTogglePill("近期焦点 (${snapshot.workingEntries.size})", filterTab == "WORKING", onClick = { filterTab = "WORKING" })
                OmsTogglePill("知识库 ($vaultCount)", filterTab == "VAULT", onClick = { filterTab = "VAULT" })
            }

            // Memory List
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                if (filteredList.isEmpty()) {
                    item(key = "empty_memory") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(50.dp).clip(CircleShape).background(palette.subtleSurface)
                            ) {
                                Icon(OmsIcons.Bookmark, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(22.dp))
                            }
                            Text("当前分类下暂无记忆片段", color = palette.textPrimary, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "随身助理会在日常非无痕对话中自动沉淀高价值偏好，你也可以点右上角「手动添加」随时录入。",
                                color = palette.textTertiary,
                                fontSize = 12.5.sp,
                                lineHeight = 19.sp
                            )
                        }
                    }
                } else {
                    items(items = filteredList, key = { "${it.category}_${it.id}" }) { entry ->
                        MemoryEntryCard(
                            entry = entry,
                            onEdit = {
                                editingEntry = entry
                                editorText = entry.text
                                showAddOrEditDialog = true
                            },
                            onPromoteToManual = {
                                coordinator.upsertMemoryCenterEntry(
                                    text = entry.text,
                                    source = "manual",
                                    originDetail = if (entry.category == "vault") "从知识库固化为常驻记忆" else "从近期焦点固化为常驻记忆"
                                )
                                OmsFeedbackController.show("已固化为常驻手动记忆（永不自动淘汰）", OmsToastKind.SUCCESS)
                            },
                            onOpenVaultSource = {
                                val rel = entry.originDetail.substringAfter("· ", "").trim()
                                if (rel.endsWith(".md")) {
                                    onDismiss()
                                    onOpenVaultDoc(rel)
                                }
                            },
                            onForget = {
                                coordinator.deleteMemoryCenterEntry(entry)
                                OmsFeedbackController.show("已遗忘该记忆片段（后台不再重复提取）", OmsToastKind.INFO)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddOrEditDialog) {
        AlertDialog(
            onDismissRequest = { showAddOrEditDialog = false },
            containerColor = palette.composer,
            title = {
                Text(
                    text = if (editingEntry == null) "手动添加随身助理记忆" else "编辑记忆片段",
                    color = palette.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "手动添加或修改后的记忆会标记为「手动添加」，在非无痕对话中持续生效且永不会被自动淘汰。",
                        color = palette.textTertiary,
                        fontSize = 12.sp,
                        lineHeight = 17.5.sp
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.subtleSurface)
                            .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        if (editorText.isEmpty()) {
                            Text(
                                text = "例如：回答技术方案时先给结论再展开；偏好极简无冗余代码风格…",
                                color = palette.textTertiary,
                                fontSize = 13.sp
                            )
                        }
                        BasicTextField(
                            value = editorText,
                            onValueChange = { editorText = it },
                            textStyle = TextStyle(color = palette.textPrimary, fontSize = 14.sp, lineHeight = 20.sp),
                            cursorBrush = SolidColor(palette.accent),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = editorText.trim()
                    if (clean.isNotBlank()) {
                        coordinator.upsertMemoryCenterEntry(
                            id = editingEntry?.id,
                            text = clean,
                            source = "manual",
                            originDetail = if (editingEntry == null) "手动添加" else "手动编辑"
                        )
                        showAddOrEditDialog = false
                        OmsFeedbackController.show("已保存记忆", OmsToastKind.SUCCESS)
                    }
                }) {
                    Text("保存", color = palette.accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddOrEditDialog = false }) {
                    Text("取消", color = palette.textTertiary)
                }
            }
        )
    }
}

@Composable
private fun MemoryStatChip(
    label: String,
    value: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val palette = OmsTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(palette.cardSurface)
            .border(0.6.dp, palette.hairline, RoundedCornerShape(13.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = tint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = palette.textTertiary, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MemoryEntryCard(
    entry: LivingMemoryEntryUi,
    onEdit: () -> Unit,
    onPromoteToManual: () -> Unit,
    onOpenVaultSource: () -> Unit,
    onForget: () -> Unit
) {
    val palette = OmsTheme.colors
    val isManual = entry.source == "manual"
    val isVault = entry.category == "vault"
    val isWorking = entry.category == "working"

    val badgeText = when {
        isManual -> "手动添加"
        isVault -> "知识库脉络"
        isWorking -> "近期焦点 · 自动提取"
        entry.observations > 1 -> "自动提取 · 对话强化 ${entry.observations} 次"
        else -> "自动提取 · 历史对话"
    }
    val badgeBg = when {
        isManual -> palette.accentSoft
        isVault -> palette.subtleSurface
        else -> palette.successSoft
    }
    val badgeFg = when {
        isManual -> palette.accent
        isVault -> palette.textSecondary
        else -> palette.success
    }
    val timeStr = remember(entry.lastSeenAtMs) {
        if (entry.lastSeenAtMs > 0L) formatCompactRelativeTime(entry.lastSeenAtMs) else ""
    }

    OmsSurfaceCard(
        cornerRadius = 15.dp,
        contentPadding = PaddingValues(13.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = badgeText,
                    color = badgeFg,
                    fontSize = 10.8.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeBg)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
                if (timeStr.isNotBlank()) {
                    Text(timeStr, color = palette.textTertiary, fontSize = 11.sp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (entry.category == "profile") {
                    OmsCircleIconButton(
                        icon = OmsIcons.NewChat,
                        contentDescription = "编辑",
                        size = 28.dp,
                        iconSize = 14.dp,
                        contentColor = palette.textSecondary,
                        onClick = onEdit
                    )
                } else {
                    OmsSecondaryButton(
                        label = "固化常驻",
                        icon = OmsIcons.Pin,
                        height = 26.dp,
                        onClick = onPromoteToManual
                    )
                }
                if (isVault && entry.originDetail.contains(".md")) {
                    OmsCircleIconButton(
                        icon = OmsIcons.BookOpen,
                        contentDescription = "查看原笔记",
                        size = 28.dp,
                        iconSize = 14.dp,
                        contentColor = palette.accent,
                        onClick = onOpenVaultSource
                    )
                }
                OmsCircleIconButton(
                    icon = OmsIcons.Trash,
                    contentDescription = "遗忘此记忆",
                    size = 28.dp,
                    iconSize = 14.dp,
                    contentColor = palette.danger,
                    onClick = onForget
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = entry.text,
            color = palette.textPrimary,
            fontSize = 14.5.sp,
            lineHeight = 21.5.sp,
            fontWeight = FontWeight.Medium
        )

        if (entry.originDetail.isNotBlank()) {
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = "来源：${entry.originDetail}",
                color = palette.textTertiary,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
