# 困困输入法 · KunKunIME

困困输入法是一款面向 Windows 的开源拼音与英语输入法。默认使用全拼，也提供小鹤双拼和英文补全。设置与词库管理共用一个窗口和固定的左侧导航。点“词库管理”后，可以随时从左侧切回其他设置页。Quicker 可直接打开拼音主词库，窗口已经打开时会切到相应页面。后台输入服务与管理界面共用一个 `KunKunIME.exe`。

## 安装与使用

**Android 手机版已合入主分支。** 手机端位于 [android/](android/README.md)，与 Windows 共用输入核心和词库，使用独立的界面、应用标识、版本和构建流程。Android `0.1.1` 加入可按需下载的 **SenseVoiceSmall INT8 离线语音包**，最低 Android 16、ARM64。进入「语音输入」下载并启用后，长按空格说话、松手在手机本地识别，无需手机提供系统识别服务；原有系统语音选择继续保留。另有独立保存的常用语，以及在「关于 → 应用更新」主动检查、下载并安装新版。常用语可新增、编辑、移到顶部或删除，点选只填入输入框。手机发行采用独立的 `android-v…` 标签；下列安装步骤适用于 Windows，Android 的下载、升级、语音设置与签名说明见其目录。

1. 从 [GitHub Releases](https://github.com/shuimowang/KunKunIME/releases) 下载 Windows x64 压缩包，将**整个文件夹**解压到长期使用的位置。
2. 双击 **KunKunIME.exe**，点击 **注册输入法** 并确认 Windows 管理员授权。
3. 按 **Win + 空格** 选择 **困困输入法**。如果希望每次登录 Windows 后默认使用困困，到 **设置 → 输入规则 → 默认状态 → Windows 登录默认输入法** 点 **设为默认输入法**。同一处可恢复 Windows 自动选择。
4. 平时调整输入法设置会自动保存，可以直接关闭窗口继续打字。

在 **设置 → 关于** 可以检查 Windows 正式版更新，或打开卸载向导。卸载时可以选择保留个人词库和设置，也可以一并清除。已注册的安装同时提供 Windows「已安装的应用」卸载入口。

| 我想输入什么 | 怎么用 |
|---|---|
| 中文词语和句子 | 在全拼模式连续打拼音，用空格选首选，或按数字选其他候选。也可以在设置中改用小鹤双拼。 |
| 原样输入英文、代码或网址 | 单点左 Shift 或右 Shift 切换中英文。切到英文直出后，大写、小写和标点由当前应用接收。 |
| 需要英文候选和补全 | 在输入方案中选 **英文补全**。默认快捷键 **Ctrl + Shift + E** 可在已启用的方案之间循环。 |
| 写中文时夹一个英文单词 | 在拼音状态直接输入英文，符合条件的英文词或前缀补全会进入候选列表。 |
| 找生僻字 | 按 **Ctrl + Shift + ;** 进入生僻字模式，再输入拼音选字。 |

内置下一词联想和可单独开关的 **王者荣耀人名** 词库（139 条）。个人词库支持批量导入、勾选多项后一次删除；同码下未勾选的候选会保留。使用 Quicker 时，可安装[「困困输入法」动作，查看图文使用说明](quicker/README.md)。也可以用 `KunKunIME.exe --page dict --schema pinyin --type user-dict` 创建直达拼音主词库的快捷方式。

### 手动添加输入补全

打开 **词库管理 → 输入补全**，选择「邮箱后缀」或「常用网址」，就能在同一页开关输入模式、添加、编辑或删除个人条目。也可以从 **输入规则 → 网址/邮箱输入** 一键到达对应页。快捷文本仍在词库管理里添加。手动添加网址或快捷文本时，自动记录历史的开关保持原来的设置。

| 分类 | 填什么 | 输入时怎样调用 |
|---|---|---|
| 邮箱后缀 | 常用域名已预置。添加自己的域名时可粘贴完整邮箱，如 `name@example.com`，只保存 `example.com`。 | 开启邮箱输入模式后，在拼音中文状态下连续打 `demo@gm`，按空格可选 `demo@gmail.com`。 |
| 常用网址 | 常用网址，例如 `https://example.com/docs` | 开启网址输入模式后，在拼音中文状态下打 `www.` 或 `http` 等触发前缀。 |
| 快捷输入历史 | 想补全的内容，例如「请查收附件」 | 在「输入规则 → 快捷输入」设置触发键，按该键后输入 `qingcha` 选整句。英文内容可按原文开头补全。 |

个人设置和词库默认存储在 `%APPDATA%\KunKunIME`，缓存和日志在 `%LOCALAPPDATA%\KunKunIME`。发行目录里的两个 TSF DLL 分别服务 64 位和 32 位 Windows 应用。注册后请保持程序目录稳定。需要撤销系统注册时，见[卸载说明](scripts/卸载Windows输入法注册.md)。

## 用 Agent 管理词库和设置

从 v0.1.7 起，Codex 等支持本机 MCP 的客户端可以通过 `KunKunIME.exe mcp` 搜索个人词库、批量加词、按候选精确删除，以及查询和更改常用设置。例如可以让 Agent 给一本小说建分库，或把每页候选数改成 8 个。在 v0.1.8 的设置左栏打开 **AI 助手 MCP**，可以复制适用于本机的命令或配置。“关于”页和设置搜索也能找到入口。[接入配置与工具清单](docs/MCP.md)

## 从源码构建

使用 Windows x64、PowerShell 7、Python 3.11 或更新版本、Rust stable（MSVC 工具链）和 Visual Studio C++ 桌面开发工具（含 CMake、x86 与 x64 工具）。Python 用于随包收集第三方许可。在仓库根目录执行：

```powershell
pwsh -File scripts/build-windows.ps1 -Profile release
```

默认产物在 `dist/困困输入法/`。已有 Cargo 依赖缓存时可加 `-Offline`。注册构建产物后，可运行 `pwsh -File scripts/test-typing.ps1 -Package "dist/困困输入法"`，在专用编辑框中验证 Windows 实际上屏；测试会短暂使用前台键盘。

### Windows 发行附件

生成正式发行包及对应的 Cargo 依赖源码归档：

```powershell
pwsh -File scripts/build-windows.ps1 -Profile release -ReleaseDir dist/releases
```

该命令在 `dist/releases/` 输出以下文件，版本号来自 `WindInput/docs/VERSION`：

- `KunKunIME-<版本>-Windows-x64.zip`：完整程序目录，包含主程序、64 位与 32 位 TSF DLL、词库、使用说明和许可文件。
- `KunKunIME-<版本>-Cargo-sources.zip`：同一锁定依赖图中的 crates.io 源码包、`Cargo.lock` 和许可索引；生成时逐项核对源码包 SHA-256。
- `SHA256SUMS.txt`：上述两个压缩包的 SHA-256 校验值。

发布前保持 `WindInput/docs/VERSION`、`wind-setting/Cargo.toml`、`WindInput/data_custom/custom.toml` 中的版本，以及两份 Cargo 锁文件中 `wind_setting` 的版本一致。脚本会检查发行包内 EXE 和两个 DLL 的产品版本；`-ReleaseDir` 只接受 `release` 编译配置。新 Windows 发行沿用 `v<版本>` 标签，例如 `v0.1.10`，将这些附件上传到对应标签的 GitHub Release；本仓源码由该标签的源码归档提供，Cargo 依赖源码在单独的附件中。

Cargo 的 `release` 配置决定二进制的优化方式，GitHub Release 提供版本说明和下载附件。Windows 工作流检测到产品版本值变化时，改用 `release` 配置构建和运行回归；主分支的 Windows 与设置检查都成功后，自动创建对应版本的 Release，上传并核对这三个附件，再公开发布。PR 只做检查，不发布。新版本有发行说明、但尚未建立标签时，后续修复提交继续使用发行配置；标签建立后，普通代码提交恢复 `dev-variant`，不重复发行同一个版本。

手动运行 Windows 工作流并勾选 `publish_release` 可以请求当前版本的发行构建。上传中断时只重试发布 job，可复用原构建的附件；已有公开版本不允许被新构建覆盖。发行标签必须指向实际通过检查的提交，版本对应的说明保存在 `releases/v<版本>.md`。

Android 工作流分别验证 debug 的初始化暂存流程和非 debug release 的真实输入流程，成功后为新版本准备独立的发行草稿。`android-v0.1.0` 等稳定版本创建正式版草稿，带 `-preview.…` 等后缀的版本仍标为预览版；Android 草稿不自动替换仓库的 Latest。维护者按 [Android 签名说明](android/README.md#分发签名)使用固定私钥签名，上传手机 APK、对应依赖源码和最终校验文件，再公开草稿；CI 的临时证书 APK 不自动成为用户下载包。私钥和恢复密码不进入仓库或工作流产物。

| 开发入口 | 源码 |
|---|---|
| Windows 程序入口 | [程序启动与模式分派](WindInput/wind_input/apps/service/src/main.rs) |
| 拼音、英语与候选排序 | [输入引擎](WindInput/wind_input/crates/wind-engine)、[产品默认配置](WindInput/data_custom/config.toml) |
| 词库管理与设置 | [设置界面](wind-setting/src/lib.rs) |
| Windows 按键与上屏 | [TSF 前端](WindInput/wind_tsf) |

本项目从清风输入法（WindInput）及相关组件独立分支开发，沿用开源拼音引擎的基础，在本仓维护。应用源码使用 **GPL-3.0-or-later**；词库、模型及其他第三方组件按各自许可使用。详见 [LICENSE](LICENSE) 和 [来源与许可说明](ORIGINS.md)。

