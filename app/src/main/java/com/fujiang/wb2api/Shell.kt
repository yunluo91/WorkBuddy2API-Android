package com.fujiang.wb2api

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp

/**
 * 底部页签。
 *
 * 前两个（对话、调用记录）是原生 Compose 页面 —— 这两个交互最重：
 * 对话要流式刷新、要自动滚到底，调用记录要下拉刷新、要长列表滚动，
 * 放在 WebView 里手感总是差一截。
 *
 * 后三个仍然是网页：看板是上游的登录页，控制台和调试台是网关自带的
 * 页面，改造成本高而收益小。
 */
enum class WbTab(val label: String, val icon: ImageVector) {
    Chat("对话", Icons.Filled.Chat),
    Calls("调用记录", Icons.Filled.Receipt),
    Board("看板", Icons.Filled.Dashboard),
    Console("控制台", Icons.Filled.Terminal),
    /** WB-SETTINGS-TAB
     *  原来是「调试台」，指向 8789 的思考调试台。
     *  思考调试台已经搬进控制台（多一个「思考」页签），
     *  这个页签改成「设置」，放 App 自己的配置。
     */
    Settings("设置", Icons.Filled.Settings)
}

/**
 * 底部页签栏。
 *
 * 原来是一排三等分 TextView（46dp 高，选中变绿）。
 * 换成 MD3 NavigationBar 之后要点有两个：
 *   1. 高度会默认到 80dp —— 太高，把页面挤掉一块，所以压到 58dp
 *   2. 图标和文字的间距默认很大，这里收紧
 */
@Composable
fun WbTabBar(
    current: WbTab,
    onSelect: (WbTab) -> Unit
) {
    NavigationBar(
        containerColor = WbCard,
        contentColor = WbMuted,
        tonalElevation = 0.dp,
        modifier = Modifier.height(58.dp)
    ) {
        WbTab.entries.forEach { tab ->
            // WbTab 自己带 icon 字段（枚举构造参数），
            // 不用在这里再手写一遍映射 —— 加了页签也不用改这里
            val icon = tab.icon
            NavigationBarItem(
                selected = current == tab,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = tab.label,
                        modifier = Modifier.size(20.dp)
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        fontSize = 11.sp,
                        fontWeight = if (current == tab) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = WbAccent,
                    selectedTextColor = WbAccent,
                    indicatorColor = WbAccent.copy(alpha = 0.14f),
                    unselectedIconColor = WbMuted,
                    unselectedTextColor = WbMuted
                )
            )
        }
    }
}

/**
 * 启动遮罩。
 *
 * 原来是一个 ScrollView，纵向排：logo / 标题 / 副标题 / 进度条 /
 * 状态文字 / 日志 / 重试按钮。
 *
 * 换成 Compose 之后保留了「可滚动 + 可选中日志」这两个特性：
 *   · 可滚动 —— 日志刷满一屏时能翻
 *   · 日志可选中 —— 用户能长按复制错误信息发出来，这个很重要
 */
@Composable
fun WbBootScreen(
    status: String,
    log: String,
    showRetry: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WbBg)
            // WB-FULLSCREEN：全屏后内容会跑到状态栏/导航栏底下，
            // 用 systemBarsPadding 避开它们（而不是写死 72dp ——
            // 那个数在刘海屏/挖孔屏上会不够）。
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .padding(top = 56.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 原来的 boot_logo 是 88dp 的图。这里先留一个同尺寸的占位，
        // 等确认整链路能跑通再换成真实 drawable。
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(WbCard2),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "WB",
                color = WbAccent,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "WorkBuddy2API",
            color = WbText,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = "手机上的模型网关",
            color = WbMuted,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(28.dp))

        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = WbAccent,
            trackColor = WbCard2
        )

        Spacer(Modifier.height(14.dp))

        Text(
            text = status,
            color = WbAccent,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        if (log.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SelectionContainer {
                Text(
                    text = log,
                    color = WbMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (showRetry) {
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WbAccent,
                    contentColor = WbOnAccent
                )
            ) {
                Text("重试", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * 工具页（控制台 / 调试台）加载失败时贴在中间的卡片。
 * 原来是个 TextView，点一下重试。这里保留「整卡可点」。
 */
@Composable
fun WbToolErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onRetry,
        modifier = modifier.padding(horizontal = 28.dp),
        colors = CardDefaults.cardColors(containerColor = WbCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = message,
            color = WbMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        )
    }
}

/**
 * 顶部那条 3dp 的加载进度。
 * 原来用 ?android:attr/progressBarStyleHorizontal，这里换成 MD3 的细条。
 */
@Composable
fun WbToolProgress(progress: Int, visible: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(3.dp)) {
        if (visible) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxSize(),
                color = WbAccent,
                trackColor = WbCard,
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
        }
    }
}
