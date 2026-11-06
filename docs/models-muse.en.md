# Muse (MCP Brain)

**English** · [简体中文](models-muse.md)

Muse (B1) lets Mika's on-device agent loop use a remote MCP server as its model brain. The agent loop, tool execution, and confirmation gates remain on the phone; on each model turn, Mika connects to the configured MCP server over Streamable HTTP and calls the `muse_complete` tool.

## Adding the Muse Provider

1. Open **Settings › Models › Add provider**.
2. Pick the **Muse (MCP Brain)** preset (or choose **Custom** and select the **Muse** interface kind).
3. Fill in the connection fields:
   - **MCP Server URL (Base URL)**: Full `http://` or `https://` endpoint of your MCP server (for example `https://muse.example.com/mcp` or a LAN bridge such as `http://192.168.1.20:8765/mcp`).
   - **API Key / Token**: Optional. When present, Mika sends it as `Authorization: Bearer <token>` on MCP requests and never logs it.
   - **Model name**: Entered manually; defaults to `"muse"`. Muse has no model-list endpoint, so it always uses the manual model-name path.
4. Tap **Test and save**. The preset defaults to `toolCalls = true`, `vision = true`, `groundingVerified = false`, `contextTokens = 128000`, and `"Implicit"` prompt caching. The context window remains user-editable in Settings like any other provider.

## `muse_complete` Tool Contract

On each `complete(system, messages, tools)` call, `MuseModel` invokes the MCP tool `muse_complete`:

- **Request `arguments`**:
  - `model`: Model identifier string (default `"muse"`).
  - `system`: System prompt string.
  - `messages`: Array of messages with `role` (`system` / `user` / `assistant` / `tool`) and serialized `parts` (`text`, `image`, `tool_call`, `tool_result`, `thinking`).
  - `tools`: Array of tool specs (`name`, `description`, `parametersJsonSchema`).
- **Response JSON**:
  - `parts`: Array of reply parts (`text`, `tool_call`, `thinking`).
  - `stopReason`: `"EndTurn"`, `"ToolUse"`, `"MaxTokens"`, or `"Other"`.
  - `usage`: `{"inputTokens", "outputTokens", "cachedInputTokens", "reasoningTokens"}` or `null` (token counts are never guessed when omitted).

## v1 Limits

- **Non-streaming**: v1 implements `ChatModel` only (not `StreamingChatModel`).
- **One MCP connection per `complete()` call**: Each `complete()` call connects once, calls `muse_complete`, and closes the MCP endpoint in a `finally` block.
- **Read timeout**: Follows the existing model read-timeout setting (default **120 seconds**, configurable per model in Settings).
- **Reference bridge (`tools/muse-brain/`)**: The Python bridge under `tools/muse-brain/` is only a test stand-in that forwards `muse_complete` to an OpenAI-compatible upstream so the phone side can be exercised end-to-end today; it is not Muse itself.
