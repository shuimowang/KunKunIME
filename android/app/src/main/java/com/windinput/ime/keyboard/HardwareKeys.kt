package com.windinput.ime.keyboard

import android.view.KeyEvent

/**
 * 物理键盘按键 → 软键同款的 [KeyAction]，之后与软键走同一条路进引擎（`ImeSession.keyPressed`）。
 *
 * 字符键不按键码查表，而是用系统按当前键盘布局算好的字符（`KeyEvent.getUnicodeChar`）：
 * 非美式布局、Shift 后的符号都由系统解析，这里只需把字符交给核心（`press`，美式键盘 VK 表在核心里）。
 *
 * 带 Ctrl / Alt / Meta 的组合键一律不接（null）：那是应用快捷键（Ctrl+C、Alt+Tab），
 * 输入法吃掉就坏了宿主的行为。
 */
object HardwareKeys {

    /** @param shifted 只对字母有意义：Shift+字母在中文方案下走宿主直输大写（与软键 ⇧ 同语义） */
    data class Mapped(val action: KeyAction, val shifted: Boolean)

    private const val BLOCKING_META =
        KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON

    /**
     * @param unicodeChar `KeyEvent.getUnicodeChar(metaState)` 的结果，0 表示不是字符键
     * @return null = 输入法不接，交回系统默认处理
     */
    fun map(keyCode: Int, unicodeChar: Int, metaState: Int): Mapped? {
        if (metaState and BLOCKING_META != 0) return null
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> return Mapped(KeyAction.Space, false)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return Mapped(KeyAction.Enter, false)
            KeyEvent.KEYCODE_DEL -> return Mapped(KeyAction.Backspace, false)
        }
        if (unicodeChar <= 0 || unicodeChar > 0x7E) return null
        val c = unicodeChar.toChar()
        return when {
            c in 'a'..'z' -> Mapped(KeyAction.Text(c), false)
            c in 'A'..'Z' -> Mapped(KeyAction.Text(c.lowercaseChar()), true)
            c > ' ' -> Mapped(KeyAction.Text(c), false)
            else -> null
        }
    }
}
