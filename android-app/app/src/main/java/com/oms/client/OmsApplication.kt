package com.oms.client

import android.app.Application
import com.oms.client.capability.NotificationExecutor
import com.oms.client.data.StreamRepository
import com.oms.client.share.ShareReceiverActivity
import com.oms.client.transport.OmsTransportClient

class OmsApplication : Application() {

    companion object {
        @Volatile
        var instance: OmsApplication? = null
            private set
    }

    lateinit var repository: StreamRepository
        private set

    lateinit var transportClient: OmsTransportClient
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = StreamRepository(this)
        transportClient = OmsTransportClient(this, repository)
        NotificationExecutor.ensureChannelsAndTts(this)
        ShareReceiverActivity.publishDirectShareShortcuts(this)
        transportClient.startAutoConnect()
    }
}
