package app.autospace.car

/**
 * Minimal JSON reader (r25): a tiny recursive-descent parser for exactly what the AI
 * path needs — the model's flat action object, and Gemini's candidates/content/parts
 * reply shape. It exists because the module carries a no-third-party-dependencies
 * policy and because `org.json` is an Android-framework stub on the JVM unit-test
 * classpath: its constructor throws there, which turned `extractText` into a null
 * under test (the stub exception was swallowed by `runCatching`). This parser is
 * pure Kotlin, runs identically on device and on the JVM, and returns null for any
 * malformed input instead of throwing — callers treat null as "unusable reply".
 */
object JsonMini {

    sealed class Value {
        data class Obj(val map: Map<String, Value>) : Value()
        data class Arr(val list: List<Value>) : Value()
        data class Str(val s: String) : Value()
        data class Num(val raw: String) : Value()
        data class Bool(val b: Boolean) : Value()
        object Null : Value()
    }

    fun parse(text: String): Value? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value() ?: return null
        p.skipWs()
        return if (p.atEnd()) v else null
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun skipWs() {
            while (i < s.length && s[i] in " \t\n\r") i++
        }

        fun value(): Value? {
            skipWs()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()?.let { Value.Str(it) }
                't' -> literal("true", Value.Bool(true))
                'f' -> literal("false", Value.Bool(false))
                'n' -> literal("null", Value.Null)
                '-', in '0'..'9' -> num()
                else -> null
            }
        }

        private fun literal(word: String, v: Value): Value? {
            if (!s.startsWith(word, i)) return null
            i += word.length
            return v
        }

        private fun obj(): Value? {
            i++ // {
            val map = LinkedHashMap<String, Value>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return Value.Obj(map)
            }
            while (true) {
                skipWs()
                val key = str() ?: return null
                skipWs()
                if (i >= s.length || s[i] != ':') return null
                i++
                val v = value() ?: return null
                map[key] = v
                skipWs()
                if (i >= s.length) return null
                when (s[i]) {
                    ',' -> {
                        i++
                        continue
                    }
                    '}' -> {
                        i++
                        return Value.Obj(map)
                    }
                    else -> return null
                }
            }
        }

        private fun arr(): Value? {
            i++ // [
            val list = ArrayList<Value>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return Value.Arr(list)
            }
            while (true) {
                val v = value() ?: return null
                list.add(v)
                skipWs()
                if (i >= s.length) return null
                when (s[i]) {
                    ',' -> {
                        i++
                        continue
                    }
                    ']' -> {
                        i++
                        return Value.Arr(list)
                    }
                    else -> return null
                }
            }
        }

        private fun str(): String? {
            if (i >= s.length || s[i] != '"') return null
            i++
            val out = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (i >= s.length) return null
                        when (val e = s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) return null
                                val hex = s.substring(i, i + 4)
                                val code = hex.toIntOrNull(16) ?: return null
                                out.append(code.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                    }
                    else -> out.append(c)
                }
            }
            return null // unterminated
        }

        private fun num(): Value? {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            if (i < s.length && s[i] == '.') {
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i == start) return null
            return Value.Num(s.substring(start, i))
        }
    }
}

/** Field readers for [JsonMini.Value], as top-level extensions so any file can use them. */
fun JsonMini.Value.objField(name: String): JsonMini.Value? = (this as? JsonMini.Value.Obj)?.map?.get(name)

fun JsonMini.Value.asString(): String? = (this as? JsonMini.Value.Str)?.s

fun JsonMini.Value.asInt(): Int? = (this as? JsonMini.Value.Num)?.raw?.toIntOrNull()

fun JsonMini.Value.asBool(): Boolean? = (this as? JsonMini.Value.Bool)?.b
