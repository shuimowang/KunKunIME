package com.windinput.ime.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class QuickPhrasesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun onlyMissingFileGetsSamplesAndAnEmptySavedListStaysEmpty() {
        val file = File(temporary.root, "phrases.json")
        assertEquals(4, QuickPhrases.read(file).size)
        QuickPhrases.write(file, emptyList())
        assertTrue(QuickPhrases.read(file).isEmpty())
    }

    @Test fun addEditAndReloadKeepMultilineUnicodeAndStableOrder() {
        val first = QuickPhrases.upsert(emptyList(), null, "你好 👋\n明天见")
        val second = QuickPhrases.upsert(first, null, "收到，谢谢。")
        val edited = QuickPhrases.upsert(second, first.single().id, "好的\n晚点联系 🙂")
        assertEquals(second.map { it.id }, edited.map { it.id })
        val file = File(temporary.root, "phrases.json")
        QuickPhrases.write(file, edited)
        assertEquals(edited, QuickPhrases.read(file))
    }

    @Test fun rejectsBlankDuplicateOversizedAndStaleEdits() {
        val list = QuickPhrases.defaults
        assertThrows(IllegalArgumentException::class.java) { QuickPhrases.upsert(list, null, " \n ") }
        assertThrows(IllegalArgumentException::class.java) { QuickPhrases.upsert(list, null, list.first().text) }
        assertThrows(IllegalArgumentException::class.java) { QuickPhrases.upsert(list, null, "字".repeat(QuickPhrases.MAX_LENGTH + 1)) }
        assertThrows(IllegalArgumentException::class.java) { QuickPhrases.upsert(list, "deleted", "内容") }
    }

    @Test fun corruptFileIsNotSilentlyReplacedBySamples() {
        val file = temporary.newFile().apply { writeText("not json") }
        assertThrows(Exception::class.java) { QuickPhrases.read(file) }
        assertEquals("not json", file.readText())
    }

    @Test fun invalidWriteLeavesOriginalFileAndDoesNotLeaveTemporaryFiles() {
        val file = File(temporary.root, "phrases.json")
        QuickPhrases.write(file, QuickPhrases.defaults)
        assertThrows(IllegalArgumentException::class.java) { QuickPhrases.write(file, listOf(QuickPhrase("bad/id", "内容"))) }
        assertEquals(QuickPhrases.defaults, QuickPhrases.read(file))
        assertFalse(File(temporary.root, "phrases.json.tmp").exists())
    }
}
