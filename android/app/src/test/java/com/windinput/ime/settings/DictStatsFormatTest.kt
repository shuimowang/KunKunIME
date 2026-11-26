package com.windinput.ime.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** 词库统计的文字与电脑版对话框（stats_dialog.rs 的用例）逐字对得上 */
class DictStatsFormatTest {

    private val codetable = JSONObject(
        """
        {"schemaId":"wubi86","schemaName":"五笔 86","engineType":"codetable","cached":true,
         "layers":[
           {"id":"main","label":"极点五笔主词库","kind":"main","enabled":true,"failed":false,"entries":120000,"chars":30000,"words":89000,"other":1000},
           {"id":"ext","label":"扩展","kind":"extra","enabled":false,"failed":false},
           {"id":"bad","label":"坏的","kind":"extra","enabled":true,"failed":true},
           {"id":"u","label":"我的","kind":"userLib","enabled":true,"failed":false,"entries":3,"chars":0,"words":3,"other":0}],
         "total":{"entries":120003,"uniqueChars":29000,"uniqueWords":88000,"byLen":{"1":30000,"2":60000,"3":10000,"4":15000,"5+":4003},"other":1000,"multiCodeChars":5000},
         "codeLen":{"char":{"1":25,"2":600,"3":3000,"4":26000,"5+":0},"word":{"1":0,"2":0,"3":0,"4":89000,"5+":0}},
         "shortCodes":{"levels":{"3":3748,"1":50,"2":654},"charsWithShort":4124},
         "dup":{"groups":9550,"entries":21814,"nonFirst":12000,"max":41,"maxCode":"cccc",
                "charFull":{"groups":2889,"chars":6402,"max":12,"top":[]},
                "main":{"groups":8913,"entries":20294,"max":13,"maxCode":"wyve"},
                "top":[{"code":"cccc","size":41,"texts":["又","叉"]},{"code":"aaaa","size":3,"texts":["工"]}]},
         "charsets":[{"key":"common_han_1","label":"通规一级","size":3500,"missing":2,"missingChars":["䶮"],"staticDup":120,"dupGroups":100,"dynRate":0.01234,"avgCodeLen":2.4567},
                     {"key":"common_han_12","label":"通规一二级","size":6500,"missing":10,"missingChars":[],"staticDup":null,"dupGroups":null,"dynRate":null,"avgCodeLen":null}],
         "pinyin":null,"freqSource":"白霜拼音字表（8105 + 41448）",
         "computedAtMs":0,"elapsedMs":120,
         "user":{"userWords":7,"tempWords":2}}
        """,
    )

    @Test
    fun overviewCountsAndUserWords() {
        val o = DictStatsFormat.overview(codetable).toMap()
        assertEquals("120,003", o["系统词库词条"])
        assertEquals("29,000", o["单字（去重）"])
        assertEquals("4,003", o["五字及以上"])
        assertEquals("7", o["用户词"])
        assertEquals("2", o["临时词"])
    }

    @Test
    fun overviewLinesGroupTheSameNumbers() {
        val lines = DictStatsFormat.overviewLines(codetable)
        assertEquals("系统词库词条 120,003", lines[0].title)
        assertEquals("系统词库（已启用 3 / 4）", lines[0].detail)
        assertEquals("单字 29,000 · 词组 88,000", lines[1].detail)
        assertEquals("二字 60,000 · 三字 10,000 · 四字 15,000 · 五字及以上 4,003", lines[2].detail)
        assertEquals("其他 1,000 · 用户词 7 · 临时词 2", lines[3].detail)
    }

    @Test
    fun layersShowStatusForDisabledAndFailed() {
        assertEquals("范围：系统词库（已启用 3 / 4）", DictStatsFormat.scope(codetable))
        val rows = DictStatsFormat.layers(codetable)
        assertEquals("极点五笔主词库（主）", rows[0].title)
        assertEquals("条目 120,000 · 单字 30,000 · 词组 89,000 · 其他 1,000", rows[0].detail)
        assertEquals("未启用", rows[1].detail)
        assertEquals("加载失败", rows[2].detail)
        assertEquals("用户分库：我的", rows[3].title)
    }

    @Test
    fun codetableLinesMatchDesktopWording() {
        assertEquals("简码：一级 50 · 二级 654 · 三级 3,748（有简码的字 4,124）", DictStatsFormat.shortCodes(codetable))
        assertEquals("重码组 9,550 · 涉及 21,814 条 · 最大 41 个（编码 cccc）", DictStatsFormat.dup(codetable))
        assertEquals("主库：重码组 8,913 · 涉及 20,294 条 · 最大 13 个（编码 wyve）", DictStatsFormat.mainDup(codetable))
        assertEquals("单字全码：重码组 2,889 · 涉及 6,402 字 · 最大 12", DictStatsFormat.charFull(codetable))
        assertEquals("多码字 5,000", DictStatsFormat.extra(codetable))
        assertEquals("1 码 25 · 2 码 600 · 3 码 3,000 · 4 码 26,000 · ≥5 码 0", DictStatsFormat.codeLen(codetable)[0].detail)
        assertEquals("字频：白霜拼音字表（8105 + 41448）（同字各读音权重之和）", DictStatsFormat.freq(codetable))
    }

    @Test
    fun topAndCharsets() {
        val top = DictStatsFormat.top(codetable, 1)
        assertEquals(1, top.size)
        assertEquals("cccc · 41 个", top[0].title)
        assertEquals("又 叉", top[0].detail)
        val cs = DictStatsFormat.charsets(codetable)
        assertEquals("字数 3,500 · 未收 2 · 静重 120 · 选重率 1.234% · 加权码长 2.46", cs[0].detail)
        assertEquals("字数 6,500 · 未收 10 · 静重 — · 选重率 — · 加权码长 —", cs[1].detail)
    }

    @Test
    fun pinyinUsesSameCodeWordingAndNoCodetableLines() {
        val v = JSONObject(
            """{"schemaId":"pinyin","schemaName":"拼音","engineType":"pinyin",
                "total":{"entries":10,"multiCodeChars":9042},"pinyin":{"syllables":424,"zeroWeight":45138},
                "dup":{"groups":41330,"max":30,"maxCode":"yi"},"charsets":[{"label":"通规全表","size":8105,"missing":3}]}""",
        )
        assertEquals("同码多词的编码 41,330 个 · 最多一码 30 个（yi）", DictStatsFormat.dup(v))
        assertEquals("多音字 9,042 · 音节 424 · 零权重条目 45,138", DictStatsFormat.extra(v))
        assertNull(DictStatsFormat.shortCodes(v))
        assertNull(DictStatsFormat.mainDup(v))
        assertNull(DictStatsFormat.freq(v))
        assertTrue(DictStatsFormat.codeLen(v).isEmpty())
        assertEquals("字数 8,105 · 未收 3", DictStatsFormat.charsets(v)[0].detail)
    }

    @Test
    fun mixedSplitsIntoMembersAndStatusSumsElapsed() {
        val v = JSONObject(
            """{"schemaId":"wp","engineType":"mixed","members":[
                {"schemaName":"五笔 86","engineType":"codetable","cached":true,"computedAtMs":3600000,"elapsedMs":100},
                {"schemaName":"拼音","engineType":"pinyin","cached":false,"computedAtMs":3600000,"elapsedMs":250}]}""",
        )
        assertEquals(listOf("五笔 86", "拼音"), DictStatsFormat.columns(v).map { it.optString("schemaName") })
        val tz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("统计于 01:00:00 · 用时 0.35 s", DictStatsFormat.status(v, Locale.US))
        } finally {
            TimeZone.setDefault(tz)
        }
        assertEquals("统计于  · 用时 0.12 s（缓存）", DictStatsFormat.status(codetable, Locale.US))
    }

    @Test
    fun englishOverviewByLetters() {
        val v = JSONObject("""{"engineType":"english","total":{"entries":5,"uniqueWords":5,"byLen":{"5+":2}},"user":null}""")
        val o = DictStatsFormat.overview(v).toMap()
        assertEquals("2", o["5 字母及以上"])
        assertEquals(null, o["用户词"])
    }
}
