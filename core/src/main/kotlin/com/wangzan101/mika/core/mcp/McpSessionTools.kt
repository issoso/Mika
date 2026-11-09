package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.remote.RemoteTaskService
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 远程直接驱动(Direct Drive)的无模型工具会话注册表。
 * 同一时刻只允许一个活动会话，空闲超过 [idleTimeoutMs] 自动关闭回收；
 * 受手机端「允许电脑直接驱动工具」([enabled])开关门控，默认关闭。
 */
open class McpSessionRegistry(
    internal val server: McpServer,
    private val enabled: () -> Boolean = { true },
    val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    internal val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(
        assemble: (RunSpec) -> ToolDispatcher,
        enabled: () -> Boolean = { true },
        idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        McpServer(assemble, McpTaskHost { error("Task submission uses RemoteTaskService") }, clock = clock),
        enabled = enabled,
        idleTimeoutMs = idleTimeoutMs,
        clock = clock,
    )

    init {
        require(idleTimeoutMs > 0) { "idleTimeoutMs must be positive" }
    }

    private data class Entry(val id: String, val session: McpToolSession, var lastActiveAtMs: Long, val owner: String? = null)

    private val lock = Mutex()
    private var active: Entry? = null

    val isEnabled: Boolean get() = enabled()

    open fun scope(owner: String): McpSessionRegistry = ScopedMcpSessionRegistry(this, owner)

    open suspend fun open(conversation: String? = null, allowedTools: Set<String>? = null, owner: String? = null): McpToolSession = lock.withLock {
        ensureEnabledLocked()
        val now = clock()
        expireIdleLocked(now)
        active?.let { current ->
            throw McpTaskTools.ToolError(
                "A tool session is already open (${current.id}); close it first with $CLOSE."
            )
        }
        val threadId = conversation?.trim()?.takeIf { it.isNotEmpty() } ?: RemoteTaskService.newConversation()
        val session = server.openToolSession(threadId, Attendance.Unattended, allowedTools)
        active = Entry(session.spec.id.value, session, now, owner)
        session
    }

    open suspend fun call(sessionId: String, toolName: String, argumentsJson: String, owner: String? = null): JsonObject {
        val session = lock.withLock {
            ensureEnabledLocked()
            val now = clock()
            val expiredId = expireIdleLocked(now)
            if (expiredId == sessionId) {
                throw McpTaskTools.ToolError("Tool session $sessionId has expired due to inactivity.")
            }
            val current = active?.takeIf { it.id == sessionId }
                ?: throw McpTaskTools.ToolError("No active tool session with id $sessionId")
            if (current.owner != null && owner != null && current.owner != owner) {
                throw McpTaskTools.ToolError("Tool session $sessionId belongs to another client.")
            }
            current.lastActiveAtMs = now
            current.session
        }
        val content = session.call(toolName, argumentsJson)
        lock.withLock {
            active?.takeIf { it.id == sessionId }?.let { it.lastActiveAtMs = clock() }
        }
        if ((content["ok"] as? JsonPrimitive)?.contentOrNull == "false" &&
            (content["error"] as? JsonPrimitive)?.contentOrNull == "unknown_tool"
        ) {
            throw McpTaskTools.ToolError("Unknown session tool: $toolName")
        }
        return content
    }

    open suspend fun close(sessionId: String, owner: String? = null): Boolean = lock.withLock {
        ensureEnabledLocked()
        val now = clock()
        expireIdleLocked(now)
        val current = active?.takeIf { it.id == sessionId }
            ?: throw McpTaskTools.ToolError("No active tool session with id $sessionId")
        if (current.owner != null && owner != null && current.owner != owner) {
            throw McpTaskTools.ToolError("Tool session $sessionId belongs to another client.")
        }
        active = null
        current.session.close()
        true
    }

    suspend fun closeAll() = lock.withLock {
        val current = active ?: return@withLock
        active = null
        current.session.close()
    }

    private suspend fun ensureEnabledLocked() {
        if (!enabled()) {
            active?.let {
                active = null
                it.session.close()
            }
            throw McpTaskTools.ToolError(DISABLED_NOTE)
        }
    }

    private suspend fun expireIdleLocked(now: Long): String? {
        val current = active ?: return null
        if (current.session.isClosed) {
            active = null
            return current.id
        }
        if (now - current.lastActiveAtMs >= idleTimeoutMs) {
            active = null
            current.session.close()
            return current.id
        }
        return null
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 10 * 60_000L
        const val OPEN = "mika_open_tool_session"
        const val CALL = "mika_call_session_tool"
        const val CLOSE = "mika_close_tool_session"
        const val DISABLED_NOTE =
            "Remote direct drive is disabled on the phone. Enable 'Allow remote direct drive' in Connect a computer -> Advanced."
    }
}

/** 暴露给外部大脑(如 Muse)的无模型工具会话 MCP 适配器。 */
class McpSessionTools(private val registry: McpSessionRegistry) {
    val tools: List<McpTaskTools.Tool> = listOf(
        McpTaskTools.Tool(
            name = McpSessionRegistry.OPEN,
            description = "Open a model-less direct-drive tool session on the phone. Freezes the session tool catalog " +
                "and returns session_id and tools. Only one tool session can be open at a time.",
            schema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("conversation") {
                        put("type", "string")
                        put("description", "Optional conversation or thread identifier to associate with this tool session.")
                    }
                    putJsonObject("tools") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Optional allowlist of low-level tool names to expose in this session.")
                    }
                }
                put("required", buildJsonArray {})
                put("additionalProperties", false)
            },
            readOnly = false,
        ),
        McpTaskTools.Tool(
            name = McpSessionRegistry.CALL,
            description = "Call a low-level tool inside an open direct-drive tool session without running an on-phone model. " +
                "All tool calls pass through Mika's ActionExecutor and safety guards.",
            schema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("session_id") {
                        put("type", "string")
                        put("description", "The session_id returned by ${McpSessionRegistry.OPEN}.")
                    }
                    putJsonObject("tool") {
                        put("type", "string")
                        put("description", "The low-level tool name from the session's tool catalog.")
                    }
                    putJsonObject("arguments") {
                        put("type", "object")
                        put("description", "Tool arguments matching the tool's inputSchema.")
                    }
                }
                putJsonArray("required") {
                    add("session_id")
                    add("tool")
                }
                put("additionalProperties", false)
            },
            readOnly = false,
        ),
        McpTaskTools.Tool(
            name = McpSessionRegistry.CLOSE,
            description = "Close an open direct-drive tool session and mark its run record ended.",
            schema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("session_id") {
                        put("type", "string")
                        put("description", "The session_id returned by ${McpSessionRegistry.OPEN}.")
                    }
                }
                putJsonArray("required") { add("session_id") }
                put("additionalProperties", false)
            },
            readOnly = false,
        ),
    )

    suspend fun call(name: String, args: JsonObject): JsonObject = when (name) {
        McpSessionRegistry.OPEN -> {
            val conversation = optString(args, "conversation")
            val filter = optStringSet(args, "tools")
            val session = registry.open(conversation, filter)
            buildJsonObject {
                put("session_id", session.spec.id.value)
                put("conversation", session.spec.threadId)
                put("idle_timeout_ms", registry.idleTimeoutMs)
                putJsonArray("tools") {
                    session.tools().forEach { spec ->
                        add(buildJsonObject {
                            put("name", spec.name)
                            put("description", spec.description)
                            val parsedSchema = runCatching {
                                Json.parseToJsonElement(spec.parametersJsonSchema) as? JsonObject
                            }.getOrNull() ?: buildJsonObject { put("type", "object") }
                            put("inputSchema", parsedSchema)
                            put("parametersJsonSchema", spec.parametersJsonSchema)
                        })
                    }
                }
            }
        }
        McpSessionRegistry.CALL -> {
            val sessionId = requiredString(args, "session_id")
            val toolName = requiredString(args, "tool")
            val argumentsJson = when (val raw = args["arguments"]) {
                null, JsonNull -> "{}"
                is JsonObject -> raw.toString()
                is JsonPrimitive -> if (raw.isString) raw.content else throw McpTaskTools.ToolError("arguments must be a JSON object")
                else -> throw McpTaskTools.ToolError("arguments must be a JSON object")
            }
            val content = registry.call(sessionId, toolName, argumentsJson)
            buildJsonObject {
                put("session_id", sessionId)
                put("tool", toolName)
                put("result", content)
                content.forEach { (k, v) ->
                    if (k != "session_id" && k != "tool" && k != "result") put(k, v)
                }
            }
        }
        McpSessionRegistry.CLOSE -> {
            val sessionId = requiredString(args, "session_id")
            val closed = registry.close(sessionId)
            buildJsonObject {
                put("session_id", sessionId)
                put("closed", closed)
            }
        }
        else -> throw McpTaskTools.ToolError("Unknown tool: $name")
    }

    private fun requiredString(args: JsonObject, key: String): String =
        optString(args, key)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw McpTaskTools.ToolError("$key is required")

    private fun optString(args: JsonObject, key: String): String? = when (val v = args[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else throw McpTaskTools.ToolError("$key must be a string")
        else -> throw McpTaskTools.ToolError("$key must be a string")
    }

    private fun optStringSet(args: JsonObject, key: String): Set<String>? = when (val v = args[key]) {
        null, JsonNull -> null
        is JsonArray -> v.map { item ->
            val p = item as? JsonPrimitive
            if (p == null || !p.isString || p.content.isBlank()) {
                throw McpTaskTools.ToolError("$key must be an array of non-empty strings")
            }
            p.content.trim()
        }.toSet()
        else -> throw McpTaskTools.ToolError("$key must be an array of strings")
    }
}

private class ScopedMcpSessionRegistry(
    private val delegate: McpSessionRegistry,
    private val owner: String,
) : McpSessionRegistry(
    server = delegate.server,
    enabled = { delegate.isEnabled },
    idleTimeoutMs = delegate.idleTimeoutMs,
    clock = delegate.clock,
) {
    override fun scope(owner: String): McpSessionRegistry = delegate.scope(owner)
    override suspend fun open(conversation: String?, allowedTools: Set<String>?, owner: String?): McpToolSession =
        delegate.open(conversation, allowedTools, owner = owner ?: this.owner)
    override suspend fun call(sessionId: String, toolName: String, argumentsJson: String, owner: String?): JsonObject =
        delegate.call(sessionId, toolName, argumentsJson, owner = owner ?: this.owner)
    override suspend fun close(sessionId: String, owner: String?): Boolean =
        delegate.close(sessionId, owner = owner ?: this.owner)
}
