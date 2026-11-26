package com.windinput.ime.keyboard

/**
 * 键位模型与内置 qwerty 布局。
 *
 * 占位实现：布局暂以代码内置；后续迁移为 TOML 布局定义文件
 * （与配置文件家族格式统一，见 docs/architecture.md §4.1）。
 */
sealed interface KeyAction {
    /** 字符键：小写形态，Shift 态由视图层换算 */
    data class Text(val char: Char) : KeyAction
    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object Shift : KeyAction
    data object SwitchSymbols : KeyAction

    /**
     * 中英切换（底栏，回车左侧）。
     *
     * 点击 = 在中文方案与**英文方案**之间切换。用英文方案而非「系统英文模式」是有意的：
     * 后者只是把字母直通给宿主、没有候选，而移动端打英文很依赖词库补全。
     * 长按 = 打开快捷面板做其它切换（方案 / 标点 / 全半角 / 简繁）。
     */
    data object SwitchLang : KeyAction

    /** 打开表情面板（123 层底栏） */
    data object Emoji : KeyAction

    /**
     * 原样上屏，**不经引擎**。用于九宫格数字键盘和 123 层的半角句点：那里要的就是
     * 键面上那个字符，中文模式下也不能被引擎转成「。」，有编码时数字也不能变成选词。
     */
    data class Commit(val text: String) : KeyAction

    /** 打开分类符号页（123 层、数字键盘的「符号」键） */
    data object SymbolPage : KeyAction

    /** 切到九宫格数字键盘（123 层的「1234」键） */
    data object NumberPad : KeyAction

    /**
     * 占位空白：不绘制、不响应。用于第 2 行两侧各缩进半个键（大众布局）。
     * 落在空白里的触点归最近的键（见 KeyboardView.findBounds），边缘不会点空。
     */
    data object Spacer : KeyAction
}

data class KeyDef(
    val label: String,
    val action: KeyAction,
    /** 相对宽度权重，同一行内按权重分配 */
    val widthWeight: Float = 1f,
    /** 特殊键（功能键）用不同底色 */
    val isSpecial: Boolean = false,
    /** 上滑输出的副字符（右上角小字提示） */
    val swipeUp: Char? = null,
    /** 长按气泡字符集（每字符一项，横移选择）；空 = 无气泡 */
    val longPress: String = "",
    /** 英文模式下改输出的字符（`，。`合一键：中文点按是逗号，英文是句点）；null = 同 [action] */
    val enText: Char? = null,
    /** 英文模式下的上滑字符；null = 同 [swipeUp] */
    val enSwipeUp: Char? = null,
    /** 纵向占几行（数字键盘的「换行」占两行）；下面那一行同一位置要放等宽的占位空白 */
    val rowSpan: Int = 1,
    /** 强调色键面（123 层、数字键盘左下角的返回键，两家都用强调色） */
    val isAccent: Boolean = false,
    /**
     * 稳定 id（用户键位覆盖的锚点），规则见 [LayoutIds]。构造处不必写：解析布局 / 内置布局出口统一补齐；
     * 空串 = 尚未分配。只是标识，不参与任何绘制与手势逻辑。
     */
    val id: String = "",
) {
    /** 按当前模式取点按输出的字符；非字符键为 null */
    fun textFor(english: Boolean): Char? =
        if (english && enText != null) enText else (action as? KeyAction.Text)?.char

    /** 按当前模式取上滑字符 */
    fun swipeFor(english: Boolean): Char? = if (english && enSwipeUp != null) enSwipeUp else swipeUp

    /** 小写字母键（含首行）：长按气泡是 [大写 | 符号 | 小写]，符号取当前模式的上滑字符 */
    private fun isLetterPopupKey(): Boolean =
        longPress.isEmpty() && (action as? KeyAction.Text)?.char in 'a'..'z'

    /**
     * 长按气泡字符集（每字符一项）。字母键是「大写、符号、小写」，没有上滑字符时退回「小写、大写」的旧样式；
     * 其余键用布局里的 [longPress]。
     */
    fun popupChars(english: Boolean): String {
        if (!isLetterPopupKey()) return longPress
        val c = (action as KeyAction.Text).char
        val sym = swipeFor(english) ?: return "$c${c.uppercaseChar()}"
        return "${c.uppercaseChar()}$sym$c"
    }

    /** 气泡初始高亮项：字母键带符号时是中间的符号（长按不动抬手即出符号），其余是第 0 项 */
    fun popupInitialIndex(english: Boolean): Int =
        if (isLetterPopupKey() && swipeFor(english) != null) 1 else 0
}

enum class KeyboardLayer { LETTERS, SYMBOLS, NUMBER }

/**
 * 键盘尺寸（dp）：横竖屏由 Service 按配置注入（twinInt 模式，
 * 见 docs/android-ime-features.md §10 建议 3）。面板高度随之联动。
 */
object KeyboardDimens {
    var rowHeightDp: Int = 60

    /**
     * 第一排键上方额外的留白（dp）。条区与键盘共用底色后，第一排键本身在行内就下移了
     * 半个行距（行高 60 − 键高 51 的一半 = 4.5dp），已经不贴条区；实测对照微信、豆包，
     * 再垫 3dp 就比它们高出约 8px，故取 0。保留常量是因为面板（符号、快捷功能）
     * 都按它算整体高度，日后想调只改这一处，切换时窗口不跳。
     */
    const val PAD_TOP_DP = 0f

    /** 手势起算距离（dp）：滑过它才算「开始滑」（空格移光标、上滑副字符、退格手势共用） */
    const val SWIPE_ACTIVATE_DP = 16f

    /** 上滑「选中」阈值（dp）：副字符 / 退格气泡的选项都过了它才生效 */
    const val SWIPE_THRESHOLD_DP = 40f

    /** 空格移光标 / 退格扩选的步进距离（dp） */
    const val CURSOR_STEP_DP = 12f

    // ── 键帽几何：KeyboardView 画键与面板里的键帽格子（符号页）共用，两边才对得齐 ──

    /** 键与键横向间隙的一半（dp）：两键之间共 5.4dp */
    const val KEY_GAP_DP = 2.7f

    /** 键区离视图左右边缘的留白（dp）：加上半个键间隙，首末键离屏幕边约 16px，不贴边、少误点 */
    const val EDGE_PAD_DP = 3.4f

    /** 键帽圆角（dp） */
    const val KEY_RADIUS_DP = 8f

    /** 键帽高度（dp）；行高减去它就是上下两行之间的间隙（60 − 51 = 9dp ≈ 24px） */
    const val KEY_HEIGHT_DP = 51f

    /** 键与键纵向间隙的一半（px）：行高减键帽高，至少留 2dp */
    fun keyGapVPx(density: Float): Float =
        (rowHeightPx(density) - KEY_HEIGHT_DP * density).coerceAtLeast(2 * density) / 2

    /** 条区编码行高度（dp）；横屏收矮，见 [BarView] */
    var barPreeditDp: Float = 19f

    /** 条区候选行高度（dp） */
    var barMainRowDp: Float = 38f

    // ── 像素高度：取整规则只在这里写一份 ──
    // 键盘、条区、面板（符号、快捷功能…）各自 dp→px 时取整方式不同，整体高度就会差几个像素，
    // 展开面板时窗口轻微跳变。三方都走这三个函数，面板高度 = 条区 + 键盘，恒等于键盘态的整体高度。

    /** 一行的像素高度（先取整，再乘行数：与 KeyboardView 逐行布局一致） */
    fun rowHeightPx(density: Float): Int = (rowHeightDp * density).toInt()

    /** 键盘 [rows] 行的像素高度 */
    fun keyboardHeightPx(density: Float, rows: Int): Int =
        (PAD_TOP_DP * density + rowHeightPx(density) * rows).toInt()

    /** 条区的像素高度 */
    fun barHeightPx(density: Float): Int = ((barPreeditDp + barMainRowDp) * density).toInt()
}

object KeyboardLayouts {

    /**
     * 字符键批量展开。字母键的长按气泡由 [KeyDef.popupChars] 生成「大写 | 符号 | 小写」，
     * 这里只给非字母键挂上滑字符兼长按。[enSwipes] 位置对齐，空格 = 英文模式同中文。
     */
    private fun charKeys(chars: String, swipes: String = "", enSwipes: String = ""): List<KeyDef> =
        chars.mapIndexed { i, c ->
            val swipe = swipes.getOrNull(i)
            KeyDef(
                c.toString(),
                KeyAction.Text(c),
                swipeUp = swipe,
                enSwipeUp = enSwipes.getOrNull(i)?.takeIf { it != ' ' },
                longPress = if (c.isLetter()) "" else swipe?.toString() ?: "",
            )
        }

    private val halfSpacer = KeyDef("", KeyAction.Spacer, widthWeight = 0.5f)

    /**
     * 字母层底栏，按豆包默认布局（真机截图，docs/android-ime-features.md §14.12）：
     * 符号 · 123 · 逗号 · 空格 · 句号 · 中英 · 回车，宽度按实测比例，总权重 10 与字母行对齐。
     * 逗号、句号各占一键：中文里最常用的两个标点，不该藏在合一键的上滑里。
     * **标点一律写 ASCII**（中英形态由 [Punct] 按模式派生，见该文件说明）。
     */
    private val lettersBottomRow: List<KeyDef> = listOf(
        KeyDef("符号", KeyAction.SymbolPage, widthWeight = 1.35f, isSpecial = true),
        KeyDef("123", KeyAction.SwitchSymbols, widthWeight = 1.15f, isSpecial = true),
        KeyDef(",", KeyAction.Text(','), longPress = ",;:\"'\\"),
        KeyDef("空格", KeyAction.Space, widthWeight = 2.95f),
        KeyDef(".", KeyAction.Text('.'), longPress = ".?!^"),
        KeyDef("中英", KeyAction.SwitchLang, widthWeight = 1.15f, isSpecial = true),
        KeyDef("换行", KeyAction.Enter, widthWeight = 1.4f, isSpecial = true, isAccent = true),
    )

    /** 内置 qwerty 主布局（26 键，上滑/长按副字符） */
    val qwerty: List<List<KeyDef>> = LayoutIds.assign(listOf(
        charKeys("qwertyuiop", "1234567890"),
        listOf(halfSpacer) + charKeys("asdfghjkl", "~!@#%\"”*?", "     '&  ") + listOf(halfSpacer),
        buildList {
            add(KeyDef("⇧", KeyAction.Shift, widthWeight = 1.5f, isSpecial = true))
            addAll(charKeys("zxcvbnm", "()-_:;\\", "      /"))
            add(KeyDef("⌫", KeyAction.Backspace, widthWeight = 1.5f, isSpecial = true))
        },
        lettersBottomRow,
    ))

    /**
     * 123 层，按两家的形态（docs/android-ime-features.md §14.2）。标点一律写 ASCII，
     * 中文模式下显示「：（）…“」等中文形态。第 3 行最后一个是**半角句点**（原样上屏，
     * 打小数用）；英文模式下它改出分号，免得一行里两个句点。
     */
    val symbols: List<List<KeyDef>> = LayoutIds.assign(listOf(
        charKeys("1234567890"),
        buildList {
            addAll(charKeys("-/:~()"))
            add(KeyDef("^", KeyAction.Text('^'), enText = '_'))
            addAll(charKeys("@\"'"))
        },
        buildList {
            add(KeyDef("1234", KeyAction.NumberPad, widthWeight = 1.5f, isSpecial = true))
            add(KeyDef(".", KeyAction.Text('.')))
            add(KeyDef(",", KeyAction.Text(',')))
            add(KeyDef("\\", KeyAction.Text('\\'), enText = '\''))
            addAll(charKeys("?!"))
            add(KeyDef(".", KeyAction.Commit("."), enText = ';'))
            add(KeyDef("⌫", KeyAction.Backspace, widthWeight = 1.5f, isSpecial = true))
        },
        // 与字母层底行同宽对齐：「返回」正好落在字母层「123」那一格（哪里进、哪里回）；
        // 「1234」在左下，与数字键盘左下的「返回」同位
        listOf(
            KeyDef("符号", KeyAction.SymbolPage, widthWeight = 1.35f, isSpecial = true),
            KeyDef("返回", KeyAction.SwitchSymbols, widthWeight = 1.15f, isSpecial = true, isAccent = true),
            KeyDef("☺", KeyAction.Emoji, isSpecial = true),
            KeyDef("空格", KeyAction.Space, widthWeight = 5.1f),
            KeyDef("换行", KeyAction.Enter, widthWeight = 1.4f, isSpecial = true, isAccent = true),
        ),
    ))

    private fun digit(c: Char) = KeyDef(c.toString(), KeyAction.Commit(c.toString()), widthWeight = 1.4f)
    private val sideSpacer = KeyDef("", KeyAction.Spacer)

    /**
     * 九宫格数字键盘（§14.4）。左列前三行是**占位空白**：那里由视图画可滚动的符号列
     * （见 KeyboardView.sideSymbols）；「换行」占两行，第 4 行同一位置是等宽占位。
     * 数字和句点都原样上屏，不经引擎。
     */
    val number: List<List<KeyDef>> = LayoutIds.assign(listOf(
        listOf(sideSpacer, digit('1'), digit('2'), digit('3'),
            KeyDef("⌫", KeyAction.Backspace, isSpecial = true)),
        listOf(sideSpacer, digit('4'), digit('5'), digit('6'),
            KeyDef("空格", KeyAction.Space, isSpecial = true)),
        listOf(sideSpacer, digit('7'), digit('8'), digit('9'),
            KeyDef("换行", KeyAction.Enter, isSpecial = true, isAccent = true, rowSpan = 2)),
        listOf(
            KeyDef("符号", KeyAction.SymbolPage, isSpecial = true),
            KeyDef("返回", KeyAction.SwitchSymbols, widthWeight = 1.4f, isSpecial = true, isAccent = true),
            digit('0'),
            KeyDef(".", KeyAction.Commit("."), widthWeight = 1.4f),
            sideSpacer,
        ),
    ))

    fun rows(layer: KeyboardLayer): List<List<KeyDef>> = when (layer) {
        KeyboardLayer.LETTERS -> qwerty
        KeyboardLayer.SYMBOLS -> symbols
        KeyboardLayer.NUMBER -> number
    }
}
