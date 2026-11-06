#!/usr/bin/env python3
"""Minimal Streamable HTTP MCP bridge server for Mika's Muse (B1) model provider.

NOTE: This bridge is only a test stand-in so the phone side can be exercised
end-to-end today; it is NOT Muse itself. The production Muse brain server is a
later step and only needs to speak the same `muse_complete` MCP tool contract.

Environment variables:
  MUSE_BRAIN_UPSTREAM_URL  OpenAI-compatible base URL (e.g. https://api.openai.com/v1)
  MUSE_BRAIN_UPSTREAM_KEY  Upstream Bearer API key (optional for local servers)
  MUSE_BRAIN_MODEL         Upstream model override (optional; defaults to client request model)
  MUSE_BRAIN_TOKEN         Optional Bearer token required by this MCP bridge itself
  MUSE_BRAIN_HOST          Bind host (default: 127.0.0.1)
  MUSE_BRAIN_PORT          Bind port (default: 8765)
"""

from __future__ import annotations

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import sys
from typing import Any
import urllib.error
import urllib.request


PROTOCOL_VERSION = "2025-03-26"
TOOL_NAME = "muse_complete"

TOOL_SCHEMA: dict[str, Any] = {
    "name": TOOL_NAME,
    "description": (
        "Complete a single non-streaming chat turn for Mika's phone agent loop."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {
            "model": {"type": "string"},
            "system": {"type": "string"},
            "messages": {"type": "array"},
            "tools": {"type": "array"},
        },
        "required": ["model", "system", "messages", "tools"],
    },
}


def translate_messages(system: str, messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Translate muse_complete messages into OpenAI Chat Completions wire messages."""
    wire: list[dict[str, Any]] = [{"role": "system", "content": system}]
    for msg in messages:
        role = msg.get("role", "user")
        parts = msg.get("parts") or []
        if role == "assistant":
            texts = [p.get("text", "") for p in parts if p.get("type") == "text" and p.get("text")]
            thinkings = [p.get("text", "") for p in parts if p.get("type") == "thinking" and p.get("text")]
            calls = [p for p in parts if p.get("type") == "tool_call"]
            entry: dict[str, Any] = {"role": "assistant"}
            joined_text = "\n".join(texts)
            if not joined_text and calls:
                entry["content"] = None
            else:
                entry["content"] = joined_text
            if thinkings:
                entry["reasoning_content"] = "".join(thinkings)
            if calls:
                entry["tool_calls"] = [
                    {
                        "id": c.get("id", ""),
                        "type": "function",
                        "function": {
                            "name": c.get("name", ""),
                            "arguments": c.get("argsJson") or "{}",
                        },
                    }
                    for c in calls
                ]
            wire.append(entry)
            continue

        for p in parts:
            if p.get("type") == "tool_result":
                wire.append(
                    {
                        "role": "tool",
                        "tool_call_id": p.get("callId", ""),
                        "content": p.get("content", ""),
                    }
                )

        rest = [p for p in parts if p.get("type") in ("text", "image")]
        if rest:
            target_role = "system" if role == "system" else "user"
            has_image = any(p.get("type") == "image" for p in rest)
            if not has_image:
                wire.append(
                    {
                        "role": target_role,
                        "content": "\n".join(p.get("text", "") for p in rest),
                    }
                )
            else:
                content_blocks: list[dict[str, Any]] = []
                for p in rest:
                    if p.get("type") == "image":
                        mime = p.get("mime") or "image/png"
                        b64 = p.get("pngBase64") or ""
                        content_blocks.append(
                            {
                                "type": "image_url",
                                "image_url": {"url": f"data:{mime};base64,{b64}"},
                            }
                        )
                    else:
                        content_blocks.append(
                            {"type": "text", "text": p.get("text", "")}
                        )
                wire.append({"role": target_role, "content": content_blocks})
    return wire


def translate_tools(tools: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Translate muse_complete ToolSpec list into OpenAI function tools."""
    out: list[dict[str, Any]] = []
    for t in tools:
        raw_schema = t.get("parametersJsonSchema", "{}")
        if isinstance(raw_schema, str):
            try:
                schema = json.loads(raw_schema) if raw_schema.strip() else {"type": "object"}
            except json.JSONDecodeError:
                schema = {"type": "object"}
        elif isinstance(raw_schema, dict):
            schema = raw_schema
        else:
            schema = {"type": "object"}
        out.append(
            {
                "type": "function",
                "function": {
                    "name": t.get("name", ""),
                    "description": t.get("description", ""),
                    "parameters": schema,
                },
            }
        )
    return out


def translate_upstream_response(data: dict[str, Any]) -> dict[str, Any]:
    """Translate an OpenAI Chat Completions JSON response into muse_complete format."""
    choices = data.get("choices") or []
    if not choices or not isinstance(choices[0], dict):
        raise ValueError("Upstream response is missing choices[0]")
    choice = choices[0]
    msg = choice.get("message")
    if not isinstance(msg, dict):
        raise ValueError("Upstream response is missing choices[0].message")

    parts: list[dict[str, Any]] = []
    reasoning = msg.get("reasoning_content")
    if isinstance(reasoning, str) and reasoning:
        parts.append({"type": "thinking", "text": reasoning})

    content = msg.get("content")
    if isinstance(content, str) and content:
        parts.append({"type": "text", "text": content})

    tool_calls = msg.get("tool_calls") or []
    for tc in tool_calls:
        if not isinstance(tc, dict):
            continue
        fn = tc.get("function") or {}
        parts.append(
            {
                "type": "tool_call",
                "id": str(tc.get("id") or ""),
                "name": str(fn.get("name") or ""),
                "argsJson": str(fn.get("arguments") or "{}"),
            }
        )

    finish = choice.get("finish_reason")
    if finish == "stop":
        stop_reason = "EndTurn"
    elif finish in ("tool_calls", "function_call"):
        stop_reason = "ToolUse"
    elif finish == "length":
        stop_reason = "MaxTokens"
    else:
        stop_reason = (
            "ToolUse"
            if any(p.get("type") == "tool_call" for p in parts)
            else "Other"
        )

    usage_raw = data.get("usage")
    usage: dict[str, int] | None = None
    if isinstance(usage_raw, dict):
        hit = usage_raw.get("prompt_cache_hit_tokens")
        miss = usage_raw.get("prompt_cache_miss_tokens")
        in_tokens = usage_raw.get("prompt_tokens")
        if not isinstance(in_tokens, int) and isinstance(hit, int) and isinstance(miss, int):
            in_tokens = hit + miss
        if isinstance(in_tokens, int):
            out_tokens = usage_raw.get("completion_tokens")
            if not isinstance(out_tokens, int):
                out_tokens = 0
            prompt_details = usage_raw.get("prompt_tokens_details")
            cached_details = (
                prompt_details.get("cached_tokens")
                if isinstance(prompt_details, dict)
                else 0
            )
            cached = max(
                cached_details if isinstance(cached_details, int) else 0,
                hit if isinstance(hit, int) else 0,
            )
            cached = min(cached, in_tokens)
            comp_details = usage_raw.get("completion_tokens_details")
            reasoning_tokens = (
                comp_details.get("reasoning_tokens")
                if isinstance(comp_details, dict)
                else 0
            )
            if not isinstance(reasoning_tokens, int):
                reasoning_tokens = 0
            usage = {
                "inputTokens": in_tokens,
                "outputTokens": out_tokens,
                "cachedInputTokens": cached,
                "reasoningTokens": reasoning_tokens,
            }

    return {
        "parts": parts,
        "stopReason": stop_reason,
        "usage": usage,
    }


class MuseBrainConfig:
    def __init__(
        self,
        upstream_url: str = "",
        upstream_key: str = "",
        upstream_model: str = "",
        bridge_token: str = "",
        timeout_sec: float = 120.0,
    ) -> None:
        self.upstream_url = upstream_url.strip()
        self.upstream_key = upstream_key.strip()
        self.upstream_model = upstream_model.strip()
        self.bridge_token = bridge_token.strip()
        self.timeout_sec = timeout_sec

    @classmethod
    def from_env(cls) -> "MuseBrainConfig":
        return cls(
            upstream_url=os.environ.get("MUSE_BRAIN_UPSTREAM_URL", ""),
            upstream_key=os.environ.get("MUSE_BRAIN_UPSTREAM_KEY", ""),
            upstream_model=os.environ.get("MUSE_BRAIN_MODEL", ""),
            bridge_token=os.environ.get("MUSE_BRAIN_TOKEN", ""),
        )

    def completions_endpoint(self) -> str:
        if not self.upstream_url:
            raise ValueError("MUSE_BRAIN_UPSTREAM_URL is not configured")
        trimmed = self.upstream_url.rstrip("/")
        if trimmed.endswith("/chat/completions"):
            return trimmed
        return trimmed + "/chat/completions"


def call_upstream(config: MuseBrainConfig, arguments: dict[str, Any]) -> dict[str, Any]:
    endpoint = config.completions_endpoint()
    model = config.upstream_model or str(arguments.get("model") or "muse")
    system = str(arguments.get("system") or "")
    messages = arguments.get("messages") or []
    tools = arguments.get("tools") or []

    payload: dict[str, Any] = {
        "model": model,
        "messages": translate_messages(system, messages),
        "stream": False,
    }
    if tools:
        payload["tools"] = translate_tools(tools)

    body_bytes = json.dumps(payload).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    if config.upstream_key:
        headers["Authorization"] = f"Bearer {config.upstream_key}"

    req = urllib.request.Request(endpoint, data=body_bytes, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=config.timeout_sec) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        err_body = exc.read().decode("utf-8", errors="replace")[:300]
        raise RuntimeError(f"Upstream HTTP {exc.code}: {err_body}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Upstream connection error: {exc.reason}") from exc

    parsed = json.loads(raw)
    if not isinstance(parsed, dict):
        raise ValueError("Upstream response is not a JSON object")
    return translate_upstream_response(parsed)


def create_handler(config: MuseBrainConfig):
    class McpHandler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt: str, *args: Any) -> None:
            # Avoid logging headers or tokens; keep output minimal.
            sys.stderr.write("[muse-brain] " + (fmt % args) + "\n")

        def _send_json(self, status: int, payload: dict[str, Any] | None) -> None:
            raw = b"" if payload is None else json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            if raw:
                self.wfile.write(raw)

        def _check_auth(self) -> bool:
            if not config.bridge_token:
                return True
            auth = self.headers.get("Authorization", "")
            return auth == f"Bearer {config.bridge_token}"

        def do_POST(self) -> None:  # noqa: N802
            if not self._check_auth():
                self._send_json(
                    401,
                    {
                        "jsonrpc": "2.0",
                        "id": None,
                        "error": {"code": -32001, "message": "Unauthorized"},
                    },
                )
                return

            length = int(self.headers.get("Content-Length", "0"))
            raw_body = self.rfile.read(length).decode("utf-8", errors="replace")
            try:
                req = json.loads(raw_body)
            except json.JSONDecodeError:
                self._send_json(
                    400,
                    {
                        "jsonrpc": "2.0",
                        "id": None,
                        "error": {"code": -32700, "message": "Parse error"},
                    },
                )
                return

            if not isinstance(req, dict):
                self._send_json(
                    400,
                    {
                        "jsonrpc": "2.0",
                        "id": None,
                        "error": {"code": -32600, "message": "Invalid Request"},
                    },
                )
                return

            method = req.get("method", "")
            req_id = req.get("id")
            params = req.get("params") or {}

            # Notifications (no id) such as notifications/initialized
            if req_id is None:
                self._send_json(202, None)
                return

            if method == "initialize":
                self._send_json(
                    200,
                    {
                        "jsonrpc": "2.0",
                        "id": req_id,
                        "result": {
                            "protocolVersion": PROTOCOL_VERSION,
                            "capabilities": {"tools": {}},
                            "serverInfo": {
                                "name": "muse-brain-bridge",
                                "version": "0.1.0",
                            },
                        },
                    },
                )
                return

            if method == "tools/list":
                self._send_json(
                    200,
                    {
                        "jsonrpc": "2.0",
                        "id": req_id,
                        "result": {"tools": [TOOL_SCHEMA]},
                    },
                )
                return

            if method == "tools/call":
                name = params.get("name") if isinstance(params, dict) else None
                arguments = (
                    params.get("arguments") if isinstance(params, dict) else None
                )
                if name != TOOL_NAME:
                    self._send_json(
                        200,
                        {
                            "jsonrpc": "2.0",
                            "id": req_id,
                            "error": {
                                "code": -32601,
                                "message": f"Tool not found: {name}",
                            },
                        },
                    )
                    return

                if not isinstance(arguments, dict):
                    self._send_json(
                        200,
                        {
                            "jsonrpc": "2.0",
                            "id": req_id,
                            "result": {
                                "content": [
                                    {
                                        "type": "text",
                                        "text": "Invalid arguments: expected JSON object",
                                    }
                                ],
                                "isError": True,
                            },
                        },
                    )
                    return

                try:
                    reply = call_upstream(config, arguments)
                    self._send_json(
                        200,
                        {
                            "jsonrpc": "2.0",
                            "id": req_id,
                            "result": {
                                "content": [
                                    {
                                        "type": "text",
                                        "text": json.dumps(reply),
                                    }
                                ],
                                "isError": False,
                            },
                        },
                    )
                except Exception as exc:  # pylint: disable=broad-except
                    self._send_json(
                        200,
                        {
                            "jsonrpc": "2.0",
                            "id": req_id,
                            "result": {
                                "content": [{"type": "text", "text": str(exc)}],
                                "isError": True,
                            },
                        },
                    )
                return

            self._send_json(
                200,
                {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {
                        "code": -32601,
                        "message": f"Method not found: {method}",
                    },
                },
            )

    return McpHandler


def serve(host: str, port: int, config: MuseBrainConfig) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer((host, port), create_handler(config))
    return server


def main() -> None:
    parser = argparse.ArgumentParser(description="Muse MCP Brain reference bridge server")
    parser.add_argument(
        "--host",
        default=os.environ.get("MUSE_BRAIN_HOST", "127.0.0.1"),
        help="Host to bind (default: 127.0.0.1)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.environ.get("MUSE_BRAIN_PORT", "8765")),
        help="Port to bind (default: 8765)",
    )
    args = parser.parse_args()
    config = MuseBrainConfig.from_env()
    httpd = serve(args.host, args.port, config)
    sys.stderr.write(f"[muse-brain] listening on http://{args.host}:{args.port}/mcp\n")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
