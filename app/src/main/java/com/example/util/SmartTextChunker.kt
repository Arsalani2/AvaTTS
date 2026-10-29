package com.example.util

object SmartTextChunker {

    private val SENTENCE_DELIMITERS = charArrayOf('.', '!', '?', '؟', '؛', '\n', ':')
    private val CLAUSE_DELIMITERS = charArrayOf('،', ',', ';', '-', '—')

    /**
     * Splits text into natural semantic chunks close to targetSize characters.
     * Prevents cutting words or sentences abruptly wherever possible.
     */
    fun chunkText(text: String, targetSize: Int): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val chunkSize = targetSize.coerceAtLeast(10)
        if (trimmed.length <= chunkSize) return listOf(trimmed)

        val chunks = mutableListOf<String>()
        var start = 0

        while (start < trimmed.length) {
            val remaining = trimmed.length - start
            if (remaining <= chunkSize) {
                val lastChunk = trimmed.substring(start).trim()
                if (lastChunk.isNotEmpty()) chunks.add(lastChunk)
                break
            }

            val end = (start + chunkSize).coerceAtMost(trimmed.length)

            // 1. Look for a sentence delimiter within the range (down to 40% of chunk)
            val searchWindowStart = start + (chunkSize * 0.4).toInt()
            var splitIndex = -1

            for (i in end - 1 downTo searchWindowStart) {
                // BUG-11: مرز فقط با اعتبارسنجی واقعی پذیرفته می‌شود —
                // نقطهٔ اعشار (3.14)، ساعت (12:30)، URL و ایمیل دیگر جمله را نمی‌شکنند
                if (trimmed[i] in SENTENCE_DELIMITERS && isSentenceBoundary(trimmed, i)) {
                    splitIndex = i + 1
                    break
                }
            }

            // 2. If no sentence delimiter, look for phrase / clause delimiters (،, ,, etc.)
            if (splitIndex == -1) {
                for (i in end - 1 downTo searchWindowStart) {
                    if (trimmed[i] in CLAUSE_DELIMITERS) {
                        splitIndex = i + 1
                        break
                    }
                }
            }

            // 3. If no phrase delimiter, look for space or newline from end down to start + 1 (never cut words!)
            if (splitIndex == -1) {
                for (i in end - 1 downTo start + 1) {
                    if (Character.isWhitespace(trimmed[i])) {
                        splitIndex = i + 1
                        break
                    }
                }
            }

            // 4. Fallback only if a single unbroken word exceeds targetSize: split at end
            if (splitIndex <= start) {
                splitIndex = end
            }

            val chunk = trimmed.substring(start, splitIndex).trim()
            if (chunk.isNotEmpty()) {
                chunks.add(chunk)
            }
            start = splitIndex

            // Skip any leading whitespace for next chunk
            while (start < trimmed.length && Character.isWhitespace(trimmed[start])) {
                start++
            }
        }

        return chunks.filter { it.isNotBlank() }
    }

    /**
     * BUG-11: تشخیص مرز واقعی جمله برای '.'، ':'، '؛' (و علائم پایان همیشه معتبر):
     * - کاراکتر بعدی باید فاصله یا پایان متن باشد (نه 12:30 یا 3.14)
     * - کاراکتر قبلی نباید رقم باشد (اعشار)
     * - توکن جاری نباید URL (شامل //) یا ایمیل (شامل @) باشد
     */
    private fun isSentenceBoundary(text: String, index: Int): Boolean {
        val ch = text[index]
        if (ch == '\n' || ch == '!' || ch == '?' || ch == '؟') return true
        val next = text.getOrNull(index + 1)
        val prev = text.getOrNull(index - 1)
        if (next != null && !Character.isWhitespace(next)) return false
        if (prev != null && prev.isDigit()) return false
        var s = index
        while (s > 0 && !Character.isWhitespace(text[s - 1])) s--
        val token = text.substring(s, index)
        if (token.contains("//") || token.contains('@')) return false
        return true
    }
}
