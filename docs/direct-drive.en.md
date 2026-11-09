# Remote Direct Drive (Model-less Tool Sessions)

[简体中文](direct-drive.md) | **English**

Remote Direct Drive (B2) exposes `McpToolSession` over MCP via `McpSessionRegistry` and `McpSessionTools` while keeping the high-level task tools (`mika_run_task`, etc.) unchanged. An external brain (such as Muse running on a computer) can open a model-less tool session, inspect the frozen low-level tool catalog, and directly invoke phone tools (`phone_observe`, `web_navigate`, `run_command`, etc.) without invoking an on-device LLM.

## Safety Gate and Session Limits

- **Explicit phone-side opt-in (off by default)**: Controlled by `directDriveEnabled` in `McpSettings` (persisted in `mcp.json`, default `false`). When disabled, session tool calls fail with an explicit error message.
- **Full `ActionExecutor` guardrails**: Every low-level tool call inside `McpToolSession` runs through `ActionExecutor`, preserving operation guards, risk checks, and run event recording (`mcp_session_started`, `mcp_session_ended`).
- **Single active session**: At most one direct-drive session may be active at a time. Calling `mika_open_tool_session` while a session is already active fails until the existing session is closed with `mika_close_tool_session` or expires.
- **Idle timeout**: Sessions automatically expire and close after 10 minutes of inactivity (`DEFAULT_IDLE_TIMEOUT_MS`), allowing a fresh session to be opened afterwards.
- **Local and private network only**: Requests arriving over the remote relay do not receive direct-drive access (`directDriveAccess = false`).

## MCP Session Tools

| Tool | Arguments | Purpose |
| --- | --- | --- |
| `mika_open_tool_session` | `conversation` (optional), `tools` (optional array of tool names) | Opens a model-less tool session, freezes the session's low-level tool catalog, and returns `session_id`, `conversation`, `idle_timeout_ms`, and `tools` (`name`, `description`, `inputSchema`, `parametersJsonSchema`). |
| `mika_call_session_tool` | `session_id`, `tool`, `arguments` (optional JSON object) | Executes a low-level tool inside the active session and returns `session_id`, `tool`, `result`, and the tool's output fields. |
| `mika_close_tool_session` | `session_id` | Closes the active tool session and marks its run record ended, returning `session_id` and `closed: true`. |

## Typical Workflow

1. Enable computer access on the phone ([Connect a computer (MCP)](mcp.en.md)) and turn on `directDriveEnabled`.
2. Call `mika_open_tool_session` to obtain `session_id` and the frozen `tools` list.
3. Call `mika_call_session_tool` one or more times with `session_id`, `tool` (for example `run_command` or `phone_observe`), and `arguments`.
4. Call `mika_close_tool_session` when finished.
