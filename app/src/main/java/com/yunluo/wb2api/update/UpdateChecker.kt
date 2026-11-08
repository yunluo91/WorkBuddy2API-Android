package com.yunluo.wb2api.update

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * 检查更新 / 下载 / 安装。
 *
 * 设计要点（跟旧版那套的区别，都是踩过的坑）：
 *
 *  ① 版本比较用「是否更新」，不是「是否相同」。
 *     旧版写的是 version != CURRENT_VERSION，服务器版本比本地旧时也会提示更新，
 *     点下去等于降级。
 *
 *  ② 兼容各种 JSON 形态。
 *     服务器可能返回 JSON 字符串、也可能被重定向到 HTML 下载页。
 *     这里只在能解析出 version 时才认为是有效响应，其余静默失败，
 *     不会把一坨 HTML 当成更新内容塞给用户。
 *
 *  ③ contentLength = -1 时用不定长进度条。
 *     旧版直接 total * 100 / length，服务器不给长度时会除零/负数。
 *
 *  ④ 下载地址不是硬编码。
 *     旧版「浏览器下载」写死了一个网盘链接，无论哪个版本都指同一个包。
 *     这里统一用服务器下发的 url 打开浏览器。
 */
object UpdateChecker {

    /** 更新源。指向仓库里那个 update.json 的 raw 地址。 */
    private const val UPDATE_URL =
        "https://raw.githubusercontent.com/yunluo91/WorkBuddy2API-Android/main/update/update.json"

    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 8000
    private const val CONNECT_TIMEOUT_DOWNLOAD_MS = 15000
    private const val READ_TIMEOUT_DOWNLOAD_MS = 30000

    private const val PREFS = "update_prefs"
    private const val KEY_IGNORED = "ignored_version"

    /** 检查结果。 */
    sealed class Result {
        /** 已是最新（或服务器版本不比本地新）。 */
        data object UpToDate : Result()

        /** 这个版本用户点过「忽略」，自动检查时跳过。 */
        data object Ignored : Result()

        /** 有新版本。 */
        data class Available(
            val version: String,
            val content: String,
            val apkUrl: String,
        ) : Result()

        /** 检查失败（网络、解析、超时）。 */
        data class Failed(val reason: String) : Result()
    }

    // ────────────────────────── 版本比较 ──────────────────────────

    /**
     * 把 "2.0.0-app186" 这类版本号拆成可比较的数字序列。
     *
     * 规则：
     *   · 先按非数字字符切开，取所有数字段
     *   · "2.0.0-app186" → [2, 0, 0, 186]
     *   · 缺失的段按 0 补齐后逐段比较
     *
     * 这样 app186 会大于 app185，而 upstream 的 2.0.0 也会大于 1.10.11。
     */
    private fun versionKey(raw: String): List<Int> =
        Regex("\\d+").findAll(raw).map { it.value.toIntOrNull() ?: 0 }.toList()

    private fun isNewer(remote: String, local: String): Boolean {
        val a = versionKey(remote)
        val b = versionKey(local)
        if (a.isEmpty() || b.isEmpty()) return false
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // ────────────────────────── 检查 ──────────────────────────

    /**
     * 拉取更新信息并和本地版本比。
     *
     * @param localVersion 当前 App 的 versionName
     * @param allowIgnored 用户主动点「检查更新」时传 true，忽略之前的「忽略此版本」
     */
    fun check(context: android.content.Context, localVersion: String, allowIgnored: Boolean): Result {
        val body = try {
            fetchText(UPDATE_URL)
        } catch (e: Exception) {
            return Result.Failed(e.message ?: "网络请求失败")
        }

        val json = try {
            JSONObject(body.trim())
        } catch (e: Exception) {
            // 拿到 HTML（多半是被重定向到下载页）或空响应
            return Result.Failed("更新源返回的不是 JSON")
        }

        val remoteVersion = json.optString("version").trim()
        if (remoteVersion.isEmpty()) return Result.Failed("更新源缺少 version 字段")

        // 服务器不比本地新 → 已是最新（防止降级提示）
        if (!isNewer(remoteVersion, localVersion)) return Result.UpToDate

        if (!allowIgnored && ignoredVersion(context) == remoteVersion) return Result.Ignored

        val content = json.optString("content").trim()
        val apkUrl = json.optString("url").trim()
        if (apkUrl.isEmpty()) return Result.Failed("更新源缺少 url 字段")

        return Result.Available(remoteVersion, content, apkUrl)
    }

    private fun fetchText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ────────────────────────── 忽略 ──────────────────────────

    fun ignoredVersion(context: android.content.Context): String =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_IGNORED, "") ?: ""

    fun ignore(context: android.content.Context, version: String) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_IGNORED, version).apply()
    }

    // ────────────────────────── 下载 ──────────────────────────

    /** 下载进度：0f..1f；total 未知时为 -1f。 */
    fun interface Progress {
        fun onProgress(fraction: Float)
    }

    /**
     * 下载 APK 到外部私有目录，返回文件；失败抛异常。
     *
     * 进度回调在调用线程上执行（由调用方负责切主线程）。
     */
    fun download(context: Activity, apkUrl: String, onProgress: Progress): File {
        val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_DOWNLOAD_MS
            readTimeout = READ_TIMEOUT_DOWNLOAD_MS
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")

            // 服务器没给长度时是 -1：这时候只能报不定长
            val length = conn.contentLengthLong
            val target = File(context.getExternalFilesDir(null), "update.apk")
            target.parentFile?.mkdirs()

            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var lastReported = 0
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        total += read
                        if (length > 0) {
                            val pct = (total * 100 / length).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress.onProgress(total.toFloat() / length)
                            }
                        } else {
                            onProgress.onProgress(-1f)
                        }
                    }
                    output.flush()
                }
            }
            return target
        } finally {
            conn.disconnect()
        }
    }

    /** 调系统安装器。 */
    fun install(context: Activity, apkFile: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile,
                ),
                "application/vnd.android.package-archive",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /** 用浏览器打开下载页（「下载太慢」时用）。 */
    fun openInBrowser(context: Activity, url: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse(url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

// ────────────────────────── 弹窗状态 ──────────────────────────

/**
 * 更新弹窗的状态。
 *
 * 三个值原来分别挂在 ProgressBar、它的可见性、两个按钮的 enabled 上；
 * 集中到一个对象里，Compose 侧只读不写。
 */
class UpdateDialogState(
    val versionLabel: String,
    val contentText: String,
    val apkUrl: String,
) {
    /** 0..100；下载中且总长度未知时 progressKnown=false。 */
    var progress by mutableIntStateOf(0)
    var progressKnown by mutableStateOf(true)
    var downloading by mutableStateOf(false)
    var buttonsEnabled by mutableStateOf(true)
}

// ────────────────────────── 弹窗 UI ──────────────────────────

/**
 * 更新弹窗（Compose）。
 *
 * 按钮颜色固定：Material3 在 enabled=false 时会把底色刷成 onSurface 12%、
 * 文字刷成 onSurface 38%（一片灰），和安装前的粉色按钮对不上。
 * 这里把禁用态也写成同一套颜色，下载中看起来仍是同一个按钮。
 */
@Composable
fun UpdateDialogContent(
    state: UpdateDialogState,
    onBrowserDownload: () -> Unit,
    onUpdate: () -> Unit,
    onIgnore: () -> Unit,
) {
    val accent = Color(0xFF7B68EE)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 14.dp),
    ) {
        Text(
            text = "下载太慢？点我在浏览器打开",
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { onBrowserDownload() },
        )
        Spacer(Modifier.height(14.dp))

        Text(
            text = "发现新版本",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = state.versionLabel,
            color = accent,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(14.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = state.contentText,
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (state.downloading) {
            Spacer(Modifier.height(12.dp))
            if (state.progressKnown) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${state.progress}%",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            } else {
                // 服务器没给 Content-Length：用不定长进度，别显示假百分比
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onUpdate,
            enabled = state.buttonsEnabled,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(25.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = Color.White,
                disabledContainerColor = accent,
                disabledContentColor = Color.White,
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp,
            ),
        ) {
            Text(text = if (state.downloading) "下载中…" else "立即更新", fontSize = 15.sp)
        }

        TextButton(
            onClick = onIgnore,
            enabled = state.buttonsEnabled,
            colors = ButtonDefaults.textButtonColors(
                contentColor = accent,
                disabledContentColor = accent,
            ),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(text = "忽略此版本", color = accent, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}
