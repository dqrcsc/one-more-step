package com.oms.core.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.net.InetAddress
import kotlin.math.min
import kotlin.math.pow

const val DEFAULT_PREFER_REGIONS = "新加坡|日本|美国|台湾"
const val LEGACY_DEFAULT_PREFER_REGIONS = "美国|新加坡"
const val DEFAULT_MAX_PREFER_LATENCY_MS = 4000.0
const val DEFAULT_PROBE_HOST = "www.gstatic.com"
const val DEFAULT_PROBE_PORT = 80
const val DEFAULT_PROBE_PATH = "/generate_204"

private val RESTRICTED_AI_REGION_REGEX = Regex(
    """(?:香港|Hong\s*Kong|🇭🇰|(?<![A-Za-z])HK(?![A-Za-z])|(?<![A-Za-z])HKG(?![A-Za-z])|澳门|澳門|Macau|🇲🇴|俄罗斯|Russia|🇷🇺)""",
    RegexOption.IGNORE_CASE
)

private val REGION_ALIAS_PATTERNS = mapOf(
    "us" to """(?:美国|United\s*States|America|🇺🇸|(?<![A-Za-z])US(?![A-Za-z])|(?<![A-Za-z])USA(?![A-Za-z])|洛杉矶|硅谷|圣何塞|西雅图|芝加哥|纽约|达拉斯|凤凰城|波特兰)""",
    "美国" to """(?:美国|United\s*States|America|🇺🇸|(?<![A-Za-z])US(?![A-Za-z])|(?<![A-Za-z])USA(?![A-Za-z])|洛杉矶|硅谷|圣何塞|西雅图|芝加哥|纽约|达拉斯|凤凰城|波特兰)""",
    "sg" to """(?:新加坡|狮城|Singapore|🇸🇬|(?<![A-Za-z])SG(?![A-Za-z])|(?<![A-Za-z])SGP(?![A-Za-z]))""",
    "新加坡" to """(?:新加坡|狮城|Singapore|🇸🇬|(?<![A-Za-z])SG(?![A-Za-z])|(?<![A-Za-z])SGP(?![A-Za-z]))""",
    "jp" to """(?:日本|Japan|🇯🇵|(?<![A-Za-z])JP(?![A-Za-z])|(?<![A-Za-z])JPN(?![A-Za-z])|东京|大阪)""",
    "日本" to """(?:日本|Japan|🇯🇵|(?<![A-Za-z])JP(?![A-Za-z])|(?<![A-Za-z])JPN(?![A-Za-z])|东京|大阪)""",
    "hk" to """(?:香港|Hong\s*Kong|🇭🇰|(?<![A-Za-z])HK(?![A-Za-z])|(?<![A-Za-z])HKG(?![A-Za-z]))""",
    "香港" to """(?:香港|Hong\s*Kong|🇭🇰|(?<![A-Za-z])HK(?![A-Za-z])|(?<![A-Za-z])HKG(?![A-Za-z]))""",
    "tw" to """(?:台湾|臺灣|台北|Taiwan|🇹🇼|(?<![A-Za-z])TW(?![A-Za-z])|(?<![A-Za-z])TWN(?![A-Za-z]))""",
    "台湾" to """(?:台湾|臺灣|台北|Taiwan|🇹🇼|(?<![A-Za-z])TW(?![A-Za-z])|(?<![A-Za-z])TWN(?![A-Za-z]))""",
    "kr" to """(?:韩国|韓國|首尔|Korea|🇰🇷|(?<![A-Za-z])KR(?![A-Za-z])|(?<![A-Za-z])KOR(?![A-Za-z]))""",
    "韩国" to """(?:韩国|韓國|首尔|Korea|🇰🇷|(?<![A-Za-z])KR(?![A-Za-z])|(?<![A-Za-z])KOR(?![A-Za-z]))""",
    "uk" to """(?:英国|英國|伦敦|United\s*Kingdom|Britain|🇬🇧|(?<![A-Za-z])UK(?![A-Za-z])|(?<![A-Za-z])GB(?![A-Za-z]))""",
    "英国" to """(?:英国|英國|伦敦|United\s*Kingdom|Britain|🇬🇧|(?<![A-Za-z])UK(?![A-Za-z])|(?<![A-Za-z])GB(?![A-Za-z]))""",
    "de" to """(?:德国|德國|法兰克福|Germany|🇩🇪|(?<![A-Za-z])DE(?![A-Za-z])|(?<![A-Za-z])DEU(?![A-Za-z]))""",
    "德国" to """(?:德国|德國|法兰克福|Germany|🇩🇪|(?<![A-Za-z])DE(?![A-Za-z])|(?<![A-Za-z])DEU(?![A-Za-z]))"""
)

data class RegionTier(val label: String, val regex: Regex)

fun compileRegionTiers(preferRegions: String?): List<RegionTier> {
    if (preferRegions.isNullOrBlank() || preferRegions.trim().equals("NONE", ignoreCase = true)) {
        return emptyList()
    }
    val out = mutableListOf<RegionTier>()
    for (tierExpr in preferRegions.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
        val tokens = tierExpr.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) continue
        val subPatterns = tokens.map { tok ->
            val alias = REGION_ALIAS_PATTERNS[tok.lowercase()]
            when {
                alias != null -> alias
                tok.length <= 3 && tok.all { it.isLetter() && it.code < 128 } ->
                    """(?<![A-Za-z])${Regex.escape(tok)}(?![A-Za-z])"""
                else -> Regex.escape(tok)
            }
        }
        val combined = subPatterns.joinToString("|") { "(?:$it)" }
        out.add(RegionTier(tierExpr, Regex(combined, RegexOption.IGNORE_CASE)))
    }
    return out
}

class RuleMatcher(rawRules: List<String> = emptyList()) {
    private val directExact = HashSet<String>()
    private val proxyExact = HashSet<String>()
    private val directSuffixes = mutableListOf(
        ".cn",
        ".weixin.qq.com",
        ".qq.com",
        ".wechat.com",
        ".qpic.cn",
        ".myqcloud.com"
    )
    private val proxySuffixes = mutableListOf(
        ".openai.com",
        ".googleapis.com",
        ".google.com",
        ".gstatic.com",
        ".openrouter.ai",
        ".anthropic.com"
    )
    private val directKeywords = mutableListOf<String>()
    private val proxyKeywords = mutableListOf<String>()
    private val directCidrs = mutableListOf<CidrBlock>()

    private data class CidrBlock(val networkBytes: ByteArray, val prefixBits: Int) {
        fun contains(addrBytes: ByteArray): Boolean {
            if (addrBytes.size != networkBytes.size) return false
            val fullBytes = prefixBits / 8
            val remBits = prefixBits % 8
            for (i in 0 until fullBytes) {
                if (addrBytes[i] != networkBytes[i]) return false
            }
            if (remBits > 0) {
                val mask = (0xFF shl (8 - remBits)) and 0xFF
                if ((addrBytes[fullBytes].toInt() and mask) != (networkBytes[fullBytes].toInt() and mask)) {
                    return false
                }
            }
            return true
        }
    }

    init {
        directExact.addAll(listOf("weixin.qq.com", "ilinkai.weixin.qq.com", "mp.weixin.qq.com", "qq.com"))
        proxyExact.addAll(listOf("openai.com", "api.openai.com", "generativelanguage.googleapis.com"))

        for (rule in rawRules) {
            val parts = rule.split(',').map { it.trim() }
            if (parts.size < 3) continue
            val rType = parts[0].uppercase()
            val value = parts[1].lowercase()
            val action = parts[2].uppercase()
            val isDirect = action == "DIRECT" || action.contains("直连")
            val isProxy = !isDirect && action != "REJECT"

            when (rType) {
                "DOMAIN" -> if (isDirect) directExact.add(value) else if (isProxy) proxyExact.add(value)
                "DOMAIN-SUFFIX" -> {
                    val sfx = if (value.startsWith(".")) value else ".$value"
                    val bare = value.trimStart('.')
                    if (isDirect) {
                        directSuffixes.add(sfx)
                        directExact.add(bare)
                    } else if (isProxy) {
                        proxySuffixes.add(sfx)
                        directExact.remove(bare)
                    }
                }
                "DOMAIN-KEYWORD" -> {
                    if (isDirect) directKeywords.add(value)
                    else if (isProxy) proxyKeywords.add(value)
                }
                "IP-CIDR", "IP-CIDR6" -> if (isDirect) {
                    parseCidr(value)?.let { directCidrs.add(it) }
                }
            }
        }
    }

    private fun parseCidr(cidr: String): CidrBlock? {
        val slash = cidr.indexOf('/')
        if (slash <= 0) return null
        return try {
            val ipPart = cidr.substring(0, slash)
            val prefix = cidr.substring(slash + 1).toInt()
            val bytes = InetAddress.getByName(ipPart).address
            if (prefix !in 0..(bytes.size * 8)) return null
            CidrBlock(bytes, prefix)
        } catch (_: Throwable) {
            null
        }
    }

    fun isPrivateOrLoopback(host: String): Boolean {
        val clean = host.trim().removeSurrounding("[", "]").lowercase()
        if (clean == "localhost" || clean == "ip6-localhost" ||
            clean.endsWith(".local") || clean.endsWith(".lan") || clean.endsWith(".internal")
        ) {
            return true
        }
        if (!PoisonFreeDnsResolver.isIpLiteral(clean)) return false
        return try {
            val addr = InetAddress.getByName(clean)
            if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress) {
                return true
            }
            val b = addr.address
            // RFC 6598 Carrier-Grade NAT / Tailscale 100.64.0.0/10
            if (b.size == 4 && (b[0].toInt() and 0xFF) == 100 && ((b[1].toInt() and 0xC0) == 64)) {
                return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    fun shouldDirect(host: String): Boolean {
        val clean = host.trim().removeSurrounding("[", "]").lowercase()
        if (isPrivateOrLoopback(clean)) return true
        if (PoisonFreeDnsResolver.isIpLiteral(clean)) {
            return try {
                val bytes = InetAddress.getByName(clean).address
                directCidrs.any { it.contains(bytes) }
            } catch (_: Throwable) {
                false
            }
        }
        if (clean in directExact) return true
        if (clean in proxyExact) return false
        if (proxySuffixes.any { clean.endsWith(it) }) return false
        if (directSuffixes.any { clean.endsWith(it) }) return true
        if (proxyKeywords.any { clean.contains(it) }) return false
        if (directKeywords.any { clean.contains(it) }) return true
        return false
    }
}

data class ProxyNodeStatusView(
    val key: String,
    val name: String,
    val protocol: String,
    val regionBadge: String,
    val latencyMs: Long?,
    val healthy: Boolean,
    val isActive: Boolean,
    val isPinned: Boolean,
    val aiGeoBlocked: Boolean = false
)

data class RoutedTunnelResult(
    val tunnel: ProxyTunnel,
    val initialServerChunk: ByteArray,
    val node: ProxyNode?,
    val targetHost: String
)

class SmartRegionRouter(
    initialNodes: List<ProxyNode>,
    rules: List<String> = emptyList(),
    @Volatile var clockOffsetSec: Int = 0,
    @Volatile var routing: String = "rule", // "proxy" | "rule" | "manual" | "direct"
    preferRegions: String? = DEFAULT_PREFER_REGIONS,
    val maxPreferredLatencyMs: Double = DEFAULT_MAX_PREFER_LATENCY_MS,
    val connectTimeoutMs: Int = 6000,
    val maxFailoverAttempts: Int = 4,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private var nodes: List<ProxyNode> = initialNodes.toList()
    private var matcher: RuleMatcher = RuleMatcher(rules)
    private val lastUsedNodeByHost = java.util.concurrent.ConcurrentHashMap<String, ProxyNode>()
    @Volatile
    var pinnedNodeKey: String? = null
    @Volatile
    var regionTiers: List<RegionTier> = compileRegionTiers(preferRegions)

    companion object {
        fun nodeKey(node: ProxyNode): String = "${node.name}@${node.server}:${node.port}"

        fun isRestrictedAiRegionName(name: String): Boolean =
            RESTRICTED_AI_REGION_REGEX.containsMatchIn(name)

        fun detectRegionBadge(name: String): String {
            for ((label, pat) in listOf(
                "🇺🇸 美国" to REGION_ALIAS_PATTERNS["us"]!!,
                "🇸🇬 新加坡" to REGION_ALIAS_PATTERNS["sg"]!!,
                "🇯🇵 日本" to REGION_ALIAS_PATTERNS["jp"]!!,
                "🇭🇰 香港" to REGION_ALIAS_PATTERNS["hk"]!!,
                "🇹🇼 台湾" to REGION_ALIAS_PATTERNS["tw"]!!,
                "🇰🇷 韩国" to REGION_ALIAS_PATTERNS["kr"]!!,
                "🇬🇧 英国" to REGION_ALIAS_PATTERNS["uk"]!!,
                "🇩🇪 德国" to REGION_ALIAS_PATTERNS["de"]!!
            )) {
                if (Regex(pat, RegexOption.IGNORE_CASE).containsMatchIn(name)) return label
            }
            return "🌐 其他"
        }
    }

    fun updatePreferRegions(preferRegions: String?) {
        regionTiers = compileRegionTiers(preferRegions)
    }

    fun setRoutingPolicy(mode: String, pinnedKey: String? = pinnedNodeKey) {
        routing = mode.lowercase()
        pinnedNodeKey = pinnedKey?.takeIf { it.isNotBlank() }
    }

    fun markAiGeoBlocked(node: ProxyNode) {
        node.aiGeoBlocked = true
    }

    fun lastUsedNodeForHost(host: String): ProxyNode? =
        lastUsedNodeByHost[host.trim().lowercase()]

    fun updateNodes(newNodes: List<ProxyNode>, newRules: List<String> = emptyList(), newClockOffsetSec: Int = 0) {
        synchronized(lock) {
            val prevByKey = nodes.associateBy { nodeKey(it) }
            nodes = newNodes.map { incoming ->
                val prev = prevByKey[nodeKey(incoming)]
                if (prev != null) {
                    if (prev.aiGeoBlocked) incoming.aiGeoBlocked = true
                    if (!incoming.latencyMs.isFinite() && prev.latencyMs.isFinite()) {
                        incoming.latencyMs = prev.latencyMs
                        incoming.healthy = prev.healthy
                    }
                }
                incoming
            }
            if (newRules.isNotEmpty()) {
                matcher = RuleMatcher(newRules)
            }
            if (newClockOffsetSec != 0) {
                clockOffsetSec = newClockOffsetSec
            }
        }
    }

    fun allNodesSnapshot(): List<ProxyNode> = synchronized(lock) { nodes.toList() }

    fun matchRegionTier(node: ProxyNode): Int? {
        val tiers = regionTiers
        for (idx in tiers.indices) {
            if (tiers[idx].regex.containsMatchIn(node.name)) {
                return idx
            }
        }
        return null
    }

    private fun priorityComparator(): Comparator<ProxyNode> = Comparator { a, b ->
        val ka = priorityKey(a)
        val kb = priorityKey(b)
        var cmp = ka.group.compareTo(kb.group)
        if (cmp != 0) return@Comparator cmp
        cmp = ka.tierIdx.compareTo(kb.tierIdx)
        if (cmp != 0) return@Comparator cmp
        cmp = ka.failCount.compareTo(kb.failCount)
        if (cmp != 0) return@Comparator cmp
        ka.latencyMs.compareTo(kb.latencyMs)
    }

    private data class SortKey(val group: Int, val tierIdx: Int, val failCount: Int, val latencyMs: Double)

    private fun priorityKey(node: ProxyNode): SortKey {
        if (!node.healthy) {
            return SortKey(4, 999, node.failCount, node.latencyMs)
        }
        if (node.aiGeoBlocked) {
            return SortKey(3, 999, node.failCount, node.latencyMs)
        }
        val tIdx = matchRegionTier(node)
        val isUntestedOrWithinBudget = !node.latencyMs.isFinite() || node.latencyMs <= maxPreferredLatencyMs
        if (tIdx != null && node.failCount == 0 && isUntestedOrWithinBudget) {
            return SortKey(0, tIdx, 0, node.latencyMs)
        }
        if (!node.latencyMs.isFinite() && isRestrictedAiRegionName(node.name)) {
            return SortKey(2, 0, node.failCount, node.latencyMs)
        }
        return SortKey(1, 0, node.failCount, node.latencyMs)
    }

    fun orderedCandidates(): List<ProxyNode> {
        val currentNodes = allNodesSnapshot()
        if (currentNodes.isEmpty()) return emptyList()
        val pinnedKey = pinnedNodeKey
        if (routing.equals("manual", ignoreCase = true) && !pinnedKey.isNullOrBlank()) {
            val pinned = currentNodes.firstOrNull { nodeKey(it) == pinnedKey || it.name == pinnedKey }
            if (pinned != null) {
                val others = currentNodes.filter { it !== pinned }.sortedWith(priorityComparator())
                return listOf(pinned) + others
            }
        }
        val now = nowMsProvider()
        val ready = currentNodes.filter { it.cooldownUntilMs <= now }.ifEmpty { currentNodes }
        return ready.sortedWith(priorityComparator())
    }

    val bestNode: ProxyNode?
        get() = if (routing.equals("direct", ignoreCase = true)) null else orderedCandidates().firstOrNull()

    fun describeSelection(): String {
        if (routing.equals("direct", ignoreCase = true)) return "🔗 仅直连模式（已停用代理）"
        val best = bestNode ?: return "无可用节点"
        val lat = if (best.latencyMs.isFinite()) "${best.latencyMs.toLong()}ms" else "未测速"
        val geoTag = if (best.aiGeoBlocked) " · 🚫AI锁区" else ""
        if (routing.equals("manual", ignoreCase = true) && !pinnedNodeKey.isNullOrBlank()) {
            return "📌 手动锁定 -> ${best.name} ($lat$geoTag)"
        }
        val tiers = regionTiers
        if (tiers.isEmpty()) return "全局最快 -> ${best.name} ($lat$geoTag)"
        val tIdx = matchRegionTier(best)
        if (tIdx != null && best.healthy && !best.aiGeoBlocked && best.failCount == 0 &&
            (!best.latencyMs.isFinite() || best.latencyMs <= maxPreferredLatencyMs)
        ) {
            return "优先地区 [${tiers[tIdx].label}] -> ${best.name} ($lat)"
        }
        return "降级优选 -> ${best.name} ($lat$geoTag)"
    }

    fun getNodesStatusViews(): List<ProxyNodeStatusView> {
        val active = bestNode
        val pinKey = pinnedNodeKey
        val sorted = allNodesSnapshot().sortedWith(priorityComparator())
        return sorted.map { n ->
            val key = nodeKey(n)
            ProxyNodeStatusView(
                key = key,
                name = n.name,
                protocol = if (n.cipher.startsWith("2022-")) "SS-2022" else n.protocol.uppercase(),
                regionBadge = detectRegionBadge(n.name),
                latencyMs = if (n.latencyMs.isFinite()) n.latencyMs.toLong() else null,
                healthy = n.healthy,
                isActive = active != null && nodeKey(active) == key,
                isPinned = pinKey != null && (pinKey == key || pinKey == n.name),
                aiGeoBlocked = n.aiGeoBlocked
            )
        }
    }

    fun markSuccess(node: ProxyNode, sampleMs: Double? = null) {
        node.healthy = true
        node.failCount = 0
        node.cooldownUntilMs = 0L
        if (sampleMs != null && sampleMs.isFinite()) {
            node.latencyMs = if (!node.latencyMs.isFinite()) {
                sampleMs
            } else {
                node.latencyMs * 0.7 + sampleMs * 0.3
            }
        }
    }

    fun markFailure(node: ProxyNode) {
        val nextFails = node.failCount + 1
        node.failCount = nextFails
        val cooldownSec = min(60.0, 5.0 * 2.0.pow(min(nextFails - 1, 4).toDouble()))
        node.cooldownUntilMs = nowMsProvider() + (cooldownSec * 1000.0).toLong()
        if (nextFails >= 2) {
            node.healthy = false
        }
    }

    fun shouldUseDirect(targetHost: String): Boolean {
        val mode = routing.lowercase()
        if (mode == "direct") return true
        val m = synchronized(lock) { matcher }
        if (m.isPrivateOrLoopback(targetHost)) return true
        if (mode == "rule" || mode == "manual") return m.shouldDirect(targetHost)
        return false
    }

    fun probeSingleNodeBlocking(node: ProxyNode, timeoutMs: Int = 4500): Double {
        val probeReq = (
            "GET $DEFAULT_PROBE_PATH HTTP/1.1\r\n" +
                "Host: $DEFAULT_PROBE_HOST\r\n" +
                "User-Agent: curl/8.0.1\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val t0 = System.nanoTime()
        var tunnel: ProxyTunnel? = null
        return try {
            tunnel = ProxyTunnelConnector.openNodeTunnel(
                node = node,
                targetHost = DEFAULT_PROBE_HOST,
                targetPort = DEFAULT_PROBE_PORT,
                initialPayload = probeReq,
                clockOffsetSec = clockOffsetSec,
                connectTimeoutMs = timeoutMs,
                readTimeoutMs = timeoutMs
            )
            val data = tunnel.read()
            val headStr = if (data.isNotEmpty()) String(data, 0, minOf(16, data.size), Charsets.ISO_8859_1) else ""
            if (!headStr.contains("HTTP/1.")) {
                throw EOFException("Invalid 204 probe response")
            }
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            node.latencyMs = ms
            node.healthy = true
            node.failCount = 0
            node.cooldownUntilMs = 0L
            ms
        } catch (_: Throwable) {
            node.healthy = false
            node.latencyMs = Double.POSITIVE_INFINITY
            Double.POSITIVE_INFINITY
        } finally {
            runCatching { tunnel?.close() }
        }
    }

    suspend fun healthCheckAll(concurrency: Int = 16, timeoutMs: Int = 4500): List<ProxyNode> = coroutineScope {
        val current = allNodesSnapshot()
        if (current.isEmpty()) return@coroutineScope emptyList()
        val sem = Semaphore(concurrency.coerceAtLeast(1))
        current.map { node ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    probeSingleNodeBlocking(node, timeoutMs)
                }
            }
        }.awaitAll()
        orderedCandidates()
    }

    fun openRoutedTunnelBlocking(
        targetHost: String,
        targetPort: Int,
        initialPayload: ByteArray = ByteArray(0),
        nodeConnector: (ProxyNode, String, Int, ByteArray, Int, Int) -> ProxyTunnel = { n, h, p, init, clk, tmo ->
            ProxyTunnelConnector.openNodeTunnel(n, h, p, init, clk, tmo)
        }
    ): Pair<ProxyTunnel, ByteArray> {
        val res = openRoutedTunnelWithNodeBlocking(targetHost, targetPort, initialPayload, nodeConnector)
        return res.tunnel to res.initialServerChunk
    }

    fun openRoutedTunnelWithNodeBlocking(
        targetHost: String,
        targetPort: Int,
        initialPayload: ByteArray = ByteArray(0),
        nodeConnector: (ProxyNode, String, Int, ByteArray, Int, Int) -> ProxyTunnel = { n, h, p, init, clk, tmo ->
            ProxyTunnelConnector.openNodeTunnel(n, h, p, init, clk, tmo)
        }
    ): RoutedTunnelResult {
        val cleanHost = targetHost.trim().lowercase()
        if (shouldUseDirect(targetHost)) {
            val direct = ProxyTunnelConnector.openDirectTunnel(
                targetHost = targetHost,
                targetPort = targetPort,
                initialPayload = initialPayload,
                connectTimeoutMs = connectTimeoutMs
            )
            return RoutedTunnelResult(direct, ByteArray(0), null, cleanHost)
        }

        val candidates = orderedCandidates()
        if (candidates.isEmpty()) {
            throw IllegalStateException("未配置可用的代理节点")
        }
        val attempts = minOf(candidates.size, maxFailoverAttempts.coerceAtLeast(1))
        var lastErr: Throwable? = null

        for (idx in 0 until attempts) {
            val node = candidates[idx]
            val t0 = System.nanoTime()
            var tunnel: ProxyTunnel? = null
            try {
                // Use fast connectTimeoutMs (default 6s) for initial handshake + first upstream ServerHello/HTTP byte,
                // so a proxy node whose outbound link stalls fails over in ~6s instead of blocking for 65s!
                tunnel = nodeConnector(node, targetHost, targetPort, initialPayload, clockOffsetSec, connectTimeoutMs)
                val firstChunk = if (initialPayload.isNotEmpty()) {
                    val readBytes = tunnel.read()
                    if (readBytes.isEmpty()) {
                        throw EOFException("Upstream closed before sending response bytes")
                    }
                    readBytes
                } else {
                    ByteArray(0)
                }
                // Once tunnel handshake & first byte succeed, relax read timeout to 660s for SSE / Live WebSocket / Video gen
                tunnel.setReadTimeoutMs(660_000)
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
                markSuccess(node, if (initialPayload.isNotEmpty()) elapsedMs else null)
                lastUsedNodeByHost[cleanHost] = node
                return RoutedTunnelResult(tunnel, firstChunk, node, cleanHost)
            } catch (t: Throwable) {
                lastErr = t
                markFailure(node)
                runCatching { tunnel?.close() }
            }
        }
        throw IllegalStateException(
            "代理连接 $targetHost:$targetPort 失败（已自动尝试 $attempts 个节点）: ${lastErr?.message}",
            lastErr
        )
    }

    suspend fun probeEndpointLatencyAsync(): Pair<ProxyNode?, Long?> = withContext(Dispatchers.IO) {
        val best = bestNode ?: return@withContext null to null
        val ms = probeSingleNodeBlocking(best, 4000)
        if (ms.isFinite()) best to ms.toLong() else best to null
    }
}
