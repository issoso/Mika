package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.cap.mcp.McpEndpoint
import com.wangzan101.mika.core.cap.mcp.McpException
import com.wangzan101.mika.core.cap.mcp.McpRemoteTool
import com.wangzan101.mika.core.cap.mcp.McpToolCallResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MuseModelTest {
    private val defaultCaps = Capabilities(
        toolCalls = true,
        vision = true,
        groundingVerified = false,
        contextTokens = 128_000,
        promptCaching = PromptCaching.Implicit,
    )

    private class FakeEndpoint(
        private val onCall: suspend (String, JsonObject) -> McpToolCallResult,
    ) : McpEndpoint {
        var closed: Boolean = false
        var calls: Int = 0

        override suspend fun listTools(): List<McpRemoteTool> = emptyList()

        override suspend fun callTool(name: String, arguments: JsonObject): McpToolCallResult {
            calls++
            return onCall(name, arguments)
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun successfulParseMapsThinkingTextToolCallAndUsageAndClosesEndpoint() = runTest {
        var capturedUrl: String? = null
        var capturedToken: String? = null
        var capturedToolName: String? = null
        var capturedArgs: JsonObject? = null
        lateinit var endpoint: FakeEndpoint

        val transport = MuseMcpTransport { url, token, _ ->
            capturedUrl = url
            capturedToken = token
            FakeEndpoint { name, args ->
                capturedToolName = name
                capturedArgs = args
                McpToolCallResult(
                    content = """
                        {
                          "parts": [
                            {"type": "thinking", "text": "Let me check the screen."},
                            {"type": "text", "text": "Tapping the search button."},
                            {"type": "tool_call", "id": "call_42", "name": "tap", "argsJson": "{\"nodeId\":12}"}
                          ],
                          "stopReason": "ToolUse",
                          "usage": {
                            "inputTokens": 320,
                            "outputTokens": 45,
                            "cachedInputTokens": 128,
                            "reasoningTokens": 19
                          }
                        }
                    """.trimIndent(),
                    isError = false,
                )
            }.also { endpoint = it }
        }

        val model = MuseModel(
            baseUrl = "https://muse.example.com/mcp",
            apiKey = "secret-token-123",
            model = "muse",
            capabilities = defaultCaps,
            http = OkHttpClient(),
            transport = transport,
        )

        val reply = model.complete(
            system = "You are Mika.",
            messages = listOf(
                Message(Role.User, listOf(Part.Text("Open search"))),
                Message(
                    Role.Assistant,
                    listOf(
                        Part.Thinking("Prior thought"),
                        Part.ToolCall("call_1", "observe", "{}"),
                    ),
                ),
                Message(
                    Role.Tool,
                    listOf(Part.ToolResult("call_1", "screen ok", isError = false)),
                ),
            ),
            tools = listOf(
                ToolSpec(
                    name = "tap",
                    description = "Tap element",
                    parametersJsonSchema = """{"type":"object","properties":{"nodeId":{"type":"integer"}}}""",
                ),
            ),
        )

        assertEquals("https://muse.example.com/mcp", capturedUrl)
        assertEquals("secret-token-123", capturedToken)
        assertEquals("muse_complete", capturedToolName)
        assertTrue(endpoint.closed)
        assertEquals(1, endpoint.calls)

        val args = capturedArgs!!
        assertEquals("muse", args["model"]!!.jsonPrimitive.content)
        assertEquals("You are Mika.", args["system"]!!.jsonPrimitive.content)
        val toolsArray = args["tools"]!!.jsonArray
        assertEquals(1, toolsArray.size)
        assertEquals("tap", toolsArray[0].jsonObject["name"]!!.jsonPrimitive.content)

        val msgs = args["messages"]!!.jsonArray
        assertEquals(3, msgs.size)
        val toolResultPart = msgs[2].jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", toolResultPart["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", toolResultPart["callId"]!!.jsonPrimitive.content)
        assertEquals("screen ok", toolResultPart["content"]!!.jsonPrimitive.content)
        assertFalse(toolResultPart["isError"]!!.jsonPrimitive.boolean)

        assertEquals(StopReason.ToolUse, reply.stopReason)
        assertEquals(
            listOf(
                Part.Thinking("Let me check the screen."),
                Part.Text("Tapping the search button."),
                Part.ToolCall("call_42", "tap", """{"nodeId":12}"""),
            ),
            reply.parts,
        )
        assertEquals(
            Usage(
                inputTokens = 320,
                outputTokens = 45,
                cachedInputTokens = 128,
                reasoningTokens = 19,
            ),
            reply.usage,
        )
    }

    @Test
    fun imagePartPassthroughPreservesBase64AndScreenshotIdAndNullUsageStaysNull() = runTest {
        var capturedArgs: JsonObject? = null
        val transport = MuseMcpTransport { _, _, _ ->
            FakeEndpoint { _, args ->
                capturedArgs = args
                McpToolCallResult(
                    content = """{"parts":[{"type":"text","text":"Done"}],"stopReason":"EndTurn","usage":null}""",
                    isError = false,
                )
            }
        }

        val model = MuseModel(
            baseUrl = "http://127.0.0.1:8765/mcp",
            apiKey = null,
            model = "muse-v1",
            capabilities = defaultCaps,
            http = OkHttpClient(),
            transport = transport,
        )

        val reply = model.complete(
            system = "sys",
            messages = listOf(
                Message(
                    Role.User,
                    listOf(
                        Part.Text("Look at this screenshot"),
                        Part.Image(
                            pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB",
                            mime = "image/png",
                            width = 1080,
                            height = 2400,
                            screenshotId = 99,
                        ),
                    ),
                ),
            ),
            tools = emptyList(),
        )

        val parts = capturedArgs!!["messages"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        val img = parts[1].jsonObject
        assertEquals("image", img["type"]!!.jsonPrimitive.content)
        assertEquals("iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB", img["pngBase64"]!!.jsonPrimitive.content)
        assertEquals("image/png", img["mime"]!!.jsonPrimitive.content)
        assertEquals(1080, img["width"]!!.jsonPrimitive.int)
        assertEquals(2400, img["height"]!!.jsonPrimitive.int)
        assertEquals(99, img["screenshotId"]!!.jsonPrimitive.int)

        assertEquals(StopReason.EndTurn, reply.stopReason)
        assertEquals(listOf(Part.Text("Done")), reply.parts)
        assertNull(reply.usage)
    }

    @Test
    fun errorMappingMaps401ToAuth429ToQuotaToolNotFoundToModelNotFoundAndOtherErrorsToServer() = runTest {
        // 1. 401 -> ModelError.Auth
        val authModel = MuseModel(
            baseUrl = "https://muse.example.com/mcp",
            apiKey = "bad-token",
            model = "muse",
            capabilities = defaultCaps,
            http = OkHttpClient(),
            transport = MuseMcpTransport { _, _, _ ->
                throw McpException("MCP HTTP 401: Unauthorized")
            },
        )
        try {
            authModel.complete("s", emptyList(), emptyList())
            fail("Expected ModelError.Auth")
        } catch (e: ModelError.Auth) {
            // expected
        }

        // 2. 429 -> ModelError.QuotaExceeded
        val quotaModel = MuseModel(
            baseUrl = "https://muse.example.com/mcp",
            apiKey = "tok",
            model = "muse",
            capabilities = defaultCaps,
            http = OkHttpClient(),
            transport = MuseMcpTransport { _, _, _ ->
                throw McpException("MCP HTTP 429: Too Many Requests")
            },
        )
        try {
            quotaModel.complete("s", emptyList(), emptyList())
            fail("Expected ModelError.QuotaExceeded")
        } catch (e: ModelError.QuotaExceeded) {
            // expected
        }

        // 3. Tool not found -> ModelError.ModelNotFound
        lateinit var notFoundEndpoint: FakeEndpoint
        val notFoundModel = MuseModel(
            baseUrl = "https://muse.example.com/mcp",
            apiKey = "tok",
            model = "muse",
            capabilities = defaultCaps,
            http = OkHttpClient(),
            transport = MuseMcpTransport { _, _, _ ->
                FakeEndpoint { _, _ ->
                    throw McpException("MCP tools/call error (-32601): Tool not found: muse_complete")
                }.also { notFoundEndpoint = it }
            },
        )
        try {
            notFoundModel.complete("s", emptyList(), emptyList())
            fail("Expected ModelError.ModelNotFound")
        } catch (e: ModelError.ModelNotFound) {
            assertEquals("muse", e.model)
            assertTrue(notFoundEndpoint.closed)
        }

        // 4. isError=true result -> ModelError.Server
        lateinit var isErrorEndpoint: FakeEndpoint
        val isErrorModel = MuseModel(
            baseUrl = "https://muse.example.com/mcp",
            apiKey = "tok",
            model = "muse",
            capabilities = defaultCaps,
            http = OkHttpClient(),
            transport = MuseMcpTransport { _, _, _ ->
                FakeEndpoint { _, _ ->
                    McpToolCallResult(content = "Upstream gateway timeout", isError = true)
                }.also { isErrorEndpoint = it }
            },
        )
        try {
            isErrorModel.complete("s", emptyList(), emptyList())
            fail("Expected ModelError.Server for isError=true")
        } catch (e: ModelError.Server) {
            assertTrue(e.message!!.contains("Upstream gateway timeout"))
            assertTrue(isErrorEndpoint.closed)
        }

        // 5. Malformed JSON -> ModelError.Server
        lateinit var malformedEndpoint: FakeEndpoint
        val malformedModel = MuseModel(
            baseUrl = "https://muse.example.com/mcp",
            apiKey = "tok",
            model = "muse",
            capabilities = defaultCaps,
            http = OkHttpClient(),
            transport = MuseMcpTransport { _, _, _ ->
                FakeEndpoint { _, _ ->
                    McpToolCallResult(content = "{not valid json", isError = false)
                }.also { malformedEndpoint = it }
            },
        )
        try {
            malformedModel.complete("s", emptyList(), emptyList())
            fail("Expected ModelError.Server for malformed JSON")
        } catch (e: ModelError.Server) {
            assertTrue(e.message!!.contains("Malformed JSON"))
            assertTrue(malformedEndpoint.closed)
        }
    }
}
