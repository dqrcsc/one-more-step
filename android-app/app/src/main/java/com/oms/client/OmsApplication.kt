package com.oms.client

import android.app.Application
import com.oms.client.capability.NotificationExecutor
import com.oms.client.cockpit.ChatMemoryRepository
import com.oms.client.cockpit.CockpitRuntimeCoordinator
import com.oms.client.data.StreamRepository
import com.oms.client.edge.EdgeSecureStore
import com.oms.client.edge.EdgeWeChatStandbyEngine
import com.oms.client.share.ShareReceiverActivity
import com.oms.client.transport.OmsTransportClient
import com.oms.core.ai.DualLlmEngine
import com.oms.core.proxy.OmsProxyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OmsApplication : Application() {

    companion object {
        @Volatile
        var instance: OmsApplication? = null
            private set
    }

    lateinit var repository: StreamRepository
        private set

    lateinit var edgeStore: EdgeSecureStore
        private set

    lateinit var proxyManager: OmsProxyManager
        private set

    lateinit var llmEngine: DualLlmEngine
        private set

    lateinit var wechatStandby: EdgeWeChatStandbyEngine
        private set

    lateinit var transportClient: OmsTransportClient
        private set

    lateinit var chatRepository: ChatMemoryRepository
        private set

    lateinit var cockpitCoordinator: CockpitRuntimeCoordinator
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = StreamRepository(this)
        edgeStore = EdgeSecureStore(this)

        val (bootNodes, subUrls, regionPrefs) = edgeStore.loadProxyBootstrapConfig()
        proxyManager = OmsProxyManager(
            preferRegions = regionPrefs.first,
            maxPreferredLatencyMs = regionPrefs.second
        )
        val (userProxyMode, userPinnedNode) = edgeStore.loadProxyUserPolicy()
        proxyManager.router.setRoutingPolicy(userProxyMode, userPinnedNode)
        if (bootNodes.isNotEmpty() || subUrls.isNotEmpty()) {
            proxyManager.applySyncedConfig(bootNodes, subUrls, refreshSubscriptionsInBackground = true)
        }

        llmEngine = DualLlmEngine(
            httpClientProvider = { proxyManager.getOrCreateOkHttpClient() },
            runtimeConfig = edgeStore.loadAiRuntimeConfig(),
            roleOverrides = edgeStore.loadModelRoleSelections()
        )

        transportClient = OmsTransportClient(this, repository)
        chatRepository = ChatMemoryRepository(this)
        cockpitCoordinator = CockpitRuntimeCoordinator(
            appContext = this,
            chatRepo = chatRepository,
            streamRepo = repository,
            edgeStore = edgeStore,
            proxyManager = proxyManager,
            llmEngine = llmEngine,
            transportClient = transportClient
        )
        cockpitCoordinator.start()
        wechatStandby = EdgeWeChatStandbyEngine(
            context = this,
            store = edgeStore,
            streamRepo = repository,
            llmEngine = llmEngine,
            sendHostRpc = { method, params, timeoutMs ->
                transportClient.callHostRpc(method, params, timeoutMs)
            }
        )

        appScope.launch {
            transportClient.activeTierFlow.collect { tier ->
                wechatStandby.onTransportTierChanged(tier)
            }
        }
        wechatStandby.start()

        NotificationExecutor.ensureChannelsAndTts(this)
        ShareReceiverActivity.publishDirectShareShortcuts(this)
        transportClient.startAutoConnect()
    }

    fun reloadEdgeRuntimeFromStore() {
        val (bootNodes, subUrls, regionPrefs) = edgeStore.loadProxyBootstrapConfig()
        val (userProxyMode, userPinnedNode) = edgeStore.loadProxyUserPolicy()
        proxyManager.router.updatePreferRegions(regionPrefs.first)
        proxyManager.setRoutingPolicyAndEvict(userProxyMode, userPinnedNode)
        if (bootNodes.isNotEmpty() || subUrls.isNotEmpty()) {
            proxyManager.applySyncedConfig(bootNodes, subUrls, refreshSubscriptionsInBackground = true)
        }
        llmEngine.runtimeConfig = edgeStore.loadAiRuntimeConfig()
        llmEngine.roleOverrides = edgeStore.loadModelRoleSelections()
        cockpitCoordinator.refreshProxyUiState()
        wechatStandby.refreshState()
    }
}
