package com.yunluo.wb2api.ui

import com.yunluo.wb2api.WbCard
import com.yunluo.wb2api.WbCard2
import com.yunluo.wb2api.WbDanger
import com.yunluo.wb2api.WbLine
import com.yunluo.wb2api.WbText
import com.yunluo.wb2api.WbMuted
import com.yunluo.wb2api.WbWarn
import androidx.compose.foundation.border
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
import com.yunluo.wb2api.WbAccent
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
    onPortChange: (String) -> Unit,
    onLanChange: (Boolean) -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
    onPanelPwdChange: (String) -> Unit,
    onRestartGateway: () -> Unit,

    // ---- WB-THINK-LEVEL
    /** 打开「数据管理」二级页。 */
    onOpenData: () -> Unit,

    // ---- WB-KEEPALIVE
    /** 是否已在电池优化白名单。 */
    batteryExempt: Boolean,
    /** 网关服务当前是否在跑。 */
    serviceRunning: Boolean,
    /** 通知权限是否给了（没给的话前台服务通知看不见）。 */
    notifAllowed: Boolean,
    onRequestBattery: () -> Unit,
    onOpenNotifSettings: () -> Unit,

    // ---- WB-ACCOUNT-IO

    // ---- WB-DATA-MIGRATE
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
        // ---------------- 数据管理入口（WB-DATA-ENTRY）
        // 账号导入导出 + 数据迁移都收进一个二级页，设置页这里只留一个入口。
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(WbCard2)
                    .border(1.dp, WbLine, RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenData)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "数据管理",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = WbText,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "账号导入导出 · 换包名迁移",
                        style = MaterialTheme.typography.labelSmall,
                        color = WbMuted,
                    )
                }
                Text(text = "\u203A", fontSize = 20.sp, color = WbMuted)
            }
        }

        // ---------------- 后台保活（WB-KEEPALIVE）
        item {
            SettingCard("后台保活") {
                Text(
                    "网关跑在后台才能一直用。系统省电策略会把它杀掉 —— " +
                            "这里三项都做到位，基本就不会掉。",
                    style = MaterialTheme.typography.bodySmall,
                    color = WbMuted,
                )
                Spacer(Modifier.height(10.dp))

                // ① 电池优化
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "电池优化豁免",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (batteryExempt) "已加入白名单 —— 系统不会因为省电杀掉它"
                            else "没加白名单 —— 这是掉后台最常见的原因",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (batteryExempt) WbAccent else WbWarn,
                        )
                    }
                    if (batteryExempt) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(WbAccent.copy(alpha = 0.16f))
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "已开启",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = WbAccent,
                            )
                        }
                    } else {
                        OutlinedButton(onClick = onRequestBattery) { Text("去开启") }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ② 通知权限
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "通知权限",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (notifAllowed) "已允许 —— 前台服务的常驻通知能显示"
                            else "被关了 —— 前台服务会被降级，更容易被系统回收",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (notifAllowed) WbMuted else WbWarn,
                        )
                    }
                    if (!notifAllowed) {
                        OutlinedButton(onClick = onOpenNotifSettings) { Text("去开启") }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ③ 服务状态
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "网关服务",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (serviceRunning) "正在运行（前台服务 + 唤醒锁已持有）"
                            else "没在跑 —— 回看板等一下，或者重启 App",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (serviceRunning) WbMuted else WbWarn,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (serviceRunning) WbAccent else WbDanger)
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "说明：前台服务用的是 specialUse 类型。" +
                            "之前的 dataSync 在 Android 14 以后有" +
                            "「每 24 小时累计 6 小时」的硬上限，到点系统强制停 ——" +
                            "这就是「跑着跑着掉后台」的直接原因。",
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
                    hint = "默认 8888。改了要重启网关才生效。",
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
                        text = "WorkBuddyAPI 手机版\n" +
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


/**
 * 数据管理（二级页）。
 *
 * 从设置页那个入口点进来，装两块：
 *   · 账号导入导出（账号池，同 uid 覆盖 / 新 uid 添加）
 *   · 数据迁移（整包 zip，换包名时用）
 *
 * 为什么不摊在设置首页：这两块都是一次性操作，平时不用，
 * 放在首页只会把真正要改的选项挤下去。
 */
@Composable
fun SettingsDataScreen(
    accountIoMsg: String,
    migrateMsg: String,
    onExportAccounts: () -> Unit,
    onImportAccounts: () -> Unit,
    onBackupData: () -> Unit,
    onRestoreData: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(WbCard)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "\u2039 设置",
                    style = MaterialTheme.typography.labelMedium,
                    color = WbAccent,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "数据管理",
                style = MaterialTheme.typography.labelMedium,
                color = WbMuted,
            )
            Spacer(Modifier.width(10.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 14.dp, 16.dp, 108.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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

        // ---------------- 数据迁移（WB-DATA-MIGRATE）
        item {
            SettingCard("数据迁移（换包名用）") {
                Text(
                    "改包名等于换一个应用，旧数据系统层面继承不过来。" +
                            "这里把数据打成 zip 放到你能选的位置，" +
                            "新包装上后再读回来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = WbMuted,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onBackupData,
                        modifier = Modifier.weight(1f),
                    ) { Text("备份到文件") }
                    OutlinedButton(
                        onClick = onRestoreData,
                        modifier = Modifier.weight(1f),
                    ) { Text("从文件恢复") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "备份内容：账号池 + API Key + 面板密码 + " +
                            "全部调用记录 + 思考档位 + 所有开关偏好。\n" +
                            "不含 Python 运行时（新装会自己解压）。\n\n" +
                            "恢复后 App 会自己重启一次 —— 必须重启，" +
                            "否则内存里的旧账号池会把恢复的文件冲回去。",
                    style = MaterialTheme.typography.labelSmall,
                    color = WbMuted,
                    lineHeight = 16.sp,
                )
                if (migrateMsg.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = migrateMsg,
                        style = MaterialTheme.typography.labelSmall,
                        color = WbAccent,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
        }
    }
}
