# Muse（MCP 大脑）

[English](models-muse.en.md) · **简体中文**

Muse（B1）把手机端 Mika 的模型调用转发到远端 MCP 服务端：手机本身的 Agent 循环、工具执行与安全确认全部留在手机上，每次需要模型思考时，Mika 通过 Streamable HTTP 连接 MCP 服务端并调用 `muse_complete` 工具。

## 添加 Muse 服务商

1. 打开 **设置 › 模型 › 添加服务商**。
2. 预设选择 **Muse (MCP Brain)**（或选「自定义」并将接口类型切换为 **Muse**）。
3. 填写连接信息：
   - **MCP 服务地址（Base URL）**：远端 MCP 服务的完整 HTTP/HTTPS 地址（例如 `https://muse.example.com/mcp` 或局域网测试桥 `http://192.168.1.20:8765/mcp`）。
   - **API Key / Token**：可选；填写后会在每次 MCP 请求头中以 `Authorization: Bearer <token>` 发送，不会写入日志。
   - **模型名**：手填，默认值为 `"muse"`。Muse 服务商不提供模型列表接口（无 `/models` 列表端点），直接走手动填写模型名的路径。
4. 点击 **测试并保存**。预设默认能力为支持工具调用、支持看图、未验证看图定位、上下文窗口 `128K`（`128000` tokens）以及隐式缓存（`"Implicit"`），保存后可在模型设置中按需调整上下文窗口和读取超时。

## `muse_complete` 工具协议

`MuseModel` 在每次 `complete(system, messages, tools)` 调用时向 MCP 服务端发起一次 `tools/call`，工具名为 `muse_complete`：

- **请求参数（`arguments`）**：
  - `model`：模型名字符串（默认 `"muse"`）
  - `system`：系统提示词字符串
  - `messages`：消息列表，每项包含 `role`（`system` / `user` / `assistant` / `tool`）和 `parts`（`text`、`image`、`tool_call`、`tool_result`、`thinking`）
  - `tools`：当前轮可用工具定义列表，每项包含 `name`、`description`、`parametersJsonSchema`
- **返回结果（JSON 文本）**：
  - `parts`：仅允许 `text`、`tool_call`、`thinking` 三种分片
  - `stopReason`：`"EndTurn"`、`"ToolUse"`、`"MaxTokens"` 或 `"Other"`
  - `usage`：`{"inputTokens", "outputTokens", "cachedInputTokens", "reasoningTokens"}` 或 `null`

## v1 限制与说明

- **非流式调用**：v1 仅实现 `ChatModel`（非 `StreamingChatModel`），每次回复完整返回后展示。
- **每次调用独立连接**：每次 `complete()` 调用建立一次 MCP 连接、调用 `muse_complete`，并在 `finally` 中关闭连接。
- **读取超时**：沿用现有的模型读取超时设置（默认 **120 秒**，可在模型设置中按模型调整）。
- **本地参考测试桥**：仓库中的 `tools/muse-brain/` 仅作为端到端联调的测试替身（把 `muse_complete` 转发到任意 OpenAI 兼容接口），并不是生产环境的 Muse 本身。
