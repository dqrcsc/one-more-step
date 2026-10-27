package com.oms.client.transport

import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Instant

/** Fixed fields only: never pass an exception message, URL, identity or application payload. */
class ConnectionDiagnostics(private val file: File?, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Event(val summary: String, val advice: String) {
        STARTED("正在连接 Mac", "正在尝试保存的配对；历史日志不代表当前连接状态。"),
        NOT_PAIRED("尚未配对", "请使用 Mac 本地生成的完整 v2 配对链接。"),
        OLD_PAIRING("配对版本需要升级", "两端更新后，使用 Mac 本地完整 v2 链接重新配对一次。"),
        WIFI_AVAILABLE("Wi-Fi 已连接，正在重连", "连接 Wi-Fi 不代表设备之间一定可以互访。"),
        WIFI_UNAVAILABLE("未检测到 Wi-Fi", "请连接与 Mac 可互通的 Wi-Fi；离线内容继续保留。"),
        CONNECTING("正在连接 Mac", "正在发现已绑定的 Mac 并建立安全连接。"),
        MAC_UNREACHABLE("暂时无法访问 Mac", "检查 Mac 服务与两端网络；可用手机浏览器访问 Mac 安装页做对照，超时不等于配对失效。"),
        CONNECTION_TIMEOUT("连接 Mac 超时", "检查局域网互通和 Mac 服务；不能仅据超时判断路由器隔离。"),
        CONNECTION_FAILED("无法建立到 Mac 的连接", "检查 Mac 服务、局域网互通和防火墙；此分类不能单独确定故障原因。"),
        CONNECTION_LOST("与 Mac 的连接已断开", "正在自动重连；若持续失败，检查网络互通并复制诊断。"),
        HANDSHAKE_STARTED("正在建立安全连接", "已建立传输连接，等待双方完成认证。"),
        HANDSHAKE_TIMEOUT("安全握手超时", "检查网络稳定性和两端版本；不要仅因超时清除配对。"),
        SECURITY_REJECTED("安全校验未通过", "检查两端版本并复制诊断；此错误不证明密钥丢失，也不会回退明文。"),
        CONNECTED("加密连接已恢复", "无需重新配对。")
    }
    private data class Entry(val time: Long, val event: Event, val tier: String, val attempt: Long) {
        fun line() = "$time\t${event.name}\t$tier\t$attempt"
    }
    private val entries = ArrayDeque<Entry>()
    private var current = Event.STARTED
    private var lastFailure: Event? = null
    var persistenceAvailable = true
        private set

    init {
        runCatching {
            if (file != null && file.exists() && file.length() <= 65536) {
                file.readLines().takeLast(200).forEach { line ->
                    val fields = line.split('\t')
                    if (fields.size == 4) runCatching {
                        val tier = fields[2]
                        require(tier in setOf("LAN_WS", "BT_RFCOMM", "WAN_WEBRTC", "SYSTEM"))
                        entries.addLast(Entry(fields[0].toLong(), Event.valueOf(fields[1]), tier, fields[3].toLong()))
                    }
                }
            }
        }.onFailure { persistenceAvailable = false }
        record(Event.STARTED, "SYSTEM")
    }

    @Synchronized fun record(event: Event, tier: String = "LAN_WS", attempt: Long = 0, affectState: Boolean = true) {
        require(tier in setOf("LAN_WS", "BT_RFCOMM", "WAN_WEBRTC", "SYSTEM"))
        if (affectState && (tier == "LAN_WS" || tier == "SYSTEM")) {
            current = event
            when (event) {
                Event.CONNECTED, Event.NOT_PAIRED, Event.OLD_PAIRING -> lastFailure = null
                Event.WIFI_AVAILABLE -> if (lastFailure == Event.WIFI_UNAVAILABLE) lastFailure = null
                Event.WIFI_UNAVAILABLE, Event.MAC_UNREACHABLE, Event.CONNECTION_TIMEOUT,
                Event.CONNECTION_FAILED, Event.CONNECTION_LOST, Event.HANDSHAKE_TIMEOUT,
                Event.SECURITY_REJECTED -> lastFailure = event
                else -> Unit
            }
        }
        val now = clock()
        val last = entries.lastOrNull()
        if (last != null && last.event == event && last.tier == tier && now - last.time in 0..29_999) return
        entries.addLast(Entry(now, event, tier, attempt))
        while (entries.size > 200) entries.removeFirst()
        runCatching { file?.writeText(entries.joinToString("\n") { it.line() } + "\n") }
            .onFailure { persistenceAvailable = false }
    }

    @Synchronized fun reason(): Event = when (current) {
        Event.CONNECTING, Event.HANDSHAKE_STARTED, Event.WIFI_AVAILABLE -> lastFailure ?: current
        else -> current
    }
    @Synchronized fun offlineSummary() = reason().summary
    @Synchronized fun exportText(): String = buildString {
        append("局域网诊断：${reason().summary}\n${reason().advice}\n")
        append("以下为历史事件（最多 200 条；UTC 时间）。仅含连接元数据，不含消息、密钥或配对链接。\n")
        if (!persistenceAvailable) append("日志文件不可用：本次记录仅在内存中，重启后可能丢失。\n")
        entries.forEach { append("${Instant.ofEpochMilli(it.time)} ${it.tier} #${it.attempt} ${it.event.name}\n") }
    }

    companion object {
        fun networkFailure(error: Throwable): Event = when (error) {
            is SocketTimeoutException -> Event.CONNECTION_TIMEOUT
            is ConnectException -> Event.CONNECTION_FAILED
            else -> Event.CONNECTION_LOST
        }
    }
}
