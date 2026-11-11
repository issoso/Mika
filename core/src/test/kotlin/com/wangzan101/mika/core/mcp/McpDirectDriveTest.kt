package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.remote.HttpRequest
import com.wangzan101.mika.core.remote.RemoteEndpoints
import com.wangzan101.mika.core.remote.RemoteHost
import com.wangzan101.mika.core.run.MemoryRunStore
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class McpDirectDriveTest {
    private var nowMs = 1_000L
    private var directDriveEnabled = true
    private var commandHook: (suspend (String) -> CommandResult)? = null
    private val store = MemoryRunStore()
    private val sandbox = object : SandboxPort {
        override suspend fun runCommand(command: String, timeoutSeconds: Int): CommandResult =
            commandHook?.invoke(command) ?: CommandResult(0, "ran:$command", "")
    }
    private val server = McpServer(
        assemble = { spec -> toolDispatcher(FakeScreen(), sandbox = sandbox, runSpec = spec) },
        tasks = McpTaskHost { error("unused") },
        store = store,
        clock = { nowMs },
    )
    private val registry = McpSessionRegistry(
        server = server,
        enabled = { directDriveEnabled },
        idleTimeoutMs = 60_000L,
        clock = { nowMs },
    )
    private val rpc = McpRpc(McpTaskTools(FakeRemoteTasks(), sessions = registry), "1.0.0")

    private fun post(body: String): JsonObject {
        val req = HttpRequest(
            method = "POST",
            path = McpRpc.PATH,
            headers = mapOf(
                "content-type" to "application/json",
                "mcp-protocol-version" to McpRpc.MODERN,
            ),
            body = body.toByteArray(Charsets.UTF_8),
            remote = InetAddress.getLoopbackAddress(),
        )
        val res = rpc.handle(req)
        assertEquals(200, res.status)
        return Json.parseToJsonElement(res.body.decodeToString()).jsonObject
    }

    private fun callTool(name: String, argsJson: String = "{}"): Pair<Boolean, JsonObject> {
        val reply = post(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","_meta":{"protocolVersion":"${McpRpc.MODERN}","clientCapabilities":{}},"params":{"name":"$name","arguments":$argsJson}}"""
        )
        val result = reply.getValue("result").jsonObject
        val isError = result["isError"]?.jsonPrimitive?.boolean == true
        val text = result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        val parsed = if (isError) {
            JsonObject(mapOf("error" to JsonPrimitive(text)))
        } else {
            Json.parseToJsonElement(text).jsonObject
        }
        return isError to parsed
    }

    @Test
    fun toolsListIncludesThreeDirectDriveSessionToolsWhenProvided() {
        val reply = post(
            """{"jsonrpc":"2.0","id":1,"method":"tools/list","_meta":{"protocolVersion":"${McpRpc.MODERN}","clientCapabilities":{}}}"""
        )
        val names = reply.getValue("result").jsonObject.getValue("tools").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertTrue(names.contains("mika_open_tool_session"))
        assertTrue(names.contains("mika_call_session_tool"))
        assertTrue(names.contains("mika_close_tool_session"))
        assertEquals(10, names.size)
    }

    @Test
    fun openCallAndCloseSessionRoundTripThroughMcpRpc() {
        val (openErr, opened) = callTool(
            "mika_open_tool_session",
            """{"conversation":"mcp-direct-1","tools":["run_command","phone_observe"]}"""
        )
        assertFalse(openErr)
        val sessionId = opened.getValue("session_id").jsonPrimitive.content
        assertEquals("mcp-direct-1", opened.getValue("conversation").jsonPrimitive.content)
        assertFalse(opened.getValue("resumed").jsonPrimitive.boolean)
        val toolNames = opened.getValue("tools").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet()
        assertEquals(setOf("run_command", "phone_observe"), toolNames)

        val (callErr, called) = callTool(
            "mika_call_session_tool",
            """{"session_id":"$sessionId","tool":"run_command","arguments":{"command":"echo hello"}}"""
        )
        assertFalse(callErr)
        assertEquals(sessionId, called.getValue("session_id").jsonPrimitive.content)
        assertEquals("run_command", called.getValue("tool").jsonPrimitive.content)
        assertEquals(0, called.getValue("exitCode").jsonPrimitive.int)
        assertEquals("ran:echo hello", called.getValue("stdout").jsonPrimitive.content)
        assertEquals("ran:echo hello", called.getValue("result").jsonObject.getValue("stdout").jsonPrimitive.content)

        // Unoffered tool is rejected
        val (unofferedErr, unoffered) = callTool(
            "mika_call_session_tool",
            """{"session_id":"$sessionId","tool":"web_navigate","arguments":{"url":"https://example.com"}}"""
        )
        assertTrue(unofferedErr)
        assertTrue(unoffered.getValue("error").jsonPrimitive.content.contains("Unknown session tool"))

        val (closeErr, closed) = callTool(
            "mika_close_tool_session",
            """{"session_id":"$sessionId"}"""
        )
        assertFalse(closeErr)
        assertTrue(closed.getValue("closed").jsonPrimitive.boolean)
        assertEquals(RunPhase.Ended, store.get(RunId(sessionId))?.phase)

        // Calling after close fails
        val (afterCloseErr, _) = callTool(
            "mika_call_session_tool",
            """{"session_id":"$sessionId","tool":"run_command","arguments":{"command":"pwd"}}"""
        )
        assertTrue(afterCloseErr)
    }

    @Test
    fun singleActiveSessionLimitRejectsSecondOpenUntilClosed() {
        val (err1, first) = callTool("mika_open_tool_session")
        assertFalse(err1)
        val id1 = first.getValue("session_id").jsonPrimitive.content

        val (err2, second) = callTool("mika_open_tool_session")
        assertTrue(err2)
        assertTrue(second.getValue("error").jsonPrimitive.content.contains(id1))

        val (closeErr, _) = callTool("mika_close_tool_session", """{"session_id":"$id1"}""")
        assertFalse(closeErr)

        val (err3, third) = callTool("mika_open_tool_session")
        assertFalse(err3)
        val id3 = third.getValue("session_id").jsonPrimitive.content
        callTool("mika_close_tool_session", """{"session_id":"$id3"}""")
    }

    @Test
    fun sameOwnerCanResumeIdleSessionOrReplaceWhenConfigChangesAndRejectsWhenInFlight() = kotlinx.coroutines.runBlocking {
        val clientA = registry.scope("client-a")
        val clientB = registry.scope("client-b")

        // 1. Open initial session for client A
        val first = clientA.openWithStatus(conversation = "mcp-conv-a", allowedTools = setOf("run_command"))
        assertFalse(first.resumed)
        val id1 = first.session.spec.id.value

        // 2. Resuming with identical owner, idle (inFlight == 0), and compatible parameters adopts the same session in-place
        nowMs += 10_000L
        val adopted = clientA.openWithStatus(resume = true, allowedTools = setOf("run_command"))
        assertTrue(adopted.resumed)
        assertEquals(id1, adopted.session.spec.id.value)
        assertEquals("mcp-conv-a", adopted.session.spec.threadId)

        // 3. Unscoped RPC cannot see or hijack clientA's sessionId
        val (rpcOpenErr, rpcOpened) = callTool("mika_open_tool_session", """{"conversation":"mcp-rpc-1"}""")
        assertTrue(rpcOpenErr)
        assertFalse(rpcOpened.getValue("error").jsonPrimitive.content.contains(id1))

        // 4. Different owner cannot resume client A's session and never leaks session_id
        var bResumeFailed = false
        try {
            clientB.open(resume = true)
        } catch (e: McpTaskTools.ToolError) {
            bResumeFailed = true
            assertFalse(e.message.orEmpty().contains(id1))
            assertTrue(e.message.orEmpty().contains("another client"))
        }
        assertTrue(bResumeFailed)

        // 5. Same owner with resume=true and a new tool allowlist replaces the old idle session cleanly
        val replaced = clientA.openWithStatus(
            conversation = "mcp-conv-a2",
            allowedTools = setOf("run_command", "phone_observe"),
            resume = true,
        )
        assertTrue(replaced.resumed)
        val id2 = replaced.session.spec.id.value
        assertNotEquals(id1, id2)
        assertEquals(RunPhase.Ended, store.get(RunId(id1))?.phase)
        assertEquals("mcp-conv-a2", replaced.session.spec.threadId)

        // 6. While a tool call is in-flight (inFlight > 0), even the same owner with resume=true cannot replace/resume it
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        commandHook = { cmd ->
            started.complete(Unit)
            release.await()
            CommandResult(0, "done:$cmd", "")
        }
        val inFlightJob = async {
            clientA.call(id2, "run_command", """{"command":"sleep 1"}""")
        }
        started.await()

        var inFlightResumeFailed = false
        try {
            clientA.open(resume = true)
        } catch (e: McpTaskTools.ToolError) {
            inFlightResumeFailed = true
            assertTrue(e.message.orEmpty().contains("in-flight"))
        } finally {
            release.complete(Unit)
            commandHook = null
        }
        assertTrue(inFlightResumeFailed)
        val callResult = inFlightJob.await()
        assertEquals("done:sleep 1", callResult.getValue("stdout").jsonPrimitive.content)

        assertTrue(clientA.close(id2))
    }

    @Test
    fun idleSessionExpiresAndAllowsOpeningNewSession() {
        val (err1, first) = callTool("mika_open_tool_session")
        assertFalse(err1)
        val id1 = first.getValue("session_id").jsonPrimitive.content

        nowMs += 60_000L
        val (expiredCallErr, expiredMsg) = callTool(
            "mika_call_session_tool",
            """{"session_id":"$id1","tool":"run_command","arguments":{"command":"ls"}}"""
        )
        assertTrue(expiredCallErr)
        assertTrue(expiredMsg.getValue("error").jsonPrimitive.content.contains("expired"))
        assertEquals(RunPhase.Ended, store.get(RunId(id1))?.phase)

        val (err2, second) = callTool("mika_open_tool_session")
        assertFalse(err2)
        val id2 = second.getValue("session_id").jsonPrimitive.content
        callTool("mika_close_tool_session", """{"session_id":"$id2"}""")
    }

    @Test
    fun disablingDirectDriveClosesActiveSessionAndRejectsCalls() {
        val (err1, first) = callTool("mika_open_tool_session")
        assertFalse(err1)
        val id1 = first.getValue("session_id").jsonPrimitive.content

        directDriveEnabled = false
        val (disabledErr, disabledMsg) = callTool(
            "mika_call_session_tool",
            """{"session_id":"$id1","tool":"run_command","arguments":{"command":"ls"}}"""
        )
        assertTrue(disabledErr)
        assertTrue(disabledMsg.getValue("error").jsonPrimitive.content.contains("Remote direct drive is disabled"))
        assertEquals(RunPhase.Ended, store.get(RunId(id1))?.phase)

        val (openWhileOffErr, _) = callTool("mika_open_tool_session")
        assertTrue(openWhileOffErr)
    }

    @Test
    fun remoteEndpointsHidesSessionToolsWhenDirectDriveAccessIsFalse() {
        val endpoints = RemoteEndpoints(FakeRemoteTasks(), "1.0.0", sessions = registry)
        val listBody = """{"jsonrpc":"2.0","id":1,"method":"tools/list","_meta":{"protocolVersion":"${McpRpc.MODERN}","clientCapabilities":{}}}"""
        val req = HttpRequest(
            method = "POST",
            path = McpRpc.PATH,
            headers = mapOf(
                "content-type" to "application/json",
                "mcp-protocol-version" to McpRpc.MODERN,
            ),
            body = listBody.toByteArray(Charsets.UTF_8),
            remote = InetAddress.getLoopbackAddress(),
        )
        val localTools = Json.parseToJsonElement(endpoints.handle(req, directDriveAccess = true).body.decodeToString())
            .jsonObject.getValue("result").jsonObject.getValue("tools").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertTrue(localTools.contains("mika_open_tool_session"))

        val relayTools = Json.parseToJsonElement(endpoints.handle(req, directDriveAccess = false).body.decodeToString())
            .jsonObject.getValue("result").jsonObject.getValue("tools").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertFalse(relayTools.contains("mika_open_tool_session"))
    }

    @Test
    fun remoteHostDynamicallyEvaluatesDirectDriveAccess() {
        var directDriveSwitch = false
        val host = RemoteHost(
            tasks = FakeRemoteTasks(),
            token = { null },
            port = 0,
            allowAllInterfaces = false,
            serverVersion = "1.0.0",
            sessions = registry,
            directDriveAccess = { directDriveSwitch },
        )
        val listBody = """{"jsonrpc":"2.0","id":1,"method":"tools/list","_meta":{"protocolVersion":"${McpRpc.MODERN}","clientCapabilities":{}}}"""
        val req = HttpRequest(
            method = "POST",
            path = McpRpc.PATH,
            headers = mapOf(
                "content-type" to "application/json",
                "mcp-protocol-version" to McpRpc.MODERN,
            ),
            body = listBody.toByteArray(Charsets.UTF_8),
            remote = InetAddress.getLoopbackAddress(),
        )
        val offTools = Json.parseToJsonElement(host.handle(req).body.decodeToString())
            .jsonObject.getValue("result").jsonObject.getValue("tools").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertFalse(offTools.contains("mika_open_tool_session"))

        directDriveSwitch = true
        val onTools = Json.parseToJsonElement(host.handle(req).body.decodeToString())
            .jsonObject.getValue("result").jsonObject.getValue("tools").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertTrue(onTools.contains("mika_open_tool_session"))
    }

    @Test
    fun sessionOwnerIsolationPreventsCrossClientAccess() = kotlinx.coroutines.runBlocking {
        val clientA = registry.scope("client-a")
        val clientB = registry.scope("client-b")

        val sessionA = clientA.open()
        val sessionId = sessionA.spec.id.value

        var bOpenFailed = false
        try {
            clientB.open()
        } catch (e: McpTaskTools.ToolError) {
            bOpenFailed = true
            assertFalse(e.message.orEmpty().contains(sessionId))
            assertTrue(e.message?.contains("another client") == true)
        }
        assertTrue(bOpenFailed)

        var bCallFailed = false
        try {
            clientB.call(sessionId, "run_command", """{"command":"echo hi"}""")
        } catch (e: McpTaskTools.ToolError) {
            bCallFailed = true
            assertTrue(e.message?.contains("belongs to another client") == true)
        }
        assertTrue(bCallFailed)

        // Raw registry without owner cannot call or close Client A's session (Fail-closed)
        var rawCallFailed = false
        try {
            registry.call(sessionId, "run_command", """{"command":"echo hi"}""")
        } catch (e: McpTaskTools.ToolError) {
            rawCallFailed = true
            assertTrue(e.message?.contains("belongs to another client") == true)
        }
        assertTrue(rawCallFailed)

        var rawCloseFailed = false
        try {
            registry.close(sessionId)
        } catch (e: McpTaskTools.ToolError) {
            rawCloseFailed = true
            assertTrue(e.message?.contains("belongs to another client") == true)
        }
        assertTrue(rawCloseFailed)

        assertTrue(clientA.close(sessionId))
    }
}
