package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.execution
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.MemoryRunStore
import com.wangzan101.mika.core.run.RunEventBus
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.RunStore
import com.wangzan101.mika.core.run.StoredRun
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** 传输层桩实现这个接口，外部连接只能拿到 ID 和事件流。 */
fun interface McpTaskHost {
    fun submit(spec: RunSpec): McpTaskLink
}

class McpTaskLink(val id: RunId, val events: RunEventBus, val cancel: () -> Unit = {})

/**
 * 无模型的工具会话：工具目录冻结，每次调用仍经过 [ActionExecutor]（守卫、风险分级、事件记录、截图落盘都在），
 * 不会绕过安全边界。
 */
class McpToolSession internal constructor(
    val spec: RunSpec,
    private val tools: ToolDispatcher,
    private val store: RunStore,
    approvals: ApprovalSettings,
    private val clock: () -> Long,
    allowedTools: Set<String>? = null,
    private val onClose: () -> Unit = {},
) {
    val events = RunEventBus()
    private val lock = Mutex()
    private var step = 0
    private var closed = false
    private val definitions = tools.definitions().let { all ->
        if (allowedTools == null) all else all.filter { it.name in allowedTools }
    }
    private val offered = definitions.map { it.name }.toSet()
    private val executor = ActionExecutor(tools, OperationGuards(), null, gate = {},
        recorder = ActionRecorder(events, clock), offScreen = tools.offScreenGuard(), approvals = approvals,
        provenance = spec.provenance, autonomous = approvals.autonomous())

    init {
        store.save(StoredRun(spec.id, spec.threadId, spec.goal, RunPhase.Running, roundInProgress = true))
        events.event(RunEvent.State(clock(), "mcp_session_started", spec.id.value))
    }

    val isClosed: Boolean get() = closed

    fun tools() = definitions

    suspend fun call(name: String, argumentsJson: String): JsonObject = lock.withLock {
        if (closed) return@withLock error("session_closed")
        if (name !in offered) return@withLock error("unknown_tool")
        step++
        val call = Part.ToolCall("mcp-$step", name, argumentsJson)
        executor.execute(call, Origin.McpTool(step)).execution().content
    }

    suspend fun close() = lock.withLock {
        if (closed) return@withLock
        closed = true
        runCatching { onClose() }
        store.get(spec.id)?.let { store.save(it.copy(phase = RunPhase.Ended, roundInProgress = false)) }
        events.event(RunEvent.State(clock(), "mcp_session_ended", spec.id.value))
    }

    private fun error(code: String) = buildJsonObject { put("ok", false); put("error", code) }
}

/**
 * MCP 服务端门面：不绑传输层（socket/stdio 后续再接），提供“工具级会话”和“整任务提交”两种能力，
 * 产生的运行统一带 `origin=Mcp`。
 */
class McpServer(
    private val assemble: (RunSpec) -> ToolDispatcher,
    private val tasks: McpTaskHost,
    private val store: RunStore = MemoryRunStore(),
    private val approvals: ApprovalSettings = ApprovalSettings { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun openToolSession(
        threadId: String,
        attendance: Attendance = Attendance.Unattended,
        allowedTools: Set<String>? = null,
        onClose: () -> Unit = {},
    ): McpToolSession {
        val spec = RunSpec(RunId(UUID.randomUUID().toString()), "MCP tool session", threadId,
            provenance = RunProvenance(RunOrigin.Mcp, attendance))
        return McpToolSession(spec, assemble(spec), store, approvals, clock, allowedTools, onClose)
    }

    fun submitTask(goal: String, threadId: String, attendance: Attendance = Attendance.Unattended): McpTaskLink {
        val spec = RunSpec(RunId(UUID.randomUUID().toString()), goal, threadId,
            provenance = RunProvenance(RunOrigin.Mcp, attendance))
        return tasks.submit(spec)
    }
}
