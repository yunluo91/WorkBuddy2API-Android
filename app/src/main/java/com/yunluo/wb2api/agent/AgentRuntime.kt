package com.yunluo.wb2api.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 工具调用的循环。
 *
 * 一轮完整的对话长这样：
 *
 *   ① 发消息（带 tools schema）
 *   ② 模型可能返回 tool_calls
 *   ③ 逐个执行，把结果作为 role=tool 的消息追加进历史
 *   ④ 带着新历史再发一次
 *   ⑤ 直到模型不再要工具，输出最终回答
 *
 * 为什么要有轮数上限：
 *   模型可能陷入"调工具 → 结果不满意 → 再调"的循环。
 *   没有上限的话一次对话能跑几十轮、烧掉大量额度。
 *   上限到了就强行收尾，并在界面上说明。
 */
class AgentRuntime(
    private val client: AgentChatClient,
    private val tools: AgentTools,
    private val approval: ApprovalMode,
    /** 问用户要不要放行。返回 true 表示允许。在后台线程里调，实现方自己切主线程。 */
    private val ask: (summary: String, warning: String?) -> Boolean,
    private val onEvent: (Event) -> Unit,
) {
    companion object {
        private const val TAG = "AgentRuntime"
        /** 一次对话最多跑几轮工具调用。 */
        private const val MAX_ROUNDS = 12
    }

    /** 循环里对外报的事件。 */
    sealed interface Event {
        data class Content(val text: String) : Event
        data class Reasoning(val text: String) : Event
        /** 开始调某个工具。 */
        data class ToolStart(val name: String) : Event
        /** 工具执行完，带上结果摘要（给界面截图用）。 */
        data class ToolDone(val name: String, val ok: Boolean, val text: String) : Event
        /** 用户拒绝了这次工具调用。 */
        data class ToolDenied(val name: String) : Event
        data class Error(val message: String) : Event
        /** 整轮结束。 */
        data object Done : Event
    }

    /**
     * 跑一次完整对话（含任意轮工具调用）。
     *
     * 这个函数会**阻塞**到结束，调用方放进后台线程。
     *
     * @param history 已有的消息历史（会被就地追加）。每条形如
     *                {"role":"user","content":"..."}
     * @return 追加过新消息的同一个列表
     */
    fun run(
        model: String,
        history: MutableList<JSONObject>,
        effort: String,
        cancel: AtomicBoolean,
    ): MutableList<JSONObject> {
        val schemas = AgentTools.schemas()

        for (round in 1..MAX_ROUNDS) {
            if (cancel.get()) return history

            val calls = client.stream(
                model = model,
                messages = history,
                tools = schemas,
                effort = effort,
                cancel = cancel,
            ) { ev ->
                when (ev) {
                    is AgentChatClient.Event.Content -> onEvent(Event.Content(ev.delta))
                    is AgentChatClient.Event.Reasoning -> onEvent(Event.Reasoning(ev.delta))
                    is AgentChatClient.Event.ToolStart -> onEvent(Event.ToolStart(ev.name))
                    is AgentChatClient.Event.Error -> onEvent(Event.Error(ev.message))
                    is AgentChatClient.Event.RoundDone -> Unit
                }
            }

            if (cancel.get()) return history

            // 模型没要工具 —— 这一轮就是最终回答，收工
            if (calls.isEmpty()) {
                onEvent(Event.Done)
                return history
            }

            // 把模型这一轮的"我要调工具"记进历史。
            // 必须带上 tool_calls 的完整结构，否则下一轮上游会拒绝 ——
            // 它要求 tool 消息前面必须有一条声明了对应 tool_call_id 的 assistant 消息。
            history.add(JSONObject().apply {
                put("role", "assistant")
                put("content", "")
                put("tool_calls", JSONArray().apply {
                    for (c in calls) {
                        put(JSONObject().apply {
                            put("id", c.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", c.name)
                                put("arguments", c.arguments)
                            })
                        })
                    }
                })
            })

            // 逐个执行
            for (c in calls) {
                if (cancel.get()) return history

                val args = parseArgs(c.arguments)
                val summary = ToolPolicy.summarize(c.name, args)
                val warning = ToolPolicy.warningFor(c.name, args)

                // 审批
                if (ToolPolicy.needsApproval(approval, c.name)) {
                    val allowed = runCatching { ask(summary, warning) }.getOrDefault(false)
                    if (!allowed) {
                        onEvent(Event.ToolDenied(c.name))
                        // 拒绝也要回一条 tool 消息 —— 否则历史里那个
                        // tool_call_id 没人应答，上游会报错
                        history.add(toolMessage(c.id, "用户拒绝执行这个操作。"))
                        continue
                    }
                }

                val outcome = tools.run(c.name, args)
                onEvent(Event.ToolDone(c.name, outcome.ok, outcome.text))
                history.add(toolMessage(c.id, outcome.text))
            }

            if (round == MAX_ROUNDS) {
                onEvent(Event.Error("工具调用已达上限（$MAX_ROUNDS 轮），先停在这里。可以继续发消息接着来。"))
                onEvent(Event.Done)
                return history
            }
        }

        onEvent(Event.Done)
        return history
    }

    /** 构造一条 role=tool 的消息。 */
    private fun toolMessage(callId: String, text: String): JSONObject =
        JSONObject().apply {
            put("role", "tool")
            put("tool_call_id", callId)
            put("content", text)
        }

    /** 解析工具参数。模型偶尔会给出坏 JSON，别让整轮对话因此崩掉。 */
    private fun parseArgs(raw: String): Map<String, Any?> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val map = HashMap<String, Any?>()
            for (key in obj.keys()) map[key] = obj.opt(key)
            map
        }.getOrElse {
            Log.w(TAG, "bad tool arguments: $raw", it)
            emptyMap()
        }
    }
}
