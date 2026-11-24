# 困困输入法 · Quicker 动作

**困困输入法（KunKunIME）是一款开源的 Windows 拼音与英文输入法。** 它支持全拼、小鹤双拼和英文补全，个人词库与设置默认保存在本机。

配套的 Quicker 动作可以一键打开拼音主词库。写小说时，把人物名和地名一次加进去，下次打拼音就能从候选里直接选出整词。

项目基于清风输入法（WindInput）独立维护，应用源码采用 [GPL-3.0-or-later 许可证](https://github.com/shuimowang/KunKunIME/blob/main/LICENSE)。从长句怎样切词、候选怎样排序，到词库管理界面怎样实现，都可以在 GitHub 上查看，也可以自行编译 Windows 版。

[查看 GitHub 源码](https://github.com/shuimowang/KunKunIME) · [从源码构建](https://github.com/shuimowang/KunKunIME#%E4%BB%8E%E6%BA%90%E7%A0%81%E6%9E%84%E5%BB%BA) · [组件与词库的来源和许可](https://github.com/shuimowang/KunKunIME/blob/main/ORIGINS.md)

[在 Quicker 安装动作](https://getquicker.net/Sharedaction?code=97a34a52-d2fd-4505-ab9b-0961e7718c53) · [手动下载动作 JSON](https://111.229.149.25/public/share/5KrzfvBkWjZPTnTEFXtodg) · [下载 Windows x64 程序包](https://111.229.149.25/public/share/82K7DlFUYVQveuGn3TZsQQ)

## 用 AI 助手管理词库

在困困输入法 v0.1.8 的设置左栏打开 **AI 助手 MCP**，可以复制 Codex 接入命令或其他客户端需要的 JSON。“关于”页和设置搜索也有入口。连好后可以先问“用困困输入法查看每页有几个候选词”，再按需管理词库和设置。[可用工具与示例](../docs/MCP.md)

## 第一次使用

在 Windows x64 上使用 Quicker 2.3.19 或更新版本。点击上方「在 Quicker 安装动作」。如果手动下载了动作 JSON，在 Quicker 面板右键目标场景标签，选「导入动作到此场景」，再选中该文件。

点面板里的「困困输入法」。电脑上已经注册困困输入法时，动作会直接打开主词库。首次使用可点**「下载并打开」**，也可点**「选择已有程序」**并选中解压目录里的 `KunKunIME.exe`。

要在记事本、聊天框等应用里输入自己的词，先在软件中点「注册输入法」，按 Windows 提示确认，再按 **Win + 空格** 选择「困困输入法」。

## 把小说里的地名加入词库

只加一个名字，可以点搜索框右边的「添加」。若有一整份人物或地名表，把每个词单独写一行，保存成 UTF-8 文本文件。

![拼音主词库，右上角有批量导入按钮，下方列出三个示例地名](https://111.229.149.25/public/api/resources/download?hash=u7Jbo0QY01URgzdfi8cB9Q&file=%2F)

可以把下面三行保存为文本文件。

```text
云岫谷
星渡台
归舟阁
```

在右上角点 **「批量导入」** 并选文件。软件会为纯中文词条生成拼音，遇到多音字可以先点「预览编码」核对。

![批量导入窗口已识别示例文件中的三个词，右下角为合并导入](https://111.229.149.25/public/api/resources/download?hash=mvDlRk2dAbhMdN87cDHkrQ&file=%2F)

确认目标是「拼音」、识别到 3 个词后，点 **「合并导入」**，原有词条会保留。以后清理同音词时，只勾选要删的候选，就能一次删除。

遇到切词或候选排序问题，可以附上「输入的拼音、想打的词、实际出现的候选」，方便复现。[到 GitHub 反馈问题](https://github.com/shuimowang/KunKunIME/issues)
