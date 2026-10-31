package com.fujiang.wb2api.ui

import com.fujiang.wb2api.WbMuted
import com.fujiang.wb2api.WbWarn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy

import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.fujiang.wb2api.WbAccent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置页。
 *
 * 原来这个位置是「调试台」，指向 8789 的思考调试台。
 * 思考调试台已经搬进控制台（多一个「思考」页签），
 * 所以这里改成 App 自己的入口。
 *
 * 放的都是"平时想找但不知道在哪"的东西：
 *   · 三个本地服务的地址和状态 —— 方便在别的设备/客户端上接入
 *   · 一键复制（复制出来的地址粘给剪映、IDE、别的手机都能用）
 *   · 打开网页版（想在浏览器里操作时）
 *   · 调用记录筛选偏好 —— 你说要的"国内/国际/全部"
 */
@Composable
fun SettingsScreen(
    port: Int,
    panelPassword: String,
    hasRoot: Boolean,
    toolsEnabled: Boolean,
    approvalLabel: String,
    callsRealmFilter: String,
    onSelectRealmFilter: (String) -> Unit,
    /** 跳去控制台的「密钥」页签。 */
    onGoKeys: () -> Unit,

    // ---- WB-SETTINGS-MERGE
    // 这些原来是 App 里那个「设置」弹窗（SettingsSheet）的内容。
    // 弹窗的入口（悬浮菜单按钮）在某次改版里丢了，导致设置点不到。
    // 现在全部搬到这里，弹窗保留但不再是唯一入口。
    prefPort: String,
    prefLan: Boolean,
    prefAutoStart: Boolean,
    prefPanelPwd: String,
    thinkEnabled: Boolean,
    thinkMode: String,
    thinkForceOff: Boolean,
    onPortChange: (String) -> Unit,
    onLanChange: (Boolean) -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
    onPanelPwdChange: (String) -> Unit,
    onThinkEnabledChange: (Boolean) -> Unit,
    onThinkModeChange: (String) -> Unit,
    onThinkForceOffChange: (Boolean) -> Unit,
    onRestartGateway: () -> Unit,

    // ---- WB-THINK-LEVEL
    /** 全局默认思考强度：off / low / high / max */
    thinkLevel: String,
    onSelectThinkLevel: (String) -> Unit,

    // ---- WB-ACCOUNT-IO
    onExportAccounts: () -> Unit,
    onImportAccounts: () -> Unit,
    /** 上一步导入导出的回执（空就不显示）。 */
    accountIoMsg: String,
    /** 手机当前的局域网 IP，取不到就是占位文案。 */
    lanIp: String,
    /** 局域网是否真的在监听（实测过的，不是看开关值）。 */
    lanLive: Boolean,

    onCopy: (label: String, text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------------- 账号导入导出（WB-ACCOUNT-IO）
        item {
            SettingCard("账号导入导出") {
                Text(
                    "看板那个导出按钮在安卓里点不动（它靠浏览器下载），" +
                            "这里补一个能用的。",
                    style = MaterialTheme.typography.bodySmall,
                    color = WbMuted,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onExportAccounts,
                        modifier = Modifier.weight(1f),
                    ) { Text("导出全部") }
                    OutlinedButton(
                        onClick = onImportAccounts,
                        modifier = Modifier.weight(1f),
                    ) { Text("从文件导入") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "规则：\n" +
                            "· 导出：国内 + 国际**全部**账号，含 accessToken / " +
                            "refreshToken，文件要收好\n" +
                            "· 导入：uid 相同 → 覆盖；uid 不同 → 添加",
                    style = MaterialTheme.typography.labelSmall,
                    color = WbMuted,
                    lineHeight = 16.sp,
                )
                if (accountIoMsg.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = accountIoMsg,
                        style = MaterialTheme.typography.labelSmall,
                        color = WbAccent,
                        lineHeight = 16.sp,
                    )
                }
            }
        }

        // ---------------- 思考强度（WB-THINK-LEVEL）
        item {
            SettingCard("思考强度") {
                Text(
                    "新建对话默认用哪一档。对话页输入框上方那一排 chip " +
                            "也能临时改，那是按会话存的。",
                    style = MaterialTheme.typography.bodySmall,
                    color = WbMuted,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((key, label) in listOf(
                        "off" to "不思考",
                        "low" to "Low",
                        "high" to "High",
                        "max" to "Max",
                    )) {
                        ChoiceChip(
                            text = label,
                            selected = thinkLevel == key,
                            onClick = { onSelectThinkLevel(key) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "说明：\n" +
                            "· 不思考 → 发 reasoning_effort=\"none\"，上游认这个值\n" +
                            "· 模型不支持关思考时（比如 hy3），「不思考」不生效，" +
                            "网关会退回该模型的默认档\n" +
                            "· 档位越高思考 token 越多，耗额度更快",
                    style = MaterialTheme.typography.labelSmall,
                    color = WbMuted,
                    lineHeight = 16.sp,
                )
            }
        }

        // ---------------- 网关设置（WB-SETTINGS-MERGE）
        item {
            SettingCard("网关设置") {
                LabelledField(
                    label = "端口",
                    value = prefPort,
                    hint = "默认 8788。改了要重启网关才生效。",
                    onValueChange = onPortChange,
                )
                Spacer(Modifier.height(12.dp))
                /* WB-LAN-STATUS
                   局域网开关 + 一个状态块。
                   
                   三件事说清楚：
                     ① 开关值（prefLan）只代表"你想开"
                     ② 右边那个红/绿标签代表"实际开着没"
                        —— 它来自实测（请求一次自己的局域网 IP），
                        不是读开关值
                     ③ 开关打开了但还没重启 → 红色「未开启」，
                        并提示点下面的按钮
                   
                   为什么红绿用实测：开关是个愿望，
                   端口绑定是既成事实。两者不一致时，
                   用户最需要知道的是后者。 */
                SwitchRow(
                    label = "局域网访问",
                    desc = "打开后同一 WiFi 下的设备能连到这台手机。",
                    checked = prefLan,
                    onChange = onLanChange,
                    trailing = {
                        LanBadge(lanLive = lanLive, wanted = prefLan)
                    },
                )

                // 小板块：IP + 地址 + 状态，样式跟"客户端接入"那种行一致
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "手机 IP",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(64.dp),
                        )
                        Text(
                            text = lanIp,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "访问地址",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(64.dp),
                        )
                        Text(
                            text = "http://$lanIp:$prefPort/",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onCopy("访问地址", "http://$lanIp:$prefPort/") }) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                        }
                    }
                    if (prefLan && !lanLive) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "开关已打开，但网关还没按新配置启动 —— "
                                    + "点下面的「保存并重启网关」才会真正监听。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 17.sp,
                        )
                    }
                    if (!prefLan) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "局域网关闭时只有这台手机能访问。"
                                    + "要让别的设备连进来，先打开开关再重启。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 17.sp,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    label = "开机自启",
                    desc = "开机后自动把网关拉起来。",
                    checked = prefAutoStart,
                    onChange = onAutoStartChange,
                )
                Spacer(Modifier.height(12.dp))
                LabelledField(
                    label = "面板密码",
                    value = prefPanelPwd,
                    hint = "网页看板/控制台登录用。",
                    onValueChange = onPanelPwdChange,
                    isPassword = true,
                )
                Spacer(Modifier.height(14.dp))
                TextButton(
                    onClick = onRestartGateway,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存并重启网关", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ---------------- 思考档位
        item {
            SettingCard("思考档位") {
                Text(
                    "控制上游模型跑多深的思考。关掉就是不走这套，"
                            + "上游按它自己的默认来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    label = "启用档位调节",
                    desc = null,
                    checked = thinkEnabled,
                    onChange = onThinkEnabledChange,
                )
                if (thinkEnabled) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "默认档位",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        /* ThinkConfig.MODES = {"off", "catalog", "high"}
                           catalog 的意思：按模型目录里该模型支持的最高档走。
                           （原来我按 off/low/medium/high 写，那是错的） */
                        for ((m, label) in listOf(
                            "off" to "不思考",
                            "catalog" to "按模型默认",
                            "high" to "最高档",
                        )) {
                            ChoiceChip(
                                text = label,
                                selected = thinkMode == m,
                                onClick = { onThinkModeChange(m) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SwitchRow(
                        label = "强制关闭",
                        desc = "连客户端显式给的档位也抹掉，一律不思考。",
                        checked = thinkForceOff,
                        onChange = onThinkForceOffChange,
                    )
                }
            }
        }

        // ---------------- 调用记录筛选偏好
        item {
            SettingCard("调用记录筛选") {
                Text(
                    "默认显示哪些账号的调用记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((key, label) in listOf(
                        "all" to "全部",
                        "cn" to "国内版",
                        "intl" to "国际版",
                    )) {
                        ChoiceChip(
                            text = label,
                            selected = callsRealmFilter == key,
                            onClick = { onSelectRealmFilter(key) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "「全部」会同时显示两个版本的请求 —— " +
                            "如果你只关心一边，选另一个能少一半噪音。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------------- 客户端接入
        item {
            SettingCard("客户端接入") {
                Text(
                    "任何支持 OpenAI 兼容协议的客户端都能接到这个网关。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                CopyLine(
                    label = "Base URL",
                    value = "http://127.0.0.1:$port/v1",
                    onCopy = onCopy,
                )
                Spacer(Modifier.height(10.dp))

                /* API Key 不是"复制一段文字"能解决的 ——
                   它得去密钥页看/新建。
                   原来这里是个复制按钮，复制出来的是"去控制台→密钥 里看"
                   这句话本身，点了等于没点。
                   现在改成一行可点的入口，直接跳过去。 */
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .clickable(onClick = onGoKeys)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "API Key",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "去密钥页查看或新建",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text(
                        text = "→",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "不需要 Key 也能调 —— 网关没配 Key 时是开放的。" +
                            "配了 Key 就必须带。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------------- 当前状态
        item {
            SettingCard("当前状态") {
                StatusRow("网关端口", port.toString())
                StatusRow("面板密码", panelPassword.ifEmpty { "（未设置）" })
                StatusRow("root", if (hasRoot) "已授权" else "未授权")
                StatusRow("对话工具", if (toolsEnabled) "开启（$approvalLabel）" else "关闭")
            }
        }

        // ---------------- 说明
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "关于",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "WorkBuddy2API 手机版\n" +
                                "把上游模型服务包成本地网关，附带调用记录、" +
                                "账号池、思考档位调节和可操作手机的 Agent 对话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 组件

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** 带标签和说明的输入框。 */
@Composable
private fun LabelledField(
    label: String,
    value: String,
    hint: String?,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                fontSize = 14.sp,
            ),
            visualTransformation = if (isPassword) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            shape = RoundedCornerShape(10.dp),
        )
        if (!hint.isNullOrEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一行开关：左边标题（可带说明），右边开关。 */
@Composable
private fun SwitchRow(
    label: String,
    desc: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    /** 开关左边额外放的东西（比如状态标签）。 */
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (!desc.isNullOrEmpty()) {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.let {
            it()
            Spacer(Modifier.width(8.dp))
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
        )
    }
}

/**
 * 局域网状态小标签。
 *
 * 三态：
 *   绿「已开启」  实测在监听（不是看开关值）
 *   红「未开启」  没在监听 —— 可能是开关没开，也可能是开了但没重启
 *   灰「未开启」  开关关着，且本来就不该开（不是错误，只是状态）
 *
 * 为什么要区分红和灰：
 *   "你开了但没生效"是**需要你操作**的错误，
 *   "你没开"是**正常状态**。同样显示红色的"未开启"会让人以为坏了。
 */
@Composable
private fun LanBadge(lanLive: Boolean, wanted: Boolean) {
    val (text, fg, bg) = when {
        lanLive -> Triple("已开启", Color(0xFF3FB950), Color(0x223FB950))
        wanted -> Triple("未开启", Color(0xFFF85149), Color(0x22F85149))
        else -> Triple("未开启", Color(0xFF8B949E), Color(0x228B949E))
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val fg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun CopyLine(
    label: String,
    value: String,
    onCopy: (String, String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onCopy(label, value) }) {
            Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}
