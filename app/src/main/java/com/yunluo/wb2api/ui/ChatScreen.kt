package com.yunluo.wb2api.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.yunluo.wb2api.ApprovalRequest
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunluo.wb2api.data.AgentEntry
import com.yunluo.wb2api.data.ChatState
import com.yunluo.wb2api.data.HubModel

/**
 * 对话页。
 *
 * 版式照 WorkBuddy-Hub-Android 的 AgentScreen 做：上面是记录，下面是
 * 输入区，输入区里带一排 chip 用来切会话 / 模型 / 思考档位。
 *
 * 跟参考实现不同的是：那个 Agent 能读写文件、执行命令，所以有一堆
 * root 开关、审批模式、存储权限提示。这个 App 的对话只走上游的
 * /v1/chat/completions，不碰本地文件，那些开关一个都用不上，
 * 留着只会让界面更挤。
 */
@Composable
fun ChatScreen(
    state: ChatState,
    models: List<HubModel>,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (String) -> Unit,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    /** 工具调用开关。关着就是普通聊天。 */
    toolsEnabled: Boolean = false,
    onToggleTools: (Boolean) -> Unit = {},
    approvalMode: com.yunluo.wb2api.agent.ApprovalMode = com.yunluo.wb2api.agent.ApprovalMode.AUTO,
    onSelectApproval: (com.yunluo.wb2api.agent.ApprovalMode) -> Unit = {},
    /** 有 root 吗，显示在工具开关旁边。 */
    hasRoot: Boolean = false,
) {
    val listState = rememberLazyListState()

    // 跟着最新的那行走。回答是边生成边长出来的，不自动滚的话
    // 尾巴会一直停在屏幕外面。
    LaunchedEffect(state.entries.size, state.entries.lastOrNull()?.text?.length) {
        if (state.entries.isNotEmpty()) {
            listState.animateScrollToItem(state.entries.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),

            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.entries.isEmpty()) {
                item { EmptyHint() }
            }
            itemsIndexed(state.entries) { _, entry -> EntryRow(entry) }
        }

        Composer(
            state = state,
            models = models,
            onInputChange = onInputChange,
            onSend = onSend,
            onSelectModel = onSelectModel,
            onSelectEffort = onSelectEffort,
            onNewSession = onNewSession,
            onOpenSession = onOpenSession,
            onDeleteSession = onDeleteSession,
            toolsEnabled = toolsEnabled,
            onToggleTools = onToggleTools,
            approvalMode = approvalMode,
            onSelectApproval = onSelectApproval,
            hasRoot = hasRoot,
        )
    }
}

/**
 * 输入区，连同所有影响这次请求的选项。
 *
 * 选项都塞在输入框上方那一条里，而不是单独占一块 —— 它们一局只改一次，
 * 应该把高度让给对话记录。
 */
@Composable
private fun Composer(
    state: ChatState,
    models: List<HubModel>,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (String) -> Unit,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    toolsEnabled: Boolean,
    onToggleTools: (Boolean) -> Unit,
    approvalMode: com.yunluo.wb2api.agent.ApprovalMode,
    onSelectApproval: (com.yunluo.wb2api.agent.ApprovalMode) -> Unit,
    hasRoot: Boolean,
) {
    val selected = models.firstOrNull { it.id == state.modelId }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 底部留出悬浮底栏的高度，输入框不能被它盖住
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .padding(bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 一行横滚，避免窄屏上 chip 折成两行把输入框挤没了
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 会话选择器。新建会话是最高频的动作，放列表第一项。
            ChipPicker(
                value = state.sessionTitle.ifBlank { "新会话" },
                options = buildList<Pair<String, String>> {
                    add(NEW_SESSION to "＋ 新会话")
                    state.sessions.forEach { add(it.id to it.displayTitle) }
                },
                onSelect = { picked ->
                    if (picked == NEW_SESSION) onNewSession() else onOpenSession(picked)
                },
                onDelete = onDeleteSession,
            )

            // 模型选择器
            ChipPicker(
                value = selected?.displayName ?: state.modelId.ifBlank { "选择模型" },
                options = models.sortedBy { it.sortKey }.map { it.id to it.displayName },
                onSelect = onSelectModel,
            )

            // 思考档位。模型不支持就不显示这个 chip。
            if (selected != null && selected.supportsEffort) {
                ChipPicker(
                    value = if (state.effort.isBlank() || state.effort == EFFORT_OFF) "不思考" else state.effort,
                    options = buildList {
                        /* WB-EFFORT-OFF
                           只有模型真能关思考时才给「不思考」。
                           上游说 reasoning_can_disable = false 的模型
                           （比如 hy3），选了也关不掉，给了是骗人。 */
                        if (selected.canDisableThinking) add(EFFORT_OFF to "不思考")
                        selected.efforts.forEach { add(it to it) }
                    },
                    onSelect = onSelectEffort,
                )
            }
        }

        /* WB-AGENT 工具开关那一行。
           
           放在 chip 下面单独一行，因为它跟"选哪个模型"不是一类事 ——
           它决定这个对话能不能动你的文件系统，是该被看见的设置。 */
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = { onToggleTools(!toolsEnabled) },
                label = {
                    Text(
                        if (toolsEnabled) "工具：开" else "工具：关",
                        maxLines = 1,
                    )
                },
                leadingIcon = {
                    Text(
                        if (toolsEnabled) "●" else "○",
                        color = if (toolsEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            if (toolsEnabled) {
                ChipPicker(
                    value = approvalMode.label,
                    options = com.yunluo.wb2api.agent.ApprovalMode.entries
                        .map { it.name to it.label },
                    onSelect = { picked ->
                        com.yunluo.wb2api.agent.ApprovalMode.fromName(picked)
                            .let(onSelectApproval)
                    },
                )
                if (hasRoot) {
                    AssistChip(
                        onClick = {},
                        label = { Text("root", maxLines = 1) },
                    )
                }
            }
        }

        // 输入框和发送键同一行，按钮看起来像是输入框的一部分
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp, max = 140.dp),
                placeholder = { Text("说点什么…") },
                maxLines = 6,
            )
            Spacer(Modifier.width(6.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = !state.running && state.input.isNotBlank() && state.modelId.isNotBlank(),
                modifier = Modifier.size(52.dp),
            ) {
                if (state.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

/**
 * 一个画成 chip 的下拉框。
 *
 * 用带标题的行会每个占两行，而输入框上方有好几个这样的选项。
 */
@Composable
private fun ChipPicker(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    onDelete: ((String) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name.ifEmpty { id }, maxLines = 1) },
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                    trailingIcon = if (onDelete != null && id != NEW_SESSION) {
                        {
                            IconButton(onClick = { onDelete(id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除会话")
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** 一行记录。思考过程折叠，其余直接显示。 */
@Composable
private fun EntryRow(entry: AgentEntry) {
    if (entry.collapsible) CollapsibleRow(entry) else MessageRow(entry)
}

/** 用户 / 助手 / 错误，三态直接铺开。 */
@Composable
private fun MessageRow(entry: AgentEntry) {
    val scheme = MaterialTheme.colorScheme
    val (title, color) = when (entry.role) {
        AgentEntry.Role.USER -> "你" to scheme.primary
        AgentEntry.Role.ASSISTANT -> "助手" to scheme.onSurface
        AgentEntry.Role.ERROR -> "错误" to scheme.error
        else -> "" to scheme.onSurface
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), Arrangement.spacedBy(4.dp)) {
            if (title.isNotEmpty()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(entry.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 思考过程：折成一行，点开才展开。它通常比正文长得多。 */
@Composable
private fun CollapsibleRow(entry: AgentEntry) {
    var expanded by remember(entry.text) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "思考",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    // 折起来时显示第一行，够看出在想什么了
                    text = entry.text.lineSequence().firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                )
            }
        }
    }
}

/** 一句提示都没有时显示。 */
@Composable
private fun EmptyHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("对话", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "直接跟上游客服模型对话。模型和思考档位在下面那排 chip 里选。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 「不思考」的哨兵值，跟「没设置」区分开。 */
const val EFFORT_OFF = "off"

/** 会话选择器里代表「新建」的哨兵 id。 */
const val NEW_SESSION = "__new__"


/**
 * 工具调用的确认框。
 *
 * 用 Compose 的 Dialog（跟主界面同一棵组合树），
 * 不是 android.app.Dialog —— 后者在 WebView 回调里会抛
 * ViewTreeLifecycleOwner not found，那个坑踩过一次了。
 *
 * 显示三样东西：
 *   · 这次要干什么（带路径/命令原文，不是"bash"这种工具名）
 *   · 危险命令的额外提醒（如果有）
 *   · 允许 / 拒绝
 *
 * 关掉（点外面 / 返回键）当作拒绝 ——
 * 一个因为手滑消失的对话框不该等于放行。
 */
@Composable
fun ToolApprovalDialog(
    request: ApprovalRequest,
    onAnswer: (Boolean) -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { onAnswer(false) },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(22.dp)) {
                Text(
                    text = "允许这个操作吗？",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = request.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                request.warning?.let { w ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = w,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { onAnswer(false) }) {
                        Text("拒绝", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { onAnswer(true) }) {
                        Text(
                            "允许",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
