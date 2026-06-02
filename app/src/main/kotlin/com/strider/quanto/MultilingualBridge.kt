package com.strider.quanto

import android.util.Log

private const val TAG = "MultilingualBridge"

/** Keeps Latin (incl. accented), digits, Indic, Arabic, Cyrillic, CJK. */
private val TOKENIZE_CLEAN = Regex(
    "[^a-z0-9\\u00C0-\\u024F\\u0900-\\u097F\\u0600-\\u06FF\\u0750-\\u077F" +
        "\\u0980-\\u0DFF\\u0400-\\u04FF\\u4E00-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF\\s]"
)
private val TOKENIZE_SPLIT = Regex("\\s+")

enum class LanguageHint(val label: String) {
    HINDI("hindi"),
    ENGLISH("english"),
    GERMAN("german"),
    FRENCH("french"),
    SPANISH("spanish"),
    ITALIAN("italian"),
    PORTUGUESE("portuguese"),
    DUTCH("dutch"),
    RUSSIAN("russian"),
    TAMIL("tamil"),
    TELUGU("telugu"),
    BENGALI("bengali"),
    MARATHI("marathi"),
    GUJARATI("gujarati"),
    KANNADA("kannada"),
    MALAYALAM("malayalam"),
    PUNJABI("punjabi"),
    URDU("urdu"),
    ARABIC("arabic"),
    CHINESE("chinese"),
    JAPANESE("japanese"),
    KOREAN("korean")
}

enum class ScriptRange(val displayName: String, val ranges: List<IntRange>) {
    LATIN("latin", listOf(0x0041..0x007A, 0x0061..0x007A, 0x00C0..0x024F)),
    DEVANAGARI("devanagari", listOf(0x0900..0x097F)),
    BENGALI("bengali", listOf(0x0980..0x09FF)),
    GURMUKHI("gurmukhi", listOf(0x0A00..0x0A7F)),
    GUJARATI("gujarati", listOf(0x0A80..0x0AFF)),
    ORIYA("oriya", listOf(0x0B00..0x0B7F)),
    TAMIL("tamil", listOf(0x0B80..0x0BFF)),
    TELUGU("telugu", listOf(0x0C00..0x0C7F)),
    KANNADA("kannada", listOf(0x0C80..0x0CFF)),
    MALAYALAM("malayalam", listOf(0x0D00..0x0D7F)),
    ARABIC("arabic", listOf(0x0600..0x06FF, 0x0750..0x077F)),
    CYRILLIC("cyrillic", listOf(0x0400..0x04FF)),
    CJK("cjk", listOf(0x4E00..0x9FFF, 0x3040..0x30FF, 0xAC00..0xD7AF));

    companion object {
        fun detect(text: String): Set<ScriptRange> {
            if (text.isBlank()) return emptySet()
            val found = mutableSetOf<ScriptRange>()
            for (ch in text) {
                val code = ch.code
                for (script in values()) {
                    if (script.ranges.any { code in it }) found.add(script)
                }
            }
            return found
        }
    }
}

/**
 * Conversational stop words across major languages.
 * Stops "Gib mir meine X" / "Montrez-moi mes X" from polluting scoring.
 */
val MULTILINGUAL_STOP_WORDS: Set<String> = setOf(
    // English
    "find", "show", "get", "search", "look", "fetch", "give", "bring", "tell", "open",
    "load", "pull", "grab", "list", "me", "my", "the", "a", "an", "some", "any", "all",
    "this", "that", "these", "those", "it", "its", "your", "our", "you", "i", "we",
    "please", "can", "could", "would", "should", "will", "want", "need", "help",
    "also", "just", "really", "actually", "maybe", "for", "about", "of", "on", "in",
    "at", "to", "from", "with", "by", "into", "via", "as", "and", "or", "but", "so",
    "if", "when", "where", "info", "information", "details", "detail", "stuff",
    "thing", "things", "file", "files", "document", "documents", "doc", "data", "record",
    // Hindi / Hinglish
    "mera", "meri", "mere", "mujhe", "mujhko", "dhundo", "dikhao", "dedo", "chahiye",
    "karo", "wala", "wali", "wale", "hai", "hain", "tha", "thi", "jaldi", "abhi",
    "yahan", "wahan", "kya", "konsa", "konsi", "batao", "bata", "ko", "ka", "ki", "ke",
    "se", "mein", "me", "par", "pe",
    // German
    "gib", "gibt", "mir", "mich", "meine", "mein", "meiner", "meinem", "meinen",
    "zeig", "zeige", "finde", "finden", "suche", "such", "hol", "hole", "bitte",
    "alle", "alles", "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer",
    "einem", "einen", "und", "oder", "aber", "fuer", "fur", "von", "zu", "zum", "zur",
    "mit", "bei", "nach", "aus", "ist", "sind", "war", "waren", "kann", "koennen",
    "ich", "du", "er", "sie", "es", "wir", "ihr", "mal", "doch", "noch", "schon",
    // French
    "montre", "montrez", "trouve", "trouvez", "cherche", "chercher", "donne", "donnez",
    "moi", "mes", "mon", "ma", "le", "la", "les", "un", "une", "des", "du", "de",
    "dans", "sur", "avec", "pour", "par", "est", "sont", "et", "ou", "je", "tu",
    "il", "elle", "nous", "vous", "ils", "elles", "ce", "cette", "ces", "svp",
    // Spanish
    "busca", "buscar", "encuentra", "muestra", "mostrar", "dame", "dame", "mis",
    "mi", "tu", "su", "los", "las", "el", "la", "un", "una", "unos", "unas",
    "de", "del", "en", "con", "por", "para", "que", "como", "yo", "tu", "el",
    "ella", "nosotros", "ellos", "ellas", "porfavor",
    // Italian
    "trova", "cerca", "mostra", "dammi", "mio", "mia", "miei", "mie", "il", "lo",
    "la", "i", "gli", "le", "un", "uno", "una", "di", "del", "della", "dei",
    "che", "con", "per", "non", "sono", "io", "tu", "lui", "lei", "noi", "voi",
    // Portuguese
    "achar", "ache", "mostre", "meu", "minha", "meus", "minhas", "o", "a", "os",
    "as", "um", "uma", "de", "do", "da", "dos", "das", "em", "com", "por", "para",
    "eu", "voce", "ele", "ela", "nos", "eles", "elas",
    // Dutch
    "zoek", "vind", "toon", "laat", "mijn", "de", "het", "een", "van", "in", "op",
    "met", "voor", "door", "ik", "jij", "hij", "zij", "wij", "jullie",
    // Russian (romanized common)
    "найди", "покажи", "мой", "моя", "мои", "мне", "дай", "и", "в", "на", "с", "по"
)

private val LANGUAGE_HINT_PATTERNS = listOf(
    LanguageHint.HINDI to listOf(
        "in hindi", "hindi mein", "hindi me", "hindi wale", "hindi wala", "hindi wali",
        "hindi notes", "hindi file", "hindi document", "hindi title", "hindi named",
        "devanagari", "hindi language"
    ),
    LanguageHint.ENGLISH to listOf(
        "in english", "english mein", "english me", "english wale", "english wala",
        "english title", "english named"
    ),
    LanguageHint.GERMAN to listOf(
        "in german", "auf deutsch", "deutsch", "german", "deutsche", "deutschen"
    ),
    LanguageHint.FRENCH to listOf("in french", "en francais", "en français", "francais", "français"),
    LanguageHint.SPANISH to listOf("in spanish", "en espanol", "en español", "espanol", "español"),
    LanguageHint.ITALIAN to listOf("in italian", "in italiano", "italiano"),
    LanguageHint.PORTUGUESE to listOf("in portuguese", "em portugues", "em português", "portugues"),
    LanguageHint.TAMIL to listOf("in tamil", "tamil mein", "tamil wale"),
    LanguageHint.TELUGU to listOf("in telugu", "telugu mein", "telugu wale"),
    LanguageHint.BENGALI to listOf("in bengali", "bengali mein", "bangla mein"),
    LanguageHint.MARATHI to listOf("in marathi", "marathi mein"),
    LanguageHint.URDU to listOf("in urdu", "urdu mein"),
    LanguageHint.ARABIC to listOf("in arabic", "arabic mein", "in arabic")
)

/** Colloquial command words mapped to English (voice + typed, many languages). */
private val COLLOQUIAL_TO_ENGLISH = mapOf(
    // Hindi
    "bhejna" to "send", "bhejo" to "send", "bhej" to "send", "bhejdo" to "send",
    "khojo" to "find", "dhundo" to "find", "dhundho" to "find",
    "dikhao" to "show", "dikha" to "show", "dedo" to "give",
    "lao" to "bring", "laao" to "bring", "khol" to "open", "kholo" to "open",
    "mera" to "my", "meri" to "my", "mere" to "my",
    "tasveer" to "photo", "tasvir" to "photo", "foto" to "photo",
    "bajat" to "budget", "bajet" to "budget", "padhai" to "study",
    "paisa" to "money", "kaam" to "work", "ghar" to "home",
    // German
    "geburtstag" to "birthday", "geburtstagfotos" to "birthday photos",
    "geburtstagsfotos" to "birthday photos", "fotos" to "photos", "foto" to "photo",
    "bilder" to "photos", "bild" to "photo", "urlaub" to "vacation", "reise" to "travel",
    "hochzeit" to "wedding", "familie" to "family", "musik" to "music", "lied" to "song",
    "dokument" to "document", "rechnung" to "invoice", "gehalt" to "salary",
    // French
    "anniversaire" to "birthday", "photos" to "photos", "photo" to "photo",
    "vacances" to "vacation", "voyage" to "travel", "mariage" to "wedding",
    "famille" to "family", "musique" to "music", "facture" to "invoice",
    // Spanish
    "cumpleanos" to "birthday", "cumpleaños" to "birthday", "fotos" to "photos",
    "vacaciones" to "vacation", "viaje" to "travel", "boda" to "wedding",
    "familia" to "family", "musica" to "music", "música" to "music",
    // Italian
    "compleanno" to "birthday", "foto" to "photo", "vacanza" to "vacation",
    "matrimonio" to "wedding", "famiglia" to "family", "musica" to "music",
    // Portuguese
    "aniversario" to "birthday", "aniversário" to "birthday", "fotos" to "photos",
    "ferias" to "vacation", "férias" to "vacation", "casamento" to "wedding",
    "familia" to "family", "família" to "family"
)

/** Cross-language + cross-script equivalents for lexical matching. */
private val CONCEPT_ALIASES: Map<String, Set<String>> = buildMap {
    fun alias(vararg forms: String) {
        val all = forms.map { it.lowercase() }.toSet()
        for (form in forms) {
            put(form.lowercase(), all)
        }
    }
    alias("python", "paithon", "paiton", "पाइथन", "पायथन")
    alias("notes", "note", "nots", "notizen", "नोट्स", "नोट")
    alias("birthday", "geburtstag", "geburtstags", "anniversaire", "cumpleanos", "cumpleaños", "compleanno", "aniversario")
    alias("photos", "photo", "fotos", "foto", "bilder", "bild", "picture", "pictures", "tasveer", "tasvir", "फोटो", "तस्वीर")
    alias("geburtstagfotos", "geburtstagsfotos", "birthday photos", "birthday photos")
    alias("budget", "bajat", "bajet", "haushalt", "बजट")
    alias("aadhaar", "aadhar", "adhar", "आधार")
    alias("resume", "cv", "biodata", "lebenslauf", "रिज्यूम")
    alias("invoice", "bill", "rechnung", "facture", "factura", "challan")
    alias("salary", "payslip", "gehalt", "salaire", "salario")
    alias("meeting", "besprechung", "reunion", "reunión")
    alias("report", "bericht", "rapport", "informe")
    alias("vacation", "urlaub", "vacances", "vacaciones", "ferias", "férias", "holiday", "trip")
    alias("family", "familie", "famille", "familia", "família", "parivaar", "parivar")
    alias("wedding", "hochzeit", "mariage", "boda", "matrimonio", "casamento")
    alias("music", "musik", "musique", "música", "sangeet", "song", "lied")
    alias("hindi", "हिंदी", "हिन्दी")
    alias("english", "angrezi", "angreji", "englisch", "अंग्रेजी")
    alias("german", "deutsch", "deutsche")
    alias("document", "dokument", "documento")
    alias("tax", "steuer", "impuesto", "kar", "कर")
    alias("study", "studium", "estudio", "padhai", "पढ़ाई")
}

data class MultilingualForms(
    val languageHint: LanguageHint?,
    val strippedQuery: String,
    val embedQuery: String,
    val coreTokens: List<String>,
    val lexicalTokens: List<String>
)

object MultilingualBridge {

    fun extractLanguageHint(rawQuery: String): Pair<LanguageHint?, String> {
        var remaining = rawQuery.lowercase()
        var hint: LanguageHint? = null
        for ((lang, phrases) in LANGUAGE_HINT_PATTERNS) {
            for (phrase in phrases.sortedByDescending { it.length }) {
                if (remaining.contains(phrase)) {
                    hint = lang
                    remaining = remaining.replace(phrase, " ")
                }
            }
        }
        return hint to remaining.replace(Regex("\\s+"), " ").trim()
    }

    fun buildForms(rawQuery: String): MultilingualForms {
        val (hint, stripped) = extractLanguageHint(rawQuery)
        val colloquial  = normalizeColloquial(stripped)
        val withoutStop = stripStopWords(colloquial)
        val embedQuery  = tokenize(withoutStop).take(10).joinToString(" ")
            .ifBlank { tokenize(stripped).take(10).joinToString(" ") }
        val core        = coreTokens(embedQuery.ifBlank { stripped })
        val expanded    = expandTokens(core.ifEmpty { tokenize(stripped) })

        Log.d(TAG, "hint=$hint embed='$embedQuery' core=$core")

        return MultilingualForms(
            languageHint  = hint,
            strippedQuery = stripped,
            embedQuery    = embedQuery,
            coreTokens    = core,
            lexicalTokens = expanded
        )
    }

    fun normalizeVoiceTranscript(transcript: String): String {
        val forms = buildForms(transcript)
        return forms.embedQuery.ifBlank { transcript.trim() }
    }

    fun normalizeColloquial(text: String): String =
        text.lowercase()
            .split(TOKENIZE_SPLIT)
            .mapNotNull { word ->
                val clean = word.trim('.', ',', '?', '!', '"', '\'')
                if (clean.isBlank()) return@mapNotNull null
                COLLOQUIAL_TO_ENGLISH[clean] ?: clean
            }
            .joinToString(" ")

    fun stripStopWords(text: String): String =
        text.lowercase()
            .split(TOKENIZE_SPLIT)
            .filter { it.length > 1 && it !in MULTILINGUAL_STOP_WORDS }
            .joinToString(" ")

    fun coreTokens(text: String): List<String> =
        tokenize(text).filter { it !in MULTILINGUAL_STOP_WORDS }

    fun coreTokensFromList(tokens: List<String>): List<String> =
        tokens.filter { it !in MULTILINGUAL_STOP_WORDS && it.length > 1 }

    fun expandTokens(tokens: List<String>): List<String> {
        val out = linkedSetOf<String>()
        for (token in tokens) {
            out.add(token)
            CONCEPT_ALIASES[token]?.let { out.addAll(it) }
            for ((key, aliases) in CONCEPT_ALIASES) {
                if (token.length >= 4 && (token.contains(key) || aliases.any { token.contains(it) && it.length >= 4 })) {
                    out.addAll(aliases)
                }
            }
        }
        return out.filter { it.length > 1 }.toList()
    }

    fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(TOKENIZE_CLEAN, " ")
            .split(TOKENIZE_SPLIT)
            .filter { it.length > 1 }

    fun indexKeywordExtras(nameWithoutExt: String, contentSnippet: String?): String {
        val parts = mutableListOf<String>()
        val combined = "$nameWithoutExt ${contentSnippet.orEmpty()}"
        val scripts = ScriptRange.detect(combined)
        val nameTokens = tokenize(nameWithoutExt.replace(Regex("[_\\-.]"), " "))

        for (token in nameTokens) {
            CONCEPT_ALIASES[token]?.let { parts.addAll(it) }
        }
        for (token in nameTokens) {
            expandTokens(listOf(token)).forEach { parts.add(it) }
        }

        if (ScriptRange.DEVANAGARI in scripts) parts.add("hindi devanagari")
        if (ScriptRange.CYRILLIC in scripts) parts.add("russian cyrillic")
        for (script in scripts) {
            if (script != ScriptRange.LATIN) parts.add(script.displayName)
        }
        detectDominantLanguage(combined)?.let { parts.add("$it language") }
        return parts.distinct().joinToString(" ")
    }

    fun detectDominantLanguage(text: String): String? {
        val scripts = ScriptRange.detect(text)
        return when {
            ScriptRange.DEVANAGARI in scripts && ScriptRange.LATIN in scripts -> "mixed"
            ScriptRange.DEVANAGARI in scripts -> "hindi"
            ScriptRange.TAMIL in scripts -> "tamil"
            ScriptRange.TELUGU in scripts -> "telugu"
            ScriptRange.BENGALI in scripts -> "bengali"
            ScriptRange.GUJARATI in scripts -> "gujarati"
            ScriptRange.KANNADA in scripts -> "kannada"
            ScriptRange.MALAYALAM in scripts -> "malayalam"
            ScriptRange.GURMUKHI in scripts -> "punjabi"
            ScriptRange.ARABIC in scripts -> "arabic"
            ScriptRange.CYRILLIC in scripts -> "russian"
            ScriptRange.CJK in scripts -> "cjk"
            ScriptRange.LATIN in scripts -> "latin"
            else -> null
        }
    }

    fun fileHasScript(text: String, hint: LanguageHint): Boolean {
        val scripts = ScriptRange.detect(text)
        val lower = text.lowercase()
        return when (hint) {
            LanguageHint.HINDI, LanguageHint.MARATHI ->
                ScriptRange.DEVANAGARI in scripts || lower.contains("hindi") || lower.contains("devanagari")
            LanguageHint.ENGLISH ->
                ScriptRange.LATIN in scripts && ScriptRange.DEVANAGARI !in scripts
            LanguageHint.GERMAN ->
                lower.contains("german") || lower.contains("deutsch") ||
                    (ScriptRange.LATIN in scripts && detectGermanSignals(lower))
            LanguageHint.FRENCH ->
                lower.contains("french") || lower.contains("francais") || lower.contains("français")
            LanguageHint.SPANISH ->
                lower.contains("spanish") || lower.contains("espanol") || lower.contains("español")
            LanguageHint.ITALIAN -> lower.contains("italian") || lower.contains("italiano")
            LanguageHint.PORTUGUESE -> lower.contains("portuguese") || lower.contains("portugues")
            LanguageHint.DUTCH -> lower.contains("dutch") || lower.contains("nederlands")
            LanguageHint.RUSSIAN -> ScriptRange.CYRILLIC in scripts || lower.contains("russian")
            LanguageHint.TAMIL -> ScriptRange.TAMIL in scripts
            LanguageHint.TELUGU -> ScriptRange.TELUGU in scripts
            LanguageHint.BENGALI -> ScriptRange.BENGALI in scripts
            LanguageHint.GUJARATI -> ScriptRange.GUJARATI in scripts
            LanguageHint.KANNADA -> ScriptRange.KANNADA in scripts
            LanguageHint.MALAYALAM -> ScriptRange.MALAYALAM in scripts
            LanguageHint.PUNJABI -> ScriptRange.GURMUKHI in scripts
            LanguageHint.URDU, LanguageHint.ARABIC -> ScriptRange.ARABIC in scripts
            LanguageHint.CHINESE, LanguageHint.JAPANESE, LanguageHint.KOREAN ->
                ScriptRange.CJK in scripts
        }
    }

    private fun detectGermanSignals(text: String): Boolean =
        Regex("\\b(geburtstag|urlaub|rechnung|dokument|hochzeit|bilder|fotos|deutsch)\\b")
            .containsMatchIn(text)

    /**
     * Language-constraint multiplier — filename script is the strongest signal.
     * "python notes in hindi" must rank Devanagari filenames above English ones.
     */
    fun languageHintMultiplier(
        hint: LanguageHint?,
        fileName: String,
        metadata: String,
        content: String
    ): Float {
        if (hint == null) return 1f

        val nameOnly = fileName
        val metaContent = "$metadata $content"
        val nameMatches  = fileHasScript(nameOnly, hint)
        val bodyMatches  = fileHasScript(metaContent, hint) ||
            metaContent.contains("${hint.label} language", ignoreCase = true)
        val nameLatinOnly = ScriptRange.LATIN in ScriptRange.detect(nameOnly) &&
            !fileHasScript(nameOnly, hint)

        return when {
            nameMatches -> 6f
            bodyMatches && !nameLatinOnly -> 2.5f
            nameLatinOnly || (!nameMatches && !bodyMatches) -> 0.08f
            else -> 0.5f
        }
    }

    /** True when a token strongly matches the filename (used to avoid filler-token penalties). */
    fun hasStrongFilenameHit(nameStem: String, tokens: List<String>): Boolean =
        tokens.any { t ->
            t.length >= 4 && (
                nameStem == t ||
                    nameStem.startsWith("$t ") ||
                    nameStem.endsWith(" $t") ||
                    nameStem.contains(" $t ") ||
                    nameStem.contains(t)
                )
        }
}
