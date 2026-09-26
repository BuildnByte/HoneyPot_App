package com.example.myapplication.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kotlin implementation of the HuggingFace BertTokenizer (WordPiece).
 *
 * Matches the tokenizer config:
 *   do_lower_case = false  (MuRIL is cased)
 *   strip_accents  = false
 *   tokenize_chinese_chars = true
 *
 * Special token IDs (0-indexed line numbers in vocab.txt):
 *   [PAD]  = 0
 *   [UNK]  = 100
 *   [MASK] = 103
 *   [CLS]  = 104
 *   [SEP]  = 105
 */
class BertWordPieceTokenizer(private val context: Context) {

    // word → token ID   (pre-allocated to avoid rehashing the 197k-entry vocab)
    private val vocab = HashMap<String, Int>(220_000)

    companion object {
        private const val TAG = "BertTokenizer"

        const val MAX_SEQ_LEN = 128   // model was trained with 128, ignore tokenizer_config's 512

        const val CLS_ID = 104L   // [CLS]
        const val SEP_ID = 105L   // [SEP]
        const val PAD_ID = 0L     // [PAD]
        const val UNK_ID = 100L   // [UNK]
    }

    data class TokenizerOutput(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray
    )

    /** Must be called once on a background thread before [tokenize]. */
    suspend fun loadVocab() = withContext(Dispatchers.IO) {
        if (vocab.isNotEmpty()) return@withContext
        val t0 = System.currentTimeMillis()
        context.assets.open("vocab.txt").bufferedReader().useLines { lines ->
            lines.forEachIndexed { idx, line -> vocab[line.trim()] = idx }
        }
        Log.d(TAG, "Vocab loaded: ${vocab.size} tokens in ${System.currentTimeMillis() - t0} ms")
    }

    /** Tokenizes [text] and returns padded tensors of length [MAX_SEQ_LEN]. */
    fun tokenize(text: String): TokenizerOutput {
        val tokenIds = ArrayList<Long>(MAX_SEQ_LEN)
        tokenIds.add(CLS_ID)

        for (word in basicTokenize(text)) {
            for (id in wordPieceTokenize(word)) {
                if (tokenIds.size >= MAX_SEQ_LEN - 1) break
                tokenIds.add(id)
            }
            if (tokenIds.size >= MAX_SEQ_LEN - 1) break
        }
        tokenIds.add(SEP_ID)

        val inputIds      = LongArray(MAX_SEQ_LEN) { PAD_ID }
        val attentionMask = LongArray(MAX_SEQ_LEN) { 0L }
        val tokenTypeIds  = LongArray(MAX_SEQ_LEN) { 0L }

        tokenIds.forEachIndexed { i, id ->
            inputIds[i]      = id
            attentionMask[i] = 1L
        }
        return TokenizerOutput(inputIds, attentionMask, tokenTypeIds)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Splits text on whitespace, handling Chinese characters (adds spaces around
     * them per BERT spec) and then punctuation-splits each token.
     */
    private fun basicTokenize(text: String): List<String> {
        val result = ArrayList<String>()
        val buf = StringBuilder()

        for (ch in text) {
            when {
                isChineseChar(ch.code) -> {
                    if (buf.isNotEmpty()) { result.addAll(splitOnPunctuation(buf.toString())); buf.clear() }
                    result.add(ch.toString())
                }
                ch.isWhitespace() -> {
                    if (buf.isNotEmpty()) { result.addAll(splitOnPunctuation(buf.toString())); buf.clear() }
                }
                else -> buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) result.addAll(splitOnPunctuation(buf.toString()))
        return result.filter { it.isNotBlank() }
    }

    private fun splitOnPunctuation(word: String): List<String> {
        val out = ArrayList<String>()
        val buf = StringBuilder()
        for (ch in word) {
            if (isPunctuation(ch)) {
                if (buf.isNotEmpty()) { out.add(buf.toString()); buf.clear() }
                out.add(ch.toString())
            } else {
                buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) out.add(buf.toString())
        return out
    }

    /**
     * Greedy longest-match-first WordPiece subword splitting.
     * Returns [UNK_ID] if the word cannot be decomposed.
     */
    private fun wordPieceTokenize(word: String): List<Long> {
        if (word.length == 1) return listOf(vocab.getOrDefault(word, UNK_ID.toInt()).toLong())

        val ids = ArrayList<Long>()
        var start = 0
        var isBad = false

        while (start < word.length) {
            var end = word.length
            var foundId: Int? = null

            while (start < end) {
                val sub = if (start == 0) word.substring(start, end) else "##${word.substring(start, end)}"
                foundId = vocab[sub]
                if (foundId != null) break
                end--
            }

            if (foundId == null) { isBad = true; break }
            ids.add(foundId.toLong())
            start = end
        }

        return if (isBad) listOf(UNK_ID) else ids
    }

    private fun isChineseChar(cp: Int) =
        (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) ||
        (cp in 0x20000..0x2A6DF) || (cp in 0x2A700..0x2B73F) ||
        (cp in 0xF900..0xFAFF)

    private fun isPunctuation(ch: Char): Boolean {
        val cp = ch.code
        if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) return true
        return ch.category in setOf(
            CharCategory.OTHER_PUNCTUATION,
            CharCategory.CONNECTOR_PUNCTUATION,
            CharCategory.DASH_PUNCTUATION,
            CharCategory.START_PUNCTUATION,
            CharCategory.END_PUNCTUATION,
            CharCategory.INITIAL_QUOTE_PUNCTUATION,
            CharCategory.FINAL_QUOTE_PUNCTUATION
        )
    }
}
