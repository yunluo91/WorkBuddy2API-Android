package com.yunluo.wb2api

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * MD3 色板：跟 App 现有 colors.xml / 网页控制台保持一致。
 *
 * 取值对照（res/values/colors.xml）：
 *   bg     #0E1116
 *   card   #161B22
 *   card2  #1C232C
 *   accent #0DBD8B
 *   text   #E6EDF3
 *   muted  #8B949E
 *   line   #232A33
 *
 * 不做浅色主题：整个 App 的网页本来就是深色，切浅色只会花。
 * 这里 isSystemInDarkTheme() 只在需要时留给以后，现在恒定深色。
 */
val WbBg = Color(0xFF0E1116)
val WbCard = Color(0xFF161B22)
val WbCard2 = Color(0xFF1C232C)
val WbAccent = Color(0xFF0DBD8B)
val WbText = Color(0xFFE6EDF3)
val WbMuted = Color(0xFF8B949E)
val WbLine = Color(0xFF232A33)
val WbDanger = Color(0xFFF85149)
val WbWarn = Color(0xFFF0883E)

/** 主按钮上那层墨绿字色，跟原布局 bootRetry 的 textColor 一致 */
val WbOnAccent = Color(0xFF06231B)

val WbDarkColors = darkColorScheme(
    primary = WbAccent,
    onPrimary = WbOnAccent,
    primaryContainer = WbAccent.copy(alpha = 0.16f),
    onPrimaryContainer = WbAccent,
    secondary = WbAccent,
    onSecondary = WbOnAccent,
    background = WbBg,
    onBackground = WbText,
    surface = WbCard,
    onSurface = WbText,
    surfaceVariant = WbCard2,
    onSurfaceVariant = WbMuted,
    surfaceContainer = WbCard,
    surfaceContainerHigh = WbCard2,
    outline = WbLine,
    outlineVariant = WbLine,
    error = WbDanger,
    onError = Color.White
)

/**
 * 字号表：照原布局里那些 sp 值搬过来，别用 MD3 默认的
 * （默认标题 22sp 在 46dp 高的底栏里放不下）。
 */
private val WbTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 11.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 11.sp),
    labelSmall = TextStyle(fontSize = 10.sp)
)

@Composable
fun WbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WbDarkColors,
        typography = WbTypography,
        content = content
    )
}
