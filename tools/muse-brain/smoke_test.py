#!/usr/bin/env python3
"""End-to-end smoke test for tools/muse-brain/server.py against a fake OpenAI upstream."""

from __future__ import annotations

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
from typing import Any
import urllib.request

import server as muse_brain


class _FakeUpstreamState:
    def __init__(self) -> None:
        self.last_headers: dict[str, str] = {}
        self.last_body: dict[str, Any] = {}


def _start_fake_upstream(state: _FakeUpstreamState) -> tuple[ThreadingHTTPServer, str]:
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt: str, *args: Any) -> None:
            pass

        def do_POST(self) -> None:  # noqa: N802
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length).decode("utf-8")
            state.last_headers = {k.lower(): v for k, v in self.headers.items()}
            state.last_body = json.loads(raw)

            reply = {
                "id": "chatcmpl-test",
                "choices": [
                    {
                        "index": 0,
                        "finish_reason": "tool_calls",
                        "message": {
                            "role": "assistant",
                            "reasoning_content": "Need to tap the search bar.",
                            "content": "Searching now.",
                            "tool_calls": [
                                {
                                    "id": "call_1",
                                    "type": "function",
                                    "function": {
                                        "name": "tap",
                                        "arguments": '{"target":"search"}',
                                    },
                                }
                            ],
                        },
                    }
                ],
                "usage": {
                    "prompt_tokens": 120,
                    "completion_tokens": 30,
                    "prompt_tokens_details": {"cached_tokens": 40},
                    "completion_tokens_details": {"reasoning_tokens": 12},
                },
            }
            payload = json.dumps(reply).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    port = httpd.server_address[1]
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    return httpd, f"http://127.0.0.1:{port}/v1"


def _post_json(url: str, payload: dict[str, Any], token: str = "") -> dict[str, Any] | None:
    data = json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=10) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else None


def main() -> None:
    upstream_state = _FakeUpstreamState()
    upstream_srv, upstream_url = _start_fake_upstream(upstream_state)
    config = muse_brain.MuseBrainConfig(
        upstream_url=upstream_url,
        upstream_key="upstream-secret",
        upstream_model="gpt-4.1-mini",
        bridge_token="bridge-token",
    )
    bridge_srv = muse_brain.serve("127.0.0.1", 0, config)
    bridge_port = bridge_srv.server_address[1]
    bridge_url = f"http://127.0.0.1:{bridge_port}/mcp"
    threading.Thread(target=bridge_srv.serve_forever, daemon=True).start()

    try:
        # 1. initialize
        init_res = _post_json(
            bridge_url,
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2025-03-26",
                    "capabilities": {},
                    "clientInfo": {"name": "smoke-test", "version": "1.0"},
                },
            },
            token="bridge-token",
        )
        assert init_res is not None
        assert init_res["result"]["protocolVersion"] == "2025-03-26"

        # 2. notifications/initialized
        _post_json(
            bridge_url,
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            token="bridge-token",
        )

        # 3. tools/list
        list_res = _post_json(
            bridge_url,
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
            token="bridge-token",
        )
        assert list_res is not None
        tools = list_res["result"]["tools"]
        assert len(tools) == 1 and tools[0]["name"] == "muse_complete"

        # 4. tools/call muse_complete
        call_res = _post_json(
            bridge_url,
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {
                    "name": "muse_complete",
                    "arguments": {
                        "model": "muse",
                        "system": "You are Mika.",
                        "messages": [
                            {
                                "role": "user",
                                "parts": [
                                    {"type": "text", "text": "Tap search"},
                                    {
                                        "type": "image",
                                        "pngBase64": "aGVsbG8=",
                                        "mime": "image/png",
                                        "width": 100,
                                        "height": 200,
                                        "screenshotId": 7,
                                    },
                                ],
                            }
                        ],
                        "tools": [
                            {
                                "name": "tap",
                                "description": "Tap an element",
                                "parametersJsonSchema": '{"type":"object","properties":{"target":{"type":"string"}}}',
                            }
                        ],
                    },
                },
            },
            token="bridge-token",
        )
        assert call_res is not None
        result = call_res["result"]
        assert result["isError"] is False
        parsed = json.loads(result["content"][0]["text"])
        assert parsed["stopReason"] == "ToolUse"
        assert parsed["parts"] == [
            {"type": "thinking", "text": "Need to tap the search bar."},
            {"type": "text", "text": "Searching now."},
            {
                "type": "tool_call",
                "id": "call_1",
                "name": "tap",
                "argsJson": '{"target":"search"}',
            },
        ]
        assert parsed["usage"] == {
            "inputTokens": 120,
            "outputTokens": 30,
            "cachedInputTokens": 40,
            "reasoningTokens": 12,
        }

        # Verify upstream translation
        assert upstream_state.last_headers.get("authorization") == "Bearer upstream-secret"
        assert upstream_state.last_body["model"] == "gpt-4.1-mini"
        assert upstream_state.last_body["messages"][0] == {
            "role": "system",
            "content": "You are Mika.",
        }
        assert upstream_state.last_body["tools"][0]["function"]["name"] == "tap"
        print("SMOKE TEST PASSED")
    finally:
        bridge_srv.shutdown()
        bridge_srv.server_close()
        upstream_srv.shutdown()
        upstream_srv.server_close()


if __name__ == "__main__":
    main()
