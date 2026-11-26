package com.windinput.ime.service

import android.content.ComponentName
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import com.windinput.ime.core.SchemaEntry

/**
 * 系统输入法列表里的「当前方案」：每个启用的方案注册成一个附加子类型（标签 = 方案名），
 * 系统切换器、通知栏、导航栏键盘图标里看到的就是「清风输入法 · 全拼」而不是固定的一行。
 *
 * - 方案变了 → [sync] 把系统当前子类型同步过去；
 * - 用户在系统切换器里点了某个方案的子类型 → Service 的 onCurrentInputMethodSubtypeChanged
 *   经 [schemaOf] 认出方案、切过去。
 *
 * 防回环：自己同步时记下目标子类型（[pendingSelfSwitches]），系统回调回来的正是它就不再切方案；
 * 回调里的方案已经是当前方案时同样不动；按用户的选择切方案期间，引擎还没切完推来的旧状态
 * 不往系统同步（见 Service 的 awaitingSchema）。
 *
 * 用 setSubtypeNameOverride 直接显示方案名；核心尚未就绪时保留 method.xml 的兜底子类型。
 */
internal class SchemaSubtypes(private val service: InputMethodService) {

    companion object {
        private const val TAG = "SchemaSubtypes"
        private const val EXTRA_PREFIX = "schema="

        /** 英文方案的子类型报英文语言：系统、别的应用（拼写检查、语音）据此知道在打英文 */
        private const val ENGLISH_ID = "english"

        /**
         * 子类型 id：方案 id 的稳定哈希（String.hashCode 由语言规范定死，跨版本、跨进程不变）。
         * 系统按它记住用户选的是哪个子类型，变了就对不上；0 表示「没设 id」，避开。
         */
        fun subtypeId(schemaId: String): Int = ("wind-schema:$schemaId").hashCode().let { if (it == 0) 1 else it }

        fun extraValue(schemaId: String): String = EXTRA_PREFIX + schemaId

        /** 从子类型的 extraValue 认出方案 id；兜底子类型（method.xml 那个）没有，返回 null */
        fun schemaOf(extraValue: String?): String? =
            extraValue?.split(',')?.firstOrNull { it.startsWith(EXTRA_PREFIX) }
                ?.removePrefix(EXTRA_PREFIX)?.takeIf { it.isNotEmpty() }
    }

    private val imm = service.getSystemService(InputMethodManager::class.java)

    /** 与 InputMethodInfo.getId() 同形（包名/短类名） */
    private val imiId = ComponentName(service, service.javaClass).flattenToShortString()

    /** 上次注册的（方案 id, 名称）：没变就不重复注册（注册会让系统重写子类型文件） */
    private var registered: List<Pair<String, String>>? = null

    /**
     * 自己发起、系统还没回调的切换（子类型 hashCode）。可能连发几次（状态推送接连到），
     * 回调按序到达，只记最后一次会把前一次的回调当成用户操作、又切回去——所以记全部
     */
    private val pendingSelfSwitches = mutableSetOf<Int>()

    /**
     * 注册子类型（启用方案有增减或改名时）并把系统当前子类型对齐到 [currentId]。
     * 方案列表为空（核心未就绪）时什么都不做。
     */
    fun sync(schemas: List<SchemaEntry>, currentId: String?) {
        if (schemas.isEmpty()) return
        val list = schemas.map { it.id to it.name.ifEmpty { it.id } }
        val subtypes = list.map { (id, name) -> build(id, name) }
        if (list != registered) {
            runCatching {
                imm.setAdditionalInputMethodSubtypes(imiId, subtypes.toTypedArray())
                // 显式启用全部方案子类型：不然系统按语言挑「隐式启用」的子类型，
                // 系统语言不是中文时一个都挑不中，切换器里还是只有兜底那一行
                imm.setExplicitlyEnabledInputMethodSubtypes(imiId, subtypes.map { it.hashCode() }.toIntArray())
                registered = list
            }.onFailure { Log.w(TAG, "注册方案子类型失败", it) }
        }
        val target = subtypes.firstOrNull { schemaOf(it.extraValue) == currentId } ?: return
        if (imm.currentInputMethodSubtype?.hashCode() == target.hashCode()) return
        pendingSelfSwitches += target.hashCode()
        runCatching { service.switchInputMethod(imiId, target) }
            .onFailure {
                pendingSelfSwitches -= target.hashCode()
                Log.w(TAG, "同步系统当前子类型失败", it)
            }
    }

    /** 系统回调来的子类型是不是自己同步过去的（是就消费掉这次标记） */
    fun consumeSelfSwitch(subtype: InputMethodSubtype): Boolean = pendingSelfSwitches.remove(subtype.hashCode())

    private fun build(id: String, name: String): InputMethodSubtype =
        InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeId(subtypeId(id))
            .setSubtypeNameOverride(name)
            .setSubtypeLocale(if (id == ENGLISH_ID) "en_US" else "zh_CN")
            .setLanguageTag(if (id == ENGLISH_ID) "en-US" else "zh-CN")
            .setSubtypeMode("keyboard")
            .setSubtypeExtraValue(extraValue(id))
            .setIsAsciiCapable(true)
            .build()
}
