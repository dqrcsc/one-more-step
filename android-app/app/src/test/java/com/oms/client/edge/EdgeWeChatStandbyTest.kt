package com.oms.client.edge

import org.junit.Assert.*
import org.junit.Test

class EdgeWeChatStandbyTest {

    @Test
    fun standbyLeasePolicyPrevents5gSplitBrainAndSavesOvernightBattery() {
        // 1. When Mac is connected -> strictly MAC_CONNECTED_IDLE
        assertEquals(
            StandbyLeaseState.MAC_CONNECTED_IDLE,
            StandbyLeasePolicy.evaluate(
                standbyFeatureEnabled = true,
                hasClawBotAuth = true,
                isMacConnected = true,
                macSleepingFlagFromHost = true,
                forceAwayStandbyEnabled = false,
                disconnectedDurationMs = 0L,
                pairedLanSsidHint = "HomeWiFi",
                currentWifiSsid = "HomeWiFi",
                isScreenInteractive = true,
                msSinceScreenOff = 0L
            )
        )

        // 2. User leaves home on 5G (currentWifiSsid = ""), Mac at home did NOT report sleep -> BLOCKED_AWAY_UNCONFIRMED!
        assertEquals(
            StandbyLeaseState.BLOCKED_AWAY_UNCONFIRMED,
            StandbyLeasePolicy.evaluate(
                standbyFeatureEnabled = true,
                hasClawBotAuth = true,
                isMacConnected = false,
                macSleepingFlagFromHost = false,
                forceAwayStandbyEnabled = false,
                disconnectedDurationMs = 120_000L,
                pairedLanSsidHint = "HomeWiFi",
                currentWifiSsid = "",
                isScreenInteractive = true,
                msSinceScreenOff = 0L
            )
        )

        // 3. On HomeWiFi at 3:00 AM, Mac unreachable >= 45s, phone screen OFF for 30 min -> STANDBY_SCREEN_OFF_POWERSAVE (0 polling!)
        assertEquals(
            StandbyLeaseState.STANDBY_SCREEN_OFF_POWERSAVE,
            StandbyLeasePolicy.evaluate(
                standbyFeatureEnabled = true,
                hasClawBotAuth = true,
                isMacConnected = false,
                macSleepingFlagFromHost = false,
                forceAwayStandbyEnabled = false,
                disconnectedDurationMs = 60_000L,
                pairedLanSsidHint = "HomeWiFi",
                currentWifiSsid = "HomeWiFi",
                isScreenInteractive = false,
                msSinceScreenOff = 1_800_000L
            )
        )

        // 4. At 6:30 AM in bed on HomeWiFi, user unlocks phone to read WeChat -> ACTIVE_POLLING!
        assertEquals(
            StandbyLeaseState.ACTIVE_POLLING,
            StandbyLeasePolicy.evaluate(
                standbyFeatureEnabled = true,
                hasClawBotAuth = true,
                isMacConnected = false,
                macSleepingFlagFromHost = false,
                forceAwayStandbyEnabled = false,
                disconnectedDurationMs = 60_000L,
                pairedLanSsidHint = "HomeWiFi",
                currentWifiSsid = "HomeWiFi",
                isScreenInteractive = true,
                msSinceScreenOff = 0L
            )
        )

        // 5. User forwards article and locks screen 30s later -> stays ACTIVE_POLLING inside 120s grace window
        assertEquals(
            StandbyLeaseState.ACTIVE_POLLING,
            StandbyLeasePolicy.evaluate(
                standbyFeatureEnabled = true,
                hasClawBotAuth = true,
                isMacConnected = false,
                macSleepingFlagFromHost = true,
                forceAwayStandbyEnabled = false,
                disconnectedDurationMs = 10_000L,
                pairedLanSsidHint = "HomeWiFi",
                currentWifiSsid = "",
                isScreenInteractive = false,
                msSinceScreenOff = 30_000L
            )
        )

        // 6. User is away on 5G, and Tencent Cloud WAN relay deterministically confirms Mac host is offline -> ACTIVE_POLLING!
        assertEquals(
            StandbyLeaseState.ACTIVE_POLLING,
            StandbyLeasePolicy.evaluate(
                standbyFeatureEnabled = true,
                hasClawBotAuth = true,
                isMacConnected = false,
                macSleepingFlagFromHost = false,
                forceAwayStandbyEnabled = false,
                disconnectedDurationMs = 20_000L,
                pairedLanSsidHint = "HomeWiFi",
                currentWifiSsid = "",
                isScreenInteractive = true,
                msSinceScreenOff = 0L,
                relayConfirmedMacOffline = true
            )
        )
    }

    @Test
    fun weChatHtmlExtractorAndClawBotProtocolUnpackAccurately() {
        val sampleHtml = """
            <html>
            <head>
              <meta property="og:title" content="智能体认知架构演进" />
              <meta property="og:article:author" content="前沿技术笔记" />
              <meta property="og:description" content="深入拆解双脑协同" />
            </head>
            <body>
              <script>var ct = "1763596800";</script>
              <div id="js_content">
                <h2>一、核心命题</h2>
                <p>移动端与桌面端应当共享同一套个人上下文胶囊，避免碎片化思考流失到外部孤岛。</p>
                <p><img data-src="https://mmbiz.qpic.cn/mmbiz_png/abc/640?wx_fmt=png&amp;tp=webp" /></p>
                <p>通过应用内免虚拟网卡隧道与状态机租约，可以零冲突实现全天候及时响应。</p>
              </div>
            </body>
            </html>
        """.trimIndent()

        val article = EdgeWeChatExtractor.parseWeChatHtml(sampleHtml, "https://mp.weixin.qq.com/s/xyz")
        assertEquals("智能体认知架构演进", article.title)
        assertEquals("前沿技术笔记", article.author)
        assertTrue(article.markdownBody.contains("## 一、核心命题"))
        assertTrue(article.markdownBody.contains("![](https://mmbiz.qpic.cn/mmbiz_png/abc/640?wx_fmt=png&tp=webp)"))

        // Verify uint64 lossless quoting & URL filtering
        val rawIlink = """{"ret":0,"msgs":[{"message_id":7439281928374651923,"from_user_id":"u1@im.wechat","message_type":1,"create_time_ms":1763600000123,"context_token":"ctx_abc","item_list":[{"type":1,"text_item":{"text":"看看这篇 https://mp.weixin.qq.com/s/xyz 还有内部图 https://novac2c.cdn.weixin.qq.com/secret"}}]}]}"""
        val quoted = EdgeClawBotProtocol.quoteUint64MessageIds(rawIlink)
        assertTrue(quoted.contains("\"message_id\":\"7439281928374651923\""))
        val urls = EdgeClawBotProtocol.extractPublicUrls(quoted)
        assertEquals(listOf("https://mp.weixin.qq.com/s/xyz"), urls)
    }
}
