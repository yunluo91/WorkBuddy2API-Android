package com.yunluo.wb2api.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 带工具调用的对话客户端。
 *
 * 为什么不复用 WbClient.sendChat：
 *   WbClient 打的是控制台的 /api/chat/send，那条链路的 payload 只透传
 *   role 和 content（见 wb_console.py 的 stream_chat），tools 和
 *   tool_calls 全被丢掉。要支持工具调用必须走标准接口。
 *
 * 所以这里直接打网关的 /v1/chat/completions（OpenAI 兼容，
 * wb_proxy.py 里有 normalize_tools 处理 tools 字段），
 * 自己解析 SSE 里的 tool_calls 增量。
 */
class AgentChatClient(
    private val baseUrl: String,
    private val token: String,
) {
    companion object {
        private const val TAG = "AgentChat"
        private const val TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 180_000
    }

    // ---------------------------------------------------------------- 事件

    /** 流式过程中的事件。 */
    sealed interface Event {
        /** 正文增量。 */
        data class Content(val delta: String) : Event
        /** 思考过程增量。 */
        data class Reasoning(val delta: String) : Event
        /** 模型开始调用某个工具（只报一次，name 确定时）。 */
        data class ToolStart(val name: String) : Event
        /** 一轮生成结束，带上这一轮累积的工具调用（可能为空）。 */
        data class RoundDone(val toolCalls: List<ToolCall>) : Event
        /** 出错。 */
        data class Error(val message: String) : Event
    }

    /** 一次工具调用。 */
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String,
    )

    // ---------------------------------------------------------------- 请求

    /**
     * 跑一轮对话（流式）。
     *
     * @param messages 完整消息历史（含 role=tool 的结果）
     * @param tools    工具 schema；传空表示不给模型工具
     * @param cancel   取消标志，翻成 true 后尽快退出
     * @param onEvent  每个事件回调一次（在调用方线程里）
     * @return 这一轮累积的工具调用；空表示模型给了最终回答
     */
    fun stream(
        model: String,
        messages: List<JSONObject>,
        tools: List<Map<String, Any>>,
        effort: String,
        cancel: AtomicBoolean,
        onEvent: (Event) -> Unit,
    ): List<ToolCall> {
        var conn: HttpURLConnection? = null
        val calls = LinkedHashMap<Int, MutableToolCall>()
        val started = HashSet<String>()

        try {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray(messages))
                put("stream", true)
                if (tools.isNotEmpty()) {
                    put("tools", JSONArray(tools))
                    // 让模型自己决定要不要用工具。强制 required 会让它在
                    // 简单的问答里也硬塞一个工具调用。
                    put("tool_choice", "auto")
                }
                if (effort.isNotEmpty() && effort != "off") {
                    put("reasoning_effort", effort)
                }
            }

            conn = (URL("${baseUrl.trimEnd('/')}/v1/chat/completions")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "text/event-stream")
                if (token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("X-WB-Token", token)
                }
            }

            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            if (code != 200) {
                val err = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                onEvent(Event.Error("HTTP $code${if (err.isNotEmpty()) "：${err.take(300)}" else ""}"))
                return emptyList()
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    if (cancel.get()) return emptyList()
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue

                    val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue

                    // 上游有时把错误塞在流里
                    chunk.optJSONObject("error")?.let { e ->
                        onEvent(Event.Error(e.optString("message").ifEmpty { "上游返回错误" }))
                        return emptyList()
                    }

                    val delta = chunk.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta") ?: continue

                    // 思考过程（不同上游字段名不一样，兼容两种）
                    val reasoning = delta.optString("reasoning_content")
                        .ifEmpty { delta.optString("reasoning") }
                    if (reasoning.isNotEmpty()) onEvent(Event.Reasoning(reasoning))

                    val content = delta.optString("content")
                    if (content.isNotEmpty()) onEvent(Event.Content(content))

                    collectToolCalls(delta.optJSONArray("tool_calls"), calls, started, onEvent)
                }
            }
            return calls.values.map { it.build() }
        } catch (e: Throwable) {
            if (!cancel.get()) {
                Log.w(TAG, "stream failed", e)
                onEvent(Event.Error(e.message ?: "连接中断"))
            }
            return emptyList()
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 把一次 delta 里的 tool_calls 增量并进累积表。
     *
     * 关键：工具调用的 id / name / arguments 是**分帧**到达的 ——
     * 第一帧给 id 和 name，后续帧只给 arguments 的片段。
     * 所以要按 index 分槽累积，不能每帧当独立的调用。
     */
    private fun collectToolCalls(
        deltas: JSONArray?,
        calls: MutableMap<Int, MutableToolCall>,
        started: MutableSet<String>,
        onEvent: (Event) -> Unit,
    ) {
        if (deltas == null) return
        for (i in 0 until deltas.length()) {
            val row = deltas.optJSONObject(i) ?: continue
            val index = row.optInt("index", i)
            val slot = calls.getOrPut(index) { MutableToolCall() }

            row.optString("id").takeIf { it.isNotEmpty() }?.let { slot.id = it }

            val fn = row.optJSONObject("function") ?: continue
            val name = fn.optString("name")
            if (name.isNotEmpty()) {
                // 只在第一次见到这个名字时广播，避免刷屏
                if (slot.name.isEmpty() && started.add("$index:$name")) {
                    onEvent(Event.ToolStart(name))
                }
                slot.name = name
            }
            fn.optString("arguments").takeIf { it.isNotEmpty() }?.let {
                slot.arguments.append(it)
            }
        }
    }

    private class MutableToolCall {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()

        fun build() = ToolCall(
            id = id.ifEmpty { "call_${System.nanoTime()}" },
            name = name,
            arguments = arguments.toString().ifEmpty { "{}" },
        )
    }
}
