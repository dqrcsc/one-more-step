package com.oms.client.share

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.oms.client.OmsApplication

/**
 * Module 03: Xiaomi HyperOS / MIUI Control Center Quick Settings Tile
 * Single tap -> Launches 1x1 transparent ClipboardCatcherActivity to sync clipboard to Mac AI.
 * Long press -> Opens VoiceIdeaActivity ("我有一个想法").
 */
class OmsQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val tier = OmsApplication.instance?.transportClient?.activeTierFlow?.value ?: "DISCONNECTED"
        tile.state = Tile.STATE_ACTIVE
        tile.label = "青简·发Mac"
        tile.subtitle = when (tier) {
            "LAN_WS" -> "局域网已连"
            "BT_RFCOMM" -> "蓝牙近场已连"
            "WAN_WEBRTC" -> "远程在线"
            else -> "离线暂存"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ClipboardCatcherActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
