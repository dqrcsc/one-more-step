package com.oms.core.proxy

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

val SUPPORTED_SS_CIPHERS = setOf(
    "2022-blake3-aes-128-gcm",
    "2022-blake3-aes-256-gcm",
    "2022-blake3-chacha20-poly1305",
    "aes-128-gcm",
    "aes-256-gcm",
    "chacha20-ietf-poly1305"
)

data class ProxyNode(
    val name: String,
    val protocol: String, // "ss" | "trojan" | "socks5" | "http"
    val server: String,
    val port: Int,
    val cipher: String = "",
    val password: String = "",
    val username: String = "",
    val plugin: String? = null,
    val pluginOpts: Map<String, String> = emptyMap(),
    val sni: String = "",
    val skipCertVerify: Boolean = false,
    val udp: Boolean = false,
    val source: String = "",
    @Volatile var latencyMs: Double = Double.POSITIVE_INFINITY,
    @Volatile var healthy: Boolean = true,
    @Volatile var failCount: Int = 0,
    @Volatile var cooldownUntilMs: Long = 0L
) {
    /**
     * Redacted string representation: NEVER exposes server hostname/IP or password in logs.
     */
    override fun toString(): String {
        val lat = if (latencyMs.isFinite()) "${latencyMs.toLong()}ms" else "untested"
        val plg = if (!plugin.isNullOrBlank()) "+$plugin(${pluginOpts["mode"] ?: ""})" else ""
        val src = if (source.isNotBlank()) " src=$source" else ""
        return "<ProxyNode name='$name' proto=$protocol$plg latency=$lat healthy=$healthy$src>"
    }
}

data class SubscriptionSourceStatus(
    val source: String,
    val ok: Boolean,
    val nodeCount: Int = 0,
    val error: String = ""
)

data class SubscriptionBundle(
    val nodes: List<ProxyNode>,
    val rules: List<String> = emptyList(),
    val clockOffsetSec: Int = 0,
    val userinfo: String = "",
    val sources: List<SubscriptionSourceStatus> = emptyList()
)

object SubscriptionParser {

    fun decodeBase64Loose(raw: String): ByteArray {
        val cleaned = raw.trim().replace("\r", "").replace("\n", "").replace(" ", "")
        if (cleaned.isEmpty()) return ByteArray(0)
        val std = cleaned.replace('-', '+').replace('_', '/')
        val padded = when (std.length % 4) {
            2 -> "$std=="
            3 -> "$std="
            else -> std
        }
        return Base64.getDecoder().decode(padded)
    }

    fun redactSubscriptionError(msg: String): String {
        return Regex("""https?://[^\s'"<>]+""", RegexOption.IGNORE_CASE)
            .replace(msg, "<REDACTED_SUB_URL>")
            .trim()
    }

    fun extractSubconverterInnerUrls(url: String): List<String> {
        return try {
            val uri = URI(url)
            val rawQuery = uri.rawQuery ?: return emptyList()
            val params = parseQueryParams(rawQuery)
            val hasTargetOrSub = params.containsKey("target") || (uri.path ?: "").endsWith("/sub")
            val urlValues = params["url"] ?: return emptyList()
            if (!hasTargetOrSub) return emptyList()
            urlValues.flatMap { it.split('|') }
                .map { it.trim() }
                .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parseQueryParams(rawQuery: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (pair in rawQuery.split('&')) {
            if (pair.isBlank()) continue
            val idx = pair.indexOf('=')
            val k = if (idx >= 0) urlDecodeSafe(pair.substring(0, idx)) else urlDecodeSafe(pair)
            val v = if (idx >= 0) urlDecodeSafe(pair.substring(idx + 1)) else ""
            out.getOrPut(k) { mutableListOf() }.add(v)
        }
        return out
    }

    private fun urlDecodeSafe(s: String): String {
        return try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (_: Throwable) {
            s
        }
    }

    /**
     * Splits a Clash YAML inline flow object `{k1: v1, k2: "v,2"}` respecting quotes.
     */
    private fun splitFlowMapEntries(inner: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < inner.length) {
            val ch = inner[i]
            if (quote != null) {
                if (ch == '\\' && i + 1 < inner.length) {
                    sb.append(inner[i + 1])
                    i += 2
                    continue
                }
                if (ch == quote) {
                    quote = null
                } else {
                    sb.append(ch)
                }
            } else {
                when (ch) {
                    '\'', '"' -> quote = ch
                    ',' -> {
                        parts.add(sb.toString())
                        sb.setLength(0)
                    }
                    else -> sb.append(ch)
                }
            }
            i++
        }
        if (sb.isNotEmpty()) parts.add(sb.toString())
        return parts
    }

    fun parseClashYaml(text: String): Pair<List<Map<String, Any>>, List<String>> {
        val proxies = mutableListOf<Map<String, Any>>()
        val rules = mutableListOf<String>()
        var section = ""
        var current: MutableMap<String, Any>? = null
        var inPluginOpts = false
        var pluginOptsIndent = 0

        for (rawLine in text.lineSequence()) {
            val stripped = rawLine.trim()
            if (stripped.isEmpty() || stripped.startsWith("#")) continue
            val indent = rawLine.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }

            if (indent == 0 && stripped.endsWith(":")) {
                current?.let { proxies.add(it); current = null }
                section = stripped.dropLast(1).trim()
                inPluginOpts = false
                continue
            }

            if (section == "proxies") {
                if (stripped.startsWith("- {") && stripped.endsWith("}")) {
                    current?.let { proxies.add(it); current = null }
                    val inner = stripped.substring(3, stripped.length - 1)
                    val item = LinkedHashMap<String, Any>()
                    for (entry in splitFlowMapEntries(inner)) {
                        val colon = entry.indexOf(':')
                        if (colon > 0) {
                            val k = entry.substring(0, colon).trim().trim('\'', '"')
                            val v = entry.substring(colon + 1).trim().trim('\'', '"')
                            item[k] = coerceYamlScalar(v)
                        }
                    }
                    proxies.add(item)
                } else if (stripped.startsWith("- ")) {
                    current?.let { proxies.add(it) }
                    val next = LinkedHashMap<String, Any>()
                    current = next
                    inPluginOpts = false
                    val rest = stripped.substring(2).trim()
                    val colon = rest.indexOf(':')
                    if (colon > 0) {
                        val k = rest.substring(0, colon).trim().trim('\'', '"')
                        val v = rest.substring(colon + 1).trim().trim('\'', '"')
                        next[k] = coerceYamlScalar(v)
                    }
                } else if (current != null && stripped.contains(':')) {
                    val colon = stripped.indexOf(':')
                    val k = stripped.substring(0, colon).trim().trim('\'', '"')
                    val v = stripped.substring(colon + 1).trim().trim('\'', '"')
                    if (k == "plugin-opts" && v.isEmpty()) {
                        current!!["plugin-opts"] = LinkedHashMap<String, String>()
                        inPluginOpts = true
                        pluginOptsIndent = indent
                    } else if (inPluginOpts && indent > pluginOptsIndent) {
                        @Suppress("UNCHECKED_CAST")
                        val opts = current!!.getOrPut("plugin-opts") { LinkedHashMap<String, String>() } as MutableMap<String, String>
                        opts[k] = v
                    } else {
                        inPluginOpts = false
                        if (k == "plugin-opts" && v.startsWith("{") && v.endsWith("}")) {
                            val opts = LinkedHashMap<String, String>()
                            for (e in splitFlowMapEntries(v.substring(1, v.length - 1))) {
                                val c2 = e.indexOf(':')
                                if (c2 > 0) {
                                    opts[e.substring(0, c2).trim().trim('\'', '"')] =
                                        e.substring(c2 + 1).trim().trim('\'', '"')
                                }
                            }
                            current!!["plugin-opts"] = opts
                        } else {
                            current!![k] = coerceYamlScalar(v)
                        }
                    }
                }
            } else if (section == "rules" && stripped.startsWith("- ")) {
                rules.add(stripped.substring(2).trim().trim('\'', '"'))
            }
        }
        current?.let { proxies.add(it) }
        return proxies to rules
    }

    private fun coerceYamlScalar(v: String): Any {
        if (v.equals("true", ignoreCase = true)) return true
        if (v.equals("false", ignoreCase = true)) return false
        v.toIntOrNull()?.let { return it }
        return v
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseClashProxyDict(d: Map<String, Any>, source: String): ProxyNode? {
        val ptype = (d["type"]?.toString() ?: "").lowercase().trim()
        val name = d["name"]?.toString() ?: "unnamed"
        val server = (d["server"]?.toString() ?: "").trim()
        val port = (d["port"]?.toString()?.toIntOrNull()) ?: 0
        if (server.isEmpty() || port !in 1..65535) return null

        return when (ptype) {
            "ss" -> {
                val cipher = (d["cipher"]?.toString() ?: "").lowercase().trim()
                if (cipher !in SUPPORTED_SS_CIPHERS) return null
                var plugin = d["plugin"]?.toString()?.trim()
                if (plugin == "obfs-local" || plugin == "simple-obfs") plugin = "obfs"
                if (!plugin.isNullOrEmpty() && plugin != "obfs") return null
                val rawOpts = (d["plugin-opts"] as? Map<*, *>)
                    ?.entries
                    ?.associate { it.key.toString() to it.value.toString() }
                    ?: emptyMap()
                ProxyNode(
                    name = name,
                    protocol = "ss",
                    server = server,
                    port = port,
                    cipher = cipher,
                    password = d["password"]?.toString() ?: "",
                    plugin = plugin?.ifEmpty { null },
                    pluginOpts = rawOpts,
                    udp = d["udp"] == true,
                    source = source
                )
            }
            "trojan" -> {
                val net = d["network"]?.toString()?.lowercase()?.trim().orEmpty()
                if (net.isNotEmpty() && net != "tcp") return null
                val sni = (d["sni"] ?: d["servername"])?.toString()?.takeIf { it.isNotBlank() } ?: server
                ProxyNode(
                    name = name,
                    protocol = "trojan",
                    server = server,
                    port = port,
                    password = d["password"]?.toString() ?: "",
                    sni = sni,
                    skipCertVerify = d["skip-cert-verify"] == true,
                    udp = d["udp"] == true,
                    source = source
                )
            }
            "socks5", "http" -> {
                if (d["tls"] == true) return null
                ProxyNode(
                    name = name,
                    protocol = ptype,
                    server = server,
                    port = port,
                    username = d["username"]?.toString() ?: "",
                    password = d["password"]?.toString() ?: "",
                    source = source
                )
            }
            else -> null
        }
    }

    fun parseUriLine(rawUri: String, source: String = ""): ProxyNode? {
        val uri = rawUri.trim()
        val schemeIdx = uri.indexOf("://")
        if (schemeIdx <= 0) return null
        val scheme = uri.substring(0, schemeIdx).lowercase()

        if (scheme == "ss") {
            var body = uri.substring(5)
            var name = "ss-node"
            val fragIdx = body.indexOf('#')
            if (fragIdx >= 0) {
                name = urlDecodeSafe(body.substring(fragIdx + 1)).ifBlank { name }
                body = body.substring(0, fragIdx)
            }
            var queryStr = ""
            val qIdx = body.indexOf('?')
            if (qIdx >= 0) {
                queryStr = body.substring(qIdx + 1)
                body = body.substring(0, qIdx)
            }
            body = body.trimEnd('/')

            val userinfoPart: String
            val hostPort: String
            val atIdx = body.lastIndexOf('@')
            if (atIdx >= 0) {
                val rawUser = urlDecodeSafe(body.substring(0, atIdx))
                userinfoPart = if (':' in rawUser) {
                    rawUser
                } else {
                    runCatching { String(decodeBase64Loose(rawUser), Charsets.UTF_8) }.getOrDefault(rawUser)
                }
                hostPort = body.substring(atIdx + 1)
            } else {
                val decoded = runCatching { String(decodeBase64Loose(body), Charsets.UTF_8) }.getOrNull() ?: return null
                val decAt = decoded.lastIndexOf('@')
                if (decAt < 0) return null
                userinfoPart = decoded.substring(0, decAt)
                hostPort = decoded.substring(decAt + 1)
            }

            val colonUser = userinfoPart.indexOf(':')
            val colonHost = hostPort.lastIndexOf(':')
            if (colonUser <= 0 || colonHost <= 0) return null
            val cipher = userinfoPart.substring(0, colonUser).lowercase().trim()
            if (cipher !in SUPPORTED_SS_CIPHERS) return null
            val password = userinfoPart.substring(colonUser + 1)
            val server = hostPort.substring(0, colonHost).removeSurrounding("[", "]")
            val port = hostPort.substring(colonHost + 1).toIntOrNull() ?: return null
            if (server.isEmpty() || port !in 1..65535) return null

            var plugin: String? = null
            val pluginOpts = LinkedHashMap<String, String>()
            if (queryStr.isNotEmpty()) {
                val qs = parseQueryParams(queryStr)
                val rawPlg = qs["plugin"]?.firstOrNull()
                if (!rawPlg.isNullOrBlank()) {
                    val parts = urlDecodeSafe(rawPlg).split(';')
                    val plgName = parts[0].trim()
                    if (plgName in setOf("obfs-local", "simple-obfs", "obfs")) {
                        plugin = "obfs"
                        for (opt in parts.drop(1)) {
                            val eq = opt.indexOf('=')
                            if (eq > 0) {
                                val ok = opt.substring(0, eq).trim()
                                val ov = opt.substring(eq + 1).trim()
                                when (ok) {
                                    "obfs", "mode" -> pluginOpts["mode"] = ov
                                    "obfs-host", "host" -> pluginOpts["host"] = ov
                                    "obfs-uri", "path" -> pluginOpts["path"] = ov
                                }
                            }
                        }
                    } else {
                        return null
                    }
                }
            }

            return ProxyNode(
                name = name,
                protocol = "ss",
                server = server,
                port = port,
                cipher = cipher,
                password = password,
                plugin = plugin,
                pluginOpts = pluginOpts,
                source = source
            )
        }

        if (scheme == "trojan" || scheme == "socks5" || scheme == "socks5h" || scheme == "http") {
            return try {
                val parsed = URI(uri)
                val host = parsed.host?.removeSurrounding("[", "]") ?: return null
                val port = parsed.port
                if (port !in 1..65535) return null
                val qs = parseQueryParams(parsed.rawQuery.orEmpty())
                val fragName = parsed.rawFragment?.let { urlDecodeSafe(it) }.orEmpty()

                if (scheme == "trojan") {
                    val tType = qs["type"]?.firstOrNull().orEmpty()
                    if (tType.isNotEmpty() && tType != "tcp") return null
                    val sni = qs["sni"]?.firstOrNull() ?: qs["peer"]?.firstOrNull() ?: host
                    val insecure = qs["allowInsecure"]?.firstOrNull() in setOf("1", "true", "True")
                    val pwd = parsed.rawUserInfo?.let { urlDecodeSafe(it) }.orEmpty()
                    ProxyNode(
                        name = fragName.ifBlank { "trojan-node" },
                        protocol = "trojan",
                        server = host,
                        port = port,
                        password = pwd,
                        sni = sni,
                        skipCertVerify = insecure,
                        source = source
                    )
                } else {
                    val proto = if (scheme.startsWith("socks5")) "socks5" else "http"
                    val rawUser = parsed.rawUserInfo.orEmpty()
                    val cIdx = rawUser.indexOf(':')
                    val u = if (cIdx >= 0) urlDecodeSafe(rawUser.substring(0, cIdx)) else urlDecodeSafe(rawUser)
                    val p = if (cIdx >= 0) urlDecodeSafe(rawUser.substring(cIdx + 1)) else ""
                    ProxyNode(
                        name = fragName.ifBlank { "$proto-node" },
                        protocol = proto,
                        server = host,
                        port = port,
                        username = u,
                        password = p,
                        source = source
                    )
                }
            } catch (_: Throwable) {
                null
            }
        }

        return null
    }

    fun parseSubscriptionText(
        text: String,
        clockOffsetSec: Int = 0,
        userinfo: String = "",
        source: String = ""
    ): SubscriptionBundle {
        val stripped = text.trim().removePrefix("\uFEFF")
        if (stripped.isEmpty()) return SubscriptionBundle(emptyList())

        if ("proxies:" in stripped) {
            val (rawProxies, rules) = parseClashYaml(stripped)
            val nodes = rawProxies.mapNotNull { parseClashProxyDict(it, source) }
            return SubscriptionBundle(
                nodes = nodes,
                rules = rules,
                clockOffsetSec = clockOffsetSec,
                userinfo = userinfo
            )
        }

        val decodedText = if (!stripped.take(64).contains("://")) {
            runCatching { String(decodeBase64Loose(stripped), Charsets.UTF_8) }.getOrDefault(stripped)
        } else {
            stripped
        }
        val nodes = decodedText.lineSequence()
            .mapNotNull { parseUriLine(it, source) }
            .toList()
        return SubscriptionBundle(
            nodes = nodes,
            clockOffsetSec = clockOffsetSec,
            userinfo = userinfo
        )
    }
}
