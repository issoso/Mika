# 远程直接驱动（Direct Drive）

简体中文 | [English](direct-drive.en.md)

远程直接驱动（B2）在保持高层任务工具（`mika_run_task` 等）不变的前提下，通过 MCP 额外暴露基于 `McpToolSession` 的无模型工具会话（`McpSessionRegistry` / `McpSessionTools`）。外部大脑（例如电脑上的 Muse）可以直接打开会话、枚举并调用手机端底层工具（如 `phone_observe`、`web_navigate`、`run_command`），而无需在手机端运行大模型。

## 安全门控与会话边界

- **手机端显式授权（默认关闭）**：由 `McpSettings` 中的 `directDriveEnabled`（「允许电脑直接驱动工具」）控制，持久化在 `mcp.json` 中，默认关闭。未开启时调用会话工具会返回明确错误说明。
- **沿用现有执行守卫**：会话内的每一次底层工具调用都经过 `McpToolSession` 与 `ActionExecutor`，保留操作守卫、风险分级与运行事件记录（`mcp_session_started`、`mcp_session_ended`）。
- **单会话上限**：同一时刻最多允许一个活动工具会话。已有活动会话时再次调用 `mika_open_tool_session` 会拒绝打开并提示先调用 `mika_close_tool_session`。
- **空闲自动过期**：会话默认空闲超时为 10 分钟（`DEFAULT_IDLE_TIMEOUT_MS`）。超过空闲时间未调用时，会话自动关闭并释放，后续可正常打开新会话。
- **仅限本地与私有网络**：经远程网关转发的请求不开放直接驱动会话（`directDriveAccess = false`）。

## MCP 会话工具

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `mika_open_tool_session` | `conversation`（可选）、`tools`（可选，工具名白名单数组） | 打开一个无模型工具会话，冻结本会话可用的底层工具目录，返回 `session_id`、`conversation`、`idle_timeout_ms` 与 `tools` 列表（含每个工具的 `name`、`description`、`inputSchema`、`parametersJsonSchema`）。 |
| `mika_call_session_tool` | `session_id`、`tool`、`arguments`（可选，JSON 对象） | 在指定活动会话中执行一个底层工具，返回 `session_id`、`tool`、`result` 及工具输出字段。 |
| `mika_close_tool_session` | `session_id` | 关闭指定的活动会话并将运行记录标记为已结束，返回 `session_id` 与 `closed: true`。 |

## 典型调用流程

1. 在手机上启用电脑连接（[连接电脑（MCP）](mcp.md)）并开启 `directDriveEnabled`。
2. 调用 `mika_open_tool_session` 获取 `session_id` 和当前会话冻结的 `tools` 列表。
3. 循环调用 `mika_call_session_tool`，传入 `session_id`、目标底层工具名 `tool`（例如 `run_command` 或 `phone_observe`）和 `arguments`。
4. 完成后调用 `mika_close_tool_session` 释放会话。
