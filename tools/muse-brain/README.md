# Muse Brain Reference Bridge (`tools/muse-brain`)

> **Important:** This bridge is **only a test stand-in** so the phone side (`MuseModel` / B1) can be exercised end-to-end today; **it is NOT Muse itself**. The production Muse brain server is a later step and only needs to expose the same `muse_complete` MCP tool contract described below.

## Overview

`server.py` is a minimal Streamable HTTP MCP server written using the Python 3 standard library only (no third-party dependencies). It implements legacy JSON-RPC `initialize`, `tools/list`, and `tools/call` for the single tool `muse_complete`, forwarding each call to an OpenAI-compatible `/chat/completions` upstream and translating the reply (including `tool_calls`, `reasoning_content`, and token usage) back into the `muse_complete` JSON format expected by Mika.

## Configuration

Configure the upstream provider via environment variables:

| Variable | Required | Description |
| --- | --- | --- |
| `MUSE_BRAIN_UPSTREAM_URL` | Yes | Base URL of the OpenAI-compatible endpoint (e.g., `https://api.deepseek.com` or `https://api.openai.com/v1`) |
| `MUSE_BRAIN_UPSTREAM_KEY` | Optional | Bearer API key sent to the upstream endpoint |
| `MUSE_BRAIN_MODEL` | Optional | Upstream model name override (falls back to the `model` argument sent by Mika) |
| `MUSE_BRAIN_TOKEN` | Optional | If set, requires incoming MCP requests from Mika to carry `Authorization: Bearer <MUSE_BRAIN_TOKEN>` |
| `MUSE_BRAIN_HOST` | Optional | Bind address (default: `127.0.0.1`) |
| `MUSE_BRAIN_PORT` | Optional | Bind port (default: `8765`) |

## Running the Bridge

```bash
export MUSE_BRAIN_UPSTREAM_URL="https://api.deepseek.com"
export MUSE_BRAIN_UPSTREAM_KEY="sk-..."
export MUSE_BRAIN_MODEL="deepseek-chat"
python3 tools/muse-brain/server.py --host 0.0.0.0 --port 8765
```

Then in Mika (**Settings -> Models -> Add provider**), choose **Muse (MCP Brain)**, set the MCP server URL (for example `http://<host>:8765/mcp`), enter the token if `MUSE_BRAIN_TOKEN` is set, and keep or customize the model name (default `muse`).

## Running the Smoke Test

`smoke_test.py` starts a local fake OpenAI-compatible upstream and an instance of `server.py`, performs the MCP handshake (`initialize`, `notifications/initialized`, `tools/list`), and invokes `muse_complete` with text, image, and tool definitions:

```bash
python3 tools/muse-brain/smoke_test.py
```
