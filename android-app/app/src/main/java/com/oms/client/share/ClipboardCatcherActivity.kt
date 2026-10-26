package com.oms.client.share

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.oms.client.OmsApplication
import kotlinx.coroutines.CompletableDeferred

/**
 * Module 03: Transparent 1x1 Window Focus Catcher to bypass Android 10+ background clipboard restriction
 * Reads primaryClip within < 30ms upon gaining window focus and immediately finishes.
 */
class ClipboardCatcherActivity : Activity() {
    companion object {
        @Volatile
        var pendingRpcClipboardRead: CompletableDeferred<String>? = null
    }

    private var captured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !captured) {
            captured = true
            val isRpcOnly = intent?.getBooleanExtra("rpc_only", false) == true
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipText = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: ""
                if (isRpcOnly) {
                    pendingRpcClipboardRead?.complete(clipText)
                    pendingRpcClipboardRead = null
                } else if (clipText.isNotEmpty()) {
                    OmsApplication.instance?.transportClient?.submitItemToMacOrQueue(
                        intentTag = "default_inbox",
                        itemType = "TEXT",
                        title = "手机剪贴板同步",
                        textContent = clipText,
                        voiceTranscript = null,
                        fileUri = null
                    )
                    Toast.makeText(this, "📋 剪贴板已同步至 Mac AI", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Throwable) {
                if (isRpcOnly) {
                    pendingRpcClipboardRead?.complete("")
                    pendingRpcClipboardRead = null
                }
            }
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
