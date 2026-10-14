package com.perch.cam.util

/**
 * Tiny JSON reader/writer used by the control API.
 *
 * `org.json` is only a stub in JVM unit tests, so the control layer uses this pure-Kotlin
 * implementation instead, which keeps request parsing and response building testable.
 * Supported values: null, Boolean, Number, String, List, Map<String, *>.
 */
object MiniJson {

    private const val MAX_DEPTH = 8

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    /** Parses a JSON object. Returns null when [text] is not a well-formed JSON object. */
    fun parseObject(text: String): Map<String, Any?>? {
        return try {
            val parser = Parser(text)
            parser.skipWhitespace()
            val value = parser.readValue(0)
            parser.skipWhitespace()
            if (!parser.atEnd) return null
            @Suppress("UNCHECKED_CAST")
            value as? Map<String, Any?>
        } catch (_: ParseException) {
            null
        }
    }

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            // Float.toString keeps "33.4" instead of widening to 33.400001525878906.
            is Float -> if (value.isNaN() || value.isInfinite()) out.append("null") else out.append(value.toString())
            is Double -> writeDouble(out, value)
            is CharSequence -> writeString(out, value)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, k.toString())
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    write(out, item)
                }
                out.append(']')
            }
            else -> writeString(out, value.toString())
        }
    }

    private fun writeDouble(out: StringBuilder, value: Double) {
        // JSON has no NaN/Infinity.
        if (value.isNaN() || value.isInfinite()) out.append("null") else out.append(value)
    }

    private fun writeString(out: StringBuilder, s: CharSequence) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    private class ParseException : Exception()

    private class Parser(private val s: String) {
        private var i = 0
        val atEnd: Boolean get() = i >= s.length

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw ParseException()
            skipWhitespace()
            if (atEnd) throw ParseException()
            return when (s[i]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> readNumber()
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseException()
            i += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // {
            skipWhitespace()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw ParseException()
                val key = readString()
                skipWhitespace()
                expect(':')
                map[key] = readValue(depth + 1)
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw ParseException()
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            i++ // [
            skipWhitespace()
            if (peek() == ']') { i++; return list }
            while (true) {
                list.add(readValue(depth + 1))
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw ParseException()
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd) throw ParseException()
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd) throw ParseException()
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseException()
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw ParseException()
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw ParseException()
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val token = s.substring(start, i)
            if (token.isEmpty()) throw ParseException()
            token.toLongOrNull()?.let { return it }
            return token.toDoubleOrNull() ?: throw ParseException()
        }

        private fun peek(): Char = if (atEnd) throw ParseException() else s[i]
        private fun next(): Char = if (atEnd) throw ParseException() else s[i++]
        private fun expect(c: Char) { if (next() != c) throw ParseException() }
    }
}
