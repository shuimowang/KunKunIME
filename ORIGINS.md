# 源码与资源来源

困困输入法是从清风输入法（WindInput）及其配套项目派生的非官方 Windows 输入法，与原项目及其作者无隶属关系。本地起点包括此前完成的拼音优化、词库批量管理和王者荣耀人名词库。

| 源码目录 | 上游与基线版本 | 许可文件 |
|---|---|---|
| WindInput | [huanfeng/WindInput](https://github.com/huanfeng/WindInput) · 0.132.0 · `10a89381c254621a4376d8dc5a029735d810035e` | WindInput/LICENSE |
| wind-setting | [huanfeng/wind-setting](https://github.com/huanfeng/wind-setting) · `9bca68ce55839b570c9a3c3b521e20c581f7a262` | wind-setting/LICENSE |
| wind-ui-rust | [huanfeng/wind-ui-rust](https://github.com/huanfeng/wind-ui-rust) · `b6a4933b6024ea77e74644ba406dfb2bf87684f2` | wind-ui-rust/LICENSE-MIT、LICENSE-APACHE |
| android | [huanfeng/WindInputAndroid](https://github.com/huanfeng/WindInputAndroid) · `afd41a392b2e5029c27393389ec016e66d20cfbf` | android/LICENSE（MPL-2.0）；与 GPL 核心组合的 APK 按 GPL 条件发布 |

本分支保留上游版权和许可声明。随困困公开发行的数据按 [第三方材料清单](licenses/THIRD-PARTY.md) 逐项标注；[原项目资源说明](WindInput/NOTICE.md) 提供历史背景。独立英文拼写表从 CMUdict 生成，补全优先级来自本项目统计和编辑，重建命令为 `python scripts/generate-english.py`。困困的产品图标位于 assets/kunkun.svg，Windows 图标和语言栏使用同一套新图形。

此版把设置、词库、输入服务及扩展宿主合入同一 Windows 可执行文件，注册独立的 TSF 身份，并使用独立的数据目录和进程间通信。拼音解码、候选排序、词库格式和若干内部 crate 延续上游及此项目此前的修改，是当前版本的技术基础。

Android 配套版保留上游外壳的版权及许可，改为引用本仓现有核心与词库，并适配本仓 0.132 系列核心的数据目录接口。应用身份、产品图标、更新链接、手机默认布局及 CI 在本仓维护；手机词库在构建时从共享目录生成，未另建一份长期维护的副本。布局方向参考豆包输入法官方截图，未分发其代码、图像资源或模型。Android 与 Windows 的用户数据默认均在各自设备本地，当前没有自动同步服务。

