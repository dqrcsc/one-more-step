package com.oms.client.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.oms.client.OmsApplication
import com.oms.client.R

/**
 * Module 03: System Share Sheet Receiver (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`)
 * & Direct Share Pinned Shortcut Publisher (`default_inbox` | `pkb_vault` | `ui_bug`)
 */
class ShareReceiverActivity : Activity() {

    companion object {
        private const val CATEGORY_DIRECT_SHARE = "com.oms.client.category.DIRECT_SHARE_TARGET"

        fun publishDirectShareShortcuts(context: Context) {
            val sm = context.getSystemService(ShortcutManager::class.java) ?: return
            val targets = listOf(
                Triple("shortcut_pkb_vault", "📚 存入个人知识库", "pkb_vault"),
                Triple("shortcut_default_inbox", "🚀 发给 Mac Inbox", "default_inbox"),
                Triple("shortcut_ui_bug", "🐛 发给 AI 修 Bug", "ui_bug")
            )

            val shortcuts = targets.mapIndexed { idx, (id, label, tag) ->
                val intent = Intent(context, ShareReceiverActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    putExtra("oms_intent_tag", tag)
                }
                ShortcutInfo.Builder(context, id)
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                    .setCategories(setOf(CATEGORY_DIRECT_SHARE))
                    .setIntent(intent)
                    .setLongLived(true)
                    .setRank(idx)
                    .build()
            }
            try {
                sm.dynamicShortcuts = shortcuts
            } catch (_: Throwable) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingShare(intent)
        finish()
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent == null) return
        val client = OmsApplication.instance?.transportClient ?: return

        val shortcutId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID) ?: ""
        val intentTag = when {
            shortcutId.contains("pkb_vault") -> "pkb_vault"
            shortcutId.contains("ui_bug") -> "ui_bug"
            else -> intent.getStringExtra("oms_intent_tag") ?: "default_inbox"
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT)

        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                client.submitItemToMacOrQueue(
                    intentTag = intentTag,
                    itemType = if (streamUri != null) "FILE" else "TEXT",
                    title = sharedSubject ?: "手机系统分享 ($intentTag)",
                    textContent = sharedText,
                    voiceTranscript = null,
                    fileUri = streamUri
                )
                Toast.makeText(
                    this,
                    "✅ 已投递至 Mac ($intentTag)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
                for ((idx, u) in uris.withIndex()) {
                    client.submitItemToMacOrQueue(
                        intentTag = intentTag,
                        itemType = "MEDIA",
                        title = "批量分享 (${idx + 1}/${uris.size})",
                        textContent = if (idx == 0) sharedText else null,
                        voiceTranscript = null,
                        fileUri = u
                    )
                }
                Toast.makeText(
                    this,
                    "✅ 已批量投递 ${uris.size} 个文件至 Mac",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
