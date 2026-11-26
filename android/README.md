# 困困输入法 · Android 预览版

Android 手机键盘与 Windows 版放在同一个仓库，使用同一份 Rust 拼音、双拼、英语核心和词库。Android 界面与系统输入接口独立位于本目录，电脑版的 TSF、候选窗口和设置程序保持原来的工程边界。

这是 `0.1.0-preview.1` 试用版，尚未发布稳定版。最低 Android 8.0（API 26）；手机包面向 ARM64，CI 同时构建 x86_64 用于模拟器验证。

## 首版范围

- 离线全拼、小鹤等双拼方案、英语补全；共享仓库现有词库，包括产品自己的英语主词表。
- 26 键字母布局、九宫数字键盘、符号与表情、剪贴板、候选横滑及展开。
- 浅色与深色、单手、按键振动、高度调整、长按与上滑符号。
- 按输入框类型处理密码、数字、回车动作和禁止个性化学习。
- 字母页默认采用「123 · 逗句号合一 · 宽空格 · 中英 · 回车」底行，字母角标默认隐藏；候选与键盘共用浅灰背景、白色圆角键、蓝色回车。

布局方向参考[豆包输入法官方界面图](https://play.google.com/store/apps/details?id=com.bytedance.android.doubaoime&hl=zh-CN)，使用困困自己的图标和源码。未包含豆包的程序、图标、语音模型或服务。此版没有九键拼音、内置语音识别或桌面手机自动同步；九宫数字页只输入数字。

## 安装与试用

1. 下载 Android 工作流产生的预览 APK，在手机上安装。
2. 打开「困困输入法」，按引导在系统中启用并选择它。
3. 首次启动需要在本机准备词库，候选栏会显示准备状态，完成后输入 `nihao` 并选「你好」。
4. 调试包使用当前构建环境生成的测试证书，只用于预览。不同云端构建的证书可能不同，不能保证互相覆盖安装；更换前先导出个人词库。正式版签名需要另行管理，密钥不存入源码仓库。

本应用不申请网络权限。设置中的项目主页、反馈和版本下载由用户点击后交给浏览器打开。拼音候选、输入记录和学习数据在本机处理；其他系统输入法和应用的行为各自独立。

## 一个仓库，两个应用

| 内容 | 所在位置 | 变化时的检查 |
|---|---|---|
| 共享引擎、方案、词库 | `../WindInput/` | Windows 与 Android |
| Windows 候选窗、TSF、桌面设置 | `../wind-ui-rust/`、`../wind-setting/`、`../WindInput/wind_tsf/` | Windows；共享目录改动保守检查两端 |
| Android 系统接口、键盘、设置 | `app/` | Android |
| Android 与 Rust 的薄接口 | `rust/wind-android/` | Android |
| 手机布局和默认配置差异 | `assets/` | Android |

APK 的应用标识是 `io.github.shuimowang.kunkunime`，版本号与 Windows 独立。内部 Kotlin 命名空间保留上游 `com.windinput.ime`，不影响 Android 安装身份。两个平台没有共同的运行时窗口、安装目录或进程；共享源码改动仍须通过两端检查。

构建时 `scripts/prepare-assets.py` 将共享数据复制到 APK 的生成目录。默认配置依次合并 `WindInput/data/config.toml`、困困产品的 `WindInput/data_custom/config.toml`、`assets/engine-overrides.toml`。生成结果不提交 Git，手机键盘设置保存在独立文件，避免让手机的按键布局覆盖桌面设置。UniFFI 的 Kotlin 绑定也在同一次 Rust 构建中生成，不维护独立副本；Kotlin 编译和设备测试检查它与原生库的一致性。

## 云端验证与构建

GitHub 工作流位于 [`../.github/workflows/android.yml`](../.github/workflows/android.yml)，使用同一提交中的核心，不自动拉取上游的最新版。它验证真实拼音/英语核心、双架构编译、Kotlin 单测以及 Android 模拟器输入流程，并提供 APK 和诊断产物。

本地构建需要 Python 3.11+、JDK 21、Android SDK `platforms;android-37.0` / `build-tools;37.0.0`、NDK `28.2.13676358`、Rust 和 cargo-ndk。依赖版本固定在 Gradle 与 Cargo 锁文件中。

```bash
cd android
python3 scripts/prepare-assets.py
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --locked
scripts/build-rust.sh release
./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon
```

Windows 开发机可用 `scripts/build-rust.ps1`，并配置 `ANDROID_NDK_HOME`。缺少 ARM64 Rust 核心时 Gradle 会拒绝打包，避免产生只有模拟候选的安装包。运行时初始化失败也会明确提示。

当前核心的私有路径为 `files/rust/config/KunKunIME`（核心设置）、`files/rust/data/KunKunIME`（缓存/日志），用户词库留在 `files/rust` 下，手机键盘配置在 `files/mobile`。它们均位于 Android 应用沙箱中。词库导入导出需通过应用界面；共享文件格式不代表已实现跨设备同步。

## 开源来源

Android 外壳基于 [huanfeng/WindInputAndroid](https://github.com/huanfeng/WindInputAndroid) 提交 `afd41a392b2e5029c27393389ec016e66d20cfbf`，包括 Kotlin 键盘、设置和 UniFFI 绑定。该部分保留 [MPL-2.0](LICENSE)；与本仓 GPL-3.0-or-later 核心组合的 APK 按 GPL 条件发布，第三方数据各自保留许可。改动源码与许可随本仓提供。

其他实现参考：

- [Fcitx5 Android](https://github.com/fcitx5-android/fcitx5-android)：系统输入框、预编辑和候选交互。
- [同文 Trime](https://github.com/osfans/trime)：中文输入方案与候选流程。
- [HeliBoard](https://github.com/HeliBorg/HeliBoard)：触屏键盘、输入框类型和按键交互。

来源和修改范围见根目录 [ORIGINS.md](../ORIGINS.md)；共享词库来源见 [第三方材料](../licenses/THIRD-PARTY.md)。
