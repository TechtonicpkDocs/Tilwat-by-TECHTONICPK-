// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

/**
 * Minimal dependency-free JSON parser (pure Kotlin, no org.json — org.json lives in
 * android.jar and would break the standalone kotlinc compile of the pure engine).
 * Handles objects, arrays, strings (with \u escapes incl. surrogate pairs), numbers,
 * true/false/null. Used for ref-v2.json, phrases-v1.json and model manifests.
 */
sealed class JsonValue {
    fun asObject(): JsonObject = this as JsonObject
    fun asArray(): JsonArray = this as JsonArray
    fun asString(): String = (this as JsonString).value
    fun asDouble(): Double = (this as JsonNumber).value
    fun asInt(): Int = (this as JsonNumber).value.toInt()
    fun asLong(): Long = (this as JsonNumber).value.toLong()
    fun asBoolean(): Boolean = (this as JsonBool).value
    fun isNull(): Boolean = this is JsonNull
}

data class JsonObject(val map: LinkedHashMap<String, JsonValue>) : JsonValue() {
    operator fun get(key: String): JsonValue? = map[key]
    fun str(key: String): String = (map[key] as JsonString).value
    fun strOrNull(key: String): String? = (map[key] as? JsonString)?.value
    fun int(key: String): Int = (map[key] as JsonNumber).value.toInt()
    fun long(key: String): Long = (map[key] as JsonNumber).value.toLong()
    fun double(key: String): Double = (map[key] as JsonNumber).value
    fun bool(key: String): Boolean = (map[key] as JsonBool).value
    fun arr(key: String): JsonArray = map[key] as JsonArray
    fun obj(key: String): JsonObject = map[key] as JsonObject
    fun has(key: String): Boolean = map.containsKey(key)
}

data class JsonArray(val list: List<JsonValue>) : JsonValue() {
    val size: Int get() = list.size
    operator fun get(i: Int): JsonValue = list[i]
    fun strings(): List<String> = list.map { (it as JsonString).value }
    fun ints(): List<Int> = list.map { (it as JsonNumber).value.toInt() }
    fun objects(): List<JsonObject> = list.map { it as JsonObject }
}

data class JsonString(val value: String) : JsonValue()
data class JsonNumber(val value: Double, val raw: String) : JsonValue()
data class JsonBool(val value: Boolean) : JsonValue()
object JsonNull : JsonValue()

class JsonParseException(message: String) : Exception(message)

object Json {
    fun parse(text: String): JsonValue {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        if (p.pos != text.length) throw JsonParseException("Trailing content at ${p.pos}")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun ws() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        fun value(): JsonValue {
            ws()
            if (pos >= s.length) throw JsonParseException("Unexpected end of input")
            return when (s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JsonString(str())
                't' -> { expect("true"); JsonBool(true) }
                'f' -> { expect("false"); JsonBool(false) }
                'n' -> { expect("null"); JsonNull }
                else -> num()
            }
        }

        private fun expect(lit: String) {
            if (!s.startsWith(lit, pos)) throw JsonParseException("Expected $lit at $pos")
            pos += lit.length
        }

        private fun obj(): JsonObject {
            pos++ // {
            val map = LinkedHashMap<String, JsonValue>()
            ws()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return JsonObject(map)
            }
            while (true) {
                ws()
                if (pos >= s.length || s[pos] != '"') throw JsonParseException("Expected key at $pos")
                val k = str()
                ws()
                if (pos >= s.length || s[pos] != ':') throw JsonParseException("Expected : at $pos")
                pos++
                map[k] = value()
                ws()
                if (pos >= s.length) throw JsonParseException("Unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonObject(map)
                    }
                    else -> throw JsonParseException("Expected , or } at $pos")
                }
            }
        }

        private fun arr(): JsonArray {
            pos++ // [
            val list = ArrayList<JsonValue>()
            ws()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return JsonArray(list)
            }
            while (true) {
                list.add(value())
                ws()
                if (pos >= s.length) throw JsonParseException("Unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonArray(list)
                    }
                    else -> throw JsonParseException("Expected , or ] at $pos")
                }
            }
        }

        private fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) break
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonParseException("Bad \\u escape at $pos")
                                val hi = s.substring(pos, pos + 4).toInt(16)
                                pos += 4
                                if (hi in 0xD800..0xDBFF && pos + 6 <= s.length && s[pos] == '\\' && s[pos + 1] == 'u') {
                                    val lo = s.substring(pos + 2, pos + 6).toInt(16)
                                    if (lo in 0xDC00..0xDFFF) {
                                        pos += 6
                                        val cp = 0x10000 + ((hi - 0xD800) shl 10) + (lo - 0xDC00)
                                        sb.append(Character.toChars(cp))
                                    } else {
                                        sb.append(hi.toChar())
                                    }
                                } else {
                                    sb.append(hi.toChar())
                                }
                            }
                            else -> throw JsonParseException("Bad escape \\$e at $pos")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw JsonParseException("Unterminated string")
        }

        private fun num(): JsonNumber {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            while (pos < s.length && (s[pos] in '0'..'9' || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) pos++
            val raw = s.substring(start, pos)
            if (raw.isEmpty() || raw == "-") throw JsonParseException("Bad number at $start")
            return JsonNumber(raw.toDouble(), raw)
        }
    }
}
