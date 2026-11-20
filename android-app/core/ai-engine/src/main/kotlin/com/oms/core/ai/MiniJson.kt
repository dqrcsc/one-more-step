package com.oms.core.ai

/**
 * Zero-dependency RFC 8259 JSON parser & serializer for pure JVM/Android modules.
 */
object MiniJson {

    fun stripMarkdownFence(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            val firstNl = s.indexOf('\n')
            if (firstNl >= 0) s = s.substring(firstNl + 1).trim()
            if (s.endsWith("```")) s = s.substring(0, s.length - 3).trim()
        }
        return s
    }

    fun extractJsonObject(raw: String): String {
        val stripped = stripMarkdownFence(raw)
        val firstBrace = stripped.indexOf('{')
        val lastBrace = stripped.lastIndexOf('}')
        return if (firstBrace >= 0 && lastBrace > firstBrace) {
            stripped.substring(firstBrace, lastBrace + 1)
        } else {
            stripped
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(raw: String): Map<String, Any?> {
        val res = parse(stripMarkdownFence(raw))
        return res as? Map<String, Any?>
            ?: throw IllegalArgumentException("Expected JSON object")
    }

    fun parse(raw: String): Any? {
        val p = Parser(raw)
        val v = p.parseValue()
        p.skipWs()
        return v
    }

    fun stringify(value: Any?): String {
        val sb = StringBuilder(256)
        writeValue(sb, value)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Number -> {
                val d = v.toDouble()
                if (!d.isFinite()) sb.append("null") else sb.append(v.toString())
            }
            is String -> writeString(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, elem) in v) {
                    if (k == null) continue
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeValue(sb, elem)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (elem in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(sb, elem)
                }
                sb.append(']')
            }
            is Array<*> -> {
                sb.append('[')
                for (i in v.indices) {
                    if (i > 0) sb.append(',')
                    writeValue(sb, v[i])
                }
                sb.append(']')
            }
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun skipWs() {
            while (pos < src.length && (src[pos] == ' ' || src[pos] == '\n' || src[pos] == '\r' || src[pos] == '\t')) pos++
        }

        fun parseValue(): Any? {
            skipWs()
            if (pos >= src.length) throw IllegalArgumentException("Unexpected end of JSON")
            return when (val c = src[pos]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> parseStr()
                't' -> { expectLiteral("true"); true }
                'f' -> { expectLiteral("false"); false }
                'n' -> { expectLiteral("null"); null }
                '-', in '0'..'9' -> parseNum()
                else -> throw IllegalArgumentException("Unexpected char '$c' at $pos")
            }
        }

        private fun expectLiteral(lit: String) {
            if (!src.startsWith(lit, pos)) throw IllegalArgumentException("Expected '$lit' at $pos")
            pos += lit.length
        }

        private fun parseObj(): Map<String, Any?> {
            pos++
            skipWs()
            val map = LinkedHashMap<String, Any?>()
            if (pos < src.length && src[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (pos >= src.length || src[pos] != '"') throw IllegalArgumentException("Expected key at $pos")
                val key = parseStr()
                skipWs()
                if (pos >= src.length || src[pos] != ':') throw IllegalArgumentException("Expected ':' at $pos")
                pos++
                map[key] = parseValue()
                skipWs()
                if (pos >= src.length) throw IllegalArgumentException("Unterminated object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArr(): List<Any?> {
            pos++
            skipWs()
            val list = ArrayList<Any?>()
            if (pos < src.length && src[pos] == ']') { pos++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (pos >= src.length) throw IllegalArgumentException("Unterminated array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseStr(): String {
            pos++
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    if (pos >= src.length) throw IllegalArgumentException("Unterminated escape")
                    when (val esc = src[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > src.length) throw IllegalArgumentException("Invalid \\u")
                            sb.append(src.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> sb.append(esc)
                    }
                } else sb.append(c)
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseNum(): Number {
            val start = pos
            if (src[pos] == '-') pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
            var isFloat = false
            if (pos < src.length && src[pos] == '.') {
                isFloat = true; pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloat = true; pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            val tok = src.substring(start, pos)
            return if (isFloat) tok.toDouble()
            else tok.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: tok.toDouble()
        }
    }
}
