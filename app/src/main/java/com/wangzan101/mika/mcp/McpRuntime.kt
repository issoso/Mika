package com.wangzan101.mika.mcp

import android.content.Context
import android.content.Intent
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.cap.phone.a11y.PhoneUnavailable
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyDisplay
import com.wangzan101.mika.cap.phone.shell.AppCopyReader
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.mcp.McpServer
import com.wangzan101.mika.core.mcp.McpSessionRegistry
import com.wangzan101.mika.core.mcp.McpTaskHost
import com.wangzan101.mika.core.mcp.McpUsb
import com.wangzan101.mika.core.remote.RelayClient
import com.wangzan101.mika.core.remote.RelayGateway
import com.wangzan101.mika.core.remote.RemoteAccess
import com.wangzan101.mika.core.remote.RemoteActivity
import com.wangzan101.mika.core.remote.RemoteEndpoints
import com.wangzan101.mika.core.remote.RemoteHost
import com.wangzan101.mika.core.remote.RemoteTasks
import com.wangzan101.mika.core.remote.RunExportSource
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.shell.HelperHandoff
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.uistate.AppAccessScreenPort
import com.wangzan101.mika.device.AndroidDeviceAccess
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.McpSettings
import com.wangzan101.mika.settings.McpUsbSettings
import com.wangzan101.mika.settings.RelaySettings
import com.wangzan101.mika.task.RemoteRunExport
import com.wangzan101.mika.task.RemoteTaskHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

sealed interface McpServerState {
    data object Off : McpServerState
    data class Listening(val port: Int) : McpServerState
    data class Failed(val message: String) : McpServerState
}

/**
 * 电脑连接(MCP)的开关与服务:整个进程一份。局域网([settings])和 USB([usb])的监听范围见 [McpModes],
 * 远程网关([relaySettings])使用独立出站通道。有一种开着就起 [McpService] 前台服务,
 * 由各自开关决定本地监听和出站连接;都关了就停止。三种方式的任务共用 [tasks] 和同一协议入口。
 */
class McpRuntime(
    private val context: Context, val settings: McpSettings, val usb: McpUsbSettings, private val tasks: RemoteTasks,
    val device: AndroidDeviceAccess,
    val relaySettings: RelaySettings,
    private val history: com.wangzan101.mika.core.remote.RemoteHistory? = null,
    sessions: McpSessionRegistry? = null,
) {
    val sessions: McpSessionRegistry? = (context.applicationContext as? MikaApp)?.let { app ->
        createDirectDriveSessions(app, settings)
    } ?: sessions
    private val _state = MutableStateFlow<McpServerState>(McpServerState.Off)
    val state: StateFlow<McpServerState> = _state.asStateFlow()
    @Volatile private var host: RemoteHost? = null
    private var hostListen = McpModes.Listen.Off
    private val records: RunExportSource? = (tasks as? RemoteTaskHub)?.let { hub -> RemoteRunExport(hub) { RunStore(context) } }
    private val endpoints = RemoteEndpoints(tasks, BuildConfig.VERSION_NAME, device = device.access, history = history, records = records, sessions = this.sessions)
    private val relayAccess = RemoteAccess(token = { if (relaySettings.config.value.enabled) settings.token() else null })
    // 经网关来的请求要另开「允许经网关唤醒和解锁」才看得到屏幕能力;手机上的远程亮屏与解锁许可照样要开。
    private val relay = RelayClient(CoroutineScope(SupervisorJob() + Dispatchers.IO), { req ->
        relayAccess.handle(req) { endpoints.handle(it, deviceAccess = relaySettings.config.value.deviceAccess, historyAccess = false, directDriveAccess = false) }
    })
    private val relayNetwork = RelayNetwork(context, relay::networkChanged)
    val relayState get() = relay.state
    private val active get() = listen() != McpModes.Listen.Off || relaySettings.config.value.enabled

    /** 最近连过的客户端和最后一次请求;没在监听时为空。 */
    val activity: RemoteActivity get() = if (active) endpoints.activity.value else RemoteActivity()

    /** 配对只保存凭据,必须用户另行开启远程访问。 */
    suspend fun pairRelay(gateway: String, code: String) {
        check(!relaySettings.config.value.paired) { "Unpair the previous gateway first" }
        val remote = RelayGateway(RelayGateway.parse(gateway), RelayGateway.http())
        val paired = remote.pair(code)
        try { relaySettings.save(paired) }
        catch (e: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { remote.unpair(paired.deviceToken) } }
            throw e
        }
    }

    fun setRelayEnabled(on: Boolean) {
        if (!on) { relayNetwork.stop(); relay.stop() }
        try { relaySettings.setEnabled(on) } finally { if (!on) apply() }
        if (on) apply()
    }

    suspend fun unpairRelay() {
        setRelayEnabled(false)
        relaySettings.pairing()?.let { RelayGateway(RelayGateway.parse(it.gateway), RelayGateway.http()).unpair(it.deviceToken) }
        relaySettings.clear()
    }

    fun listen(): McpModes.Listen = McpModes.listen(settings.enabled.value, usb.enabled.value)

    fun setEnabled(on: Boolean) {
        settings.setEnabled(on)
        apply()
    }

    fun setUsbEnabled(on: Boolean) {
        if (on) usb.enable() else usb.disable()
        apply()
    }

    /** 开关变了:还有一种开着就让服务按新的开关重新监听,都关了就停服务。 */
    private fun apply() {
        if (active) ensureRunning() else {
            context.stopService(Intent(context, McpService::class.java))
            _state.value = McpServerState.Off
        }
    }

    /**
     * 开关开着时让前台服务在跑。开机广播在后台收时部分系统可能拒起前台服务:记一行日志就行,
     * 等下次打开 Mika 的界面再起。
     */
    fun ensureRunning() {
        if (!active || com.wangzan101.mika.settings.FeatureFlags.get(context).quitRequested()) return
        runCatching { context.startForegroundService(Intent(context, McpService::class.java)) }
            .onFailure { e ->
                Log.w(TAG, "could not start computer access service", e)
                _state.value = McpServerState.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
    }

    /**
     * 由 [McpService] 调:同步本地监听和远程通道。监听范围没变时复用服务,USB 开着时发布连接信息。
     * 本地监听和远程通道均未启用或未能启动时返回 false。
     */
    @Synchronized internal fun sync(): Boolean {
        if (com.wangzan101.mika.settings.FeatureFlags.get(context).quitRequested()) return false
        val want = listen()
        if (host != null && hostListen != want) stopListener()
        if (want == McpModes.Listen.Off) _state.value = McpServerState.Off
        val ok = want != McpModes.Listen.Off && (host != null || startServer(want))
        if (ok && usb.enabled.value) runCatching { usb.publish(host!!.boundPort) }.onFailure { Log.w(TAG, "cannot write USB connection info", it) }
        else usb.unpublish()
        val remoteOn = relaySettings.config.value.enabled
        if (remoteOn) {
            val pairing = relaySettings.pairing()
            if (pairing != null) { relayNetwork.start(); relay.start(pairing) }
            else { relayNetwork.stop(); relay.stop(); relaySettings.setEnabled(false) }
        } else { relayNetwork.stop(); relay.stop() }
        return ok || remoteOn && relaySettings.config.value.enabled
    }

    private fun startServer(want: McpModes.Listen): Boolean {
        val network = want == McpModes.Listen.PrivateNetworks
        val h = RemoteHost(tasks, { if (settings.enabled.value) settings.token() else null }, settings.port, allowAllInterfaces = network,
            serverVersion = BuildConfig.VERSION_NAME, onError = { Log.w(TAG, "computer access server error", it) },
            loopbackToken = usb::token, device = device.access, endpoints = endpoints,
            directDriveAccess = { settings.directDriveEnabled.value && (this.sessions?.isEnabled ?: true) })
        return try {
            h.start()
            host = h
            hostListen = want
            _state.value = McpServerState.Listening(h.boundPort)
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not bind computer access server on port ${settings.port}", e)
            _state.value = McpServerState.Failed(e.message ?: e::class.simpleName.orEmpty())
            false
        }
    }

    @Synchronized internal fun stopServer() {
        relayNetwork.stop()
        relay.stop()
        stopListener()
        this.sessions?.let { kotlinx.coroutines.runBlocking { it.closeAll() } }
    }

    private fun stopListener() {
        host?.stop()
        host = null
        hostListen = McpModes.Listen.Off
        if (_state.value is McpServerState.Listening) _state.value = McpServerState.Off
        this.sessions?.let { kotlinx.coroutines.runBlocking { it.closeAll() } }
    }

    /** 给电脑跑的「经 USB 连接」脚本:不带秘密,App 启动时写一次(外部存储不可用就跳过)。 */
    fun writeUsbScript() {
        val dir = context.getExternalFilesDir(null) ?: return
        dir.mkdirs()
        val helper = File(dir, HelperHandoff.SCRIPT_NAME).absolutePath
        File(dir, McpUsb.SCRIPT_FILE).writeText(McpUsb.script(context.packageName, helper, File(dir, McpUsb.INFO_FILE).absolutePath))
        // 顺手清掉上次开机留下的连接信息(如果有)
        usb.enabled
    }

    /** 电脑上打开 USB 连接要跑的那条 adb 命令(外部存储路径按本机包名算)。 */
    fun usbCommand(): String {
        val path = (context.getExternalFilesDir(null) ?: File("/sdcard/Android/data/${context.packageName}/files"))
            .let { File(it, McpUsb.SCRIPT_FILE).absolutePath }
        return "adb shell sh $path"
    }

    fun regenerateToken(): String = settings.regenerateToken()

    companion object {
        private const val TAG = "MikaMcp"

        internal fun createDirectDriveSessions(app: MikaApp, settings: McpSettings): McpSessionRegistry {
            val closeCallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
            val assemble: (RunSpec) -> ToolDispatcher = { spec ->
                val metrics = app.resources.displayMetrics
                var nativeScreen: AndroidScreen? = null
                var lazyDisplay: LazyDisplay<AgentDisplay>? = null
                val phoneScreen = LazyPhoneScreen(
                    metrics.widthPixels, metrics.heightPixels,
                    listApps = {
                        withContext(Dispatchers.IO) {
                            launchableApps(app.packageManager, app.packageName)
                        }
                    },
                    appCopies = {
                        app.phone.hub.shell.value?.let { s ->
                            withContext(Dispatchers.IO) {
                                runCatching { AppCopyReader.read(s, launchableApps(app.packageManager, app.packageName)) }.getOrNull()
                            }
                        }.orEmpty()
                    },
                    acquire = {
                        val shell = app.phone.hub.shell.value
                            ?: throw PhoneUnavailable(tr(R.string.task_no_phone_helper))
                        val a11y = ScreenAccessibility.instance.value
                            ?: throw PhoneUnavailable(tr(R.string.task_no_a11y))
                        val display = LazyDisplay(metrics.widthPixels, metrics.heightPixels) {
                            AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
                        }
                        lazyDisplay = display
                        AndroidScreen(
                            a11y = a11y,
                            shell = shell,
                            display = display,
                            appAccess = app.appAccess,
                        ).also { nativeScreen = it }
                    },
                )
                closeCallbacks[spec.id.value] = {
                    runCatching { lazyDisplay?.current?.release() }
                }
                val accessedPhone = AppAccessScreenPort(
                    phoneScreen,
                    app.appAccess,
                    packages = { nativeScreen?.accessPackages().orEmpty() },
                    protectedUi = { app.appAccess.sensitiveUi },
                    ownPackage = app.packageName,
                )
                toolDispatcher(
                    screen = accessedPhone,
                    sandbox = AndroidSandbox(app, app.sandboxState),
                    workspace = { spec.threadId },
                    runSpec = spec,
                )
            }
            val baseServer = McpServer(
                assemble = assemble,
                tasks = McpTaskHost { error("Task submission uses RemoteTaskService") },
            )
            return object : McpSessionRegistry(
                server = baseServer,
                enabled = { settings.directDriveEnabled.value },
            ) {
                override suspend fun openWithStatus(
                    conversation: String?,
                    allowedTools: Set<String>?,
                    owner: String?,
                    resume: Boolean,
                ): OpenOutcome {
                    val outcome = super.openWithStatus(conversation, allowedTools, owner, resume)
                    val id = outcome.session.spec.id.value
                    outcome.session.events.subscribe { event ->
                        if (event is com.wangzan101.mika.core.record.RunEvent.State && event.name == "mcp_session_ended") {
                            closeCallbacks.remove(id)?.invoke()
                        }
                    }
                    return outcome
                }
            }
        }

        /** 本机在局域网或私有 VPN 上的 IPv4 地址(跳过回环、移动数据网卡和链路本地地址)。 */
        fun addresses(): List<String> = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback && !isCellular(it.name) }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress || isCarrierNat(it) }
                .mapNotNull { it.hostAddress }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())

        private fun isCellular(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") || n.startsWith("clat")
        }

        /** 100.64.0.0/10:Tailscale 等私有组网常用这一段。 */
        private fun isCarrierNat(a: Inet4Address): Boolean {
            val b = a.address
            return (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
        }

        fun url(host: String, port: Int): String = "http://$host:$port/mcp"

        /** 主流 MCP 客户端认识的 Streamable HTTP 配置片段。 */
        fun clientConfig(url: String, token: String): String = """
{
  "mcpServers": {
    "mika": {
      "type": "http",
      "url": "$url",
      "headers": {
        "Authorization": "Bearer $token"
      }
    }
  }
}""".trimIndent()
    }
}
