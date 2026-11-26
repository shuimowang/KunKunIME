package com.windinput.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LayoutParserTest {

    private fun weights(row: List<KeyDef>) = row.map { it.widthWeight }

    @Test
    fun spacerAcceptsFloatAndIntegerWidth() {
        val rows = LayoutParser.parse(
            """
            [[rows]]
            key = [ { spacer = 0.5 }, { chars = "ab" }, { spacer = 1 } ]
            """.trimIndent(),
        )
        assertEquals(listOf(KeyAction.Spacer, KeyAction.Text('a'), KeyAction.Text('b'), KeyAction.Spacer), rows[0].map { it.action })
        assertEquals(listOf(0.5f, 1f, 1f, 1f), weights(rows[0]))
    }

    @Test
    fun letterKeysHaveSwipeButNoLongPress() {
        val key = LayoutParser.parse("[[rows]]\nkeys = \"q\"\nswipe = \"1\"")[0][0]
        assertEquals('1', key.swipeUp)
        assertEquals("", key.longPress)
    }

    private fun bundledLetters(): Map<Char, KeyDef> =
        LayoutParser.parse(File("src/main/assets/data/layouts/qwerty.toml").readText())
            .flatten().filter { (it.action as? KeyAction.Text)?.char in 'a'..'z' }
            .associateBy { (it.action as KeyAction.Text).char }

    @Test
    fun bundledLetterSwipesFollowAssignment() {
        val keys = bundledLetters()
        val zh = "asdfghjkl" + "zxcvbnm"
        // 小米输入法截图（2026-10-24）；中文 h“ j” m、，英文仅 h' j& m/ 三处不同
        val zhSym = "~!@#%\"”*?" + "()-_:;\\"
        zh.forEachIndexed { i, c -> assertEquals("zh $c", zhSym[i], keys.getValue(c).swipeFor(english = false)) }
        val enSym = mapOf('h' to '\'', 'j' to '&', 'm' to '/')
        zh.forEachIndexed { i, c -> assertEquals("en $c", enSym[c] ?: zhSym[i], keys.getValue(c).swipeFor(english = true)) }
    }

    @Test
    fun letterPopupIsUpperSymbolLowerWithSymbolInitial() {
        val l = bundledLetters().getValue('l')
        assertEquals("L?l", l.popupChars(english = false))
        assertEquals(1, l.popupInitialIndex(english = false))
        val a = bundledLetters().getValue('a')
        assertEquals("A~a", a.popupChars(english = true))
        assertEquals("A~a", a.popupChars(english = false))
        val j = bundledLetters().getValue('j')
        assertEquals("J”j", j.popupChars(english = false))
        assertEquals("J&j", j.popupChars(english = true))
        val q = LayoutParser.parse("[[rows]]\nkeys = \"q\"\nswipe = \"1\"")[0][0]
        assertEquals("Q1q", q.popupChars(english = false))
        val bare = LayoutParser.parse("[[rows]]\nkeys = \"q\"")[0][0]
        assertEquals("qQ", bare.popupChars(english = false))
        assertEquals(0, bare.popupInitialIndex(english = false))
    }

    @Test
    fun symbolKeysUseSwipeAsLongPress() {
        val key = LayoutParser.parse("[[rows]]\nkeys = \",\"\nswipe = \".\"")[0][0]
        assertEquals(".", key.longPress)
    }

    @Test
    fun combinedPunctKeySwitchesOutputByMode() {
        val key = LayoutParser.parse(
            """
            [[rows]]
            key = [ { text = ",", swipe_up = ".", en_text = ".", en_swipe_up = "'" } ]
            """.trimIndent(),
        )[0][0]
        assertEquals(',', key.textFor(english = false))
        assertEquals('.', key.swipeFor(english = false))
        assertEquals('.', key.textFor(english = true))
        assertEquals('\'', key.swipeFor(english = true))
    }

    @Test
    fun functionKeyHasNoText() {
        val key = LayoutParser.parse("[[rows]]\nkey = [ { label = \"换行\", action = \"enter\", width = 2 } ]")[0][0]
        assertEquals(KeyAction.Enter, key.action)
        assertEquals(2f, key.widthWeight)
        assertNull(key.textFor(english = true))
    }

    /** 随包布局与代码内置布局必须一致：TOML 解析失败时回退内置，两者不同步会让用户看到两套键盘 */
    private fun assertBundledMatchesBuiltin(file: String, builtin: List<List<KeyDef>>) {
        val bundled = LayoutParser.parse(File("src/main/assets/data/layouts/$file").readText())
        assertEquals(builtin.size, bundled.size)
        for (i in builtin.indices) {
            assertEquals("$file row $i actions", builtin[i].map { it.action }, bundled[i].map { it.action })
            assertEquals("$file row $i labels", builtin[i].map { it.label }, bundled[i].map { it.label })
            assertEquals("$file row $i weights", weights(builtin[i]), weights(bundled[i]))
            assertEquals("$file row $i swipe", builtin[i].map { it.swipeUp }, bundled[i].map { it.swipeUp })
            assertEquals("$file row $i enSwipe", builtin[i].map { it.enSwipeUp }, bundled[i].map { it.enSwipeUp })
            assertEquals("$file row $i longPress", builtin[i].map { it.longPress }, bundled[i].map { it.longPress })
            assertEquals("$file row $i enText", builtin[i].map { it.enText }, bundled[i].map { it.enText })
            assertEquals("$file row $i rowSpan", builtin[i].map { it.rowSpan }, bundled[i].map { it.rowSpan })
            assertEquals("$file row $i accent", builtin[i].map { it.isAccent }, bundled[i].map { it.isAccent })
            // 键底色（功能键灰、字母与空格白）也要一致：此前文件里的空格被解析成灰键，内置是白键
            assertEquals("$file row $i special", builtin[i].map { it.isSpecial }, bundled[i].map { it.isSpecial })
            // 键位 id 也要一致：覆盖按 id 定位，随包与内置两套 id 不同会让覆盖在回退时悄悄失效
            assertEquals("$file row $i id", builtin[i].map { it.id }, bundled[i].map { it.id })
        }
    }

    @Test
    fun bundledQwertyMatchesBuiltin() = assertBundledMatchesBuiltin("qwerty.toml", KeyboardLayouts.qwerty)

    @Test
    fun bundledSymbolsMatchesBuiltin() = assertBundledMatchesBuiltin("symbols.toml", KeyboardLayouts.symbols)

    @Test
    fun bundledNumberMatchesBuiltin() = assertBundledMatchesBuiltin("number.toml", KeyboardLayouts.number)

    @Test
    fun commitKeyBypassesEngineAndCanSwitchInEnglish() {
        val key = LayoutParser.parse("[[rows]]\nkey = [ { commit = \".\", en_text = \";\" } ]")[0][0]
        assertEquals(KeyAction.Commit("."), key.action)
        assertEquals(".", key.label)
        assertNull(key.textFor(english = false))
        assertEquals(';', key.textFor(english = true))
    }

    @Test
    fun rowSpanAccentAndPanelActionsParse() {
        val row = LayoutParser.parse(
            """
            [[rows]]
            key = [
              { label = "←", action = "switch_symbols", accent = true },
              { label = "符号", action = "symbol_page" },
              { label = "1234", action = "number_pad" },
              { label = "换行", action = "enter", row_span = 2 },
            ]
            """.trimIndent(),
        )[0]
        assertEquals(listOf(true, false, false, false), row.map { it.isAccent })
        assertEquals(listOf(KeyAction.SwitchSymbols, KeyAction.SymbolPage, KeyAction.NumberPad, KeyAction.Enter), row.map { it.action })
        assertEquals(listOf(1, 1, 1, 2), row.map { it.rowSpan })
    }

    /**
     * 跨行键的下方必须是等宽占位：否则第 4 行的键会画进「换行」底下。
     * 按每行累计权重求键的横向区间来比对，行总权重也必须一致（列才对得齐）。
     */
    @Test
    fun numberPadSpanningKeyHasSpacerBelow() {
        val rows = KeyboardLayouts.number
        val totals = rows.map { r -> r.sumOf { it.widthWeight.toDouble() } }
        assertEquals(1, totals.distinct().size)
        rows.forEachIndexed { r, row ->
            var x = 0.0
            for (key in row) {
                if (key.rowSpan > 1) {
                    var bx = 0.0
                    val below = rows[r + 1].first { k -> (bx >= x - 1e-6).also { bx += k.widthWeight } }
                    assertEquals(KeyAction.Spacer, below.action)
                    assertEquals(key.widthWeight, below.widthWeight)
                }
                x += key.widthWeight
            }
        }
    }

    /** 大众布局：每行总权重 10，第 2 行键宽与第 1 行相同（两侧各缩进半键） */
    @Test
    fun qwertyRowsAllSumToTen() {
        for ((i, row) in KeyboardLayouts.qwerty.withIndex()) {
            assertEquals("row $i", 10f, row.sumOf { it.widthWeight.toDouble() }.toFloat(), 0.001f)
        }
    }

    // ── 稳定 id（用户键位覆盖的锚点，见 LayoutIds）──

    private fun ids(file: String): List<String> =
        LayoutParser.parse(File("src/main/assets/data/layouts/$file").readText()).flatten().map { it.id }

    @Test
    fun idsAreUniqueNonBlankInEveryBundledLayout() {
        for (f in listOf("qwerty.toml", "symbols.toml", "number.toml")) {
            val all = ids(f)
            assertTrue("$f 有空 id", all.none { it.isBlank() })
            assertEquals("$f id 重复", all.size, all.toSet().size)
        }
    }

    /** 钉死出厂 id 集合：以后无意改键面 / 顺序导致 id 变化，用户的覆盖会静默失效，这里要红 */
    @Test
    fun qwertyIdsAreStable() {
        val all = ids("qwerty.toml")
        assertEquals(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            all.take(10),
        )
        val set = all.toSet()
        for (c in "asdfghjklzxcvbnm") assertTrue("缺字母键 $c", c.toString() in set)
        for (id in listOf("shift", "backspace", "symbol_page", "switch_symbols", "comma", "space", "period", "switch_lang", "enter")) {
            assertTrue("缺功能键 $id", id in set)
        }
        // 半宽占位：spacer、spacer-2（出现顺序）
        assertTrue("spacer" in set && "spacer-2" in set)
    }

    @Test
    fun symbolsAndNumberIdsAreStable() {
        val sym = ids("symbols.toml").toSet()
        for (id in listOf("1", "0", "number_pad", "backspace", "symbol_page", "switch_symbols", "emoji", "space", "enter", "c_period", "x5c")) {
            assertTrue("symbols 缺 $id", id in sym)
        }
        val num = ids("number.toml").toSet()
        for (id in listOf("c_1", "c_0", "c_9", "backspace", "space", "enter", "symbol_page", "switch_symbols", "c_period", "spacer")) {
            assertTrue("number 缺 $id", id in num)
        }
    }

    @Test
    fun explicitIdWinsAndDuplicatesFallBackWithWarning() {
        val warnings = mutableListOf<String>()
        val rows = LayoutParser.parse(
            """
            [[rows]]
            key = [ { text = "a", id = "mine" }, { text = "b", id = "mine" }, { text = "mine" } ]
            """.trimIndent(),
            warnings::add,
        )
        assertEquals("mine", rows[0][0].id)
        assertEquals("b", rows[0][1].id)
        assertEquals(1, warnings.size)
        assertEquals(3, rows[0].map { it.id }.toSet().size)
    }

    @Test
    fun derivedIdsGetOccurrenceSuffixOnCollision() {
        val rows = LayoutParser.parse("[[rows]]\nkeys = \"aa\"\n[[rows]]\nkeys = \"a\"\n")
        assertEquals(listOf("a", "a-2", "a-3"), rows.flatten().map { it.id })
    }
}
