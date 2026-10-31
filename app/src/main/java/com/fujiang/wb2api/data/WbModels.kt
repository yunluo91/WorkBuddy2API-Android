package com.fujiang.wb2api.data

/**
 * 调用记录 / 对话 两页的数据模型。
 *
 * 字段名对齐网关 `/api/calls` 与 `/api/chat` 的返回，不做多余的抽象：
 * 这些数据本来就是从同一个进程里出来的，中间再套一层只会让字段对不上时
 * 排查更麻烦。
 */

// ---------------------------------------------------------------- 调用记录

/**
 * 一次调用。
 *
 * 对应 `/api/calls` 里 `rows` 数组的每一项（即 wb_console.py 的 _slim_row）。
 * token 和积分来自上游自己的 usage 块，不是本地估算。
 */
data class CallRecord(
    /** 毫秒时间戳。 */
    val at: Long = 0L,
    val model: String = "",
    val ok: Boolean = true,
    /** 失败原因，成功时为空。 */
    val reason: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val credit: Double = 0.0,
    val costCny: Double? = null,
    /** 倍率的三个价：命中缓存输入 / 未命中输入 / 输出。null 表示上游没给。 */
    val rateHit: Double? = null,
    val rateMiss: Double? = null,
    val rateOut: Double? = null,
    val elapsedMs: Int = 0,
    /** 这一条请求是走国内版（cn）还是国际版（intl）的账号。 */
    val realm: String = "",
) {
    val timeText: String
        get() = if (at <= 0L) "" else formatTime(at)

    /**
     * 国内版 / 国际版。
     *
     * 网关日志里 realm 是 cn / intl。这里只显示版本，
     * **不显示账号名** —— 用户明确要求过，账号属于隐私，
     * 而且同一个人两个版本的账号名常常一样，标出来也没信息量。
     *
     * 空值（老记录没写 realm）返回 null，调用方据此不画那个标签。
     */
    val realmLabel: String?
        get() = when (realm.lowercase()) {
            "cn" -> "国内版"
            "intl", "international", "global" -> "国际版"
            else -> null
        }

    /**
     * 展示用倍率。
     *
     * 网关给的是三个价（命中/未命中/输出），单看一个没意义，所以拼起来。
     * 三个都没有就不显示 —— 宁可空着，也别写个 0 让人误会是免费。
     */
    val multiplierText: String?
        get() {
            val parts = listOfNotNull(rateMiss, rateOut)
            if (parts.isEmpty()) return null
            return parts.joinToString(" / ") { trimNumber(it) }
        }

    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
}

/** 用量统计。对应 `/api/calls` 里的 `stats`。 */
data class CallStats(
    val calls: Int = 0,
    val failures: Int = 0,
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val credits: Double = 0.0,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

/** `/api/calls` 的一次完整返回。 */
data class CallsPage(
    val records: List<CallRecord> = emptyList(),
    val stats: CallStats = CallStats(),
)

// ---------------------------------------------------------------- 对话

/** 对话里的一行。 */
data class AgentEntry(
    val role: Role,
    val text: String,
) {
    enum class Role { USER, ASSISTANT, REASONING, ERROR, TOOL }

    /** 思考过程和工具输出默认折叠：它们通常比正文长，只有出问题时才需要看。 */
    val collapsible: Boolean get() = role == Role.REASONING || role == Role.TOOL
}

/** 一个会话的摘要，用于会话选择器。 */
data class ChatSession(
    val id: String,
    val title: String = "",
    val updatedAt: Long = 0L,
) {
    val displayTitle: String get() = title.ifEmpty { "未命名对话" }
}

/** 可选模型。 */
data class HubModel(
    val id: String,
    val name: String = "",
    /** 厂商，用于 VendorBadge 的配色。空则取 name 首字。 */
    val vendor: String = "",
    /** 倍率。上游给 "x0.08" 这种，解析成 0.08；拿不到是 -1。 */
    val multiplier: Double = -1.0,
    val efforts: List<String> = emptyList(),
    /**
     * WB-EFFORT-OFF 能不能关思考。
     * 上游明确说 false 才当不能；缺字段按"能"处理。
     */
    val canDisableThinking: Boolean = true,
    val contextWindow: Int = 0,
    /** 最大输出 token。0 表示上游没给。 */
    val maxOutput: Int = 0,
    /** 能力：能不能读图 / 调工具 / 推理。 */
    val vision: Boolean = false,
    val toolCalls: Boolean = false,
    val reasoning: Boolean = false,
    /**
     * WB-PRO-BADGE 是否需要 Pro 账号。
     *
     * 来自网关 /v1/models 的 entitlement 字段：
     *   {"source":"catalog","required":"standard","at":...}
     * 有这个字段 = 上游目录声明该模型仅限 Pro（requiredUserType=standard）。
     * 免费账号发过去会被本地拦下，不发往上游。
     */
    val proOnly: Boolean = false,
) {

    /** 倍率显示：0 → "免费"，其余 → "0.08x"，没有 → 空。 */
    val multiplierText: String
        get() = when {
            multiplier < 0 -> ""
            multiplier == 0.0 -> "免费"
            else -> {
                val s = if (multiplier == multiplier.toLong().toDouble())
                    multiplier.toLong().toString()
                else String.format(java.util.Locale.US, "%.4f", multiplier).trimEnd('0').trimEnd('.')
                "${s}x"
            }
        }

    /** 上下文显示：128K / 1M。 */
    val contextText: String
        get() = when {
            contextWindow <= 0 -> ""
            contextWindow >= 1_000_000 -> "${contextWindow / 1_000_000}M"
            contextWindow >= 1000 -> "${contextWindow / 1000}K"
            else -> contextWindow.toString()
        }

    /** 能力标签。 */
    val capabilities: List<String>
        get() = buildList {
            if (vision) add("视觉")
            if (toolCalls) add("工具")
            if (reasoning) add("推理")
        }

    val displayName: String get() = name.ifEmpty { id }

    val sortKey: Double get() = if (multiplier < 0) 999.0 else multiplier

    val supportsEffort: Boolean get() = efforts.isNotEmpty()
}

/**
 * 对话页要渲染的全部状态。
 *
 * 比参考实现少了 exposeRoot / approval / storageGranted / workDir 这些 ——
 * 那些是给带工具执行的 Agent 用的，这个 App 的对话只走上游 /v1/chat/completions，
 * 不碰本地文件也不跑命令，留着只会是一堆永远不生效的开关。
 */
data class ChatState(
    val entries: List<AgentEntry> = emptyList(),
    val input: String = "",
    val modelId: String = "",
    val effort: String = "",
    val contextWindow: Int = 0,
    val running: Boolean = false,
    val sessionId: String = "",
    val sessionTitle: String = "",
    val sessions: List<ChatSession> = emptyList(),
)

// ---------------------------------------------------------------- 时间

private val FMT_HMS = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
private val FMT_MD = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.getDefault())
private val FMT_YMD = java.text.SimpleDateFormat("yyyy年M月d日 HH:mm", java.util.Locale.getDefault())

/**
 * 时间戳格式化。
 *
 * 为什么带「今天 / 昨天」：
 *   调用记录绝大多数是当天的，屏幕上写「10月29日 21:38」还要自己
 *   核对今天是几号；写「今天 21:38」就不用想。
 *   跨年的记录才带年份。
 *
 * 注意入参是**毫秒**。网关给的是秒，WbClient 解析时已经乘过 1000
 * （见 epochToMillis）—— 直接传秒会算出 1970 年，
 * 屏幕上就变成「1月22日」那种错乱。
 */
internal fun formatTime(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val now = System.currentTimeMillis()
    val d = java.util.Date(epochMs)
    val todayStart = startOfDay(now)
    val yesterdayStart = todayStart - 24L * 60 * 60 * 1000
    return when {
        epochMs >= todayStart -> "今天 " + synchronized(FMT_HMS) { FMT_HMS.format(d) }
        epochMs >= yesterdayStart -> "昨天 " + synchronized(FMT_HMS) { FMT_HMS.format(d) }
        // 同年只显示月日，跨年才带年份
        isSameYear(epochMs, now) -> synchronized(FMT_MD) { FMT_MD.format(d) }
        else -> synchronized(FMT_YMD) { FMT_YMD.format(d) }
    }
}

private fun startOfDay(ms: Long): Long {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = ms
    c.set(java.util.Calendar.HOUR_OF_DAY, 0)
    c.set(java.util.Calendar.MINUTE, 0)
    c.set(java.util.Calendar.SECOND, 0)
    c.set(java.util.Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun isSameYear(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR)
}
