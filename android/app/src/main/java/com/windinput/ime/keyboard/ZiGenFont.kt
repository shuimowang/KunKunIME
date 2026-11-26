package com.windinput.ime.keyboard

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.Log
import com.windinput.ime.core.DataDirs

/**
 * 五笔字根字体（随包 `schemas/wubi86/HeiTiZiGen.ttf`，电脑版同一份）。
 *
 * 拆字段里的字根是 Unicode 私用区字符，系统字体里没有，直接显示是方框。只给这些字符
 * 套上字根字体，其余文字仍用系统字体——整段换字体的话汉字、标点都会变成字根字体里的
 * 字形（若有）或回落得很难看。
 *
 * [ZiGenSpan] 在测量与绘制时使用同一份字根字体，保持字根宽度与显示一致。
 */
object ZiGenFont {

    private const val TAG = "ZiGenFont"
    private const val REL_PATH = "schemas/wubi86/HeiTiZiGen.ttf"

    @Volatile
    private var loaded = false
    private var typeface: Typeface? = null

    private fun typeface(context: Context): Typeface? {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    typeface = DataDirs(context).resolveDataFile(REL_PATH)?.let { file ->
                        runCatching { Typeface.createFromFile(file) }
                            .onFailure { Log.w(TAG, "字根字体加载失败，字根将显示为方框", it) }
                            .getOrNull()
                    }
                    loaded = true // 失败也只试一次：每张提示卡都重读一遍文件没有意义
                }
            }
        }
        return typeface
    }

    /** 私用区：BMP 的 E000–F8FF 与补充私用区 A/B */
    fun isPua(cp: Int): Boolean = cp in 0xE000..0xF8FF || cp in 0xF0000..0x10FFFD

    /** 给 [text] 里的私用区字符套上字根字体；没有私用区字符或字体不可用时原样返回 */
    fun apply(context: Context, text: String): CharSequence {
        if (text.codePoints().noneMatch(::isPua)) return text
        val tf = typeface(context) ?: return text
        val out = SpannableString(text)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            if (isPua(cp)) out.setSpan(ZiGenSpan(tf), i, i + n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i += n
        }
        return out
    }

    /** 测量与绘制时把画笔换成指定字体 */
    private class ZiGenSpan(private val tf: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) {
            tp.typeface = tf
        }

        override fun updateMeasureState(tp: TextPaint) {
            tp.typeface = tf
        }
    }
}
