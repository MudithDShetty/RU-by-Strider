package com.strider.quanto

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * BPE Tokenizer for granite-embedding-97m-multilingual-r2.
 * Parses tokenizer.json directly with JsonParser to handle
 * merges stored as either List<String> or List<List<String>>.
 */
class BpeTokenizer(context: Context) {

    private val vocab: Map<String, Int>
    private val merges: Map<Pair<String, String>, Int>
    private val specialTokens: Map<String, Int>

    private val clsTokenId: Int
    private val sepTokenId: Int
    private val unkTokenId: Int

    private val byteEncoder: Map<Int, String>

    init {
        val json = context.assets.open("tokenizer.json").bufferedReader().readText()
        val root = JsonParser.parseString(json).asJsonObject

        // --- vocab ---
        val modelObj = root.getAsJsonObject("model")
        val vocabObj = modelObj.getAsJsonObject("vocab")
        val vocabMutable = mutableMapOf<String, Int>()
        for ((k, v) in vocabObj.entrySet()) vocabMutable[k] = v.asInt
        vocab = vocabMutable

        // --- merges: handle both ["a b", ...] and [["a","b"], ...] formats ---
        val mergesArray = modelObj.getAsJsonArray("merges")
        val mergesMutable = HashMap<Pair<String, String>, Int>()
        for ((rank, elem) in mergesArray.withIndex()) {
            val (a, b) = parseMergeEntry(elem)
            mergesMutable[Pair(a, b)] = rank
        }
        merges = mergesMutable

        // --- special tokens ---
        val addedTokens = root.getAsJsonArray("added_tokens")
        val specials = mutableMapOf<String, Int>()
        for (elem in addedTokens) {
            val obj = elem.asJsonObject
            if (obj.get("special")?.asBoolean == true) {
                specials[obj.get("content").asString] = obj.get("id").asInt
            }
        }
        specialTokens = specials

        // Granite embedding uses <|startoftext|> as CLS and <|return|> as SEP
        // Fall back to their known IDs (179934 / 179938) if not found in added_tokens
        clsTokenId = specials["[CLS]"]  ?: specials["<|startoftext|>"] ?: 179934
        sepTokenId  = specials["[SEP]"]  ?: specials["<|return|>"]      ?: 179938
        unkTokenId  = specials["[UNK]"]  ?: specials["<unk>"]           ?: 0

        byteEncoder = buildByteEncoder()
    }

    private fun parseMergeEntry(elem: JsonElement): Pair<String, String> {
        return when {
            elem.isJsonArray -> {
                val arr = elem.asJsonArray
                Pair(arr[0].asString, arr[1].asString)
            }
            else -> {
                val parts = elem.asString.split(" ", limit = 2)
                Pair(parts[0], parts.getOrElse(1) { "" })
            }
        }
    }

    data class TokenizerOutput(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray
    )

    fun encode(text: String, maxLength: Int = 512): TokenizerOutput {
        val words = preTokenize(text)
        val tokenIds = mutableListOf<Int>()

        for (word in words) {
            if (tokenIds.size >= maxLength - 2) break
            tokenIds.addAll(bpeEncode(word))
        }

        val truncated = tokenIds.take(maxLength - 2)
        val finalIds = listOf(clsTokenId) + truncated + listOf(sepTokenId)
        val seqLen = finalIds.size

        return TokenizerOutput(
            inputIds      = LongArray(seqLen) { finalIds[it].toLong() },
            attentionMask = LongArray(seqLen) { 1L },
            tokenTypeIds  = LongArray(seqLen) { 0L }
        )
    }

    private fun preTokenize(text: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            when {
                ch == ' ' -> {
                    if (sb.isNotEmpty()) { result.add(sb.toString()); sb.clear() }
                    sb.append('Ġ')
                }
                ch.isLetterOrDigit() || ch == '\'' || ch == '-' -> sb.append(ch)
                else -> {
                    if (sb.isNotEmpty()) { result.add(sb.toString()); sb.clear() }
                    result.add(ch.toString())
                }
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result.filter { it.isNotEmpty() && it != "Ġ" }
    }

    private fun bpeEncode(word: String): List<Int> {
        if (word.isEmpty()) return emptyList()
        val symbols = word.map { ch ->
            ch.toString().toByteArray(Charsets.UTF_8)
                .joinToString("") { byte -> byteEncoder[byte.toInt() and 0xFF] ?: "?" }
        }.toMutableList()

        while (symbols.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1
            for (i in 0 until symbols.size - 1) {
                val rank = merges[Pair(symbols[i], symbols[i + 1])] ?: Int.MAX_VALUE
                if (rank < bestRank) { bestRank = rank; bestIdx = i }
            }
            if (bestIdx == -1 || bestRank == Int.MAX_VALUE) break
            symbols[bestIdx] = symbols[bestIdx] + symbols[bestIdx + 1]
            symbols.removeAt(bestIdx + 1)
        }
        return symbols.map { sym -> vocab[sym] ?: unkTokenId }
    }

    private fun buildByteEncoder(): Map<Int, String> {
        val bs = mutableListOf<Int>()
        ('!'.code..'~'.code).forEach { bs.add(it) }
        ('¡'.code..'¬'.code).forEach { bs.add(it) }
        ('®'.code..'ÿ'.code).forEach { bs.add(it) }
        val cs = bs.toMutableList()
        var n = 0
        for (b in 0..255) {
            if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
        }
        return bs.zip(cs).associate { (b, c) -> b to c.toChar().toString() }
    }
}