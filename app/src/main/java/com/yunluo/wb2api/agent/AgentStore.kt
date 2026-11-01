package com.yunluo.wb2api.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Agent 会话的本地存储。
 *
 * 为什么不用网关的 chat.json：
 *   网关那边 messages 只存 role/content 两个字段，工具调用的结构
 *   （tool_calls / tool_call_id / name）存不下来。回读历史时，
 *   工具输出就变成一坨普通文本，界面上显示成"助手说的话" ——
 *   这正是之前那个 "exit code: 0 / total 0" 显示错乱的原因。
 *
 * 所以照 WorkBuddy-Hub-Android 的 AgentStore 做，存两份：
 *   entries   给界面渲染的（role + text，扁平）
 *   messages  发给上游的完整消息（含 tool_calls 结构）
 *
 * 两者同源但用途不同：entries 是可丢的展示层，
 * messages 是必须保真的协议层。重建会话时 messages 重建 entries。
 */
class AgentStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "agent-sessions").apply { mkdirs() }

    /** 一个保存下来的会话。 */
    data class Session(
        val id: String,
        val title: String,
        val updatedAt: Long,
        /** 发给上游的完整消息，每条是 OpenAI 格式的 JSON 对象。 */
        val messages: List<JSONObject>,
        /** 界面上看到的那串行。 */
        val entries: List<StoredEntry>,
    )

    /** 界面用的一行。role 按名字存，改枚举不影响已存的数据。 */
    data class StoredEntry(val role: String, val text: String)

    // ---------------------------------------------------------------- 设置

    fun approval(): ApprovalMode =
        ApprovalMode.fromName(prefs.getString(KEY_APPROVAL, null))

    fun setApproval(mode: ApprovalMode) =
        prefs.edit().putString(KEY_APPROVAL, mode.name).apply()

    fun toolsEnabled(): Boolean = prefs.getBoolean(KEY_TOOLS, false)

    fun setToolsEnabled(v: Boolean) = prefs.edit().putBoolean(KEY_TOOLS, v).apply()

    fun workDir(): String =
        prefs.getString(KEY_WORKDIR, "").orEmpty().ifEmpty {
            AgentTools.defaultWorkDir().absolutePath
        }

    fun setWorkDir(path: String) = prefs.edit().putString(KEY_WORKDIR, path).apply()

    /**
     * 调用记录页的 realm 筛选偏好。
     *
     * "" 或 "all" → 全部（默认）
     * "cn"        → 只看国内版
     * "intl"      → 只看国际版
     *
     * 默认 all：你之前说过"关于记录的全部按全部账号"。
     */
    fun callsRealmFilter(): String =
        prefs.getString(KEY_CALLS_REALM, "all").orEmpty().ifEmpty { "all" }

    fun setCallsRealmFilter(v: String) =
        prefs.edit().putString(KEY_CALLS_REALM, v).apply()

    fun activeSessionId(): String = prefs.getString(KEY_ACTIVE, "").orEmpty()

    fun setActiveSessionId(id: String) = prefs.edit().putString(KEY_ACTIVE, id).apply()

    // ---------------------------------------------------------------- 会话

    /** 所有会话，最新的在前。 */
    fun sessions(): List<Session> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching { parse(f.readText(), f.nameWithoutExtension) }.getOrNull()
            }
            ?.sortedByDescending { it.updatedAt }
            .orEmpty()

    fun load(id: String): Session? {
        if (id.isEmpty()) return null
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return runCatching { parse(f.readText(), id) }.getOrNull()
    }

    /** 新建一个空会话，返回 id。 */
    fun create(title: String = "新会话"): Session {
        val id = "s" + System.currentTimeMillis().toString(36) +
                UUID.randomUUID().toString().take(4)
        val session = Session(
            id = id,
            title = title,
            updatedAt = System.currentTimeMillis(),
            messages = emptyList(),
            entries = emptyList(),
        )
        save(session)
        setActiveSessionId(id)
        return session
    }

    fun save(session: Session) {
        val doc = JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("updatedAt", session.updatedAt)
            put("entries", JSONArray().apply {
                session.entries.forEach { e ->
                    put(JSONObject().apply {
                        put("role", e.role)
                        put("text", e.text)
                    })
                }
            })
            put("messages", JSONArray().apply {
                session.messages.forEach { put(it) }
            })
        }
        runCatching {
            val tmp = File(dir, "${session.id}.json.tmp")
            tmp.writeText(doc.toString())
            tmp.renameTo(File(dir, "${session.id}.json"))
        }
    }

    fun delete(id: String) {
        if (id.isEmpty()) return
        runCatching { File(dir, "$id.json").delete() }
        if (activeSessionId() == id) setActiveSessionId("")
    }

    /** 从标题第一条用户消息里取。 */
    fun deriveTitle(messages: List<JSONObject>): String {
        val first = messages.firstOrNull { it.optString("role") == "user" }
            ?.optString("content").orEmpty()
        val line = first.lineSequence().firstOrNull().orEmpty().trim()
        return if (line.isEmpty()) "新会话" else line.take(24)
    }

    private fun parse(raw: String, fallbackId: String): Session {
        val o = JSONObject(raw)
        val entriesArr = o.optJSONArray("entries") ?: JSONArray()
        val msgArr = o.optJSONArray("messages") ?: JSONArray()

        val entries = ArrayList<StoredEntry>(entriesArr.length())
        for (i in 0 until entriesArr.length()) {
            val e = entriesArr.optJSONObject(i) ?: continue
            entries.add(StoredEntry(e.optString("role"), e.optString("text")))
        }
        val messages = ArrayList<JSONObject>(msgArr.length())
        for (i in 0 until msgArr.length()) {
            msgArr.optJSONObject(i)?.let { messages.add(it) }
        }
        return Session(
            id = o.optString("id").ifEmpty { fallbackId },
            title = o.optString("title").ifEmpty { "新会话" },
            updatedAt = o.optLong("updatedAt"),
            messages = messages,
            entries = entries,
        )
    }

    private companion object {
        const val PREFS = "wb_agent"
        const val KEY_APPROVAL = "approval"
        const val KEY_TOOLS = "tools_enabled"
        const val KEY_WORKDIR = "work_dir"
        const val KEY_ACTIVE = "active_session"
        const val KEY_CALLS_REALM = "calls_realm_filter"
    }
}
