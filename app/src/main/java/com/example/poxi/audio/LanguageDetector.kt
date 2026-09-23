package com.example.poxi.audio

/**
 * Robust detector for user language and conversational style.
 * Identifies Hindi (Devanagari), English (Latin), Hinglish (conversational Hindi-English blend),
 * and regional Indian languages.
 */
object LanguageDetector {

    enum class LanguageType(val label: String) {
        HINDI("Hindi"),
        ENGLISH("English"),
        HINGLISH("Hinglish"),
        MARATHI("Marathi"),
        BENGALI("Bengali"),
        TAMIL("Tamil"),
        TELUGU("Telugu"),
        GUJARATI("Gujarati"),
        PUNJABI("Punjabi"),
        KANNADA("Kannada"),
        MALAYALAM("Malayalam")
    }

    private val HINGLISH_TOKENS = setOf(
        "kholo", "khol", "karo", "kar", "karta", "karti", "karte",
        "hai", "hain", "hoon", "hun", "ho", "tha", "thi", "thay",
        "aap", "tum", "mera", "meri", "mere", "hamara", "hum",
        "kaise", "kaisa", "kaisi", "batao", "bataiye", "bata",
        "chal", "chalao", "chala", "lagao", "laga", "laga do",
        "baat", "shukriya", "dhanyawad", "theek", "sahi",
        "aur", "ye", "yeh", "woh", "wo", "ko", "par", "se", "mein", "me",
        "kya", "kyun", "kab", "kaha", "kahan", "kisko", "kiska",
        "raha", "rahi", "rahe", "hoga", "hogi", "hoge",
        "bhai", "mummy", "papa", "didi", "dost", "yaar",
        "accha", "achha", "badhiya", "namaste", "pranam"
    )

    fun detect(text: String): LanguageType {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return LanguageType.ENGLISH

        // Check for Devanagari script (Pure Hindi / Marathi)
        val hasDevanagari = trimmed.any { it in '\u0900'..'\u097F' }
        if (hasDevanagari) {
            val lower = trimmed.lowercase()
            if (lower.contains("कसा") || lower.contains("आहे") || lower.contains("नमस्कार")) {
                return LanguageType.MARATHI
            }
            return LanguageType.HINDI
        }

        // Check other Indian scripts
        if (trimmed.any { it in '\u0980'..'\u09FF' }) return LanguageType.BENGALI
        if (trimmed.any { it in '\u0B80'..'\u0BFF' }) return LanguageType.TAMIL
        if (trimmed.any { it in '\u0C00'..'\u0C7F' }) return LanguageType.TELUGU
        if (trimmed.any { it in '\u0A80'..'\u0AFF' }) return LanguageType.GUJARATI
        if (trimmed.any { it in '\u0A00'..'\u0A7F' }) return LanguageType.PUNJABI
        if (trimmed.any { it in '\u0C80'..'\u0CFF' }) return LanguageType.KANNADA
        if (trimmed.any { it in '\u0D00'..'\u0D7F' }) return LanguageType.MALAYALAM

        // Check Latin script for Hinglish tokens
        val lowerWords = trimmed.lowercase()
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }

        val hinglishMatchCount = lowerWords.count { it in HINGLISH_TOKENS }
        if (hinglishMatchCount > 0) {
            return LanguageType.HINGLISH
        }

        // Default to English if Latin script without Hinglish markers
        return LanguageType.ENGLISH
    }
}
