# 第三方材料

困困输入法的代码许可和上游基线见根目录的 LICENSE 与 ORIGINS.md。下表列出公开 Windows 产品中随附的数据。每个项目保留自己的授权方式和版权声明。

| 材料 | 来源及加工 | 许可证 |
|---|---|---|
| 拼音字表、基础词库、领域词表、英文补充及表情补充 | [白霜拼音](https://github.com/gaboolic/rime-frost) 和 [雾凇拼音](https://github.com/iDvel/rime-ice)，随所选词表保留文件头 | GPL-3.0 |
| 普通话读音 | [pinyin-data](https://github.com/mozillazg/pinyin-data)，转为拼音映射和首字母查询表 | [MIT](data/pinyin-data-MIT.txt) |
| 联想后继词 | [rime-octagram-data](https://github.com/lotem/rime-octagram-data)，由上游提取后继词，困困版调整候选顺序；标点统计来自[中文维基百科贡献者](https://zh.wikipedia.org/) | LGPL-3.0，标点统计另遵循 [CC BY-SA 4.0](data/CC-BY-SA-4.0.txt) |
| 拼音上下文模型 | amzxyz 的[万象 LTS 语言模型](https://github.com/amzxyz/rime-wanxiang-data)，随附文件是已有模型的条目子集 | [CC BY 4.0](data/CC-BY-4.0.txt) |
| 英语主词表 | [Carnegie Mellon Pronouncing Dictionary](https://github.com/cmusphinx/cmudict)，取英语拼写并合并多种读音及大小写变体 | [CMUdict 许可](data/CMUdict.txt) |
| 英语补全频序 | 由本项目统计简·奥斯汀《傲慢与偏见》、马克·吐温《汤姆·索亚历险记》、查尔斯·狄更斯《远大前程》及 Steve Klabnik、Carol Nichols 等人的 [The Rust Programming Language](https://github.com/rust-lang/book/tree/5c23042f85595490ad9f7412c4c6f57fe0b330d5) 英文正文，并加入常用办公与软件词的编辑优先级。Standard Ebooks 版本贡献使用 [CC0](data/CC0-1.0.txt)，Rust 教程采用其 [MIT](data/rust-book-MIT.txt) 许可。实际发布的是词频顺序和拼写，提交号与各章节校验摘要见 scripts/generate-english.py | [《傲慢与偏见》版本说明](https://github.com/standardebooks/jane-austen_pride-and-prejudice/blob/a77a2dbbc0d11a17d6c68c581f17e26a65bd8168/LICENSE.md)，[《汤姆·索亚历险记》版本说明](https://github.com/standardebooks/mark-twain_the-adventures-of-tom-sawyer/blob/3daf72f4d3c3dd7d68476dc77235be6ffcf20621/LICENSE.md)、[《远大前程》版本说明](https://github.com/standardebooks/charles-dickens_great-expectations/blob/a8de3e503018ef79147a06488b336fc5446fe7a4/LICENSE.md) |
| 中文与英文释义 | [ECDICT](https://github.com/skywind3000/ECDICT)，Copyright (c) 2025 Linwei；[CC-CEDICT / MDBG](https://www.mdbg.net/chinese/dictionary?page=cc-cedict)，取子集并重新编排和整理释义 | [MIT](data/ECDICT-MIT.txt)、[CC BY-SA 4.0](data/CC-BY-SA-4.0.txt) |
| 简繁转换数据 | [OpenCC](https://github.com/BYVoid/OpenCC)，转换为前缀树存储 | [Apache-2.0](data/OpenCC-Apache-2.0.txt) |
| 笔画和双拼辅助码 | [rime-stroke](https://github.com/rime/rime-stroke) 笔画，附加白霜单字词频；[rime-lua-aux-code](https://github.com/HowcanoeWang/rime-lua-aux-code) 小鹤与自然码辅助码 | LGPL-3.0、GPL-3.0、[MIT](data/rime-lua-aux-code-MIT.txt) |
| 表情扩展 | [rime-emoji](https://github.com/rime/rime-emoji) | LGPL-3.0，完整条款也随 data/emoji/LICENSE 提供 |
| 游戏专名 | 王者荣耀英雄的名称与读音由本项目整理，出处写在 honor_of_kings.dict.yaml 文件头。游戏名称和角色名称归各权利人所有。 | 本项目整理的词表随项目代码许可发布 |

根目录 LICENSE 包含 GPL-3.0 全文，WindInput/data/emoji/LICENSE 包含 LGPL-3.0 全文；Windows 软件包内也提供这两份文件。历史 WindInput/NOTICE.md 记录上游项目曾使用的更多数据，其中部分材料不属于困困的公开发行内容。

## Android 离线语音

Android 新增的 sherpa-onnx 推理引擎、ONNX Runtime、其他本地依赖和 Apache Commons 解包库，其原始许可、版权声明、固定版本与来源摘要见 [offline-voice/SOURCES.md](offline-voice/SOURCES.md)。按需下载的 SenseVoiceSmall 模型权重适用独立的 FunASR 模型协议，归属为 FunAudioLLM / Alibaba Group，ONNX 转换由 sherpa-onnx / Fangjun Kuang 提供；不将模型权重许可与推理引擎的 Apache-2.0 许可混用。

