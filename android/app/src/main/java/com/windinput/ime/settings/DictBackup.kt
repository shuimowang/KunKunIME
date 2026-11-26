package com.windinput.ime.settings

import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 词库备份的文件名与导入结果文案。
 *
 * 文件格式就是电脑版「词库 → 导出」的 WindDict（`.wdict.yaml`，多段：个人词 / 临时词 /
 * 词频 / 候选调整），由核心生成与解析——手机导出的文件电脑版能直接导入，反过来也一样。
 * 这里只放与界面有关、不碰核心的部分，JVM 单测直接跑。
 */
object DictBackup {

    /** 导入文件大小上限：整份读进内存再交给核心，挡住误选的大文件把设置页撑爆 */
    const val MAX_IMPORT_BYTES = 32L * 1024 * 1024

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /**
     * 读满 [input]，超过 [max] 字节就停下返回 null。
     *
     * 不信文件描述符报的长度：网盘一类 SAF 提供方常报「未知」（-1），按长度判大小在那里
     * 等于没判，接着整份读进内存（再 decode、再跨 JNI 拷一份）——选到一个几百 MB 的文件，
     * 设置页就 OOM 了，而 OutOfMemoryError 不是 Exception，外层也兜不住。
     */
    fun readLimited(input: InputStream, max: Long = MAX_IMPORT_BYTES): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 与电脑版同一套命名：`<方案>-<时间戳>.wdict.yaml`，连着导两次不会互相覆盖 */
    fun fileName(schemaId: String, now: LocalDateTime = LocalDateTime.now()): String =
        "$schemaId-${now.format(STAMP)}.wdict.yaml"

    /** 一段的导入结果（从绑定层的结构摊平，单测不用碰 JNI 类型） */
    data class Section(
        val key: String,
        val added: Long,
        val updated: Long,
        val unchanged: Long,
        val skipped: Long,
    )

    private val SECTION_LABELS = mapOf(
        "userWords" to "个人词",
        "tempWords" to "临时词",
        "freq" to "词频",
        "shadow" to "候选调整",
    )

    /**
     * 「个人词新增 3、更新 1；词频 120 条」这样的一句话。
     * 全是零时明说「没有新内容」：用户导的是刚导出的同一份文件时，不该看起来像失败了。
     */
    fun describe(sections: List<Section>): String {
        // 一条都没进、全被跳过：多半是文本词表的列写反了。「已导入：跳过 N」读起来像成功了
        val imported = sections.sumOf { it.added + it.updated + it.unchanged }
        val skipped = sections.sumOf { it.skipped }
        if (imported == 0L && skipped > 0) {
            return "没有导入任何词条：$skipped 行认不出。文本词表每行写「编码<Tab>词」"
        }
        val parts = sections.mapNotNull { s ->
            val label = SECTION_LABELS[s.key] ?: s.key
            val counts = buildList {
                // 个人词分新增 / 更新；其余段只有一个导入条数，写「N 条」
                if (s.added > 0) add(if (s.key == "userWords") "新增 ${s.added}" else " ${s.added} 条")
                if (s.updated > 0) add("更新 ${s.updated}")
                if (s.skipped > 0) add("跳过 ${s.skipped}")
            }
            if (counts.isEmpty()) null else "$label${counts.joinToString("、")}"
        }
        return if (parts.isEmpty()) "导入完成，没有新内容" else "已导入：" + parts.joinToString("；")
    }
}
