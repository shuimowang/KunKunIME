package com.windinput.ime.keyboard.keymap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KeymapParserTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun emptyFileMeansNoOverrides() {
        val r = KeymapParser.parse("")
        assertFalse(r.tooNew)
        assertEquals(1, r.version)
        assertTrue(r.layers.isEmpty())
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun missingFileMeansNoOverrides() {
        assertEquals(KeymapParseResult.EMPTY, KeymapParser.load(File(tmp.root, "nope.toml")))
    }

    @Test
    fun missingVersionIsTreatedAsOne() {
        val r = KeymapParser.parse("[layer.letters.key.z]\nswipe_up = { text = \"《\" }\n")
        assertEquals(1, r.version)
        assertEquals(KeyOutput.Text("《"), r.overrideFor("letters", "z", Gesture.SWIPE_UP)?.output)
    }

    @Test
    fun newerVersionIsIgnoredWholeButFlagged() {
        val r = KeymapParser.parse("version = 99\n[layer.letters.key.z]\nswipe_up = { text = \"x\" }\n")
        assertTrue(r.tooNew)
        assertEquals(99, r.version)
        assertTrue(r.layers.isEmpty())
        assertEquals(1, r.warnings.size)
    }

    @Test
    fun tooNewFileIsNeverModifiedOrDeleted() {
        val f = tmp.newFile("keymap.toml").apply { writeText("version = 5\n") }
        assertTrue(KeymapParser.load(f).tooNew)
        assertEquals("version = 5\n", f.readText())
    }

    @Test
    fun validSampleParsesAllGestureKinds() {
        val r = KeymapParser.parse(
            """
            version = 1
            [layer.letters.key.z]
            swipe_up   = { text = "《" }
            swipe_down = { func = "cut" }
            long_press = ["〔", "〕", { char = "-" }]
            [layer.letters.key.x]
            tap = { char = "q" }
            swipe_up = { template = "{date}" }
            swipe_down = { phrase = "sig" }
            [layer.letters.bottom.comma]
            long_press = ["，", "；"]
            """.trimIndent(),
        )
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
        assertEquals(KeyOutput.Func("cut"), r.overrideFor("letters", "z", Gesture.SWIPE_DOWN)?.output)
        assertEquals(
            listOf(KeyOutput.Text("〔"), KeyOutput.Text("〕"), KeyOutput.Char('-')),
            r.overrideFor("letters", "z", Gesture.LONG_PRESS)?.bubble,
        )
        assertEquals(KeyOutput.Char('q'), r.overrideFor("letters", "x", Gesture.TAP)?.output)
        assertEquals(KeyOutput.Template("{date}"), r.overrideFor("letters", "x", Gesture.SWIPE_UP)?.output)
        assertEquals(KeyOutput.Phrase("sig"), r.overrideFor("letters", "x", Gesture.SWIPE_DOWN)?.output)
        assertEquals(2, r.overrideFor("letters", "comma", Gesture.LONG_PRESS)?.bubble?.size)
    }

    @Test
    fun unknownGestureIsSkippedOthersSurvive() {
        val r = KeymapParser.parse("[layer.letters.key.z]\nswipe_left = { text = \"a\" }\nswipe_up = { text = \"b\" }\n")
        assertNull(r.overrideFor("letters", "z", Gesture.TAP))
        assertEquals(KeyOutput.Text("b"), r.overrideFor("letters", "z", Gesture.SWIPE_UP)?.output)
        assertEquals(1, r.warnings.size)
        assertTrue("swipe_left" in r.warnings[0])
    }

    @Test
    fun unknownFuncIsSkippedWithWarning() {
        val r = KeymapParser.parse("[layer.letters.key.z]\nswipe_up = { func = \"rm_rf\" }\nswipe_down = { func = \"paste\" }\n")
        assertNull(r.overrideFor("letters", "z", Gesture.SWIPE_UP))
        assertEquals(KeyOutput.Func("paste"), r.overrideFor("letters", "z", Gesture.SWIPE_DOWN)?.output)
        assertEquals(1, r.warnings.size)
    }

    @Test
    fun misspelledOutputFieldIsSkipped() {
        val r = KeymapParser.parse("[layer.letters.key.z]\nswipe_up = { txt = \"a\" }\nswipe_down = { char = \"ab\" }\ntap = { text = \"\" }\n")
        assertTrue(r.layers.isEmpty())
        assertEquals(3, r.warnings.size)
    }

    @Test
    fun unknownLayerSectionAndTopLevelAreSkippedNotFatal() {
        val r = KeymapParser.parse(
            "colour = 1\n[layer.weird.key.a]\ntap={text=\"x\"}\n[layer.letters.oops.a]\ntap={text=\"x\"}\n[layer.letters.key.a]\ntap={text=\"ok\"}\n",
        )
        assertEquals(KeyOutput.Text("ok"), r.overrideFor("letters", "a", Gesture.TAP)?.output)
        assertEquals(3, r.warnings.size)
    }

    @Test
    fun syntaxErrorIgnoresWholeFileWithoutThrowing() {
        val r = KeymapParser.parse("version = \n[[[")
        assertFalse(r.tooNew)
        assertTrue(r.layers.isEmpty())
        assertEquals(1, r.warnings.size)
    }

    @Test
    fun badVersionTypeFallsBackToOne() {
        val r = KeymapParser.parse("version = \"two\"\n[layer.letters.key.a]\ntap = { text = \"x\" }\n")
        assertEquals(1, r.version)
        assertEquals(1, r.warnings.size)
        assertEquals(KeyOutput.Text("x"), r.overrideFor("letters", "a", Gesture.TAP)?.output)
    }

    @Test
    fun emptyLongPressArrayIsAValidOverrideMeaningNoBubble() {
        val r = KeymapParser.parse("[layer.letters.bottom.period]\nlong_press = []\n")
        assertEquals(emptyList<KeyOutput>(), r.overrideFor("letters", "period", Gesture.LONG_PRESS)?.bubble)
    }
}
