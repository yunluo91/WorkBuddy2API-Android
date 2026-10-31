package com.fujiang.wb2api.agent

/**
 * 工具调用的授权策略。
 *
 * 三档设计照 WorkBuddy-Hub-Android 的 ApprovalMode 抄 ——
 * 读文件和跑任意 shell 命令不是同一个风险等级，
 * 一个"允许工具"的总开关要么形同虚设，要么会被无脑点过去。
 */
enum class ApprovalMode(val label: String, val desc: String) {
    /** 全放行，不询问。 */
    ALLOW_ALL("全部放行", "不询问，读写和命令都直接执行"),

    /** 读自动放行，写和执行前问一句。这是默认档。 */
    AUTO("自动判断", "读文件自动放行，写入和执行命令前询问"),

    /** 每次调用都问。 */
    ASK_EVERY("每次都问", "任何工具调用都先询问"),
    ;

    companion object {
        fun fromName(v: String?): ApprovalMode =
            entries.firstOrNull { it.name == v } ?: AUTO
    }
}

/**
 * 判定一次工具调用要不要问用户，以及问的时候该怎么描述。
 *
 * 描述要念出参数而不是工具名 ——
 * "执行命令：rm -rf /sdcard" 看得懂，"bash" 看不懂。
 */
object ToolPolicy {

    /** 只有 read 是纯粹的只读操作，不改磁盘也不跑程序。 */
    fun isReadOnly(name: String): Boolean = name == "read"

    /** 这一档策略下，此次调用要不要问。 */
    fun needsApproval(mode: ApprovalMode, name: String): Boolean = when (mode) {
        ApprovalMode.ALLOW_ALL -> false
        ApprovalMode.ASK_EVERY -> true
        ApprovalMode.AUTO -> !isReadOnly(name)
    }

    /** 一句话说明这次调用要干什么，给确认框用。 */
    fun summarize(name: String, args: Map<String, Any?>): String = when (name) {
        "read" -> "读取文件\n${args["path"] ?: "?"}"
        "write" -> "写入文件\n${args["path"] ?: "?"}\n（${(args["content"] as? String)?.length ?: 0} 字符）"
        "edit" -> "修改文件\n${args["path"] ?: "?"}"
        "bash" -> "执行命令\n${args["command"] ?: "?"}"
        else -> "$name ${args.values.joinToString(" ")}"
    }

    /**
     * 危险命令的粗筛。
     *
     * 不是要替代审批 —— 审批该问还是要问。这里只是给那些
     * 一眼就知道会出事的命令加一句额外提醒，让用户在点"允许"之前
     * 多看一眼。判错的代价是"多提醒一次"，不是"放行了危险操作"。
     */
    fun warningFor(name: String, args: Map<String, Any?>): String? {
        if (name != "bash") return null
        val cmd = (args["command"] as? String).orEmpty()
        if (cmd.isEmpty()) return null
        val patterns = listOf(
            "rm -rf /" to "这是删除根目录",
            "rm -rf /*" to "这是删除根目录下所有文件",
            "mkfs" to "这是格式化分区",
            "dd if=" to "这是裸写磁盘",
            "> /dev/" to "这是直接写设备节点",
            "chmod -R 777 /" to "这是给整个系统改权限",
            ":(){ " to "这是 fork 炸弹",
        )
        for ((pat, why) in patterns) {
            if (cmd.contains(pat)) return "⚠ $why"
        }
        return null
    }
}
