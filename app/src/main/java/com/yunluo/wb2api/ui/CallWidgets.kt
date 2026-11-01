package com.yunluo.wb2api.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.yunluo.wb2api.R
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunluo.wb2api.data.CallRecord
import com.yunluo.wb2api.data.HubModel

/**
 * 调用记录的两块积木：统计格子和单条记录。
 *
 * 版式对齐参考实现（WorkBuddy-Hub-Android），配色换成这个 App 的
 * 深色色板 —— 那套用的是浅色 MD3 主题，直接搬会有一堆白卡片。
 */

/** 成功用绿，失败用红。不取主题色：主题里的 tertiary/secondary 在这页另有含义。 */
val SuccessGreen = Color(0xFF2EA043)

/**
 * 统计格子：数字大、标签小。
 *
 * 数字用主色加粗，标签用弱化的灰 —— 一眼看过去先读到的是数。
 */
@Composable
fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * 厂商徽标：取首字画一个方块。
 *
 * 上游目录里没有 logo 地址，画字母反而离线可用、也不会糊。
 */
@Composable
fun VendorBadge(model: HubModel, modifier: Modifier = Modifier, size: Int = 30) {
    /* WB-VENDOR-LOGO
       有品牌 logo 就画 logo，没有就退回首字方块。

       logo 来源：lobehub/icons-static-svg（MIT）+ 用户提供的太空兔。
       用 PNG 不用 VectorDrawable —— 之前试过转矢量，
       GLM（智谱，22 个子路径的环形图）和 Gemini（fill-rule 写在
       <svg> 根上）转出来都是糊的。光栅化一步到位，渲染交给系统位图，
       不赌路径解析。每张 192×192，够 4x 屏用。

       太空兔那张是**整块头像**（自带深色圆角底），
       所以它走 fullBleed：铺满徽标，不再叠自己的底色和边框，
       否则会出现"圆角里套圆角"。
     */
    val key = vendorKey(model)
    val logo = vendorLogo(key)
    val c = vendorColors(model)
    val fullBleed = key == "space"

    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(11.dp))
            .then(
                if (fullBleed) Modifier
                else Modifier
                    .background(c.background)
                    .border(1.dp, c.border, RoundedCornerShape(11.dp))
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            logo != 0 && fullBleed -> Image(
                painter = painterResource(logo),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            logo != 0 -> Image(
                painter = painterResource(logo),
                contentDescription = null,
                modifier = Modifier.size((size * 0.62f).dp),
            )
            else -> Text(
                text = vendorMark(model),
                color = c.foreground,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 厂牌 → 内置 logo。没有就返回 0，调用方退回文字。 */
private fun vendorLogo(key: String): Int = when (key) {
    "deepseek" -> R.drawable.ic_vendor_deepseek
    "glm" -> R.drawable.ic_vendor_zhipu
    "kimi" -> R.drawable.ic_vendor_kimi
    "minimax" -> R.drawable.ic_vendor_minimax
    "hunyuan" -> R.drawable.ic_vendor_hunyuan
    "openai" -> R.drawable.ic_vendor_openai
    "gemini" -> R.drawable.ic_vendor_gemini
    "grok" -> R.drawable.ic_vendor_grok
    "claude" -> R.drawable.ic_vendor_claude
    "qwen" -> R.drawable.ic_vendor_qwen
    "space" -> R.drawable.ic_vendor_space
    "stepfun" -> R.drawable.ic_vendor_stepfun
    else -> 0
}


private data class VendorColors(val background: Color, val foreground: Color, val border: Color)

/**
 * 厂牌识别。
 *
 * 上游的 vendor 字段是**单字母代码**（f / e / j），拿它配色没意义 ——
 * 之前就是因为这个字段非空、直接用了，所以徽标全是灰的。
 * 现在以模型 id 前缀为准，单字母代码当"没有"处理。
 */
private fun vendorKey(model: HubModel): String {
    val byId = guessVendor(model.id)
    if (byId.isNotEmpty()) return byId
    val v = model.vendor.trim()
    // 长度 < 2 的一定是代码不是品牌名
    return if (v.length >= 2) v.lowercase() else ""
}

/** 徽标上显示的短标记。 */
private fun vendorMark(model: HubModel): String = when (vendorKey(model)) {
    "deepseek" -> "DS"
    "glm" -> "智"
    "kimi" -> "K"
    "minimax" -> "M"
    "hunyuan" -> "混"
    "openai" -> "AI"
    "gemini" -> "G"
    "grok" -> "X"
    "claude" -> "C"
    "qwen" -> "通"
    "xiaomi" -> "米"
    "space" -> "S"
    "stepfun" -> "阶"
    else -> model.displayName.take(1).uppercase().ifEmpty { "?" }
}

/**
 * 徽标配色。
 *
 * WB-VENDOR-LOGO 改成"品牌色淡底 + 亮色边框"：
 * logo 本身是彩色的，底色太饱和会盖住它。
 * 没有 logo 的厂牌走 vendorMark 文字，那时 foreground 要够亮。
 */
private fun vendorColors(model: HubModel): VendorColors {
    val (base, fg) = when (vendorKey(model)) {
        "hunyuan" -> Color(0xFF0055E9) to Color(0xFF6FA8FF)
        "glm" -> Color(0xFF3859FF) to Color(0xFF7E96FF)
        "deepseek" -> Color(0xFF4D6BFE) to Color(0xFF8FA2FF)
        "kimi" -> Color(0xFF7C3AED) to Color(0xFFB794F6)
        "minimax" -> Color(0xFFE8590C) to Color(0xFFFFA26B)
        "openai" -> Color(0xFF10A37F) to Color(0xFF56D9B4)
        "gemini" -> Color(0xFF4285F4) to Color(0xFF8AB4FF)
        "grok" -> Color(0xFF6366F1) to Color(0xFFA5A7FF)
        "claude" -> Color(0xFFD97757) to Color(0xFFF0A98D)
        "qwen" -> Color(0xFF082DFF) to Color(0xFF6B82FF)
        "xiaomi" -> Color(0xFFFF6900) to Color(0xFFFFA366)
        "space" -> Color(0xFF57606A) to Color(0xFFB0B8C1)
        "stepfun" -> Color(0xFF0F6FFF) to Color(0xFF6FA0FF)
        else -> Color(0xFF30363D) to Color(0xFFC9D1D9)
    }
    return VendorColors(
        background = base.copy(alpha = 0.13f),
        foreground = fg,
        border = base.copy(alpha = 0.30f),
    )
}

/**
 * 从模型 id 前缀猜厂牌，返回内部 key（小写）。
 *
 * 注意 "hy3" / "hy4-preview" 这类：前缀是 hy，不是 hy3。
 * 所以用 startsWith 而不是 substringBefore('-').
 */
private fun guessVendor(id: String): String {
    val s = id.lowercase()
    return when {
        s.startsWith("deepseek") -> "deepseek"
        s.startsWith("glm") -> "glm"
        s.startsWith("kimi") || s.startsWith("moonshot") -> "kimi"
        s.startsWith("minimax") -> "minimax"
        s.startsWith("hy") -> "hunyuan"
        s.startsWith("gpt") || s.startsWith("o1") || s.startsWith("o3") ||
            s.startsWith("o4") -> "openai"
        s.startsWith("gemini") -> "gemini"
        s.startsWith("grok") -> "grok"
        s.startsWith("claude") -> "claude"
        s.startsWith("qwen") || s.startsWith("tongyi") -> "qwen"
        s.startsWith("mimo") || s.startsWith("xiaomi") -> "xiaomi"
        s.startsWith("space") -> "space"
        s.startsWith("step") -> "stepfun"
        else -> ""
    }
}

/**
 * 单条调用记录。
 *
 * 用整块淡色底表示成败，而不是只把文字染红 —— 一列扫下去，颜色比文字快。
 * 底色压得很淡（0.10 / 0.14），保证数字仍然读得清。
 */
@Composable
fun CallRow(record: CallRecord, models: List<HubModel>, modifier: Modifier = Modifier) {
    val failed = !record.ok
    val model = models.firstOrNull { it.id == record.model }
        ?: HubModel(id = record.model, name = record.model)

    // 目录里的价格可能已经变了，优先显示当前价 —— 那才是读者拿来
    // 做决定的那个数。目录里没有这个模型就退回记录里的历史值。
    val multiplier = model.multiplier.takeIf { it >= 0 }?.let { trimNum(it) }
        ?: record.multiplierText

    val accent = if (failed) MaterialTheme.colorScheme.error else SuccessGreen

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = if (failed) 0.14f else 0.10f),
        ),
    ) {
        Column(Modifier.padding(12.dp), Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VendorBadge(model, size = 30)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = record.model.ifEmpty { "(未知模型)" },
                        style = MaterialTheme.typography.titleSmall,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 第二行：倍率 + 版本标签
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (multiplier != null) {
                            Text(
                                text = "倍率 $multiplier",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        record.realmLabel?.let { label ->
                            RealmTag(label)
                        }
                    }
                }
                Text(
                    text = record.timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (failed) {
                Text(
                    text = if (record.reason.isEmpty()) "调用失败" else "失败 · ${record.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = "${record.promptTokens} 入 / ${record.completionTokens} 出 · 消耗 ${formatCredits(record.credit)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 国内版 / 国际版 小标签。
 *
 * 只标版本，不标账号名 —— 账号是隐私，而且同一个人两个版本的账号名
 * 常常一样，标出来也没有信息量。
 *
 * 国内版用琥珀色（跟主色区分开），国际版用蓝色系，
 * 一眼能从一片绿里分辨出来。
 */
@Composable
private fun RealmTag(label: String) {
    val (fg, bg) = when (label) {
        "国内版" -> Color(0xFFE3B341) to Color(0x22E3B341)
        else -> Color(0xFF58A6FF) to Color(0x2258A6FF)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
        )
    }
}

/** 积分是倍率算出来的，尾部的零没有意义，去掉。 */
fun formatCredits(value: Double): String {
    if (value == 0.0) return "0"
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
    }
}

private fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else formatCredits(v)
