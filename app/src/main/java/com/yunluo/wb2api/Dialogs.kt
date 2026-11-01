package com.yunluo.wb2api

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.ViewGroup
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置面板。
 *
 * 原来是 AlertDialog.Builder + 一堆手动 new 出来的 View（EditText / CheckBox /
 * RadioGroup），样式跟深色主题脱节，而且是系统 Material 1 的样子。
 * 这里整块换成 Compose + MD3：
 *
 *   · 端口         OutlinedTextField，数字键盘，保存时校验 1024..65535
 *   · 局域网访问   Switch，底下顺带把局域网地址显示出来
 *   · 自动启动     Switch
 *   · 面板密码     OutlinedTextField，密码遮罩 + 小眼睛
 *   · 思考档位     主开关 Switch + 三个 RadioButton + 强制关闭 Switch
 *
 * 主开关关掉时，下面的档位和强制关闭整体灰掉并禁用 —— 跟原布局
 * applyGate 的逻辑一致：必须先开才能调。
 */
class SettingsSheet(
    private val activity: Activity,
    private val prefs: Prefs,
    private val localIp: String,
    private val onSaved: (portChanged: Boolean, pwdChanged: Boolean) -> Unit
) {

    fun show() {
        var settled = false
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
        }

        dialog.setContentView(
            ComposeView(activity).apply {
                setContent {
                    WbTheme {
                        SettingsBody(
                            prefs = prefs,
                            localIp = localIp,
                            onDismiss = {
                                if (!settled) {
                                    settled = true
                                    dialog.dismiss()
                                }
                            },
                            onSave = { portChanged, pwdChanged ->
                                if (!settled) {
                                    settled = true
                                    dialog.dismiss()
                                    onSaved(portChanged, pwdChanged)
                                }
                            }
                        )
                    }
                }
            }
        )

        dialog.show()

        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (activity.resources.displayMetrics.widthPixels * 0.92f).toInt()
            val height = (activity.resources.displayMetrics.heightPixels * 0.86f).toInt()
            w.setLayout(width, height)
            val lp = w.attributes
            lp.dimAmount = 0.66f
            w.attributes = lp
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    @Composable
    private fun SettingsBody(
        prefs: Prefs,
        localIp: String,
        onDismiss: () -> Unit,
        onSave: (Boolean, Boolean) -> Unit
    ) {
        val app = androidx.compose.ui.platform.LocalContext.current

        var port by remember { mutableStateOf(prefs.port().toString()) }
        var lan by remember { mutableStateOf(prefs.lan()) }
        var autoStart by remember { mutableStateOf(prefs.autoStart()) }
        var pwd by remember { mutableStateOf(prefs.panelPwd()) }
        var pwdVisible by remember { mutableStateOf(false) }

        var thinkOn by remember { mutableStateOf(ThinkConfig.enabled(app)) }
        var thinkMode by remember { mutableStateOf(ThinkConfig.mode(app)) }
        var forceOff by remember { mutableStateOf(ThinkConfig.forceOff(app)) }

        val modes = listOf("off", "catalog", "high")
        val labels = listOf(
            "关闭 —— 完全不想，最快",
            "按目录 —— 上游给什么档就用什么档",
            "强制 high —— 一律深度思考"
        )

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = WbCard
        ) {
            Column(Modifier.fillMaxSize()) {
                // 标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 14.dp, top = 18.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "设置",
                        color = WbAccent,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 滚动内容
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp)
                ) {
                    SectionLabel("端口")
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = LocalTextStyle.current.copy(color = WbText, fontSize = 14.sp),
                        shape = RoundedCornerShape(10.dp),
                        colors = fieldColors()
                    )

                    Spacer(Modifier.height(14.dp))

                    SwitchRow(
                        title = "允许局域网访问",
                        subtitle = "同网段设备打开 http://$localIp:${port.ifEmpty { "?"}}/ 就能用看板",
                        checked = lan,
                        onCheckedChange = { lan = it }
                    )

                    SwitchRow(
                        title = "打开 App 时自动启动网关",
                        subtitle = null,
                        checked = autoStart,
                        onCheckedChange = { autoStart = it }
                    )

                    Spacer(Modifier.height(10.dp))

                    SectionLabel("面板密码（App 用它自动登录，不用每次手输）")
                    OutlinedTextField(
                        value = pwd,
                        onValueChange = { pwd = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (pwdVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { pwdVisible = !pwdVisible }) {
                                Text(
                                    if (pwdVisible) "隐藏" else "显示",
                                    color = WbMuted,
                                    fontSize = 12.sp
                                )
                            }
                        },
                        textStyle = LocalTextStyle.current.copy(color = WbText, fontSize = 14.sp),
                        shape = RoundedCornerShape(10.dp),
                        colors = fieldColors()
                    )

                    Spacer(Modifier.height(16.dp))

                    SectionLabel("深度思考调节")

                    SwitchRow(
                        title = "启用档位调节（主开关）",
                        subtitle = null,
                        checked = thinkOn,
                        onCheckedChange = { thinkOn = it }
                    )

                    // 主开关关掉时整块灰掉并禁用
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, top = 4.dp)
                    ) {
                        val alpha = if (thinkOn) 1f else 0.38f

                        Text(
                            "思考档位",
                            color = WbMuted.copy(alpha = alpha),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )

                        modes.forEachIndexed { i, m ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = thinkMode == m,
                                        enabled = thinkOn,
                                        onClick = { if (thinkOn) thinkMode = m }
                                    )
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = thinkMode == m,
                                    onClick = { if (thinkOn) thinkMode = m },
                                    enabled = thinkOn,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = WbAccent,
                                        unselectedColor = WbMuted.copy(alpha = alpha),
                                        disabledSelectedColor = WbAccent.copy(alpha = alpha),
                                        disabledUnselectedColor = WbMuted.copy(alpha = alpha)
                                    )
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    labels[i],
                                    color = WbText.copy(alpha = alpha),
                                    fontSize = 12.5.sp
                                )
                            }
                        }

                        SwitchRow(
                            title = "强制关闭",
                            subtitle = "连客户端显式给的档位也抹掉，一律不思考",
                            checked = forceOff,
                            enabled = thinkOn,
                            onCheckedChange = { forceOff = it }
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    // 现场信息
                    Text(
                        text = buildString {
                            appendLine("实际监听：${if (PyRunner.lanMode()) "0.0.0.0（局域网已开）" else "127.0.0.1（仅本机）"} :${PyRunner.port()}")
                            appendLine("本机面板：http://127.0.0.1:${prefs.port()}/")
                            appendLine("本机 API：http://127.0.0.1:${prefs.port()}/v1")
                            if (prefs.lan()) appendLine("局域网：http://$localIp:${prefs.port()}/")
                            append("改了面板密码要同步填在这里，否则下次会停在登录页。")
                        },
                        color = WbMuted,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )

                    Spacer(Modifier.height(20.dp))
                }

                // 底部按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 18.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = WbMuted, fontSize = 14.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val oldPort = prefs.port()
                            val oldLan = prefs.lan()
                            val oldPwd = prefs.panelPwd()

                            var p = port.toIntOrNull() ?: oldPort
                            if (p < 1024 || p > 65535) p = oldPort

                            var newPwd = pwd.trim()
                            if (newPwd.isEmpty()) newPwd = Prefs.DEFAULT_PANEL_PWD

                            prefs.setPort(p)
                            prefs.setLan(lan)
                            prefs.setAutoStart(autoStart)
                            prefs.setPanelPwd(newPwd)

                            // 思考档位写文件就行：网关每秒轮询，改完 1 秒内生效，不用重启
                            ThinkConfig.save(app, thinkMode, forceOff, thinkOn)

                            val portChanged = (p != oldPort) || (lan != oldLan)
                            val pwdChanged = newPwd != oldPwd
                            onSave(portChanged, pwdChanged)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WbAccent,
                            contentColor = WbOnAccent
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("保存", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text,
            color = WbMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }

    @Composable
    private fun SwitchRow(
        title: String,
        subtitle: String?,
        checked: Boolean,
        enabled: Boolean = true,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (enabled) WbText else WbText.copy(alpha = 0.38f),
                    fontSize = 13.5.sp
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = if (enabled) WbMuted else WbMuted.copy(alpha = 0.38f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = WbOnAccent,
                    checkedTrackColor = WbAccent,
                    uncheckedThumbColor = WbMuted,
                    uncheckedTrackColor = WbCard2,
                    uncheckedBorderColor = WbLine
                )
            )
        }
    }

    @Composable
    private fun fieldColors() = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = WbAccent,
        unfocusedBorderColor = WbLine,
        focusedContainerColor = WbBg,
        unfocusedContainerColor = WbBg,
        cursorColor = WbAccent,
        focusedTextColor = WbText,
        unfocusedTextColor = WbText
    )
}

/**
 * 通用信息对话框：关于 / 日志。
 *
 * 原来一个用 AlertDialog.Builder.setMessage（关于），
 * 一个塞了个等宽 TextView 进 ScrollView（日志）。
 * 这里统一成一种：标题 + 可滚动可选中正文 + 可选复制按钮。
 */
object WbInfoDialog {

    fun show(
        activity: Activity,
        title: String,
        body: String,
        copyLabel: String? = null,
        onCopy: (() -> Unit)? = null
    ) {
        var settled = false
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
        }

        dialog.setContentView(
            ComposeView(activity).apply {
                setContent {
                    WbTheme {
                        InfoBody(
                            title = title,
                            body = body,
                            copyLabel = copyLabel,
                            onCopy = onCopy,
                            onClose = {
                                if (!settled) { settled = true; dialog.dismiss() }
                            }
                        )
                    }
                }
            }
        )

        dialog.show()

        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = activity.resources.displayMetrics
            w.setLayout((dm.widthPixels * 0.90f).toInt(), (dm.heightPixels * 0.80f).toInt())
            val lp = w.attributes
            lp.dimAmount = 0.66f
            w.attributes = lp
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    @Composable
    private fun InfoBody(
        title: String,
        body: String,
        copyLabel: String?,
        onCopy: (() -> Unit)?,
        onClose: () -> Unit
    ) {
        Surface(shape = RoundedCornerShape(20.dp), color = WbCard) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    title,
                    color = WbAccent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 10.dp)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            text = body,
                            color = WbText,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 18.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (copyLabel != null && onCopy != null) {
                        TextButton(onClick = onCopy) {
                            Text(copyLabel, color = WbAccent, fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = onClose) {
                        Text("关闭", color = WbMuted, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
