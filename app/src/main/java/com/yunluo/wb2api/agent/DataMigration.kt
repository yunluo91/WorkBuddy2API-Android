package com.yunluo.wb2api.agent

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 整包数据备份 / 恢复。
 *
 * 为什么需要：Android 按 applicationId 隔离沙箱，改了包名就是另一个应用，
 * 旧数据一个字节都继承不过来。唯一的搬运通道是外部存储
 * （/sdcard）—— 两个包名都能读写那里。
 *
 * 备什么（只备用户数据，不备解压出来的 Python 运行时）：
 *   files/app/accounts/     账号池 + settings.json（API Key / 面板密码）
 *   files/app/usage/        调用记录 + 额度撞线记录
 *   files/app/wb_app/       思考档位 / 控制台令牌
 *   shared_prefs/           端口、局域网、自启、思考强度、筛选偏好
 *
 * 不备什么：
 *   files/app/ 下的 .py 等   这些是 APK assets 解压出来的，新包装上会自己解，
 *                        备了反而可能拿旧版本覆盖新版本。
 *   files/chaquopy/      运行时，同上。
 *
 * 恢复时必须先让 Python 停下来 —— 账号池是常驻内存的，
 * 它下一次落盘会把刚恢复的文件冲回旧内容。所以调用方要在
 * restore() 之后立刻重启进程。
 */
object DataMigration {

    /** 备份 / 恢复的根（/data/data/<pkg>）。 */
    private fun dataDir(ctx: Context): File? = ctx.filesDir?.parentFile

    /** 要备份的目录，相对 dataDir。 */
    private val DIRS = listOf(
        "files/app/accounts",
        "files/app/usage",
        "files/app/wb_app",
        "shared_prefs",
    )

    data class Result(val ok: Boolean, val message: String, val count: Int = 0)

    /**
     * 把数据打成 zip 写进 [out]。调用方负责关流。
     *
     * 只写存在的文件；目录不存在就跳过，不算失败。
     */
    fun backup(ctx: Context, out: OutputStream): Result {
        val root = dataDir(ctx) ?: return Result(false, "拿不到应用数据目录")
        return try {
            var count = 0
            ZipOutputStream(out.buffered()).use { zos ->
                for (rel in DIRS) {
                    val dir = File(root, rel)
                    if (!dir.isDirectory) continue
                    dir.walkTopDown()
                        .filter { it.isFile }
                        .forEach { f ->
                            val name = f.relativeTo(root).invariantSeparatorsPath
                            zos.putNextEntry(ZipEntry(name))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                            count++
                        }
                }
            }
            Result(true, "已备份 $count 个文件", count)
        } catch (t: Throwable) {
            Result(false, "备份失败：${t.message}")
        }
    }

    /**
     * 从 zip 读回数据。
     *
     * 只接受本类 backup() 产生的条目（files/app/... 和 shared_prefs/...），
     * 其它路径一律跳过 —— 万一选错了文件，也不会往里塞乱七八糟的东西。
     */
    fun restore(ctx: Context, input: InputStream, forcePort: Int? = null): Result {
        val root = dataDir(ctx) ?: return Result(false, "拿不到应用数据目录")
        return try {
            var count = 0
            var skipped = 0
            ZipInputStream(input.buffered()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val allowed = DIRS.any { name == it || name.startsWith("$it/") }
                    if (entry.isDirectory || !allowed) {
                        skipped++
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    val target = File(root, name)
                    // 防目录穿越：解出来的路径必须在 root 里面
                    if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                        skipped++
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    target.parentFile?.mkdirs()
                    if (name.endsWith("shared_prefs/wb2api.xml") && forcePort != null) {
                        // 备份里带着旧包的端口。直接还原会让新包也去抢 8788，
                        // 跟共存的老包撞车 —— 恢复时按新包的默认端口改掉。
                        val xml = zis.readBytes().toString(Charsets.UTF_8)
                        target.writeText(forcePortPrefs(xml, forcePort), Charsets.UTF_8)
                    } else {
                        target.outputStream().use { zis.copyTo(it) }
                    }
                    count++
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            if (count == 0) {
                Result(false, "这个文件里没有可恢复的数据（跳过 $skipped 项）", 0)
            } else {
                Result(true, "已恢复 $count 个文件", count)
            }
        } catch (t: Throwable) {
            Result(false, "恢复失败：${t.message}")
        }
    }

    /** 把 prefs xml 里的 port 写成给定值；没有这一项就插一个进去。 */
    private fun forcePortPrefs(xml: String, port: Int): String {
        val entry = "<int name=\"port\" value=\"$port\" />"
        val existing = Regex("<int name=\"port\" value=\"[0-9]+\"\\s*/>")
        return if (existing.containsMatchIn(xml)) {
            existing.replace(xml, entry)
        } else {
            xml.replace("</map>", "    $entry\n</map>")
        }
    }

}
