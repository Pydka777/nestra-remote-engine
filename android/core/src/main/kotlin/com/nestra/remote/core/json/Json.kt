package com.nestra.remote.core.json

/**
 * Minimal, strict JSON for the NESTRA Remote client (no third-party dependency in the security core).
 * Values: Map<String, Any?> (object, duplicate keys rejected), List<Any?>, String, Long, Double, Boolean, null.
 * Limits: depth 32, input 64 KiB - the client only ever parses small API answers.
 */
object Json {
    class ParseException(message: String) : Exception(message)

    private const val MAX_DEPTH = 32
    private const val MAX_INPUT = 64 * 1024

    fun parse(text: String): Any? {
        if (text.length > MAX_INPUT) throw ParseException("input too large")
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw ParseException("trailing characters")
        return v
    }

    /** Parses and requires an object. */
    @Suppress("UNCHECKED_CAST")
    fun obj(text: String): Map<String, Any?> = parse(text) as? Map<String, Any?> ?: throw ParseException("not an object")

    fun write(value: Any?): String = StringBuilder().also { w(it, value) }.toString()

    private fun w(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Double -> { require(v.isFinite()); sb.append(v) }
            is Map<*, *> -> {
                sb.append('{'); var first = true
                for ((k, x) in v) { if (!first) sb.append(','); first = false; str(sb, k as String); sb.append(':'); w(sb, x) }
                sb.append('}')
            }
            is List<*> -> { sb.append('['); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); w(sb, x) }; sb.append(']') }
            else -> throw IllegalArgumentException("unsupported JSON value: ${v::class.simpleName}")
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' || c == '\u2028' || c == '\u2029' -> sb.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }
        fun fail(m: String): Nothing = throw ParseException("$m at $i")

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("too deep")
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? { if (!s.startsWith(word, i)) fail("bad literal"); i += word.length; return v }

        fun obj(depth: Int): Map<String, Any?> {
            i++; ws()
            val m = LinkedHashMap<String, Any?>()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws(); if (i >= s.length || s[i] != '"') fail("expected key")
                val k = string(); ws()
                if (i >= s.length || s[i] != ':') fail("expected ':'"); i++; ws()
                if (m.containsKey(k)) fail("duplicate key")
                m[k] = value(depth + 1); ws()
                if (i >= s.length) fail("unterminated object")
                when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> fail("expected ',' or '}'") }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++; ws()
            val l = ArrayList<Any?>()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws(); l.add(value(depth + 1)); ws()
                if (i >= s.length) fail("unterminated array")
                when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> fail("expected ',' or ']'") }
            }
        }

        fun string(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                val h = s.substring(i, i + 4)
                                if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("bad unicode escape")
                                sb.append(h.toInt(16).toChar()); i += 4
                            }
                            else -> fail("bad escape '$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Any {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) fail("bad number")
            if (s[i] == '0') i++ else if (s[i] in '1'..'9') { while (i < s.length && s[i] in '0'..'9') i++ } else fail("bad number")
            var frac = false
            if (i < s.length && s[i] == '.') { frac = true; i++; val d = i; while (i < s.length && s[i] in '0'..'9') i++; if (i == d) fail("bad fraction") }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                frac = true; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val d = i; while (i < s.length && s[i] in '0'..'9') i++; if (i == d) fail("bad exponent")
            }
            val t = s.substring(start, i)
            return if (frac) t.toDouble() else t.toLongOrNull() ?: fail("integer out of range")
        }
    }
}

/** Typed accessors for parsed objects (absent or wrong type -> null). */
fun Map<String, Any?>.str(k: String): String? = this[k] as? String
fun Map<String, Any?>.long(k: String): Long? = when (val v = this[k]) { is Long -> v; is Double -> if (v % 1.0 == 0.0) v.toLong() else null; else -> null }
fun Map<String, Any?>.bool(k: String): Boolean? = this[k] as? Boolean
