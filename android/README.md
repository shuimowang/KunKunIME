# 困困输入法 · Android 预览版

Android 手机键盘与 Windows 版放在同一个仓库，使用同一份 Rust 拼音、双拼、英语核心和词库。Android 界面与系统输入接口独立位于本目录，电脑版的 TSF、候选窗口和设置程序保持原来的工程边界。

这是 `0.1.0-preview.4` 试用版，分发包使用**非调试 release 构建**，发布渠道仍为预览版。按个人使用需求，最低要求为 **Android 16（API 36）**，不再维护旧系统兼容分支。`targetSdk` 保持 36，使用 Android 37.0 SDK 编译；最低安装版本与目标平台版本分别管理。手机包面向 ARM64，CI 另行构建 x86_64 用于模拟器验证。两个架构分别打包，手机无需下载模拟器的原生库。

## 当前范围

- 离线全拼、小鹤等双拼方案、英语补全；共享仓库现有词库，包括产品自己的英语主词表。
- 26 键字母布局、九宫数字键盘、符号与表情、剪贴板、候选横滑及展开。
- 浅色与深色、单手、按键振动、高度调整、长按与上滑符号。
- 按输入框类型处理密码、数字、回车动作和禁止个性化学习。
- 字母页默认采用「123 · 逗句号合一 · 宽空格 · 中英 · 回车」底行，字母角标默认隐藏；候选与键盘共用浅灰背景、白色圆角键、蓝色强调色。

### preview.4 的发行构建

- 使用 `versionCode = 4` 的非调试 release APK，保留 R8 代码与资源压缩；应用标识、共享核心和 API 36 基线不变。
- CI 分别运行 debug 的 6 项控件测试和启动队列烟测，再安装实际 release APK，验证真实中文候选、退格、英语、密码、数字与键盘收起重开。release 没有启动门闩接口，其测试不声称覆盖人为暂停初始化的场景。
- 校验实际 APK 的版本、非调试标志、非 test-only 标志、原生架构及两种架构共有的代码/资源/词库内容；只将验证通过的 release APK 输出为发行工件。
- 同时提供与两个 Android 目标的锁定依赖匹配的 `Cargo-sources.zip`，包含原始 crate 归档、`Cargo.lock`、依赖索引和许可证文本。项目本身的源码对应同一发布标签。

### preview.3 已包含的改进

- 直接使用现代系统的窗口 Insets、输入法子类型、剪贴板敏感标记和振动接口，删除旧系统版本判断；保留不同手机的振动能力检查、编辑器能力回退及系统按钮避让。
- 对实际分发和设备测试使用的预览包启用 R8 代码与资源压缩，保留必要的原生接口、反射入口和测试入口；CI 同时保存本次映射，便于诊断。它仍是可调试的预览版。
- 键盘配置按文件分开同步，避免无关配置解析占用同一把锁；每次显示键盘时，将 22 个常用设置合并为一次快照读取，减少逐项重复检查配置文件。
- 在现有后台任务中预载内置 SVG 图标；读盘、解析不占用图标缓存锁，主题变化后不发布过期结果。首次绘制早于预载时仍保留正常加载路径。

### preview.2 已包含的改进

- 词库准备期间暂存中文按键，准备完成后按顺序处理；候选栏显示暂存数量。离开当前输入框时清掉未完成的输入，避免送进另一个应用或密码框。
- 密码、PIN、数字、电话和日期字段直接输入，不等待中文核心。
- 首次启用按「启用 → 选择 → 试用」逐步引导；未完成前一步时会显示对应说明。
- 浅色键盘使用深色系统导航图标，回车、确定和数字页完成键使用蓝色；按下时保留清楚的文字对比。底部同时避让系统输入法按钮的实际区域，避免最后一排按键与切换/返回按钮重叠。
- ARM64 手机包与 x86_64 模拟器包分开；构建检查原生库架构及两包的代码、资源、词库一致性。
- 分发包开始使用固定的私有签名，公开仓库只保存证书。具体迁移和构建方式见下文。

布局方向参考[豆包输入法官方界面图](https://play.google.com/store/apps/details?id=com.bytedance.android.doubaoime&hl=zh-CN)，使用困困自己的图标和源码。未包含豆包的程序、图标、语音模型或服务。此版没有九键拼音、内置语音识别或桌面手机自动同步；九宫数字页只输入数字。

## 安装与试用

1. 下载维护者分发的 ARM64 已签名预览 APK，在手机上安装。普通 ARM64 手机不使用 x86_64 模拟器包。
2. 打开「困困输入法」，按引导在系统中启用并选择它。
3. 首次启动需要在本机准备词库，候选栏会显示准备状态及暂存按键。可输入 `nihao`，待出现候选后选「你好」。

**从 preview.1 迁移：** 首版使用临时 CI 证书，与 preview.2 的固定签名不同，因此不能直接覆盖。已安装首版时，先在设置中导出个人词库，再卸载旧版并安装新版，之后导回词库。卸载会清除应用设置和未导出的本机数据。

**从已签名 preview.2 / preview.3 更新到 preview.4：** 同一应用标识、同一固定签名、递增的 `versionCode` 支持覆盖安装并保留设置与词库。GitHub Releases 的分发包使用固定签名；Actions 的原始 release APK 使用临时测试证书，必须先完成固定私钥重签，才能作为已有安装的更新包。重签过程核对所有应用内容保持不变。

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

| 架构 | Gradle 输出 | Actions 产物 |
|---|---|---|
| ARM64 手机 | `app/build/outputs/apk/release/KunKunIME-Android-0.1.0-preview.4-arm64-v8a.apk` | `KunKunIME-Android-arm64-release` |
| x86_64 模拟器 | `app/build/outputs/apk/release/KunKunIME-Android-0.1.0-preview.4-x86_64.apk` | `KunKunIME-Android-x86_64-release` |
| 发行元数据与 Rust 依赖源码 | `app/build/release-support/release-info.json`、`Cargo-sources.zip` | `KunKunIME-Android-release-support` |

设备验证分成两轮，均使用带真实 x86_64 核心的压缩 APK 和系统输入连接。debug 轮先运行 6 项无障碍和多指触控用例，再短暂暂停真实核心的交接，验证早输、退格、换框取消与后续中文上屏；门闩默认不启用，也没有外部触发入口。release 轮清空专用模拟器中的应用数据，安装实际发行构建，确认其不可调试，等待正常初始化后通过触屏按键验证 `nihaox` 加退格产生“你好”，以及英语空格、密码、数字、收起重开和系统导航按钮避让。release 轮不使用门闩、不替换核心，也不通过测试直接调用原生按键接口。两轮报告分别保存在 `app/build/smoke-results/debug` 与 `release`。

Gradle 默认测试 debug；`-PimeTestBuildType=release` 选择 release 的测试 APK，`-PciReleaseSigning=true` 只让 release 临时使用当前环境的测试证书。该参数不改变 release 的源码、优化规则或调试标志。release 不保留 debug 门闩与控件直调测试入口；两种构建保留独立测试 APK 所需的共享 Kotlin/AndroidX 运行时 ABI，避免应用压缩先移除测试框架需要的方法。输入测试台在 release 中不对其他应用导出，仍可在应用内部和同 UID 仪器测试中使用。

`scripts/verify-apks.py` 从 APK 清单提取真实版本，与 Gradle `output-metadata.json` 交叉核对后输出 `release-info.json`。其中 `version_name`、`version_code`、`git_commit` 用于确定对应提交与发布标签；`apks` 和 `cargo_sources` 记录文件名与 SHA-256。源码归档复用仓库的 `scripts/collect-cargo-notices.py --crate-sources`，对 ARM64 与 x86_64 的 `wind-android` 依赖图取并集，逐个核对原始 crate 与 Android 锁文件中的校验和。

本地构建需要 Python 3.11+、JDK 21、Android SDK `platforms;android-37.0` / `build-tools;37.0.0`、NDK `28.2.13676358`、Rust 和 cargo-ndk。依赖版本固定在 Gradle 配置、版本目录与 Cargo 锁文件中。

构建工具保持 AGP `8.13.2`、Kotlin `2.3.21` 和 Gradle `8.14.3`。依赖的 [Miuix `0.9.3` 使用 Kotlin `2.4.0` 编译](https://github.com/compose-miuix-ui/miuix/blob/v0.9.3/gradle/libs.versions.toml)，高于 AGP 内置 R8 `8.13.19` 支持的元数据版本。因此在 [`settings.gradle.kts`](settings.gradle.kts) 中，按 [R8 官方覆盖方式](https://r8.googlesource.com/r8/+/refs/tags/9.1.29/README.md#replacing-r8-in-android-gradle-plugin) 从官方构建归档的 Maven 仓库固定使用 [R8 `9.1.29`](https://storage.googleapis.com/r8-releases/raw/com/android/tools/r8/9.1.29/r8-9.1.29.pom)，满足 [Kotlin 2.4 的兼容要求](https://developer.android.com/build/kotlin-support)。此版本未发布到 Google Maven；归档仓库限定只提供 `com.android.tools:r8`。主应用与测试 APK 使用同一工具版本；CI 保存的 `outputs/mapping` 可核对实际压缩器版本。

```bash
cd android
python3 scripts/prepare-assets.py
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --locked
scripts/build-rust.sh release
./gradlew :app:assembleRelease :app:testReleaseUnitTest --no-daemon
```

未配置个人签名时，上述 release 输出未签名。复现 CI 的两套仪器测试构建和专用模拟器验证：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
./gradlew :app:testReleaseUnitTest :app:assembleRelease :app:assembleReleaseAndroidTest \
  -PimeTestBuildType=release -PciReleaseSigning=true --no-daemon
bash scripts/smoke-test.sh debug
bash scripts/smoke-test.sh release
```

Windows 开发机可用 `scripts/build-rust.ps1`，并配置 `ANDROID_NDK_HOME`。缺少 ARM64 Rust 核心时 Gradle 会拒绝打包，避免产生只有模拟候选的安装包。运行时初始化失败也会明确提示。

当前核心的私有路径为 `files/rust/config/KunKunIME`（核心设置）、`files/rust/data/KunKunIME`（缓存/日志），用户词库留在 `files/rust` 下，手机键盘配置在 `files/mobile`。它们均位于 Android 应用沙箱中。词库导入导出需通过应用界面；共享文件格式不代表已实现跨设备同步。

## 分发签名

公开证书为 [`signing/kunkun-android-cert.pem`](signing/kunkun-android-cert.pem)，证书 SHA-256：

```text
63ca51b0c3bfedd52814768dc758e56e549355038cd52d3c350b06b1f6ceb935
```

私钥和恢复密码单独备份，均不存入仓库或 Actions 产物。CI 验证通过后，对已下载并核对产物摘要的手机包执行：

```bash
python3 scripts/sign-apk.py \
  --input /path/to/ci/KunKunIME-Android-0.1.0-preview.4-arm64-v8a.apk \
  --output /path/to/distribution/KunKunIME-Android-0.1.0-preview.4-arm64-v8a.apk \
  --build-tools /path/to/android-sdk/build-tools/37.0.0 \
  --keystore /private/kunkun-android.p12 \
  --password-file /private/keystore-password.txt
```

脚本使用 Android 官方 `zipalign` 和 `apksigner`，保留 16 KiB 原生库对齐，验证签名与公开证书一致，并逐项核对代码、清单、资源和词库没有变化。密码由工具从私有文件读取，不写进命令行或输出；现有输入 APK 不会被覆盖。PKCS12 的密钥密码与密钥库密码保持一致。

也可按 Gradle 中的 `signing.*` 配置在私有构建环境直接签名；密钥来源与保管要求相同。遗失签名密钥会影响后续覆盖更新，生成另一把同名密钥无法替代原密钥。

## 开源来源

Android 外壳基于 [huanfeng/WindInputAndroid](https://github.com/huanfeng/WindInputAndroid) 提交 `afd41a392b2e5029c27393389ec016e66d20cfbf`，包括 Kotlin 键盘、设置和 UniFFI 绑定。该部分保留 [MPL-2.0](LICENSE)；与本仓 GPL-3.0-or-later 核心组合的 APK 按 GPL 条件发布，第三方数据各自保留许可。改动源码与许可随本仓提供。

其他实现参考：

- [Fcitx5 Android](https://github.com/fcitx5-android/fcitx5-android)：系统输入框、预编辑和候选交互。
- [同文 Trime](https://github.com/osfans/trime)：中文输入方案与候选流程。
- [HeliBoard](https://github.com/HeliBorg/HeliBoard)：触屏键盘、输入框类型和按键交互。

来源和修改范围见根目录 [ORIGINS.md](../ORIGINS.md)；共享词库来源见 [第三方材料](../licenses/THIRD-PARTY.md)。
