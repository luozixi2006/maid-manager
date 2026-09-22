package com.miniichat.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WordPiece is plain JVM code, so these tests drive the production tokenizer directly.
 * The shipped assets are read from the source tree: no Android runtime and no ONNX session.
 */
class WordPieceTest {
    private companion object {
        const val UNK_ID = 1L
        const val CLS_ID = 2L
        const val SEP_ID = 3L
        const val HELLO_ID = 5L
        const val WORLD_ID = 6L
        const val SUFFIX_S_ID = 7L
        const val PLAY_ID = 8L
        const val SUFFIX_ING_ID = 9L
        const val ZHONG_ID = 10L
        const val WEN_ID = 11L
        const val CAFE_ID = 12L

        val smallVocabulary = listOf(
            "[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]",
            "hello", "world", "##s", "play", "##ing",
            "\u4e2d", "\u6587", "cafe"
        )

        /**
         * Resolves an asset file no matter whether Gradle runs unit tests from the module
         * directory (app/) or from the repository root.
         */
        fun memoryAsset(name: String): File {
            var directory: File? = File("").absoluteFile
            while (directory != null) {
                for (relative in listOf("app/src/main/assets/memory/$name", "src/main/assets/memory/$name")) {
                    val candidate = File(directory, relative)
                    if (candidate.isFile) return candidate
                }
                directory = directory.parentFile
            }
            throw AssertionError("cannot locate assets/memory/$name above ${File("").absolutePath}")
        }

        fun realVocabulary(): List<String> = memoryAsset("vocab.txt").readLines()

        fun fixtures(): List<Pair<String, List<Long>>> {
            val json = memoryAsset("tokenizer-fixtures.json").readText()
            val entries = Regex("\\{\"text\":\\s*\"([^\"]*)\",\\s*\"ids\":\\s*\\[([^\\]]*)\\]\\}")
                .findAll(json)
                .map { match ->
                    val text = match.groupValues[1]
                    assertTrue("fixture text must not require JSON escapes: $text", !text.contains('\\'))
                    val ids = match.groupValues[2].split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { it.toLong() }
                    text to ids
                }
                .toList()
            assertTrue("tokenizer-fixtures.json did not yield any fixture", entries.isNotEmpty())
            return entries
        }
    }

    private val tokenizer = WordPiece(smallVocabulary, lowercase = true)

    @Test
    fun specialTokensWrapLowercasedTextAndGreedySubwordsJoin() {
        assertEquals(listOf(CLS_ID, HELLO_ID, SEP_ID), tokenizer.encode("Hello").toList())
        assertEquals(listOf(CLS_ID, HELLO_ID, WORLD_ID, SEP_ID), tokenizer.encode("HELLO WORLD").toList())
        assertEquals(
            listOf(CLS_ID, HELLO_ID, PLAY_ID, SUFFIX_S_ID, SEP_ID),
            tokenizer.encode("Hello plays").toList()
        )
        assertEquals(
            listOf(CLS_ID, PLAY_ID, SUFFIX_ING_ID, SEP_ID),
            tokenizer.encode("playing").toList()
        )
    }

    @Test
    fun caseAndDiacriticsAreStrippedBeforeVocabularyLookup() {
        val expected = listOf(CLS_ID, CAFE_ID, SEP_ID)
        assertEquals(expected, tokenizer.encode("cafe").toList())
        assertEquals(expected, tokenizer.encode("Caf\u00e9").toList())
        assertEquals(expected, tokenizer.encode("CAF\u00c9").toList())
        assertEquals(expected, tokenizer.encode("cafe\u0301").toList())
    }

    @Test
    fun punctuationAndHanCharactersBecomeSeparateTokens() {
        assertEquals(listOf(CLS_ID, ZHONG_ID, WEN_ID, SEP_ID), tokenizer.encode("\u4e2d\u6587").toList())
        assertEquals(
            listOf(CLS_ID, HELLO_ID, ZHONG_ID, WEN_ID, SEP_ID),
            tokenizer.encode("hello\u4e2d\u6587").toList()
        )
        assertEquals(listOf(CLS_ID, HELLO_ID, UNK_ID, SEP_ID), tokenizer.encode("hello!").toList())
        assertEquals(
            listOf(CLS_ID, UNK_ID, WORLD_ID, UNK_ID, SEP_ID),
            tokenizer.encode("(world)").toList()
        )
    }

    @Test
    fun unknownAndOverlongWordsFallBackToUnk() {
        assertEquals(listOf(CLS_ID, UNK_ID, SEP_ID), tokenizer.encode("zzz").toList())
        assertEquals(listOf(CLS_ID, UNK_ID, SEP_ID), tokenizer.encode("z".repeat(101)).toList())
        assertEquals(
            listOf(CLS_ID, UNK_ID, HELLO_ID, SEP_ID),
            tokenizer.encode("zzz hello").toList()
        )
    }

    @Test
    fun emptyControlAndWhitespaceOnlyInputStaysWellFormed() {
        assertEquals(listOf(CLS_ID, SEP_ID), tokenizer.encode("").toList())
        assertEquals(listOf(CLS_ID, SEP_ID), tokenizer.encode("   \t\n ").toList())
        assertEquals(listOf(CLS_ID, HELLO_ID, SEP_ID), tokenizer.encode("he\uFFFDllo\u200B").toList())
        assertEquals(
            listOf(CLS_ID, HELLO_ID, WORLD_ID, SEP_ID),
            tokenizer.encode("hello\nworld").toList()
        )
        assertEquals(
            listOf(CLS_ID, HELLO_ID, WORLD_ID, SEP_ID),
            tokenizer.encode("  hello \r\n world  ").toList()
        )
    }

    @Test
    fun limitClampsLongInputAndAlwaysKeepsWrappers() {
        assertEquals(listOf(CLS_ID, SEP_ID), tokenizer.encode("hello", 2).toList())
        assertEquals(listOf(CLS_ID, PLAY_ID, SEP_ID), tokenizer.encode("playing", 3).toList())
        val truncated = tokenizer.encode("hello world hello world", 5).toList()
        assertEquals(listOf(CLS_ID, HELLO_ID, WORLD_ID, HELLO_ID, SEP_ID), truncated)
        assertEquals(5, truncated.size)
        assertThrows(IllegalArgumentException::class.java) { tokenizer.encode("hello", 1) }
    }

    @Test
    fun everyOfficialFixtureTokenizesExactly() {
        val entries = fixtures()
        assertEquals(6, entries.size)
        val official = WordPiece(realVocabulary())
        val mismatches = entries.mapIndexedNotNull { index, (text, expected) ->
            val actual = official.encode(text).toList()
            if (actual == expected) null
            else "#$index text=\"$text\"\n  expected=$expected\n  actual  =$actual"
        }
        assertTrue(
            "tokenizer fixtures must match the shipped vocabulary exactly:\n" + mismatches.joinToString("\n"),
            mismatches.isEmpty()
        )
    }

    @Test
    fun shippedVocabularyUsesBertSpecialTokenIds() {
        val official = WordPiece(realVocabulary())
        assertEquals(listOf(101L, 102L), official.encode("").toList())
        assertEquals(listOf(101L, 102L), official.encode("\u0000\u0001\u200B").toList())
    }
}
