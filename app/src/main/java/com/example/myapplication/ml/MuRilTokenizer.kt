package com.example.myapplication.ml

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tokenizer output containing input_ids, attention_mask, and token_type_ids
 * formatted and padded/truncated for BERT/MuRIL sequence classification.
 */
data class TokenizerOutput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TokenizerOutput) return false
        return inputIds.contentEquals(other.inputIds) &&
                attentionMask.contentEquals(other.attentionMask) &&
                tokenTypeIds.contentEquals(other.tokenTypeIds)
    }

    override fun hashCode(): Int {
        var result = inputIds.contentHashCode()
        result = 31 * result + attentionMask.contentHashCode()
        result = 31 * result + tokenTypeIds.contentHashCode()
        return result
    }
}

/**
 * WordPiece tokenizer compatible with Google's MuRIL (google/muril-base-cased) model.
 *
 * Configuration:
 * - Vocabulary: 197,251 tokens from vocab.txt
 * - do_lower_case: false (cased, preserves uppercase/lowercase distinctions)
 * - [PAD] token ID: 0
 * - [UNK] token ID: 100
 * - [CLS] token ID: 104
 * - [SEP] token ID: 105
 * - [MASK] token ID: 103
 * - Max sequence length: 128
 */
class MuRilTokenizer(private val context: Context) {

    private val vocab = HashMap<String, Long>(250_000)
    private var isInitialized = false

    companion object {
        private const val TAG = "MuRilTokenizer"
        const val VOCAB_ASSET_PATH = "muril/vocab.txt"

        const val PAD_TOKEN = "[PAD]"
        const val UNK_TOKEN = "[UNK]"
        const val CLS_TOKEN = "[CLS]"
        const val SEP_TOKEN = "[SEP]"
        const val MASK_TOKEN = "[MASK]"

        const val PAD_ID = 0L
        const val UNK_ID = 100L
        const val CLS_ID = 104L
        const val SEP_ID = 105L
        const val MASK_ID = 103L

        const val DEFAULT_MAX_LENGTH = 128
        private const val MAX_INPUT_CHARS_PER_WORD = 200
    }

    /**
     * Loads vocab.txt from app assets on an IO dispatcher.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        loadVocabInternal()
    }

    @Synchronized
    private fun loadVocabInternal() {
        if (isInitialized && vocab.isNotEmpty()) return

        try {
            Log.d(TAG, "Loading MuRIL vocabulary from assets: $VOCAB_ASSET_PATH")
            context.assets.open(VOCAB_ASSET_PATH).bufferedReader(Charsets.UTF_8).useLines { lines ->
                var id = 0L
                for (line in lines) {
                    val token = line.trim()
                    if (token.isNotEmpty()) {
                        vocab[token] = id
                    }
                    id++
                }
            }
            isInitialized = true
            Log.d(TAG, "MuRIL vocabulary loaded successfully. Total tokens: ${vocab.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocabulary from $VOCAB_ASSET_PATH", e)
            throw IllegalStateException("Failed to load MuRIL vocabulary: ${e.message}", e)
        }
    }

    /**
     * Tokenizes input text, applies WordPiece subword segmentation, adds [CLS] and [SEP]
     * special tokens, and pads/truncates to [maxLength] (default 128).
     *
     * @param text Raw incoming message text.
     * @param maxLength Maximum sequence length including [CLS] and [SEP] (default 128).
     * @return [TokenizerOutput] containing inputIds, attentionMask, and tokenTypeIds.
     */
    fun tokenize(text: String, maxLength: Int = DEFAULT_MAX_LENGTH): TokenizerOutput {
        if (!isInitialized || vocab.isEmpty()) {
            loadVocabInternal()
        }

        // 1. Basic Tokenization (Whitespace and Punctuation splitting, preserving case)
        val basicTokens = basicTokenize(text)

        // 2. WordPiece subword tokenization
        val wordPieceTokens = mutableListOf<String>()
        for (token in basicTokens) {
            wordPieceTokens.addAll(wordPieceTokenize(token))
        }

        // 3. Truncate to leave room for [CLS] and [SEP]
        val maxTokens = (maxLength - 2).coerceAtLeast(0)
        val truncatedTokens = if (wordPieceTokens.size > maxTokens) {
            wordPieceTokens.subList(0, maxTokens)
        } else {
            wordPieceTokens
        }

        // 4. Build output arrays
        val inputIds = LongArray(maxLength) { PAD_ID }
        val attentionMask = LongArray(maxLength) { 0L }
        val tokenTypeIds = LongArray(maxLength) { 0L }

        // Add [CLS] at position 0
        inputIds[0] = CLS_ID
        attentionMask[0] = 1L

        // Add tokens
        for (i in truncatedTokens.indices) {
            val token = truncatedTokens[i]
            val tokenId = vocab[token] ?: UNK_ID
            inputIds[i + 1] = tokenId
            attentionMask[i + 1] = 1L
        }

        // Add [SEP] after tokens
        val sepIndex = truncatedTokens.size + 1
        if (sepIndex < maxLength) {
            inputIds[sepIndex] = SEP_ID
            attentionMask[sepIndex] = 1L
        }

        return TokenizerOutput(
            inputIds = inputIds,
            attentionMask = attentionMask,
            tokenTypeIds = tokenTypeIds
        )
    }

    /**
     * Splits text on whitespace and isolates punctuation/CJK characters into standalone tokens.
     * Case is preserved because MuRIL is a cased model.
     */
    private fun basicTokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val currentWord = java.lang.StringBuilder()

        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val charCount = Character.charCount(codePoint)

            if (isWhitespace(codePoint)) {
                if (currentWord.isNotEmpty()) {
                    tokens.add(currentWord.toString())
                    currentWord.clear()
                }
            } else if (isPunctuation(codePoint) || isChineseChar(codePoint)) {
                if (currentWord.isNotEmpty()) {
                    tokens.add(currentWord.toString())
                    currentWord.clear()
                }
                tokens.add(String(Character.toChars(codePoint)))
            } else if (!isControl(codePoint)) {
                currentWord.append(String(Character.toChars(codePoint)))
            }

            i += charCount
        }

        if (currentWord.isNotEmpty()) {
            tokens.add(currentWord.toString())
        }

        return tokens
    }

    /**
     * Greedy longest-match-first WordPiece tokenization for a single word token.
     */
    private fun wordPieceTokenize(word: String): List<String> {
        if (word.length > MAX_INPUT_CHARS_PER_WORD) {
            return listOf(UNK_TOKEN)
        }

        var isBad = false
        var start = 0
        val subTokens = mutableListOf<String>()

        while (start < word.length) {
            var end = word.length
            var curSubstr: String? = null

            while (start < end) {
                var sub = word.substring(start, end)
                if (start > 0) {
                    sub = "##$sub"
                }
                if (vocab.containsKey(sub)) {
                    curSubstr = sub
                    break
                }
                end--
            }

            if (curSubstr == null) {
                isBad = true
                break
            }

            subTokens.add(curSubstr)
            start = end
        }

        return if (isBad) listOf(UNK_TOKEN) else subTokens
    }

    private fun isWhitespace(cp: Int): Boolean {
        if (cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) {
            return true
        }
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp)
    }

    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) {
            return false
        }
        val type = Character.getType(cp)
        return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
    }

    private fun isPunctuation(cp: Int): Boolean {
        // Standard ASCII punctuation
        if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) {
            return true
        }
        // General Unicode punctuation categories
        val type = Character.getType(cp)
        return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
                type == Character.DASH_PUNCTUATION.toInt() ||
                type == Character.START_PUNCTUATION.toInt() ||
                type == Character.END_PUNCTUATION.toInt() ||
                type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
                type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                type == Character.OTHER_PUNCTUATION.toInt()
    }

    private fun isChineseChar(cp: Int): Boolean {
        return (cp in 0x4E00..0x9FFF) ||
                (cp in 0x3400..0x4DBF) ||
                (cp in 0x20000..0x2A6DF) ||
                (cp in 0x2A700..0x2B73F) ||
                (cp in 0x2B740..0x2B81F) ||
                (cp in 0x2B820..0x2CEAF) ||
                (cp in 0xF900..0xFAFF) ||
                (cp in 0x2F800..0x2FA1F)
    }
}
