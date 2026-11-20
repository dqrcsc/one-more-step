package com.oms.client.edge

import com.oms.core.ai.EdgeArticleInput
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Domestic direct HTTP extractor for WeChat Official Account (`mp.weixin.qq.com`) articles.
 * Converts `#js_content` HTML cleanly to Markdown with zero external browser dependency.
 */
object EdgeWeChatExtractor {
    private const val WECHAT_UA =
        "Mozilla/5.0 (Linux; Android 14; Xiaomi) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.50 NetType/WIFI Language/zh_CN"

    private val directHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun isWeChatMpUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("https://mp.weixin.qq.com/s") || lower.startsWith("http://mp.weixin.qq.com/s")
    }

    fun normalizeWeChatUrl(rawUrl: String): String {
        return rawUrl.trim().replace("&amp;", "&").substringBefore('#')
    }

    fun extractArticleBlocking(
        rawUrl: String,
        fallbackTitle: String = "",
        fallbackAuthor: String = "",
        fallbackDigest: String = ""
    ): EdgeArticleInput {
        val cleanUrl = normalizeWeChatUrl(rawUrl)
        val req = Request.Builder()
            .url(cleanUrl)
            .header("User-Agent", WECHAT_UA)
            .header("Referer", "https://mp.weixin.qq.com/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()

        directHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("公众号抓取 HTTP ${resp.code}")
            }
            val html = resp.body?.string().orEmpty()
            return parseWeChatHtml(html, cleanUrl, fallbackTitle, fallbackAuthor, fallbackDigest)
        }
    }

    fun parseWeChatHtml(
        html: String,
        url: String,
        fallbackTitle: String = "",
        fallbackAuthor: String = "",
        fallbackDigest: String = ""
    ): EdgeArticleInput {
        val title = matchFirst(
            html,
            Regex("""property=["']og:title["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""var\s+msg_title\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""id=["']activity-name["'][^>]*>([\s\S]*?)</h\d>""", RegexOption.IGNORE_CASE)
        ).let { decodeHtmlEntities(stripTags(it)).trim() }.ifBlank { fallbackTitle.ifBlank { "微信公众号文章" } }

        val author = matchFirst(
            html,
            Regex("""property=["']og:article:author["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""var\s+nickname\s*=\s*htmlDecode\(['"]([^'"]+)['"]\)"""),
            Regex("""id=["']js_name["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        ).let { decodeHtmlEntities(stripTags(it)).trim() }.ifBlank { fallbackAuthor }

        val digest = matchFirst(
            html,
            Regex("""property=["']og:description["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""var\s+msg_desc\s*=\s*htmlDecode\(['"]([^'"]+)['"]\)""")
        ).let { decodeHtmlEntities(stripTags(it)).trim() }.ifBlank { fallbackDigest }

        val publishDate = extractPublishDate(html)
        val contentHtml = extractJsContentInnerHtml(html)
        val markdownBody = htmlToCleanMarkdown(contentHtml.ifBlank { html })

        if (markdownBody.trim().length < 60) {
            throw IllegalStateException("公众号正文为空或链接需验证/已失效")
        }

        return EdgeArticleInput(
            url = url,
            title = title,
            author = author,
            publishDate = publishDate,
            digest = digest,
            markdownBody = markdownBody,
            extractionQualityStatus = "complete"
        )
    }

    private fun extractPublishDate(html: String): String {
        val ctSec = Regex("""(?:var\s+ct|create_time)\s*=\s*["']?(\d{10})["']?""")
            .find(html)?.groupValues?.getOrNull(1)?.toLongOrNull()
        if (ctSec != null && ctSec > 1_200_000_000L) {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            }
            return fmt.format(Date(ctSec * 1000L))
        }
        return Regex("""\b(20\d{2}-\d{2}-\d{2})\b""").find(html)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun extractJsContentInnerHtml(html: String): String {
        val idMatch = Regex("""<div[^>]*\bid=["']js_content["'][^>]*>""", RegexOption.IGNORE_CASE).find(html)
            ?: return ""
        val start = idMatch.range.last + 1
        var depth = 1
        val divTagRe = Regex("""<(/?)div\b[^>]*>""", RegexOption.IGNORE_CASE)
        var cursor = start
        while (cursor < html.length) {
            val m = divTagRe.find(html, cursor) ?: break
            if (m.groupValues[1] == "/") {
                depth--
                if (depth == 0) {
                    return html.substring(start, m.range.first)
                }
            } else {
                depth++
            }
            cursor = m.range.last + 1
        }
        return html.substring(start)
    }

    fun htmlToCleanMarkdown(rawHtml: String): String {
        var s = rawHtml
            .replace(Regex("""<(script|style|noscript|svg)\b[\s\S]*?</\1>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<!--[\s\S]*?-->"""), "")

        // Preserve WeChat lazy-loaded images (`data-src` or `src`)
        s = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).replace(s) { m ->
            val tag = m.value
            val imgUrl = Regex("""data-src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
                ?: Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
            if (!imgUrl.isNullOrBlank() && imgUrl.startsWith("http")) "\n\n![](${imgUrl.replace("&amp;", "&")})\n\n" else ""
        }

        // Headings, paragraphs, line breaks, lists, blockquotes, bold
        s = Regex("""<h1[^>]*>([\s\S]*?)</h1>""", RegexOption.IGNORE_CASE).replace(s) { "\n\n# ${stripTags(it.groupValues[1]).trim()}\n\n" }
        s = Regex("""<h2[^>]*>([\s\S]*?)</h2>""", RegexOption.IGNORE_CASE).replace(s) { "\n\n## ${stripTags(it.groupValues[1]).trim()}\n\n" }
        s = Regex("""<h3[^>]*>([\s\S]*?)</h3>""", RegexOption.IGNORE_CASE).replace(s) { "\n\n### ${stripTags(it.groupValues[1]).trim()}\n\n" }
        s = Regex("""<li[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE).replace(s) { "\n- ${stripTags(it.groupValues[1]).trim()}" }
        s = Regex("""<blockquote[^>]*>([\s\S]*?)</blockquote>""", RegexOption.IGNORE_CASE).replace(s) { "\n\n> ${stripTags(it.groupValues[1]).trim()}\n\n" }
        s = Regex("""<(strong|b)\b[^>]*>([\s\S]*?)</\1>""", RegexOption.IGNORE_CASE).replace(s) {
            val inner = stripTags(it.groupValues[2]).trim()
            if (inner.isNotEmpty()) "**$inner**" else ""
        }
        s = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("""</(p|section|div|tr|pre|ul|ol)>""", RegexOption.IGNORE_CASE).replace(s, "\n\n")
        s = stripTags(s)
        s = decodeHtmlEntities(s)

        return s.lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun matchFirst(html: String, vararg regexes: Regex): String {
        for (re in regexes) {
            val v = re.find(html)?.groupValues?.getOrNull(1)?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        return ""
    }

    private fun stripTags(s: String): String = s.replace(Regex("""<[^>]+>"""), "")

    private fun decodeHtmlEntities(s: String): String {
        return s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
    }
}
