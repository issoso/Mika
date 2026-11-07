package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.cap.mcp.HttpMcpEndpoint
import com.wangzan101.mika.core.cap.mcp.McpEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient

/**
 * Injectable transport for connecting to a remote MCP endpoint.
 * Default implementation delegates to [HttpMcpEndpoint.connect].
 */
fun interface MuseMcpTransport {
    suspend fun connect(url: String, token: String?, http: OkHttpClient): McpEndpoint

    companion object {
        val Default = MuseMcpTransport { url, token, http ->
            HttpMcpEndpoint.connect(url = url, token = token, http = http)
        }
    }
}

/**
 * Muse model provider adapter (B1): delegates each [complete] call to the `muse_complete`
 * tool on a remote MCP server over Streamable HTTP.
 *
 * Non-streaming ([ChatModel] only for v1). Connects once per [complete] call, invokes
 * `muse_complete`, and always closes the endpoint in a `finally` block.
 */
class MuseModel(
    baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    private val transport: MuseMcpTransport = MuseMcpTransport.Default,
) : ChatModel {
    override val name: String get() = model
    private val endpointUrl: String = baseUrl.trim()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val TOOL_NAME = "muse_complete"
        private val HTTP_STATUS_REGEX = Regex("""\bMCP HTTP (\d{3})\b""")
    }

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
    ): ModelReply {
        val args = buildArguments(system, messages, tools)
        val token = apiKey?.takeIf { it.isNotEmpty() }
        val result = try {
            val endpoint = transport.connect(endpointUrl, token, http)
            try {
                endpoint.callTool(TOOL_NAME, args)
            } finally {
                runCatching { endpoint.close() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            throw e
        } catch (e: Throwable) {
            throw mapException(e)
        }

        if (result.isError) {
            val detail = sanitizeMessage(result.content).ifBlank { "MCP tool $TOOL_NAME returned isError=true" }
            throw ModelError.Server("muse_complete tool error: $detail")
        }

        return parseReply(result.content)
    }

    private fun buildArguments(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("system", system)
        putJsonArray("messages") {
            messages.forEach { msg ->
                add(serializeMessage(msg))
            }
        }
        putJsonArray("tools") {
            tools.forEach { tool ->
                add(serializeTool(tool))
            }
        }
    }

    private fun serializeMessage(message: Message): JsonObject = buildJsonObject {
        put(
            "role",
            when (message.role) {
                Role.System -> "system"
                Role.User -> "user"
                Role.Assistant -> "assistant"
                Role.Tool -> "tool"
            },
        )
        putJsonArray("parts") {
            message.parts.forEach { part ->
                add(serializePart(part))
            }
        }
    }

    private fun serializePart(part: Part): JsonObject = buildJsonObject {
        when (part) {
            is Part.Text -> {
                put("type", "text")
                put("text", part.text)
            }
            is Part.Image -> {
                put("type", "image")
                put("pngBase64", part.pngBase64)
                put("mime", part.mime)
                put("width", part.width)
                put("height", part.height)
                if (part.screenshotId != null) {
                    put("screenshotId", part.screenshotId)
                } else {
                    put("screenshotId", JsonNull)
                }
            }
            is Part.ToolCall -> {
                put("type", "tool_call")
                put("id", part.id)
                put("name", part.name)
                put("argsJson", part.argsJson)
            }
            is Part.ToolResult -> {
                put("type", "tool_result")
                put("callId", part.callId)
                put("content", part.content)
                put("isError", part.isError)
            }
            is Part.Thinking -> {
                put("type", "thinking")
                put("text", part.text)
            }
        }
    }

    private fun serializeTool(tool: ToolSpec): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        put("parametersJsonSchema", tool.parametersJsonSchema)
    }

    private fun parseReply(raw: String): ModelReply {
        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            throw ModelError.Server(
                "Malformed JSON in muse_complete result: ${sanitizeMessage(e.message.orEmpty())}",
                e,
            )
        }

        val partsArray = root["parts"] as? JsonArray
            ?: throw ModelError.Server("Malformed muse_complete result: missing 'parts' array")

        val parts = partsArray.map { element ->
            val obj = element as? JsonObject
                ?: throw ModelError.Server("Malformed muse_complete result: part is not a JSON object")
            when (val type = obj.requiredString("type")) {
                "text" -> Part.Text(obj.requiredString("text"))
                "tool_call" -> Part.ToolCall(
                    id = obj.requiredString("id"),
                    name = obj.requiredString("name"),
                    argsJson = obj.requiredString("argsJson"),
                )
                "thinking" -> Part.Thinking(obj.requiredString("text"))
                else -> throw ModelError.Server("Unsupported muse_complete part type: '$type'")
            }
        }

        val stopReason = when (val stop = root.requiredString("stopReason")) {
            "EndTurn" -> StopReason.EndTurn
            "ToolUse" -> StopReason.ToolUse
            "MaxTokens" -> StopReason.MaxTokens
            "Other" -> StopReason.Other
            else -> throw ModelError.Server("Unsupported muse_complete stopReason: '$stop'")
        }

        val usage = parseUsage(root["usage"])
        return ModelReply(parts = parts, stopReason = stopReason, usage = usage)
    }

    private fun parseUsage(element: JsonElement?): Usage? {
        if (element == null || element is JsonNull) return null
        val obj = element as? JsonObject
            ?: throw ModelError.Server("Malformed muse_complete usage: expected object or null")
        val inputTokens = obj.requiredInt("inputTokens")
        val outputTokens = obj.requiredInt("outputTokens")
        val cachedInputTokens = obj.optionalInt("cachedInputTokens") ?: 0
        val reasoningTokens = obj.optionalInt("reasoningTokens") ?: 0
        return Usage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedInputTokens = cachedInputTokens,
            reasoningTokens = reasoningTokens,
        )
    }

    private fun JsonObject.requiredString(key: String): String {
        val primitive = this[key] as? JsonPrimitive
            ?: throw ModelError.Server("Malformed muse_complete result: missing field '$key'")
        if (!primitive.isString) {
            throw ModelError.Server("Malformed muse_complete result: field '$key' must be a string")
        }
        return primitive.content
    }

    private fun JsonObject.requiredInt(key: String): Int {
        val primitive = this[key] as? JsonPrimitive
            ?: throw ModelError.Server("Malformed muse_complete usage: missing field '$key'")
        return primitive.intOrNull
            ?: throw ModelError.Server("Malformed muse_complete usage: field '$key' must be an integer")
    }

    private fun JsonObject.optionalInt(key: String): Int? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        val primitive = el as? JsonPrimitive
            ?: throw ModelError.Server("Malformed muse_complete usage: field '$key' must be an integer")
        return primitive.intOrNull
            ?: throw ModelError.Server("Malformed muse_complete usage: field '$key' must be an integer")
    }

    private fun mapException(e: Throwable): ModelError {
        val rawMsg = sanitizeMessage(e.message.orEmpty())
        val lower = rawMsg.lowercase()
        val httpStatus = HTTP_STATUS_REGEX.find(rawMsg)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return when {
            httpStatus == 401 || httpStatus == 403 ||
                lower.contains("http 401") || lower.contains("http 403") ||
                lower.contains("unauthorized") || lower.contains("forbidden") ||
                lower.contains("authentication failed") || lower.contains("invalid token") ||
                lower.contains("invalid api key") || lower.contains("auth rejection") ->
                ModelError.Auth()

            httpStatus == 429 || lower.contains("http 429") ||
                lower.contains("too many requests") || lower.contains("quota exceeded") ||
                lower.contains("rate limit") ->
                ModelError.QuotaExceeded()

            lower.contains("tool not found") || lower.contains("unknown tool") ||
                lower.contains("no such tool") ||
                (lower.contains("-32601") && lower.contains("tools/call")) ||
                (lower.contains("-32602") && lower.contains("muse_complete") && lower.contains("not found")) ->
                ModelError.ModelNotFound(model, rawMsg.take(200))

            httpStatus != null ->
                ModelError.Server(httpStatus, rawMsg.take(200), e)

            else ->
                ModelError.Server(
                    detail = rawMsg.ifEmpty { e.javaClass.simpleName }.take(200),
                    cause = e,
                )
        }
    }

    private fun sanitizeMessage(msg: String): String {
        val secret = apiKey?.takeIf { it.isNotEmpty() } ?: return msg
        return msg.replace(secret, "***")
    }
}
