# 从 Agent 管理困困输入法

使用 [v0.1.7 或更新版本的 Windows x64 程序](https://github.com/shuimowang/KunKunIME/releases)，即可通过本机 MCP 客户端管理个人词库和常用设置。客户端通过标准输入输出（stdio）启动同一个 `KunKunIME.exe`。第一次需要读写数据时会连接输入服务，必要时启动后台。关闭 MCP 连接后仍可正常打字。

## 接入客户端

在 Windows 的 PowerShell 中，把示例路径换成你实际使用的程序路径，即可将服务加入本机 Codex。

```powershell
codex mcp add kunkun-ime -- "C:\Apps\困困输入法\KunKunIME.exe" mcp
codex mcp get kunkun-ime
```

打开新的 Codex 对话，输入“用困困输入法 MCP 查看每页显示几个候选词”即可验证连接。其他支持 stdio 的客户端可填写同样的程序路径与单个参数。采用 `mcpServers` 格式的客户端可参考下列配置。

```json
{
  "mcpServers": {
    "kunkun-ime": {
      "command": "C:/Apps/困困输入法/KunKunIME.exe",
      "args": ["mcp"]
    }
  }
}
```

此服务无需端口、公开网址或 API 密钥。查询结果会交给调用它的 Agent。模型如何处理这些数据取决于你选用的客户端。

## 可以怎样说

- “给这本小说新建一个叫『星河人物』的拼音分库，加入林溪、林夕、沈舟。沈舟的读音是 shen zhou。”
- “查主词库里编码为 lin xi 的候选，只删除『林溪』，保留『林夕』。”
- “看看现在每页有几个候选词，改成 8 个，再读回来确认。”

## 可用工具

| 工具 | 用途 |
|---|---|
| `dictionary_list_libraries` | 列出指定方案的个人词库和分库。 |
| `dictionary_search` | 按词语或编码搜索、分页浏览。可用返回的编码和词语精确指定候选。 |
| `dictionary_import` | 一次向主词库合并 1 至 500 条。中文词可自动生成全拼，多音字可以指定读音，英文须提供编码。 |
| `dictionary_create_library` | 创建分库，可同时加入一批词条。同名分库会保留。 |
| `dictionary_set_entry` | 向主词库或已有分库添加单条，或修改指定候选的权重。 |
| `dictionary_remove` | 一次提交要删除的「编码＋词语」列表，保留未指定的同码候选。 |
| `settings_list` | 查询开放的设置、类型及取值范围，支持中文名称或键名搜索。 |
| `settings_get` | 读取所选设置的当前值。 |
| `settings_set` | 校验所有值后向后台提交一次，报告已接受的项数、跳过项和是否需要重启。 |

全拼和双拼共享个人拼音词库，默认使用拼音主词库。在搜索、单条增改和删除时可用 `library` 指定分库。合并导入保留同码同词的较高权重，调低权重可用 `dictionary_set_entry`。当前词库工具的作用范围是个人词条。

设置覆盖拼音、英文补全、候选、标点、联想、邮箱、网址和工具栏。用 `settings_list` 找到允许的键，再读写当前值。邮箱预置后缀等列表按整项替换，追加前应先读出现有内容。如果后台报告部分成功或连接中断，先查询实际状态再决定是否重试。
