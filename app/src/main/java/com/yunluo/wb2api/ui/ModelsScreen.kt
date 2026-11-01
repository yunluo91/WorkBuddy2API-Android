package com.yunluo.wb2api.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunluo.wb2api.WbAccent
import com.yunluo.wb2api.WbBg
import com.yunluo.wb2api.WbCard
import com.yunluo.wb2api.WbCard2
import com.yunluo.wb2api.WbDanger
import com.yunluo.wb2api.WbLine
import com.yunluo.wb2api.WbMuted
import com.yunluo.wb2api.WbText
import com.yunluo.wb2api.WbWarn
import com.yunluo.wb2api.data.HubModel

/**
 * 模型清单页（原生）。
 *
 * WB-MODEL-NATIVE
 *
 * 为什么从网页搬成原生：
 *   网页那版走的是 控制台Python → REGISTRY → 网关Python → 上游，
 *   中间套了异步线程和超时，任何一环卡住界面就一直"加载中"。
 *   改了好几版都没根治。
 *
 *   参考实现（WorkBuddy-Hub）根本不做这一层 —— 它直接在
 *   App 的 Kotlin 层请求上游，结果存进 state。链路最短，最不容易出问题。
 *
 *   这里照那个思路：App 直接 HTTP 打网关的 /v1/models，
 *   拿到数据就用 Compose 渲染，不经过任何 Python。
 *
 * 版式照参考实现：一行一个模型，左边首字方块（按厂牌上色），
 * 中间名字 + id，右边倍率 + 能力标签。
 */
@Composable
fun ModelsScreen(
    models: List<HubModel>,
    loading: Boolean,
    error: String,
    realm: String,
    onSelectRealm: (String) -> Unit,
    onRefresh: () -> Unit,
    onCopy: (label: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // ---- 顶部：版本切换 + 计数 + 刷新
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(WbCard)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RealmChip("国际版", realm == "intl") { onSelectRealm("intl") }
            RealmChip("国内版", realm == "cn") { onSelectRealm("cn") }

            Spacer(Modifier.width(4.dp))

            Text(
                text = when {
                    loading -> "加载中…"
                    error.isNotEmpty() -> error
                    else -> {
                        val pro = models.count { it.proOnly }
                        buildString {
                            append("共 ${models.size} 个模型，按倍率从低到高")
                            if (pro > 0) append("\n标 PRO 的 ${pro} 个需要 Pro 账号")
                        }
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (error.isNotEmpty()) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )

            IconButton(onClick = onRefresh, enabled = !loading) {
                if (loading) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
        }

        // ---- 列表
        when {
            loading && models.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.material3.CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "正在从网关拉模型清单…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            models.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (error.isNotEmpty()) "加载失败：$error"
                        else "这个版本没有模型数据。换个版本看看，或者点刷新重试。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WbMuted,
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 108.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(models, key = { it.id }) { m ->
                        ModelRow(m) { onCopy("模型 ID", m.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RealmChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) WbAccent.copy(alpha = 0.20f) else WbCard2
    val fg = if (selected) WbAccent else WbMuted
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ModelRow(model: HubModel, onClick: () -> Unit) {
    /* WB-MODEL-CONTRAST
       原来用 MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f) 当卡片底，
       在深色主题下跟页面底色几乎一样（#0E1116 vs 半透明灰），
       而且 Card 会据此算一个偏灰的 contentColor，正文就发暗。

       现在全用固定色，明暗关系是硬编码的：
         页面 #0E1116  →  卡片 #1C232C（明显亮一档）
         正文 #E6EDF3（亮白）  →  次要 #8B949E（灰但不糊）
         边框 #232A33（比卡片再亮一点，把卡片框出来） */
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(WbCard2)
            .border(1.dp, WbLine, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VendorBadge(model, size = 42)

            Column(Modifier.weight(1f)) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = WbText,                 // 亮白，不再发灰
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.id,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = WbMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (model.contextText.isNotEmpty()) {
                        Text(
                            text = " · ${model.contextText}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = WbAccent,
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    /* WB-PRO-BADGE 需要 Pro 账号才能用。
                       橙色跟倍率那个绿色区分开 —— 它是个"限制"不是"价格"。 */
                    if (model.proOnly) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(WbWarn.copy(alpha = 0.18f))
                                .border(1.dp, WbWarn.copy(alpha = 0.45f),
                                    RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "PRO",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = WbWarn,
                            )
                        }
                    }
                    if (model.multiplierText.isNotEmpty()) {
                        val free = model.multiplier == 0.0
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (free) Color(0xFF2EA043).copy(alpha = 0.22f)
                                    else WbAccent.copy(alpha = 0.18f)
                                )
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = model.multiplierText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (free) Color(0xFF56D364) else WbAccent,
                            )
                        }
                    }
                }
                if (model.capabilities.isNotEmpty()) {
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        model.capabilities.forEach { cap ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(WbBg)
                                    .border(1.dp, WbLine, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = cap,
                                    fontSize = 10.sp,
                                    color = WbMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
