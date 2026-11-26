package com.windinput.ime.keyboard

import android.content.res.AssetManager
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import androidx.core.graphics.PathParser
import java.io.File

/**
 * 图标源：语义名（`text_edit`）→ 可画的图标。
 *
 * 查找顺序（先到先得，同名整体替代，与数据文件的「用户目录覆盖出厂」同一套规矩）：
 *   1. [init] 传入的覆盖目录，按给定顺序——用户自定义放 `<用户根>/mobile/icons/`，
 *      日后主题包挂自己的 `icons/` 目录也走这里，调用方与绘制代码都不用改；
 *   2. 内置 `assets/data/icons/<名>.svg`（Lucide，见同目录 README.md）。
 *
 * 覆盖文件解析不了就退回内置，不让一个坏文件把图标画没。
 */
object IconSet {
    private const val TAG = "IconSet"
    private const val BUNDLED_DIR = "data/icons"

    /** 解析好、路径也建好的图标；路径按 viewBox 坐标，画时整体缩放 */
    class Loaded(val icon: SvgIcon, val paths: List<Path>)

    private var assets: AssetManager? = null
    private var overlayDirs: List<File> = emptyList()
    private val cache = HashMap<String, Loaded?>()

    /** 服务启动与换主题 / 改了覆盖目录后调用；清缓存，下一次取图标重新找 */
    @Synchronized
    fun init(assets: AssetManager, overlayDirs: List<File> = emptyList()) {
        this.assets = assets
        this.overlayDirs = overlayDirs
        cache.clear()
    }

    @Synchronized
    fun get(name: String): Loaded? {
        if (cache.containsKey(name)) return cache[name]
        return load(name).also { cache[name] = it }
    }

    private fun load(name: String): Loaded? {
        for (dir in overlayDirs) {
            val f = File(dir, "$name.svg")
            if (!f.isFile) continue
            val loaded = runCatching { build(SvgIconParser.parse(f.readText())) }.getOrNull()
            if (loaded != null) return loaded
            Log.w(TAG, "覆盖图标 ${f.path} 解析不了，退回内置")
        }
        val am = assets ?: return null
        return runCatching {
            build(SvgIconParser.parse(am.open("$BUNDLED_DIR/$name.svg").bufferedReader().use { it.readText() }))
        }.onFailure { Log.w(TAG, "内置图标 $name 读不了：${it.message}") }.getOrNull()
    }

    private fun build(icon: SvgIcon?): Loaded? {
        icon ?: return null
        val paths = icon.shapes.mapNotNull { runCatching { toPath(it) }.getOrNull() }
        return if (paths.isEmpty()) null else Loaded(icon, paths)
    }

    private fun toPath(shape: SvgShape): Path = when (shape) {
        is SvgShape.PathData -> PathParser.createPathFromPathData(shape.d)
        is SvgShape.Circle -> Path().apply { addCircle(shape.cx, shape.cy, shape.r, Path.Direction.CW) }
        is SvgShape.Ellipse -> Path().apply {
            addOval(RectF(shape.cx - shape.rx, shape.cy - shape.ry, shape.cx + shape.rx, shape.cy + shape.ry), Path.Direction.CW)
        }
        is SvgShape.Line -> Path().apply {
            moveTo(shape.x1, shape.y1)
            lineTo(shape.x2, shape.y2)
        }
        is SvgShape.Poly -> Path().apply {
            moveTo(shape.points[0], shape.points[1])
            for (i in 2 until shape.points.size - 1 step 2) lineTo(shape.points[i], shape.points[i + 1])
            if (shape.closed) close()
        }
        is SvgShape.Rect -> Path().apply {
            val r = RectF(shape.x, shape.y, shape.x + shape.w, shape.y + shape.h)
            if (shape.rx > 0f || shape.ry > 0f) addRoundRect(r, shape.rx, shape.ry, Path.Direction.CW) else addRect(r, Path.Direction.CW)
        }
    }
}
