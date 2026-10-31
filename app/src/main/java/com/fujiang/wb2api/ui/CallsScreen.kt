package com.fujiang.wb2api.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fujiang.wb2api.data.CallStats
import com.fujiang.wb2api.data.CallsPage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 调用记录页。
 *
 * 版式照着 WorkBuddy-Hub-Android 的 CallsScreen 做：
 * 顶部一张用量统计卡片（两行三列），下面是记录列表，最新的在最上面。
 *
 * 数据不是本地存的 —— 走网关的 /api/calls，那边有 1000 条上限、
 * 也负责算好统计。这样网页版和原生版看到的是同一份数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(
    page: CallsPage,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    needToken: Boolean = false,
    debug: String = "",
) {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 页面在屏幕上时每 2 秒自己刷一次：调用是从后台进来的，
    // 界面收不到通知，只能靠自己轮询。
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000L)
            if (!refreshing) onRefresh()
        }
    }

    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        if (debug.isNotEmpty()) {
            Text(
                text = debug,
                style = MaterialTheme.typography.labelSmall,
                color = SuccessGreen,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                maxLines = 2,
            )
        }
        PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                onRefresh()
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // 底部留出悬浮底栏的高度（64dp + 底距 12 + 导航栏 ~24）
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 108.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatsCard(
                    stats = page.stats,
                    canClear = page.records.isNotEmpty(),
                    onClear = onClear,
                )
            }

            if (page.records.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (needToken) "正在读取调用记录…" else "还没有调用记录",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (needToken)
                                    "控制台页面还在加载，它一就绪这里就会自动出现记录。" +
                                            "如果一直空着，去「控制台」页签停一下再回来。"
                                else
                                    "通过本地 API 平台或内置对话发起请求后，这里会显示每次调用的模型、Tokens 与积分消耗。" +
                                            "不需要创建 API Key —— 没 Key 的调用同样会计入。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (needToken) {
                                TextButton(onClick = onRefresh) { Text("立即重试") }
                            }
                        }
                    }
                }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "最近 ${page.records.size} 次调用（新→旧）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                }
                itemsIndexed(page.records) { _, record ->
                    // 不传模型目录：倍率用记录里存的历史值。
                    // 目录里的价格可能已经变了，而"这次调用花了多少"是既成事实。
                    CallRow(record, emptyList())
                }
            }
        }
        }
    }
}

/** 用量统计卡片：标题行 + 两行三列。 */
@Composable
private fun StatsCard(stats: CallStats, canClear: Boolean, onClear: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "用量统计",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (canClear) {
                    TextButton(onClick = onClear) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Text("清空")
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatCell("成功调用", stats.calls.toString())
                StatCell("失败", stats.failures.toString())
                StatCell("消耗积分", formatCredits(stats.credits))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatCell("输入 Tokens", stats.promptTokens.toString())
                StatCell("输出 Tokens", stats.completionTokens.toString())
                StatCell("合计 Tokens", stats.totalTokens.toString())
            }
        }
    }
}
