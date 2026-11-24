# 困困输入法 · KunKunIME

困困输入法是一款面向 Windows 的开源拼音与英语输入法。默认使用全拼，也提供小鹤双拼和英文补全。设置与词库管理共用一个窗口和固定的左侧导航。点“词库管理”后，可以随时从左侧切回其他设置页。Quicker 可直接打开拼音主词库，窗口已经打开时会切到相应页面。后台输入服务与管理界面共用一个 `KunKunIME.exe`。

## 安装与使用

1. 从 [GitHub Releases](https://github.com/shuimowang/KunKunIME/releases) 下载 Windows x64 压缩包，将**整个文件夹**解压到长期使用的位置。
2. 双击 **KunKunIME.exe**，点击 **注册输入法** 并确认 Windows 管理员授权。
3. 按 **Win + 空格** 选择 **困困输入法**。平时调整设置会自动保存，可以直接关闭窗口继续打字。

| 我想输入什么 | 怎么用 |
|---|---|
| 中文词语和句子 | 在全拼模式连续打拼音，用空格选首选，或按数字选其他候选。也可以在设置中改用小鹤双拼。 |
| 原样输入英文、代码或网址 | 单点左 Shift 或右 Shift 切换中英文。切到英文直出后，大写、小写和标点由当前应用接收。 |
| 需要英文候选和补全 | 在输入方案中选 **英文补全**。默认快捷键 **Ctrl + Shift + E** 可在已启用的方案之间循环。 |
| 写中文时夹一个英文单词 | 在拼音状态直接输入英文，符合条件的英文词或前缀补全会进入候选列表。 |
| 找生僻字 | 按 **Ctrl + Shift + ;** 进入生僻字模式，再输入拼音选字。 |

内置下一词联想和可单独开关的 **王者荣耀人名** 词库（139 条）。个人词库支持批量导入、勾选多项后一次删除；同码下未勾选的候选会保留。使用 Quicker 时，可安装[困困词库管理动作，查看图文使用说明](quicker/README.md)。也可以用 `KunKunIME.exe --page dict --schema pinyin --type user-dict` 创建直达拼音主词库的快捷方式。

### 手动添加输入补全

打开 **词库管理 → 输入补全**，选择「邮箱后缀」或「常用网址」，就能在同一页开关输入模式、添加、编辑或删除个人条目。也可以从 **输入规则 → 网址/邮箱输入** 一键到达对应页。快捷文本仍在词库管理里添加。手动添加网址或快捷文本时，自动记录历史的开关保持原来的设置。

| 分类 | 填什么 | 输入时怎样调用 |
|---|---|---|
| 邮箱后缀 | 常用域名已预置。添加自己的域名时可粘贴完整邮箱，如 `name@example.com`，只保存 `example.com`。 | 开启邮箱输入模式后，在拼音中文状态下连续打 `demo@gm`，按空格可选 `demo@gmail.com`。 |
| 常用网址 | 常用网址，例如 `https://example.com/docs` | 开启网址输入模式后，在拼音中文状态下打 `www.` 或 `http` 等触发前缀。 |
| 快捷输入历史 | 想补全的内容，例如「请查收附件」 | 在「输入规则 → 快捷输入」设置触发键，按该键后输入 `qingcha` 选整句。英文内容可按原文开头补全。 |

个人设置和词库默认存储在 `%APPDATA%\KunKunIME`，缓存和日志在 `%LOCALAPPDATA%\KunKunIME`。发行目录里的两个 TSF DLL 分别服务 64 位和 32 位 Windows 应用。注册后请保持程序目录稳定。需要撤销系统注册时，见[卸载说明](scripts/卸载Windows输入法注册.md)。

## 从源码构建

使用 Windows x64、PowerShell 7、Python 3.11 或更新版本、Rust stable（MSVC 工具链）和 Visual Studio C++ 桌面开发工具（含 CMake、x86 与 x64 工具）。Python 用于随包收集第三方许可。在仓库根目录执行：

```powershell
pwsh -File scripts/build-windows.ps1
```

默认产物在 `dist/困困输入法/`。已有 Cargo 依赖缓存时可加 `-Offline`。注册构建产物后，可运行 `pwsh -File scripts/test-typing.ps1 -Package "dist/困困输入法"`，在专用编辑框中验证 Windows 实际上屏；测试会短暂使用前台键盘。

| 开发入口 | 源码 |
|---|---|
| Windows 程序入口 | [程序启动与模式分派](WindInput/wind_input/apps/service/src/main.rs) |
| 拼音、英语与候选排序 | [输入引擎](WindInput/wind_input/crates/wind-engine)、[产品默认配置](WindInput/data_custom/config.toml) |
| 词库管理与设置 | [设置界面](wind-setting/src/lib.rs) |
| Windows 按键与上屏 | [TSF 前端](WindInput/wind_tsf) |

本项目从清风输入法（WindInput）及相关组件独立分支开发，沿用开源拼音引擎的基础，在本仓维护。应用源码使用 **GPL-3.0-or-later**；词库、模型及其他第三方组件按各自许可使用。详见 [LICENSE](LICENSE) 和 [来源与许可说明](ORIGINS.md)。

