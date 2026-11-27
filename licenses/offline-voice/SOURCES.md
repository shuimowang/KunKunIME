# Android 离线语音的来源与许可

本目录保留困困输入法 Android 离线语音相关的原始许可、版权声明及来源记录。模型权重按用户操作另行下载，正式 APK 包含推理引擎和下载／解包代码。`android/scripts/prepare-assets.py` 会将本目录完整复制到 APK 的 `data/licenses/offline-voice/`，应用内“开源许可”页面提供正文入口。

各组件保留各自的许可。以下说明不改变困困输入法、上游输入核心或其他随附词库的既有许可。

## 1. sherpa-onnx 2.0.3

- 项目：[k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.3)，保留上游作者及各源码文件中的贡献者归属。
- 许可：[Apache License 2.0 原文](sherpa-onnx-LICENSE.txt)，取自固定 `v2.0.3` 标签的 `LICENSE`。
- 实际使用的分发物：[官方 sherpa-onnx-2.0.3.aar](https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.3/sherpa-onnx-2.0.3.aar)。大小为 **52,411,426 字节**；SHA-256 为 `4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50`，与官方 GitHub Release 资产摘要一致。
- 构建依赖和本地代码范围依据固定标签的 [CMakeLists.txt](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.3/CMakeLists.txt)、[csrc/CMakeLists.txt](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.3/sherpa-onnx/csrc/CMakeLists.txt)、[Android 构建脚本](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.3/build-android-arm64-v8a.sh) 和 [Android 发布工作流](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.3/.github/workflows/android.yaml) 核对。

应用仅调用该 AAR 的离线语音识别接口。上游 AAR 是通用分发物，仍包含其他语音功能的接口；不能据此把它描述为只含 ASR 的专用构建。`v2.0.3` 的本地构建依赖已不再包含旧版本的 `espeak-ng-for-piper` 和 `piper-phonemize` 静态实现。这一版本范围的说明不表示整个应用或其其他数据不含 GPL 组件。

正式 APK 按 `arm64-v8a` 和 `x86_64` 分别打包。本次检查官方 AAR 中这两个 ABI 的 ELF `PT_LOAD` 段均至少按 16 KiB 对齐；APK 的实际打包对齐仍由构建后的检查负责。

## 2. ONNX Runtime 1.28.2

`sherpa-onnx v2.0.3` 的 [Android ARM64 CMake 配置](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.3/cmake/onnxruntime-android-aarch64.cmake) 和 [x86_64 配置](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.3/cmake/onnxruntime-android-x86_64.cmake) 固定下载：

- [onnxruntime-android-1.28.2.zip](https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip)，**33,765,715 字节**。
- SHA-256：`01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d`，与该标签 CMake 中的摘要一致。
- 已逐字节核对，AAR 中 `arm64-v8a`、`x86_64` 的 `libonnxruntime.so` 分别与上述 ZIP 的同名 ABI 文件一致。

该 Android ZIP 和 AAR 没有附带 ONNX Runtime 的完整许可文件。本目录的 [MIT LICENSE](onnxruntime-LICENSE.txt) 与 [完整 ThirdPartyNotices](onnxruntime-ThirdPartyNotices.txt) 取自 **Microsoft 在 PyPI 发布的 `onnxruntime-windowsml 1.28.2.202609030142`**，不是从 Android ZIP 提取：

- [官方 PyPI 发布元数据](https://pypi.org/pypi/onnxruntime-windowsml/1.28.2.202609030142/json) 列出的作者为 Microsoft Corporation；下载地址与 SHA-256 可由该元数据复核。
- 所用归档为 `onnxruntime_windowsml-1.28.2.202609030142-cp311-cp311-win_amd64.whl`，**26,775,682 字节**，SHA-256 `ad15d7f8d2c57325a4b5e7003c4dba31207288bffeeff7ce97ba429eff389362`。
- 原始归档条目为 `onnxruntime/LICENSE` 和 `onnxruntime/ThirdPartyNotices.txt`，全文原样保留。后者是 Microsoft 的完整第三方声明，可能包含 Android 不使用的平台组件。
- 此记录证明许可文本的发布来源和文件完整性。没有把 WindowsML 的二进制用于 Android，也不声称已核验该 Android ORT 预编译文件对应的 Microsoft 源码提交，或已经做出可逐字节重现的本地构建。

## 3. 其他本地依赖

[NATIVE-LICENSES.txt](NATIVE-LICENSES.txt) 汇总以下原始文件，单独的原文也保留在 `native/`。依赖版本由 sherpa-onnx `v2.0.3` 的固定 CMake 下载配置确定；文件来源、长度和 SHA-256 见 [SOURCES.json](SOURCES.json)。

| 组件 | 上游固定版本／提交 | 许可 |
|---|---|---|
| [kaldi-native-fbank](https://github.com/csukuangfj/kaldi-native-fbank/tree/v1.23.1) | v1.23.1 | Apache-2.0 |
| [kaldi_native_io](https://github.com/csukuangfj/kaldi_native_io/tree/v1.23.2) | v1.23.2 | Apache-2.0，保留原文 Legal Notices |
| [KISS FFT](https://github.com/mborgerding/kissfft/tree/febd4caeed32e33ad8b2e0bb5ea77542c40f18ec) | febd4caeed32e33ad8b2e0bb5ea77542c40f18ec | BSD-3-Clause；同时保留 Mark Borgerding 的 COPYRIGHT 与被引用的完整条款 |
| [OpenFst](https://github.com/csukuangfj/openfst/tree/v1.8.5-2026-07-09) | v1.8.5-2026-07-09 | Apache-2.0；保留 Google 的 COPYING，Apache 全文见 sherpa-onnx-LICENSE.txt |
| [utf8proc](https://github.com/JuliaStrings/utf8proc/tree/v2.12.0) | v2.12.0 | MIT 及原文件内的 Unicode 数据许可 |
| [kaldifst](https://github.com/k2-fsa/kaldifst/tree/v1.9.0) | v1.9.0 | Apache-2.0，保留原文 Legal Notices |
| [kaldi-decoder](https://github.com/k2-fsa/kaldi-decoder/tree/v0.4.0) | v0.4.0 | Apache-2.0 |
| [kaldi-hmm-gmm](https://github.com/csukuangfj/kaldi-hmm-gmm/tree/v1.2.1) | v1.2.1 | Apache-2.0，保留原文 Legal Notices |
| [simple-sentencepiece](https://github.com/pkufool/simple-sentencepiece/tree/v0.7) | v0.7 | Apache-2.0 |
| [nlohmann/json](https://github.com/nlohmann/json/tree/v3.12.0) | v3.12.0 | MIT |
| [hclust-cpp](https://github.com/csukuangfj/hclust-cpp/tree/2026-02-25) | 2026-02-25 | BSD 许可，原文保留 |

上游 Android 构建脚本默认静态链接 NDK 的 libc++。作为运行时许可补充，本目录还保留 [LLVM 项目](https://github.com/llvm/llvm-project/blob/llvmorg-21.1.0/LICENSE.TXT) 与 [libc++](https://github.com/llvm/llvm-project/blob/llvmorg-21.1.0/libcxx/LICENSE.TXT) 的完整许可，来源为固定 `llvmorg-21.1.0` 标签，包括 Apache-2.0 with LLVM Exceptions 及原文中的其他声明。该许可文本来源不用于推断官方 AAR 使用的精确 NDK／LLVM 构建版本。

## 4. SenseVoiceSmall INT8 模型

- 原模型：[FunAudioLLM / Alibaba Group 的 SenseVoiceSmall](https://github.com/FunAudioLLM/SenseVoice)。保留模型名称和作者归属。
- ONNX 转换及分发：sherpa-onnx / Fangjun Kuang；模型说明见 [sherpa-onnx 官方 SenseVoice 文档](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html)。
- 固定转换版本：`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`。支持的语言包括普通话、英语、粤语、日语和韩语。应用选择这一版本以保留逆文本规范化功能。
- [官方归档](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2) 为 **163,002,883 字节**，SHA-256 `7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e`。
- 实际安装只提取 `model.int8.onnx`（239,233,841 字节，SHA-256 `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51`）和 `tokens.txt`（315,894 字节，SHA-256 `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc`）。

**权重使用独立的 [FunASR Model Open Source License Agreement v1.1](SenseVoice-FunASR-MODEL-LICENSE.txt)，不能把引擎的 Apache-2.0 许可当作模型权重许可。** 完整条款从 FunASR 固定提交 `58830eca4012644aac0c3218c3ccc7d98f003fda` 的 `MODEL_LICENSE` 原样保留，使用、复制、修改、分享时需遵循其中归属、保留模型名称等条件。官方针对第 3 条措辞的解释见 [SenseVoice 维护者的许可说明](https://github.com/QwenAudio/SenseVoice/issues/334#issuecomment-5083546605)。

归档原本的 [LICENSE](SenseVoice-archive-LICENSE.txt) 只有 71 字节，是指向 FunASR 仓库许可章节的引用，并非完整协议或 Apache 许可。因此本目录同时保留该引用与完整模型协议。模型不会因通过本项目下载而改用项目代码的许可证。

## 5. Apache Commons 下载解包依赖

[APACHE-COMMONS-LICENSES.txt](APACHE-COMMONS-LICENSES.txt) 保留以下官方 Maven Central JAR 内 `META-INF/LICENSE.txt` 和 `META-INF/NOTICE.txt` 的原始全文。单独文件保存在 `commons/`。

| Maven 坐标 | 版本 | 用途／关系 |
|---|---|---|
| org.apache.commons:commons-compress | 1.28.0 | 解压官方模型 tar.bz2 归档，应用只接受固定白名单文件 |
| commons-io:commons-io | 2.20.0 | Commons Compress 的直接运行依赖 |
| org.apache.commons:commons-lang3 | 3.18.0 | Commons Compress 的直接运行依赖 |
| commons-codec:commons-codec | 1.19.0 | Commons Compress 的直接运行依赖 |

以上组件由 Apache Software Foundation 发布，均使用 Apache-2.0。版本关系依据 [commons-compress 1.28.0 官方 POM](https://repo.maven.apache.org/maven2/org/apache/commons/commons-compress/1.28.0/commons-compress-1.28.0.pom) 中非 `optional`、非 `test` 的依赖核对；同时检查了三项传递依赖的 POM。可选的 XZ、Zstandard、Brotli、ASM 以及上游测试依赖没有被本项目显式引入。

## 校验记录

[SOURCES.json](SOURCES.json) 记录逐文件原文来源、归档内路径、字节数和 SHA-256，也记录用于取证的原始发行归档／JAR／POM 摘要。它是许可来源清单；实际应用的推理引擎和模型下载锁定值由 `android/voice-dependencies.json` 及模型安装代码负责校验。二进制、模型权重和下载缓存不提交源码仓库。
