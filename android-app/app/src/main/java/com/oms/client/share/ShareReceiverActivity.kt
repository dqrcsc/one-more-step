package com.oms.client.share

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.oms.client.OmsApplication
import com.oms.client.R
import com.oms.client.ui.theme.OmsThemeController
import com.oms.client.ui.theme.OmsThemeRoot

/**
 * Module 03: System Share Sheet Receiver (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`)
 * & Direct Share / App Launcher Shortcut Publisher (`default_inbox` | `pkb_vault` | `ui_bug` + Album/File Quick Send)
 */
class ShareReceiverActivity : ComponentActivity() {

    companion object {
        private const val CATEGORY_DIRECT_SHARE = "com.oms.client.category.DIRECT_SHARE_TARGET"

        fun publishDirectShareShortcuts(context: Context) {
            val sm = context.getSystemService(ShortcutManager::class.java) ?: return
            val directTargets = listOf(
                Triple("shortcut_pkb_vault", "📚 存入个人知识库", "pkb_vault"),
                Triple("shortcut_default_inbox", "🚀 发给 Mac Inbox", "default_inbox"),
                Triple("shortcut_ui_bug", "🐛 发给 AI 修 Bug", "ui_bug")
            )

            val directShortcuts = directTargets.mapIndexed { idx, (id, label, tag) ->
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

            val albumShortcut = ShortcutInfo.Builder(context, "shortcut_pick_album")
                .setShortLabel("🖼️ 发相册图到Mac")
                .setLongLabel("🖼️ 选相册图片发往 Mac")
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(context, QuickSendSheetActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        putExtra(QuickSendSheetActivity.EXTRA_AUTO_OPEN_PICKER, "album")
                    }
                )
                .setRank(3)
                .build()

            val fileShortcut = ShortcutInfo.Builder(context, "shortcut_pick_file")
                .setShortLabel("📎 发文件到Mac")
                .setLongLabel("📎 选 PDF/Word 等文件发往 Mac")
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(context, QuickSendSheetActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        putExtra(QuickSendSheetActivity.EXTRA_AUTO_OPEN_PICKER, "file")
                    }
                )
                .setRank(4)
                .build()

            try {
                val maxCount = sm.maxShortcutCountPerActivity.coerceAtLeast(3)
                sm.dynamicShortcuts = (directShortcuts + listOf(albumShortcut, fileShortcut)).take(maxCount)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        val incoming = intent
        if (incoming == null) {
            finish()
            return
        }

        val appDataDir = applicationInfo?.dataDir
        val extractedUris = extractSharedUris(incoming, appDataDir)
        val sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val sharedSubject = incoming.getStringExtra(Intent.EXTRA_SUBJECT)

        AttachmentResolver.selectionError(this, extractedUris)?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (extractedUris.isEmpty() && sharedText.isBlank()) {
            val hadRawStream = hasAnyRawStreamAttempt(incoming)
            Toast.makeText(
                this,
                if (hadRawStream) "⚠️ 已拦截不受信任的分享附件 URI" else "⚠️ 无有效可分享内容",
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }

        val shortcutId = incoming.getStringExtra(Intent.EXTRA_SHORTCUT_ID).orEmpty()
        val explicitTagExtra = incoming.getStringExtra("oms_intent_tag").orEmpty()
        val isInstantDirectShare = shortcutId.isNotBlank() || explicitTagExtra.isNotBlank()

        if (isInstantDirectShare) {
            val intentTag = if (shortcutId.isNotBlank()) {
                ShareInputPolicy.resolveDirectShareTag(shortcutId)
            } else {
                ShareInputPolicy.resolveVoiceIdeaTag(explicitTagExtra)
            }
            submitSharedPayloadImmediately(
                uris = extractedUris,
                sharedText = sharedText,
                sharedSubject = sharedSubject,
                intentTag = intentTag
            )
            finish()
            return
        }

        // Launched by tapping the main "青简·咫尺" icon in Gallery / WPS / WeChat / File Manager:
        // show translucent Quick-Send bottom sheet so user can 1-tap send OR pick tag / add note.
        OmsThemeController.ensureLoaded(this)
        setContent {
            OmsThemeRoot {
                QuickSendSheetScreen(
                    headerTitle = "发送分享内容至 Mac",
                    initialUris = extractedUris,
                    initialText = sharedText,
                    initialSubject = sharedSubject,
                    initialTag = "default_inbox",
                    showRecentAlbumStrip = false,
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun hasAnyRawStreamAttempt(intent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val single = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (single != null) return true
        @Suppress("DEPRECATION")
        val multi = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (!multi.isNullOrEmpty()) return true
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                if (clip.getItemAt(i)?.uri != null) return true
            }
        }
        return false
    }

    private fun extractSharedUris(intent: Intent, appDataDir: String?): List<Uri> {
        val raw = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { raw.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { raw.add(it) }
            }
        }
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { raw.add(it) }
            }
        }
        return raw.filter { ShareInputPolicy.isSafeExternalStreamUri(it, appDataDir) }
    }

    private fun submitSharedPayloadImmediately(
        uris: List<Uri>,
        sharedText: String,
        sharedSubject: String?,
        intentTag: String
    ) {
        val client = OmsApplication.instance?.transportClient ?: return
        val metas = uris.map { AttachmentResolver.describeUri(this, it) }
        val itemType = AttachmentResolver.inferItemType(metas.map { it.mimeType })
        val title = when {
            !sharedSubject.isNullOrBlank() -> sharedSubject
            metas.size == 1 -> {
                val m = metas.first()
                "${AttachmentResolver.badgeForFile(m.safeFileName, m.mimeType)} ${m.safeFileName}"
            }
            metas.size > 1 -> {
                val first = metas.first()
                "${AttachmentResolver.badgeForFile(first.safeFileName, first.mimeType)} ${first.safeFileName} 等 ${metas.size} 个附件"
            }
            else -> "手机系统分享 (#$intentTag)"
        }
        client.submitItemToMacOrQueue(
            intentTag = intentTag,
            itemType = itemType,
            title = title,
            textContent = sharedText.ifBlank { null },
            voiceTranscript = null,
            fileUri = uris.firstOrNull(),
            additionalFileUris = if (uris.size > 1) uris.drop(1) else emptyList()
        )
        Toast.makeText(
            this,
            if (uris.isNotEmpty()) "正在保存附件，投递状态请查看首页" else "内容已加入发送队列",
            Toast.LENGTH_SHORT
        ).show()
    }
}
