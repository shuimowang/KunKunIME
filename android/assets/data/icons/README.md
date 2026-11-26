# 内置图标

来自 [Lucide](https://lucide.dev)（lucide-static 0.468.0，ISC 许可，全文见 `licenses/ISC.txt`），
SVG 原样放入，只按本输入法的语义名重命名（文件名 = `PanelIcon.Glyph` 的小写名，如 `text_edit.svg`）。

## 覆盖与自定义

用户目录（`filesDir/user/icons/`）下放同名 `.svg` 即整体替代内置图标，主题包日后挂同一机制。
支持的 SVG 子集：根元素 `viewBox`、`stroke-width`；子元素 `path / circle / ellipse / line /
polyline / polygon / rect`（含圆角）。颜色一律取键盘主题的图标色，SVG 里写的颜色会被忽略。
