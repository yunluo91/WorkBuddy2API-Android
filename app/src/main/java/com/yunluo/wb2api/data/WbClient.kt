package com.yunluo.wb2api.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 控制台 HTTP 接口的客户端。
 *
 * 只做三件事：拉调用记录、拉/改会话、跑流式对话。
 * 所有网络调用都在调用方给的线程里执行 —— 这里不开线程、不持有 Context，
 * 免得跟 Activity 生命周期缠在一起。
 *
 * 认证：控制台启动时会在页面里注入一个令牌（meta wb-token），
 * 所有 /api/ 开头的路径都要带 X-WB-Token。这里从 WbApi.token 读。
 */
class WbClient(
    private val baseUrl: String,
    private val token: String,
) {
    companion object {
        private const val TAG = "WbClient"
        private const val TIMEOUT_MS = 12_000
    }

    // ------------------------------------------------------------ 调用记录

    /**
     * 拉调用记录。limit 上限 1000（网关那边就掐在 1000）。
     *
     * `only` 传 "ok" / "bad" 做筛选，null 表示全部。
     */
    /**
     * 拉调用记录。
     *
     * @param realm  "" 或 "all" 全部；"cn" 只看国内版；"intl" 只看国际版
     */
    fun calls(
        limit: Int = 1000,
        only: String? = null,
        realm: String = "all",
    ): CallsPage {
        val body = JSONObject().apply {
            put("limit", limit)
            if (only != null) put("only", only)
            if (realm.isNotEmpty()) put("realm", realm)
        }
        val json = postJson("api/calls", body) ?: return CallsPage()
        return parseCalls(json)
    }

    private fun parseCalls(json: JSONObject): CallsPage {
        val arr = json.optJSONArray("rows") ?: JSONArray()
        val list = ArrayList<CallRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rates = o.optJSONObject("rates")
            list.add(
                CallRecord(
                    // 【关键】网关的 at 是**秒**（time.time()），不是毫秒。
                    // 直接当毫秒传给 java.util.Date 会算出 1970 年 ——
                    // 屏幕上就是"明明是今天，却显示 1 月 22 日"。
                    at = epochToMillis(o.optDouble("at", 0.0)),
                    model = o.optString("model"),
                    ok = o.optBoolean("ok"),
                    reason = o.optString("reason"),
                    promptTokens = o.optInt("pt"),
                    completionTokens = o.optInt("ct"),
                    totalTokens = o.optInt("tt"),
                    credit = o.optDouble("credit", 0.0),
                    costCny = if (o.isNull("cny")) null else o.optDouble("cny"),
                    rateHit = rates?.num("hit"),
                    rateMiss = rates?.num("miss"),
                    rateOut = rates?.num("out"),
                    elapsedMs = o.optInt("ms"),
                    realm = o.optString("realm"),
                )
            )
        }
        val s = json.optJSONObject("stats") ?: JSONObject()
        return CallsPage(
            records = list,
            stats = CallStats(
                calls = s.optInt("ok"),
                failures = s.optInt("bad"),
                promptTokens = s.optLong("prompt_tokens"),
                completionTokens = s.optLong("completion_tokens"),
                credits = s.optDouble("credit", 0.0),
            ),
        )
    }

    /** 清空调用记录。 */
    fun clearCalls(): Boolean =
        postJson("api/calls", JSONObject().put("clear", true)) != null

    // ------------------------------------------------------------ 网关 Key

    /**
     * 从控制台拿当前的 API Key 列表。
     *
     * 为什么需要：工具调用走网关的 /v1/chat/completions，那条路由要 API Key
     * （_authorized → _key_ok → identify_key）。控制台令牌（X-WB-Token）
     * 那是另一套东西，网关不认。
     *
     * 所以开工具之前先取一把启用中的 Key 带上，否则一开工具就 401，
     * 消息发不出去 —— 表现就是"一开工具对话就被吞了"。
     *
     * 返回第一把启用中的 Key；一把都没有就返回空串。
     */
    fun gatewayKey(): String {
        // 注意：/api/keys 注册在控制台的 do_POST 里，GET 会 404
        val json = postJson("api/keys", JSONObject()) ?: return ""
        // 实际字段是 keys（不是 live）—— 实测 /api/keys 返回
        // {"keys":[...],"deleted":[...],"auth_on":true,"count":1,...}
        val arr = json.optJSONArray("keys") ?: return ""
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.optBoolean("enabled", true)) continue
            val k = o.optString("key")
            if (k.isNotEmpty()) return k
        }
        return ""
    }

    // ------------------------------------------------------------ 模型目录

    /**
     * 拉模型目录。`/api/models` 只给 id / 思考档位 / 上下文长度，
     * 没有名字和倍率 —— 名字就用 id，倍率在调用记录里另算。
     */
    /**
     * 拉模型目录。
     *
     * WB-MODEL-NATIVE
     * 改成直接请求**网关**的 /v1/models —— 那条路一直是最稳的：
     * 看板页（8788 自己）就是用这个接口渲染的，从来没出过问题。
     *
     * 为什么不再走控制台的 /api/models：
     *   那条路要经过 控制台Python → REGISTRY → 网关Python → 上游，
     *   中间还套了异步线程和超时，任何一环卡住界面就一直"加载中"。
     *   Android 这边直接用 HTTP 打网关，链路最短。
     *
     * @param gatewayBase 网关地址（不是控制台地址）
     * @param apiKey      网关 API Key。/v1/models 要鉴权，控制台令牌它不认。
     * @param realm       "intl" / "cn"
     */
    fun modelsFromGateway(
        gatewayBase: String,
        apiKey: String,
        realm: String = "intl",
    ): List<HubModel> {
        val base = gatewayBase.trimEnd('/')
        val url = "$base/v1/models?realm=$realm"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 12000   // 网关要走上游，给足
                if (apiKey.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "modelsFromGateway HTTP ${conn.responseCode}")
                return emptyList()
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val arr = json.optJSONArray("data") ?: return emptyList()
            val out = ArrayList<HubModel>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val caps = o.optJSONObject("capabilities")
                val effortsArr = o.optJSONArray("reasoning_efforts")
                val efforts = ArrayList<String>()
                if (effortsArr != null) {
                    for (j in 0 until effortsArr.length()) {
                        effortsArr.optString(j).takeIf { it.isNotEmpty() }?.let { efforts.add(it) }
                    }
                }
                out.add(
                    HubModel(
                        id = id,
                        name = o.optString("name").ifEmpty { id },
                        vendor = o.optString("vendor"),
                        multiplier = parseCredits(o.optString("credits")),
                        efforts = efforts,
                        contextWindow = o.optInt("context_length", 0),
                        maxOutput = o.optInt("max_output_tokens", 0),
                        vision = caps?.optBoolean("vision") == true,
                        toolCalls = caps?.optBoolean("tool_calls") == true,
                        reasoning = caps?.optBoolean("reasoning") == true,
                    )
                )
            }
            out
        } catch (e: Throwable) {
            Log.w(TAG, "modelsFromGateway failed", e)
            emptyList()
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 解析 credits 字段。上游给的是 "x0.08" 这种。 */
    private fun parseCredits(raw: String): Double {
        if (raw.isEmpty()) return -1.0
        val cleaned = raw.trim().lowercase().removePrefix("x").removeSuffix("x")
        return cleaned.toDoubleOrNull() ?: -1.0
    }

    // ------------------------------------------------------------ 账号导入导出

    /**
     * 用面板密码换一个面板会话令牌。
     *
     * 为什么不用 API Key：/accounts/ 开头的那一片走的是 _panel_ok()，
     * 只认 X-Panel-Token，不认 Bearer。实测带 API Key 会返回
     * "panel password required"。
     *
     * 令牌是内存里的，进程重启就失效，所以每次操作现换一个。
     */
    fun panelToken(gatewayBase: String, panelPassword: String): String? {
        if (panelPassword.isEmpty()) return null
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("${gatewayBase.trimEnd('/')}/panel/login")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("Content-Type", "application/json")
            }
            val body = JSONObject().put("password", panelPassword)
                .toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode != 200) {
                Log.w(TAG, "panelToken HTTP ${conn.responseCode}")
                return null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text).optString("token").ifEmpty { null }
        } catch (e: Throwable) {
            Log.w(TAG, "panelToken failed", e)
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 导出全部账号（国内 + 国际）。
     *
     * GET 网关 /accounts/export?secrets=1
     * 不带 realm 参数 = 两个版本全导 —— 这正是面板那个导出按钮
     * 在安卓 WebView 里做不到的事（它靠浏览器下载）。
     *
     * @return 导出文档的原始 JSON 文本；失败返回 null
     */
    fun exportAccounts(gatewayBase: String, panelPassword: String): String? {
        val token = panelToken(gatewayBase, panelPassword) ?: return null
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("${gatewayBase.trimEnd('/')}/accounts/export?secrets=1")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 25000
                setRequestProperty("X-Panel-Token", token)
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "exportAccounts HTTP ${conn.responseCode}")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Throwable) {
            Log.w(TAG, "exportAccounts failed", e)
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 导入账号。
     *
     * POST 网关 /accounts/import
     *   body: {"data": <导出文档>, "overwrite": true}
     *
     * overwrite=true 就是「同 uid 覆盖」。网关 import_rows() 的语义：
     *   · uid 已存在 + overwrite → updated（覆盖）
     *   · uid 不存在             → added（添加）
     *
     * @return 网关返回的报告，失败时返回带 error 的 JSONObject 或 null
     */
    fun importAccounts(
        gatewayBase: String,
        panelPassword: String,
        document: String,
        overwrite: Boolean = true,
    ): JSONObject? {
        val parsed = runCatching { JSONTokener(document).nextValue() }.getOrNull() ?: return null
        val token = panelToken(gatewayBase, panelPassword) ?: return null
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("${gatewayBase.trimEnd('/')}/accounts/import")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 40000
                setRequestProperty("X-Panel-Token", token)
                setRequestProperty("Content-Type", "application/json")
            }
            val body = JSONObject().apply {
                put("data", parsed)
                put("overwrite", overwrite)
            }.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }

            val text = if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            runCatching { JSONObject(text) }.getOrElse {
                JSONObject().put("error", text.take(300))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "importAccounts failed", e)
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 老接口：控制台的简表。保留兼容，新代码用 modelsFromGateway。 */
    fun models(): List<HubModel> {
        val json = getJson("api/models") ?: return emptyList()
        val arr = json.optJSONArray("models") ?: return emptyList()
        val out = ArrayList<HubModel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            val effortsRaw = o.optString("efforts")
            val contextRaw = o.optString("context")
            out.add(
                HubModel(
                    id = id,
                    name = id,
                    efforts = if (effortsRaw.isEmpty()) emptyList()
                    else effortsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    contextWindow = contextRaw.toIntOrNull() ?: 0,
                    // WB-EFFORT-OFF 缺字段按"能关"处理
                    canDisableThinking = o.optBoolean("can_disable", true),
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------ 会话

    /** 拉全部会话，返回当前会话 id + 消息列表。 */
    fun chat(): ChatState {
        val json = getJson("api/chat") ?: return ChatState()
        return parseChat(json)
    }

    private fun parseChat(json: JSONObject): ChatState {
        val arr = json.optJSONArray("sessions") ?: JSONArray()
        val sessions = ArrayList<ChatSession>(arr.length())
        var active: JSONObject? = null
        val activeId = json.optString("active")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            sessions.add(
                ChatSession(
                    id = id,
                    title = o.optString("title"),
                    updatedAt = o.optLong("created") * 1000L,
                )
            )
            if (id == activeId) active = o
        }
        return ChatState(
            entries = parseMessages(active?.optJSONArray("messages")),
            modelId = active?.optString("model").orEmpty(),
            effort = active?.optString("effort").orEmpty(),
            contextWindow = active?.optInt("context") ?: 0,
            sessionId = activeId,
            sessionTitle = active?.optString("title").orEmpty(),
            sessions = sessions,
        )
    }

    private fun parseMessages(arr: JSONArray?): List<AgentEntry> {
        if (arr == null) return emptyList()
        val out = ArrayList<AgentEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val role = when (o.optString("role")) {
                "user" -> AgentEntry.Role.USER
                "error" -> AgentEntry.Role.ERROR
                "reasoning" -> AgentEntry.Role.REASONING
                else -> AgentEntry.Role.ASSISTANT
            }
            val text = o.optString("text").ifEmpty { o.optString("content") }
            if (text.isNotEmpty()) out.add(AgentEntry(role, text))
        }
        return out
    }

    /** 新建会话，返回新 id。 */
    fun newSession(title: String = "新会话"): String? =
        postJson("api/chat/new", JSONObject().put("title", title))?.optString("id")

    /** 切换当前会话。 */
    fun selectSession(id: String): ChatState? =
        postJson("api/chat/active", JSONObject().put("id", id))?.let { parseChat(it) }

    /** 删除会话。 */
    fun deleteSession(id: String): Boolean =
        postJson("api/chat/delete", JSONObject().put("id", id)) != null

    /**
     * 改会话的模型 / 思考档位 / 上下文。
     *
     * 三个都是可选的：只传想改的那个，其余保持原样。
     */
    fun patchMeta(
        id: String,
        model: String? = null,
        effort: String? = null,
        context: Int? = null,
    ): Boolean {
        val body = JSONObject().put("id", id)
        model?.let { body.put("model", it) }
        effort?.let { body.put("effort", it) }
        context?.let { body.put("context", it) }
        return postJson("api/chat/meta", body) != null
    }

    // ------------------------------------------------------------ 流式对话

    /**
     * 发一条消息，边收边回调。
     *
     * 网关返的是 SSE，只有三种事件：
     *   {"type":"reasoning","text":...}   思考过程
     *   {"type":"content","text":...}     正文
     *   {"type":"error","message":...}    错误
     *
     * 这个函数会一直阻塞到流结束，调用方自己放进后台线程。
     * 返回 true 表示正常跑完，false 表示中途出错或被取消。
     */
    fun sendChat(
        sessionId: String,
        text: String,
        model: String,
        effort: String,
        cancel: AtomicBoolean,
        tools: List<Map<String, Any>>? = null,
        toolResult: Map<String, String>? = null,
        /**
         * 直接用这份消息历史，不走网关的会话。
         *
         * 工具调用必须这样：网关那边的 chat.json 只存 role/content，
         * tool_calls 的结构存不下来，回读时工具输出就变成"助手说的话"。
         * 传了它就用 App 侧维护的完整历史。
         */
        historyOverride: List<org.json.JSONObject>? = null,
        /** 网关 API Key。直连 /v1 时必须给，否则 401。 */
        apiKey: String = "",
        onEvent: (ChatEvent) -> Unit,
    ): Boolean {
        var conn: HttpURLConnection? = null
        // 有 historyOverride 就直接打网关的 /v1/chat/completions，
        // 不走控制台的会话。这样 tool_calls 的结构完整保留。
        val useDirect = historyOverride != null
        val urlPath = if (useDirect) "v1/chat/completions" else "api/chat/send"
        return try {
            val url = URL(baseUrl + urlPath)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                // 流式响应可能很长，读超时给足
                readTimeout = 180_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "text/event-stream")
                if (token.isNotEmpty()) setRequestProperty("X-WB-Token", token)
            }
            val bodyObj = if (useDirect) {
                JSONObject().apply {
                    put("model", model)
                    put("messages", org.json.JSONArray(historyOverride))
                    put("stream", true)
                    if (tools != null && tools.isNotEmpty()) {
                        put("tools", org.json.JSONArray(tools as List<Any>))
                        put("tool_choice", "auto")
                    }
                    /* WB-EFFORT-OFF
                       原来写的是 `effort != "off"` —— 选「不思考」时把字段
                       整个丢掉，网关收到"没传"就按模型默认档补回来，
                       等于没选。

                       实测：发 "none" 网关能识别（_thinking_off_value 认
                       off/none/disabled/disable），上游也接受，
                       completion_thinking_tokens 变成 0 —— 真的不思考了。

                       所以「不思考」要明确发 "none"，不是不发。 */
                    if (effort.isNotEmpty()) {
                        put("reasoning_effort", if (effort == "off") "none" else effort)
                    }
                }
            } else {
                JSONObject().apply {
                    put("id", sessionId)
                    put("text", text)
                    put("model", model)
                    put("effort", effort)
                    if (tools != null && tools.isNotEmpty()) {
                        put("tools", org.json.JSONArray(tools as List<Any>))
                    }
                    if (toolResult != null) {
                        put("tool_result", JSONObject(toolResult as Map<*, *>))
                    }
                }
            }
            val body = bodyObj.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }

            if (conn.responseCode != 200) {
                onEvent(ChatEvent.Error("HTTP ${conn.responseCode}"))
                return false
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (cancel.get()) return false
                    val raw = line!!.trim()
                    if (raw.isEmpty() || !raw.startsWith("data:")) continue
                    val payload = raw.substring(5).trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    when (obj.optString("type")) {
                        "content" -> onEvent(ChatEvent.Content(obj.optString("text")))
                        "reasoning" -> onEvent(ChatEvent.Reasoning(obj.optString("text")))
                        "tool_delta" -> onEvent(
                            ChatEvent.ToolDelta(obj.optJSONArray("calls"))
                        )
                        "error" -> {
                            onEvent(ChatEvent.Error(obj.optString("message")))
                            return false
                        }
                    }
                }
            }
            true
        } catch (e: Throwable) {
            if (!cancel.get()) {
                Log.w(TAG, "sendChat failed", e)
                onEvent(ChatEvent.Error(e.message ?: "连接中断"))
            }
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // ------------------------------------------------------------ HTTP

    private fun getJson(path: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                if (token.isNotEmpty()) setRequestProperty("X-WB-Token", token)
            }
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Throwable) {
            Log.w(TAG, "GET $path failed", e)
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun postJson(path: String, body: JSONObject): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (token.isNotEmpty()) setRequestProperty("X-WB-Token", token)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Throwable) {
            Log.w(TAG, "POST $path failed", e)
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}

/** 流式对话的一个事件。 */
sealed interface ChatEvent {
    data class Content(val text: String) : ChatEvent
    data class Reasoning(val text: String) : ChatEvent
    data class Error(val message: String) : ChatEvent

    /**
     * 工具调用的增量帧。
     *
     * 上游分帧发：第一帧给 id 和 name，后面几帧只给 arguments 的片段。
     * 这里原样透传，由 AgentRuntime 按 index 累积 ——
     * 在客户端合并会丢掉"边流边显示"的效果。
     */
    data class ToolDelta(val calls: org.json.JSONArray?) : ChatEvent
}

/**
 * 网关用的时间戳是秒（Python 的 time.time()），Date 要毫秒。
 *
 * 这里不无脑乘 1000 —— 万一以后上游改成毫秒（值会大到 1e12 量级），
 * 乘 1000 就变成公元 57000 年。按量级判断更稳：
 *   小于 1e11 → 秒（1e11 秒 ≈ 公元 5138 年，正常时间戳远小于它）
 *   否则      → 已经是毫秒
 *
 * 用 Double 接：JSON 里可能是 1793281104.2367945 这种带小数的。
 */
internal fun epochToMillis(value: Double): Long {
    if (value <= 0.0) return 0L
    return if (value < 1e11) (value * 1000.0).toLong() else value.toLong()
}

/** JSONObject 取可空数字：字段不存在或为 null 时返回 null，而不是 0。 */
private fun JSONObject.num(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

/**
 * 控制台令牌的存取。
 *
 * 令牌是控制台启动时生成的，和端口一起写在 files/wb_app/ 下。
 * 页面自己会用 <meta name="wb-token"> 拿到它，原生侧重读同一个文件。
 */
object WbApi {
    @Volatile
    var baseUrl: String = ""

    @Volatile
    var token: String = ""
}
