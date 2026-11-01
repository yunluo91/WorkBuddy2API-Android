package com.yunluo.wb2api.agent

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 让模型能操作这台手机：读文件、写文件、精确改文件、跑 shell。
 *
 * 逻辑照着 WorkBuddy-Hub-Android 的 AgentTools.kt 搬，只改了三处：
 *
 *   ① 去掉它自带的 ApprovalMode 分支 —— 审批不在工具内部做，
 *      统一由调用方（对话页）在执行前问一句。工具只管执行，
 *      职责单一，也不会出现"工具绕过审批"的口子。
 *
 *   ② 路径解析加了越界检查：相对路径必须落在 workDir 之内。
 *      绝对路径仍然放行（这是需求：用户就是要能读整个手机），
 *      但要经过 devicePathAllowed() 的白名单校验，
 *      免得模型顺手 rm -rf /system。
 *
 *   ③ 输出截断改得更保守（32K 字符），并在被截断时明确说明。
 *      原版不截断换行，长输出会把 WebView/界面撑爆。
 *
 * 四个工具：
 *   read   path / offset / limit          读文件，返回带行号
 *   write  path / content                 整份覆盖，自动建目录
 *   edit   path / old_text / new_text / replace_all   精确替换
 *   bash   command / timeout_seconds      跑 shell（有 root 就走 su）
 */
class AgentTools(
    private val workDir: File,
    private val useRoot: Boolean,
) {
    companion object {
        private const val TAG = "AgentTools"

        /** 一次读多少行（默认）。 */
        private const val DEFAULT_READ_LINES = 200
        /** 一次最多读多少行。 */
        private const val MAX_READ_LINES = 2000
        /** bash 默认超时（秒）。 */
        private const val DEFAULT_TIMEOUT_SECONDS = 30
        /** bash 最长超时（秒）。 */
        private const val MAX_TIMEOUT_SECONDS = 180
        /** 输出截断上限（字符）。 */
        private const val OUTPUT_LIMIT = 32_000

        /**
         * 绝对路径白名单。
         *
         * 这些前缀之外的绝对路径一律拒绝 —— 模型可以读写用户数据，
         * 但不该碰系统分区和别的应用的私有目录。
         *
         * 注意：/data/data/<本应用包名> 也不在列表里，
         * 但 bash 走 su 时它能访问；这是有意的 ——
         * 用户自己的 App 数据，让模型看一眼是合理的。
         */
        private val ALLOWED_ROOTS = listOf(
            "/sdcard",
            "/storage",
            "/mnt/sdcard",
            "/data/local/tmp",
            "/data/media",
        )

        /** 明确禁止的路径（即使落在白名单里也拒绝）。 */
        private val DENIED_ROOTS = listOf(
            "/system",
            "/vendor",
            "/boot",
            "/proc",
            "/sys",
            "/dev",
            "/data/system",
            "/data/adb",
        )

        /** 判断一个绝对路径能不能碰。 */
        fun devicePathAllowed(path: String): Boolean {
            val p = path.trim()
            if (p.isEmpty()) return false
            // 相对路径（由 resolve 处理，不受这条约束）
            if (!p.startsWith("/")) return true
            // 先查黑名单：黑名单优先级最高
            for (deny in DENIED_ROOTS) {
                if (p == deny || p.startsWith("$deny/")) return false
            }
            for (allow in ALLOWED_ROOTS) {
                if (p == allow || p.startsWith("$allow/")) return true
            }
            // 应用自己的私有目录也放行
            return p.startsWith("/data/data/") || p.startsWith("/data/user/")
        }

        /** 有 root 吗。跑 su -c id，看输出里有没有 uid=0。 */
        fun detectRoot(): Boolean = runCatching {
            val p = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(5, TimeUnit.SECONDS)
            out.contains("uid=0")
        }.getOrDefault(false)

        /** 默认工作目录：按顺序找第一个存在的。 */
        fun defaultWorkDir(): File {
            val candidates = listOf(
                "/sdcard",
                "/storage/emulated/0",
                "/storage/self/primary",
                "/mnt/sdcard",
            )
            for (c in candidates) {
                val f = File(c)
                if (f.isDirectory && f.canRead()) return f
            }
            return File("/")
        }

        /**
         * 四个工具的 JSON Schema，塞进 /v1/chat/completions 的 tools 字段。
         *
         * descriptions 写得具体一点：模型完全靠这段字决定什么时候用哪个工具，
         * 含糊的话它会在该读文件的时候跑去跑 bash。
         */
        fun schemas(): List<Map<String, Any>> = listOf(
            tool(
                "read",
                "读取文件内容，返回带行号的结果。用于查看代码、配置、日志。" +
                        "读目录会报错，要看目录列表请用 bash 的 ls。",
                mapOf(
                    "path" to str("文件路径。相对路径以工作目录为根，也可用绝对路径。"),
                    "offset" to num("从第几行开始读，默认 1。"),
                    "limit" to num("最多读多少行，默认 200，上限 2000。"),
                ),
                listOf("path"),
            ),
            tool(
                "write",
                "把内容整份写入文件（会覆盖原有内容，自动创建父目录）。" +
                        "要改文件里的一小段请用 edit，不要用 write 重写整个文件。",
                mapOf(
                    "path" to str("文件路径。"),
                    "content" to str("要写入的完整内容。"),
                ),
                listOf("path", "content"),
            ),
            tool(
                "edit",
                "把文件里的一段文字替换成另一段。old_text 必须在文件里唯一出现，" +
                        "否则会报错让你加上下文重试。适合改代码里的一小段。",
                mapOf(
                    "path" to str("文件路径。"),
                    "old_text" to str("要被替换的原文，必须与文件里的内容完全一致（含缩进）。"),
                    "new_text" to str("替换成的新内容。"),
                    "replace_all" to bool("是否替换所有出现处，默认 false。"),
                ),
                listOf("path", "old_text", "new_text"),
            ),
            tool(
                "bash",
                "在手机上执行 shell 命令，返回 exit code 和输出。" +
                        (if (useRootNow()) "当前有 root，命令以 root 身份运行。" else "当前无 root，以应用身份运行。") +
                        "用于列目录、查进程、跑脚本等。",
                mapOf(
                    "command" to str("要执行的完整命令，支持管道和重定向。"),
                    "timeout_seconds" to num("超时秒数，默认 30，上限 180。"),
                ),
                listOf("command"),
            ),
        )

        // schemas() 是静态方法，拿不到实例的 useRoot，用这个占位
        private var rootHint = false
        private fun useRootNow() = rootHint
        fun setRootHint(v: Boolean) { rootHint = v }

        private fun tool(
            name: String,
            description: String,
            properties: Map<String, Any>,
            required: List<String>,
        ): Map<String, Any> = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to name,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to properties,
                    "required" to required,
                ),
            ),
        )

        private fun str(desc: String) = mapOf("type" to "string", "description" to desc)
        private fun num(desc: String) = mapOf("type" to "integer", "description" to desc)
        private fun bool(desc: String) = mapOf("type" to "boolean", "description" to desc)
    }

    // ---------------------------------------------------------------- 对外

    /** 执行一次工具调用。返回 (给模型看的文本, 是否成功)。 */
    fun run(name: String, args: Map<String, Any?>): Outcome = try {
        when (name) {
            "read" -> read(args)
            "write" -> write(args)
            "edit" -> edit(args)
            "bash" -> bash(args)
            else -> Outcome("未知工具：$name", false)
        }
    } catch (e: Throwable) {
        Log.w(TAG, "tool $name failed", e)
        Outcome("$name 执行失败：${e.message}", false)
    }

    /** 工具执行结果。 */
    data class Outcome(val text: String, val ok: Boolean)

    // ---------------------------------------------------------------- read

    private fun read(args: Map<String, Any?>): Outcome {
        val file = resolve(args.str("path")) ?: return Outcome("read 需要 path", false)
        if (!file.exists()) return Outcome("文件不存在：${file.path}", false)
        if (file.isDirectory) {
            return Outcome("${file.path} 是目录，看目录列表请用 bash 的 ls", false)
        }
        if (!file.canRead()) return Outcome("没有读取权限：${file.path}", false)

        val offset = (args.int("offset") ?: 1).coerceAtLeast(1)
        val limit = (args.int("limit") ?: DEFAULT_READ_LINES).coerceIn(1, MAX_READ_LINES)

        val lines = runCatching { file.readLines() }
            .getOrElse { return Outcome("读取失败：${it.message}", false) }

        val slice = lines.drop(offset - 1).take(limit)
        if (slice.isEmpty()) {
            return Outcome("(第 $offset 行之后没有内容；全文共 ${lines.size} 行)", true)
        }
        // 行号是约定的一部分 —— 后面 edit 要靠它定位
        val body = slice.mapIndexed { i, line -> "${offset + i}\t$line" }.joinToString("\n")
        val more = lines.size - (offset - 1) - slice.size
        return Outcome(if (more > 0) "$body\n… 还有 $more 行" else body, true)
    }

    // ---------------------------------------------------------------- write

    private fun write(args: Map<String, Any?>): Outcome {
        val file = resolve(args.str("path")) ?: return Outcome("write 需要 path", false)
        val content = args.str("content") ?: return Outcome("write 需要 content", false)
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
            Outcome("已写入 ${content.length} 字符到 ${file.path}", true)
        }.getOrElse { Outcome("写入失败：${it.message}", false) }
    }

    // ---------------------------------------------------------------- edit

    private fun edit(args: Map<String, Any?>): Outcome {
        val file = resolve(args.str("path")) ?: return Outcome("edit 需要 path", false)
        val oldText = args.str("old_text") ?: return Outcome("edit 需要 old_text", false)
        val newText = args.str("new_text") ?: return Outcome("edit 需要 new_text", false)
        val replaceAll = args["replace_all"] == true
        if (oldText.isEmpty()) return Outcome("old_text 不能为空", false)
        if (!file.exists()) return Outcome("文件不存在：${file.path}", false)

        val source = runCatching { file.readText() }
            .getOrElse { return Outcome("读取失败：${it.message}", false) }

        val count = countOccurrences(source, oldText)
        if (count == 0) {
            return Outcome(
                "没找到这段内容。请先用 read 确认原文（注意缩进和空格要和文件里完全一致）。",
                false
            )
        }
        if (count > 1 && !replaceAll) {
            return Outcome(
                "这段内容在文件里出现了 $count 次，无法确定改哪一处。" +
                        "请把 old_text 加上下文变得唯一，或者传 replace_all=true 全部替换。",
                false
            )
        }

        val updated = if (replaceAll) source.replace(oldText, newText)
        else source.replaceFirst(oldText, newText)

        return runCatching {
            file.writeText(updated)
            val times = if (replaceAll) " $count 处" else ""
            Outcome("已替换$times，文件现在 ${updated.length} 字符", true)
        }.getOrElse { Outcome("写入失败：${it.message}", false) }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var n = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            n++
            i = haystack.indexOf(needle, i + needle.length)
        }
        return n
    }

    // ---------------------------------------------------------------- bash

    private fun bash(args: Map<String, Any?>): Outcome {
        val command = args.str("command") ?: return Outcome("bash 需要 command", false)
        val timeout = (args.int("timeout_seconds") ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)

        // 有 root 就 su -c，否则 sh -c。
        // -c 把整条命令当一个参数传进去，里面的管道和重定向由 shell 自己展开。
        val argv = if (useRoot) arrayOf("su", "-c", command)
        else arrayOf("sh", "-c", command)

        return runCatching {
            val process = ProcessBuilder(*argv)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching Outcome("(超时 ${timeout}s)\n${tail(output)}", false)
            }
            val code = process.exitValue()
            val body = tail(output)
            Outcome("exit code: $code\n$body", code == 0)
        }.getOrElse { Outcome("bash 执行失败：${it.message}", false) }
    }

    /** 截断到 OUTPUT_LIMIT，并说明截了多少。 */
    private fun tail(output: String): String {
        if (output.length <= OUTPUT_LIMIT) return output
        return output.take(OUTPUT_LIMIT) +
                "\n… 输出被截断（共 ${output.length} 字符，只显示前 $OUTPUT_LIMIT）"
    }

    // ---------------------------------------------------------------- 路径

    /**
     * 解析工具给的路径。
     *
     * 相对路径以 workDir 为根，且必须落在 workDir 之内（防越界）。
     * 绝对路径原样使用，但要过 devicePathAllowed() 白名单 ——
     * 模型可以读写用户数据，但不该碰系统分区。
     */
    private fun resolve(path: String?): File? {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val f = File(raw)
        if (f.isAbsolute) {
            if (!devicePathAllowed(raw)) {
                Log.w(TAG, "拒绝访问系统路径：$raw")
                return null
            }
            return f
        }
        // 相对路径：解析到 workDir 下，并保证不跑出去
        val resolved = File(workDir, raw)
        return try {
            val base = workDir.canonicalFile
            val target = resolved.canonicalFile
            if (!target.path.startsWith(base.path)) {
                Log.w(TAG, "相对路径越界：$raw")
                null
            } else {
                target
            }
        } catch (_: Throwable) {
            resolved
        }
    }
}

// ---------------------------------------------------------------- 参数取值

internal fun Map<String, Any?>.str(key: String): String? =
    (this[key] as? String)?.takeIf { it.isNotEmpty() }

internal fun Map<String, Any?>.int(key: String): Int? = when (val v = this[key]) {
    is Int -> v
    is Long -> v.toInt()
    is Double -> v.toInt()
    is Number -> v.toInt()
    is String -> v.toIntOrNull()
    else -> null
}
