# 困困输入法 · Android

Android 手机键盘与 Windows 版放在同一个仓库，使用同一份 Rust 拼音、双拼、英语核心和词库。Android 界面与系统输入接口独立位于本目录，电脑版的 TSF、候选窗口和设置程序保持原来的工程边界。

当前版本为 [`0.1.2`](https://github.com/shuimowang/KunKunIME/releases/tag/android-v0.1.2)，分发包使用**非调试 release 构建**。手机需要 **Android 16（API 36）** 或以上版本及 ARM64 处理器。`targetSdk` 为 36，使用 Android 37.0 SDK 编译。CI 另行构建 x86_64 包用于模拟器验证。

## 当前范围

- 离线全拼、小鹤等双拼方案、英语补全；共享仓库现有词库，包括产品自己的英语主词表。
- 26 键字母布局、九宫数字键盘、符号与表情、剪贴板、候选横滑及展开。
- 剪贴板与常用语双页；常用语独立保存，可新增、编辑、移到顶部与删除，点选只填入输入框。
- 浅色与深色、单手、按键振动、高度调整、长按与上滑符号。
- 按输入框类型处理密码、数字、回车动作和禁止个性化学习。
- 长按空格语音输入，松手结束、上滑取消；按需下载 SenseVoiceSmall INT8 语音包后，可在应用内离线识别，不依赖系统语音服务。原系统识别选项保留，可能联网的系统服务仍需明确选择。
- 进入「关于」自动查询最新安卓正式版，结果显示在本页的「应用更新」区域。有新版时可直接下载，校验通过后由系统确认安装。
- 字母页默认采用「123 · 逗句号合一 · 宽空格 · 中英 · 回车」底行，字母角标默认隐藏；候选与键盘共用浅灰背景、白色圆角键、蓝色强调色。

个人词库的搜索结果显示匹配条数。筛选时可逐条删除，清空整个方案的入口只出现在未筛选的完整列表中。「添加词条」的草稿可在旋转屏幕后继续填写。

设置首页的「试一试」可测试普通、搜索、数字与密码输入框。密码场景使用遮罩，在该场景点「完成」或切走会清空密码。重新选择普通场景可继续之前的草稿。试打台不把输入框正文写入系统日志。项目源码可从「关于 → 困困输入法 GitHub 主页」打开。

### 0.1.2 的更新

- 在「关于」直接查看更新结果。0.1.1 可从「关于 → 应用更新 → 检查更新」看到此版和迁移说明。0.1.2 启用新的发行签名，旧版用户需要一次重新安装。
- 调整拼音组合串中的光标定位。移动光标、跨行编辑和滑动退格时按可见字符处理组合表情和扩展汉字。
- 个人词库搜索显示匹配条数，按词条删除时保留同码的其他词。清除搜索后才显示整库清空入口。旋转屏幕可继续编辑未提交的词条。
- 「试一试」的密码框遮罩显示，完成或切换场景时清空密码。试打内容不写入系统日志。「关于」可直接打开本项目的 GitHub 主页。

### 0.1.1：应用内离线语音

- `versionCode = 6`，版本名为 `0.1.1`。沿用相同应用标识和固定证书，可覆盖升级 `0.1.0`，保留设置、个人词库与常用语。
- 集成 sherpa-onnx `v2.0.3`，使用按需下载的 SenseVoiceSmall INT8 `2024-07-17` 模型。中文、英文为主要使用场景；模型还支持粤语、日语和韩语，具体效果需按语种与手机实测。
- 下载、进度、取消、失败重试、校验和删除均在「语音输入」设置内完成。只有点「使用离线语音」才选择该引擎并请求麦克风权限；下载完成不会自动录音，也不会强行改变已有系统语音选择。
- 麦克风音频只保存在内存中，模型加载和识别在后台执行；录音前先等待模型准备完成，松手后处理整句。取消、收起键盘或切换输入框后停止本次录音并丢弃迟到结果，密码框仍禁用语音。
- 单次语音最长 60 秒。长录音可能按不超过 15 秒的片段识别后拼接；分段处上下文不足可能影响文字或标点，不能把支持手机运行等同于所有机型上都有相同速度与准确度。
- 语音包从固定的上游 GitHub 发行附件下载，按固定大小与 SHA-256 校验；位于 `noBackupFilesDir/offline-voice`，不进入系统备份。离线识别不访问网络，也不调用商业云端语音接口。

### 0.1.0 已包含：语音输入、常用语、应用内更新与正式发行

- `versionCode = 5`，版本名为 `0.1.0`，沿用固定证书，可覆盖升级已签名的 preview.2 / preview.3 / preview.4。
- 默认长按空格开始语音输入，短按仍为空格或选首选候选；开始长按前的左右滑动仍用于移动光标。设置中仍可选择长按切换输入法或不执行动作，已有明确设置会保留。
- 录音时显示状态，松手结束、上滑取消。识别草稿不直接上屏，最终结果只输入发起识别的输入框；切换输入框、收起键盘或取消后丢弃迟到结果，密码框禁用语音，无痕模式仅允许设备端识别。
- 首次使用需选择识别方式并授权麦克风；授权完成返回输入框后，再次长按空格才开始录音。设备端识别是默认选择，手机缺少支持的服务或语言模型时会给出提示；只有明确允许后，才会使用可能联网处理声音的系统服务。
- 语音能力使用 Android 的识别服务，不打包第三方语音模型，也不保存录音。自动化检查覆盖手势和识别生命周期；API 36 模拟器没有真实声音输入，不能用这些检查替代具体手机上的识别效果验证。
- 应用内检查更新仅查 `android-vX.Y.Z` 安卓正式版，排除草稿、预览版与 Windows 版本。用户主动检查、确认下载后显示进度并可取消；校验 SHA-256、包名、版本和签名通过后，交由系统确认安装。
- 常用语与剪贴板历史分开持久保存，首次提供 4 条例句；支持新增、编辑、移到顶部和删除。点选只填入当前输入框，不自动发送。

### preview.4 已包含的发行构建

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

布局方向参考[豆包输入法官方界面图](https://play.google.com/store/apps/details?id=com.bytedance.android.doubaoime&hl=zh-CN)，使用困困自己的图标和源码。未包含豆包的程序、图标、语音模型或服务。此版没有九键拼音或桌面手机自动同步；九宫数字页只输入数字。应用内离线语音使用单独下载的 SenseVoice 模型；选择原系统识别方式时，可用性和语言支持仍取决于手机提供的 Android 识别服务。

## 安装与试用

1. 下载维护者分发的 ARM64 已签名 APK，在手机上安装。普通 ARM64 手机不使用 x86_64 模拟器包。
2. 打开「困困输入法」，按引导在系统中启用并选择它。
3. 首次启动需要在本机准备词库，候选栏会显示准备状态及暂存按键。可输入 `nihao`，待出现候选后选「你好」。

**从 0.1.1 或更早版本迁移。** 0.1.2 启用新的发行签名，Android 要求先卸载旧版。在卸载前，按使用的方案分别在词库页面点「导出词库」，文件存到手机的「下载」等公共目录。另存需要的常用语。卸载会清除应用设置、常用语和已下载的语音包。然后安装正式 APK，重新启用键盘，在词库页面点「导入词库」。0.1.1 可从「关于 → 应用更新 → 检查更新」看到这次发行，在浏览器中打开[正式发行页](https://github.com/shuimowang/KunKunIME/releases/tag/android-v0.1.2)即可下载 APK。此后的正式版使用新签名进行覆盖更新。

拼音候选、输入记录和学习数据在本机处理。打开「关于」、手动检查更新、下载新版或离线语音包时，应用会连接 GitHub 及其文件分发服务。键盘日常输入不会触发更新请求。更新检查不上传输入文字、个人词库或录音。项目主页和反馈链接由浏览器打开。应用内离线语音在手机上识别。原有系统语音服务可能联网处理声音，需要用户明确允许。拒绝麦克风权限后仍可用普通键盘输入。

### 在应用内更新

从 0.1.2 起，进入「关于」会自动查询 GitHub。本页的「应用更新」区域会显示结果，也可以手动重查，有新版时点「下载更新」。下载可以取消，APK 保存在应用缓存。应用会核对 SHA-256、包名、版本号、发行签名、系统版本及非调试标志，校验通过后显示「安装更新」。

若系统要求允许「安装未知应用」，按系统提示授权后返回，再手动点安装。最终安装始终由系统弹窗确认，不会自动或静默安装。检查更新只读取安卓正式发行信息，不把本仓的 Windows Latest 版本当成手机更新。

### 使用语音输入

推荐在「设置 → 语音输入」下载 **SenseVoiceSmall INT8 离线语音包**，下载完成并通过校验后点「使用离线语音」，按系统提示允许麦克风。下载需要网络，之后的识别无需联网，也无需另装系统语音服务。模型包与词表保存在应用私有目录，不占用剪贴板或用户词库；可在同一页面删除，重新使用时再下载。

回到普通文本框，再长按空格。首次加载模型时先等待准备状态结束，再开始说话；松手后等待整句识别结果，上滑后松手则取消。加载尚未结束就松手或取消，不会在之后突然打开麦克风。短按空格的选词和空格功能不变，长按开始前横滑仍可以移动光标。单次录音最长 60 秒，实际耗时与效果取决于手机、语言、环境和录音长度。

升级保留用户原来明确选择的识别方式，下载模型也不会自动切换；需要手动点「使用离线语音」。语音包不进入系统备份，换机后重新下载并选择。

兼容选项仍包括「系统设备端识别」和用户明确允许的系统语音服务。系统的语音输入法子类型与语音识别服务是不同接口：安装了另一个输入法，不代表一定提供可供本应用调用的识别服务。应用内离线模型不受该条件限制；本版本没有新增云端语音 API。

### 使用常用语

打开剪贴板面板，切换到「常用语」。首次使用提供 4 条可修改的例句，点「+」新增；长按一条可以编辑、移到顶部或删除。点选只把文字填入当前输入框，不会自动发送消息或触发回车。

常用语最多保存 100 条，每条最多 2000 字，保存在应用私有文件 `files/quick_phrases.json`，与剪贴板历史分开。启用系统备份时，常用语会随应用数据备份与恢复；剪贴板历史仍不进入备份。语音识别方式和授权记录不随备份迁移，换机后重新选择。

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
| ARM64 手机 | `app/build/outputs/apk/release/KunKunIME-Android-0.1.2-arm64-v8a.apk` | `KunKunIME-Android-arm64-release` |
| x86_64 模拟器 | `app/build/outputs/apk/release/KunKunIME-Android-0.1.2-x86_64.apk` | `KunKunIME-Android-x86_64-release` |
| 发行元数据与 Rust 依赖源码 | `app/build/release-support/release-info.json`、`Cargo-sources.zip` | `KunKunIME-Android-release-support` |

设备验证分成 debug 与 release 两轮，均使用带真实 x86_64 核心的压缩 APK 和系统输入连接。debug 轮先运行 21 项无障碍、多指触控、空格语音手势和原生编辑框用例，再短暂暂停真实核心的交接，检查早输、退格、换框取消与后续中文上屏。release 轮在专用模拟器上启动不可调试的实际发行构建，通过屏幕按键验证拼音、英语、密码、数字、收起重开和系统导航按钮避让。两轮均检查词库草稿恢复、筛选后逐条删除、常用语和更新页面。

两轮各有一项独立的语音 IPC 流程测试：仅在测试 APK 中声明受控 `RecognitionService`，临时设为专用模拟器的识别服务，用真实长按空格、松手和上滑手势验证中间结果不上屏、最终结果进入当前输入框、语音状态栏不移动键盘，以及取消后切到密码框时迟到结果不会串入。测试服务只返回固定文字，不采集音频；生产 APK 不包含该服务。测试结束恢复识别服务、麦克风权限与临时语音偏好设置。报告分别保存在 `app/build/smoke-results/debug` 与 `release`，保留输入、语音的独立日志和截图。模拟器未提供真实麦克风音频，这些结果不代表语音识别准确率测试。

两轮还各运行一项独立的离线语音测试：模型和上游中英 WAV 仅放进测试 APK，复制到本机私有目录后，用正式 sherpa CPU/JNI 接口推理并检查实际转写关键词。随后通过真实 IME 长按等待模型准备，检查 Android 报告本地录音已开始，再上滑取消，检查录音停止、输入框未收到文字、系统测试识别服务从未启动。生产 APK 不包含测试模型或录音，也没有离线识别测试后门。独立的 `offline-voice` 日志、状态截图及 `offline-native-results.txt` 保留样例实际转写。

单元检查另覆盖下载校验及取消、旧偏好迁移、录音与推理取消后的结果隔离。样例推理能够验证引擎确实执行识别，不能替代用户手机麦克风、噪声环境或不同口音的效果测试。实际通过项及对应提交以本次 Actions 日志和发行说明为准。

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
python3 scripts/prepare-offline-voice.py --test-model
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
./gradlew :app:testReleaseUnitTest :app:assembleRelease :app:assembleReleaseAndroidTest \
  -PimeTestBuildType=release -PciReleaseSigning=true --no-daemon
bash scripts/smoke-test.sh debug
bash scripts/smoke-test.sh release
```

`prepare-offline-voice.py` 按 `voice-dependencies.json` 的固定大小和 SHA-256 获取官方推理 AAR；`--test-model` 另外准备仅供仪器测试的模型和上游样例。生成文件不提交 Git，模型与 WAV 不进入生产 APK。正常应用构建只需要推理引擎，用户语音包始终在应用中按需下载。

Windows 开发机可用 `scripts/build-rust.ps1`，并配置 `ANDROID_NDK_HOME`。缺少 ARM64 Rust 核心时 Gradle 会拒绝打包，避免产生只有模拟候选的安装包。运行时初始化失败也会明确提示。

当前核心的私有路径为 `files/rust/config/KunKunIME`（核心设置）、`files/rust/data/KunKunIME`（缓存/日志），用户词库留在 `files/rust` 下，手机键盘配置在 `files/mobile`。它们均位于 Android 应用沙箱中。词库导入导出需通过应用界面；共享文件格式不代表已实现跨设备同步。

## 分发签名

公开证书为 [`signing/kunkun-android-cert.pem`](signing/kunkun-android-cert.pem)，证书 SHA-256：

```text
ecd28998776fd8fd32ee0657815aaef57a89790dd80017954e8b73b3805953f8
```

该证书从 0.1.2 起用于正式发行。私钥和密码文件存放在签名机的私有目录。CI 验证通过后，对已下载并核对产物摘要的手机包执行：

```bash
python3 scripts/sign-apk.py \
  --input /path/to/ci/KunKunIME-Android-0.1.2-arm64-v8a.apk \
  --output /path/to/distribution/KunKunIME-Android-0.1.2-arm64-v8a.apk \
  --build-tools /path/to/android-sdk/build-tools/37.0.0 \
  --keystore /private/kunkun-android.p12 \
  --password-file /private/keystore-password.txt
```

脚本使用 Android 官方 `zipalign` 和 `apksigner`，保留 16 KiB 原生库对齐，验证签名与公开证书一致，并逐项核对代码、清单、资源和词库没有变化。密码由工具从私有文件读取，不写进命令行或输出；现有输入 APK 不会被覆盖。PKCS12 的密钥密码与密钥库密码保持一致。

也可按 Gradle 中的 `signing.*` 配置在私有构建环境直接签名；密钥来源与保管要求相同。遗失签名密钥会影响后续覆盖更新，生成另一把同名密钥无法替代原密钥。

## 开源来源

Android 外壳基于 [huanfeng/WindInputAndroid](https://github.com/huanfeng/WindInputAndroid) 提交 `afd41a392b2e5029c27393389ec016e66d20cfbf`，包括 Kotlin 键盘、设置和 UniFFI 绑定。该部分保留 [MPL-2.0](LICENSE)；与本仓 GPL-3.0-or-later 核心组合的 APK 按 GPL 条件发布，第三方数据各自保留许可。改动源码与许可随本仓提供。

其他实现参考：

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)：集成的 Android 本地语音推理引擎，代码为 Apache-2.0；按需下载的 [SenseVoice](https://github.com/FunAudioLLM/SenseVoice) 模型权重使用独立的 FunASR 模型许可，不能视为同一许可。
- [Fcitx5 Android](https://github.com/fcitx5-android/fcitx5-android)：系统输入框、预编辑和候选交互。
- [同文 Trime](https://github.com/osfans/trime)：中文输入方案与候选流程。
- [HeliBoard](https://github.com/HeliBorg/HeliBoard)：触屏键盘、输入框类型和按键交互。

来源和修改范围见根目录 [ORIGINS.md](../ORIGINS.md)；共享词库来源见 [第三方材料](../licenses/THIRD-PARTY.md)。
